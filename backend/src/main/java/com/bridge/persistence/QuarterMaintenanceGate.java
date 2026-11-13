package com.bridge.persistence;

import java.time.*;
import java.util.*;

/** Process-local fail-closed barrier. This is NOT proof of exclusive ownership of a remote workbook. */
public final class QuarterMaintenanceGate {
  public enum State { STARTING, OPEN, FINISHING, DRAINING, MAINTENANCE, RECONCILING, BLOCKED_MANUAL, BLOCKED }
  public enum Reason { NONE, CHECKING, QUARTER_CHANGE, DRAIN_REQUESTED, MAINTENANCE,
    EXCLUSIVITY_UNVERIFIED, UNRESOLVED_WRITES, STORAGE_UNAVAILABLE, VERIFICATION_FAILED, UNSUPPORTED_MODE, SHUTDOWN, RETRY_EXHAUSTED, REQUEST_BUDGET_EXHAUSTED }
  public static final class Unavailable extends IllegalStateException {
    public Unavailable() { super("Quarter maintenance unavailable"); }
  }
  private final boolean enabled;
  private final Clock clock;
  private State state;
  private Reason reason;
  private Instant boundary;
  private int operations, writes;
  private final Set<String> registeredMatches = new HashSet<>();
  private final java.util.concurrent.locks.ReentrantLock serial = new java.util.concurrent.locks.ReentrantLock(true);
  private final ThreadLocal<Permit> attached = new ThreadLocal<>();
  public QuarterMaintenanceGate(boolean enabled, Clock clock) {
    this.enabled=enabled;this.clock=Objects.requireNonNull(clock);
    state=enabled?State.STARTING:State.OPEN;reason=enabled?Reason.CHECKING:Reason.NONE;
  }
  public static QuarterMaintenanceGate disabled() { return new QuarterMaintenanceGate(false,Clock.systemUTC()); }
  public boolean enabled() { return enabled; }
  /** Same immutable clock reference for the local business coordinator, not authentication.
   * Only trusted construction can supply it; no environment clock override exists. */
  Clock businessClockForCoordinator() { return clock; }
  private void observe() {
    if(enabled && (state==State.OPEN || state==State.FINISHING) && !QuarterlyRetention.currentQuarterStart(clock.instant()).equals(boundary)) {
      state=State.FINISHING;reason=Reason.QUARTER_CHANGE;
    }
  }
  public synchronized State state() { observe();return state; }
  public synchronized Reason reason() { observe();return reason; }
  public synchronized boolean isOpen() { observe();return state==State.OPEN||state==State.FINISHING; }
  public synchronized boolean acceptsNewGames() { observe();return state==State.OPEN; }
  public synchronized void beginFinishing() {
    if(!enabled)return;observe();if(state==State.OPEN){state=State.FINISHING;reason=Reason.DRAIN_REQUESTED;}
  }
  public synchronized Instant verifiedBoundary() { return boundary; }
  public synchronized void registerMatch(String matchId) {
    if(!enabled)return;observe();
    if(state!=State.OPEN||matchId==null||matchId.isBlank()||registeredMatches.size()>=4096)throw new Unavailable();
    registeredMatches.add(matchId);
  }
  /** Trusted domain finalization only: this match will not submit any persistence work. */
  public synchronized void discardUncollectedMatch(String matchId) { registeredMatches.remove(matchId); }
  public synchronized boolean settlementAllowed(String matchId) { observe();return state==State.FINISHING && registeredMatches.contains(matchId); }
  public synchronized Permit enterSettlement(String matchId) {
    observe();
    if(!enabled||state==State.OPEN){Permit permit=enter(true);permit.matchId=matchId;return permit;}
    if(state!=State.FINISHING||!registeredMatches.contains(matchId))throw new Unavailable();
    operations++;writes++;Permit permit=new Permit(true);permit.matchId=matchId;return permit;
  }
  public synchronized int registeredMatchCount(){return registeredMatches.size();}
  public synchronized int activeOperations() { return operations; }
  public synchronized int activeWrites() { return writes; }
  public synchronized void beginDrain() {
    if(!enabled)return;observe();
    if(state==State.OPEN || state==State.FINISHING || state==State.STARTING){state=State.DRAINING;reason=Reason.DRAIN_REQUESTED;}
  }
  public synchronized void block(Reason cause) {
    Objects.requireNonNull(cause);if(!enabled)return;
    if(state!=State.BLOCKED){state=State.BLOCKED;reason=cause;}
  }
  /** Guarded-only transitions never release or reinterpret a legacy/manual block. */
  public synchronized void reconciling() {
    if(!enabled || state==State.BLOCKED || state==State.BLOCKED_MANUAL)return;
    state=State.RECONCILING;reason=Reason.STORAGE_UNAVAILABLE;
  }
  public synchronized void blockManual(Reason cause) {
    Objects.requireNonNull(cause);if(!enabled || state==State.BLOCKED || state==State.BLOCKED_MANUAL)return;
    state=State.BLOCKED_MANUAL;reason=cause;
  }
  public synchronized boolean beginExclusive() {
    if(!enabled)return false;observe();
    if((state!=State.DRAINING && state!=State.RECONCILING) || operations!=0)return false;
    state=State.MAINTENANCE;reason=Reason.MAINTENANCE;return true;
  }
  /** Caller must first prove exclusive ownership and verify durable maintenance outcome. */
  public synchronized void openVerified(Instant verifiedBoundary) {
    if(!enabled)return;
    if((state!=State.STARTING && state!=State.MAINTENANCE) || operations!=0 || verifiedBoundary==null
        || !QuarterlyRetention.currentQuarterStart(clock.instant()).equals(verifiedBoundary))throw new Unavailable();
    boundary=verifiedBoundary;registeredMatches.clear();state=State.OPEN;reason=Reason.NONE;
  }
  public Permit enterRead() { return enter(false); }
  public Permit enterWrite() { return enter(true); }
  private synchronized Permit enter(boolean write) {
    observe();if(enabled && (state!=State.OPEN && state!=State.FINISHING || write && state==State.FINISHING))throw new Unavailable();
    if(enabled){operations++;if(write)writes++;}return new Permit(write);
  }
  public final class Permit implements AutoCloseable {
    private String matchId;
    public SettlementGrant settlementGrant() {
      synchronized(QuarterMaintenanceGate.this){if(matchId==null||closed)throw new Unavailable();return new SettlementGrant(this,matchId);}
    }
    private final boolean write;
    private boolean closed, successful, settlementRecorded;
    private Thread owner;
    private Permit(boolean write) { this.write=write; }
    public Scope attach() {
      if(enabled)serial.lock();
      try { synchronized(QuarterMaintenanceGate.this) {
        if(closed || owner!=null || (enabled && state!=State.OPEN && state!=State.FINISHING && state!=State.DRAINING))throw new Unavailable();
        owner=Thread.currentThread();var previous=attached.get();attached.set(this);return new Scope(this,previous);
      } } catch(RuntimeException|Error failure) { if(enabled)serial.unlock(); throw failure; }
    }
    /** Must be called only after exact confirmed settlement readback, not queue cancellation. */
    public void settlementRecorded() { synchronized(QuarterMaintenanceGate.this){
      if(closed||matchId==null||owner!=Thread.currentThread()||attached.get()!=this)throw new Unavailable();
      settlementRecorded=true;
    } }
    public void success() { synchronized(QuarterMaintenanceGate.this){if(closed)throw new Unavailable();successful=true;} }
    @Override public void close() {
      synchronized(QuarterMaintenanceGate.this) {
        if(closed)return;
        if(owner!=null)throw new IllegalStateException("Detach operation before closing permit");
        closed=true;
        if(enabled){operations--;if(write)writes--;if(write&&successful&&settlementRecorded&&matchId!=null)registeredMatches.remove(matchId);if(write&&!successful)block(Reason.UNRESOLVED_WRITES);}
      }
    }
  }
  /** Nonconstructible capability tied to an attached, still-active settlement permit. */
  public final class SettlementGrant {
    private final Permit permit;private final String matchId;
    private SettlementGrant(Permit permit,String matchId){this.permit=permit;this.matchId=matchId;}
    public Instant require(String requestedMatchId,Instant currentBoundary) {
      synchronized(QuarterMaintenanceGate.this) {
        observe();
        if(!enabled || permit.closed || !permit.write || permit.owner!=Thread.currentThread()
            || attached.get()!=permit || !matchId.equals(requestedMatchId) || !registeredMatches.contains(matchId)
            || (state!=State.FINISHING&&state!=State.DRAINING) || boundary==null
            || !boundary.isBefore(currentBoundary)
            || !QuarterlyRetention.currentQuarterStart(clock.instant()).equals(currentBoundary))throw new Unavailable();
        return boundary;
      }
    }
  }
  public final class Scope implements AutoCloseable {
    private final Permit permit, previous;private boolean closed;
    private Scope(Permit permit,Permit previous){this.permit=permit;this.previous=previous;}
    @Override public void close(){synchronized(QuarterMaintenanceGate.this){
      if(closed)return;
      if(permit.owner!=Thread.currentThread() || attached.get()!=permit)throw new Unavailable();
      closed=true;permit.owner=null;if(previous==null)attached.remove();else attached.set(previous);
      if(enabled && permit.write && !permit.successful)block(Reason.UNRESOLVED_WRITES);
      if(enabled)serial.unlock();
    }}
  }
  private synchronized void requireWrite() {
    if(!enabled)return;observe();var permit=attached.get();
    if((state!=State.OPEN&&state!=State.FINISHING&&state!=State.DRAINING) || permit==null || permit.closed || !permit.write
        || permit.owner!=Thread.currentThread())throw new Unavailable();
  }
  public SheetsGateway wrap(SheetsGateway delegate) {
    Objects.requireNonNull(delegate);if(!enabled)return delegate;
    return new SheetsGateway(){
      public List<List<String>> read(String tab){return delegate.read(tab);}
      public Map<String,List<List<String>>> readBatch(List<String> tabs){return delegate.readBatch(tabs);}
      public Optional<String> snapshotFingerprint(){return delegate.snapshotFingerprint();}
      public void appendAtomic(Map<String,List<List<Object>>> rows){requireWrite();delegate.appendAtomic(rows);}
      public void replaceAtomic(Map<String,List<List<Object>>> rows){requireWrite();delegate.replaceAtomic(rows);}
      public void saveAccountProfile(int row,List<String> account,List<String> identity){requireWrite();delegate.saveAccountProfile(row,account,identity);}
    };
  }
}
