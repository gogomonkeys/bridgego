package com.bridge.persistence;

import java.time.*;
import java.util.*;

/** Local scheduler/coordinator, not a Sheets transport or a durable transaction engine.
 * No legacy QuarterlyRetention.execute call is made. Only a separately verified
 * DurableControl implementation may dispatch a maintenance operation.
 */
public final class QuarterMaintenanceCoordinator {
  public enum Phase { COMPLETE, DUE, PENDING, APPLIED, UNKNOWN }
  public enum Status { DISABLED, CHECKING, OPEN, DRAINING, RUNNING, BLOCKED_MANUAL }
  /** Counts/rows/identities are deliberately absent. token binds a full observed snapshot.
   * historicalRulesCompatible gates deletion of expired records only. A positively verified
   * COMPLETE current-quarter observation may open gameplay even if old records require a
   * separate policy decision; this path never executes maintenance or asserts historic coverage.
   */
  public record Observation(Phase phase, Instant through, String token, String completedOperation,
      boolean fullReadVerified, boolean ownershipVerified, boolean receiptsVerified,
      boolean historicalRulesCompatible) {
    public Observation { Objects.requireNonNull(phase); }
    public static Observation unavailable() {
      return new Observation(Phase.UNKNOWN,null,"","",false,false,false,false);
    }
  }
  public interface DurableControl {
    /** Explicit inert startup only; default providers never bypass verification failure. */
    default boolean startupWaiting(){return false;}
    /** Full fresh native read: bound target, schema, tables, control root and receipts.
     * Must detect foreign/stale ownership and unknown prior writes, including after restart.
     * COMPLETE means positively verified current-quarter data and matching durable receipts;
     * DUE means a clean pre-quarter state, not an unresolved PENDING/APPLIED operation.
     */
    Observation inspect(Instant quarter);
    /** Persistently bind operationId+quarter+expected.token, then run the verified staged
     * protocol exactly once. Must provide atomic anti-replay and exclusive writer ownership.
     * No old replaceAtomic/QuarterlyRetention.execute fallback or blind replay is allowed.
     * Any uncertain result must remain durable and visible to inspect after process restart.
     */
    void executeOnce(Instant quarter, Observation expected, String operationId);
    static DurableControl unavailable() {
      return new DurableControl() {
        public Observation inspect(Instant quarter) { return Observation.unavailable(); }
        public void executeOnce(Instant quarter, Observation expected, String operationId) {
          throw new IllegalStateException("Verified durable transport is not installed");
        }
      };
    }
  }
  private final QuarterMaintenanceGate gate;
  private final DurableControl durable;
  private final Clock clock;
  private final java.util.function.BooleanSupplier domainQuiescent;
  private final Runnable resumeDomain;
  private final Runnable forceFreeze;
  private Instant verifiedQuarter,lastTick;
  private Instant waitingQuarter;
  private boolean domainTouched;
  private Status status=Status.CHECKING;
  private Instant attemptedQuarter;
  public QuarterMaintenanceCoordinator(QuarterMaintenanceGate gate, DurableControl durable, Clock clock) {
    this(gate,durable,clock,()->false);
  }
  public QuarterMaintenanceCoordinator(QuarterMaintenanceGate gate, DurableControl durable, Clock clock,
      java.util.function.BooleanSupplier domainQuiescent) {
    this(gate,durable,clock,domainQuiescent,()->{});
  }
  /** The domain hook stops new admission first and freezes only once active games end.
   * forceFreeze is separate for unknown outcomes; resume refuses active games/shutdown. */
  public QuarterMaintenanceCoordinator(QuarterMaintenanceGate gate, DurableControl durable, Clock clock,
      java.util.function.BooleanSupplier freezeAndConfirmQuiescent, Runnable resumeDomain) {
    this(gate,durable,clock,freezeAndConfirmQuiescent,resumeDomain,()->freezeAndConfirmQuiescent.getAsBoolean());
  }
  public QuarterMaintenanceCoordinator(QuarterMaintenanceGate gate, DurableControl durable, Clock clock,
      java.util.function.BooleanSupplier drainAndFreeze, Runnable resumeDomain, Runnable forceFreeze) {
    this.forceFreeze=Objects.requireNonNull(forceFreeze);
    this.gate=Objects.requireNonNull(gate);this.durable=Objects.requireNonNull(durable);this.clock=Objects.requireNonNull(clock);
    this.domainQuiescent=Objects.requireNonNull(drainAndFreeze);this.resumeDomain=Objects.requireNonNull(resumeDomain);
  }
  public synchronized Status status() { return status; }
  /** Called at startup and on every wake/timer tick. Never sleeps waiting for writers. */
  public synchronized void tick() {
    if(!gate.enabled()){status=Status.DISABLED;return;}
    if(gate.state()==QuarterMaintenanceGate.State.STARTING && durable.startupWaiting()){status=Status.CHECKING;return;}
    if(status==Status.BLOCKED_MANUAL || gate.state()==QuarterMaintenanceGate.State.BLOCKED
        || gate.state()==QuarterMaintenanceGate.State.BLOCKED_MANUAL){block();return;}
    Instant now=clock.instant();
    if(lastTick!=null && now.isBefore(lastTick)){block();return;}lastTick=now;
    Instant quarter=QuarterlyRetention.currentQuarterStart(now);
    if(status==Status.OPEN && quarter.equals(verifiedQuarter) && gate.state()==QuarterMaintenanceGate.State.OPEN)return;
    if(waitingQuarter!=null) {
      if(!waitingQuarter.equals(quarter)){block();return;}
      drain(quarter);return;
    }
    status=Status.CHECKING;
    Observation observed=read(quarter);
    if(!verified(observed) || !sameQuarter(quarter)) { block();return; }
    if(observed.phase()==Phase.COMPLETE && quarter.equals(observed.through())) {
      open(quarter);return;
    }
    // PENDING/APPLIED/UNKNOWN require a separate, verified recovery implementation;
    // a completion marker alone never authorizes replay or opening admission.
    if(observed.phase()!=Phase.DUE || observed.through()==null || !observed.through().isBefore(quarter)
        || !QuarterlyRetention.currentQuarterStart(observed.through()).equals(observed.through())
        || !observed.historicalRulesCompatible() || quarter.equals(attemptedQuarter)){block();return;}
    waitingQuarter=quarter;
    drain(quarter);
  }
  private void drain(Instant quarter) {
    status=Status.DRAINING;
    gate.beginFinishing();
    if(!quiescent())return;
    gate.beginDrain();
    if(!gate.beginExclusive())return;
    // Re-read after every already-admitted queued/active writer has left the gate.
    Observation fresh=read(quarter);
    if(!verified(fresh)||fresh.phase()!=Phase.DUE||!fresh.historicalRulesCompatible()
        || fresh.through()==null||!fresh.through().isBefore(quarter)
        || !QuarterlyRetention.currentQuarterStart(fresh.through()).equals(fresh.through())
        || !sameQuarter(quarter)||!quiescent()){block();return;}
    attemptedQuarter=quarter;status=Status.RUNNING;
    String operation=UUID.randomUUID().toString();
    try { durable.executeOnce(quarter,fresh,operation); }
    catch(RuntimeException unknown) { /* One readback only; never submit again. */ }
    Observation after=read(quarter);
    if(verified(after)&&after.phase()==Phase.COMPLETE&&quarter.equals(after.through())
        &&operation.equals(after.completedOperation())&&sameQuarter(quarter))open(quarter);
    else block();
  }
  private Observation read(Instant quarter) {
    try { var value=durable.inspect(quarter);return value==null?Observation.unavailable():value; }
    catch(RuntimeException unavailable){return Observation.unavailable();}
  }
  private static boolean verified(Observation value) {
    return value.fullReadVerified()&&value.ownershipVerified()&&value.receiptsVerified()
        &&value.token()!=null&&!value.token().isBlank();
  }
  private boolean quiescent() { domainTouched=true;try{return domainQuiescent.getAsBoolean();}catch(RuntimeException unavailable){return false;} }
  private boolean sameQuarter(Instant expected) { return expected.equals(QuarterlyRetention.currentQuarterStart(clock.instant())); }
  private void open(Instant quarter) {
    try {
      // An in-flight read may finish after shutdown has blocked admission. Do not resume
      // the domain in that terminal state; the domain has its own irreversible shutdown
      // latch for the race after this check. No gate monitor is held across a domain call.
      var state=gate.state();
      if(state==QuarterMaintenanceGate.State.BLOCKED||state==QuarterMaintenanceGate.State.BLOCKED_MANUAL){status=Status.BLOCKED_MANUAL;return;}
      if(gate.state()!=QuarterMaintenanceGate.State.OPEN) {
        if((gate.state()==QuarterMaintenanceGate.State.DRAINING || gate.state()==QuarterMaintenanceGate.State.RECONCILING) && !gate.beginExclusive()){status=Status.DRAINING;return;}
        if(domainTouched){resumeDomain.run();domainTouched=false;}
        gate.openVerified(quarter);
      }
      verifiedQuarter=quarter;waitingQuarter=null;status=Status.OPEN;
    }catch(RuntimeException invalid){block();}
  }
  private void block() {
    gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);
    try {forceFreeze.run();domainTouched=true;}catch(RuntimeException ignored){} // Unknown is never a graceful drain.
    status=Status.BLOCKED_MANUAL;
  }
}
