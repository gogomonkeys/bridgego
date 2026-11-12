package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Per-operation deletion grant. No environment provisioning, renewal, recovery or ordinary-write authority. */
final class JournalQuarterAuthority {
  /** Trusted integration must verify explicit approval for this deletion operation and
   * independently stopped/exclusive sources. Environment flags, a CLOSED marker and
   * a self-computed baseline are not evidence. No default implementation is installed. */
  @FunctionalInterface interface ApprovalVerifier {
    void verify(ManualQuarterMaintenance.Binding binding,Instant quarter,String baselineToken,String owner,String operationId);
  }
  enum State { READY, PREPARED, IN_FLIGHT, COMPLETE, UNKNOWN }
  private final ManualQuarterMaintenance.Binding binding;
  private final Clock clock;private final ApprovalVerifier approval;
  private final String owner,operationId,baselineToken;private final Instant quarter,expires;
  private final long deadline;private Instant last;private Book confirmed;private final QuarterPhasePlans plans;
  private JournalPlan pending;private State state=State.READY;private int phase;
  private JournalQuarterAuthority(ManualQuarterMaintenance.Binding binding,Book baseline,String owner,String operationId,
      ApprovalVerifier approval,Clock clock,Duration duration){
    this.binding=Objects.requireNonNull(binding);this.clock=Objects.requireNonNull(clock);this.approval=Objects.requireNonNull(approval);
    uuid(operationId);ascii(owner,40);if(owner.isBlank()||duration==null||duration.isZero()||duration.isNegative()||duration.compareTo(Duration.ofMinutes(5))>0)throw new Rejected();
    this.owner=owner;this.operationId=operationId;last=clock.instant();quarter=QuarterlyRetention.currentQuarterStart(last);
    expires=last.plus(duration);deadline=System.nanoTime()+duration.toNanos();
    if(JournalQuarterProtocol.inspect(binding,baseline,clock).state()!=JournalQuarterProtocol.RecoveryState.READY_DUE)throw new Rejected();
    plans=new QuarterPhasePlans(binding,owner,clock);confirmed=baseline.copy();baselineToken=JournalSessionAuthority.fullToken(baseline);check();
  }
  static JournalQuarterAuthority provision(ManualQuarterMaintenance.Binding binding,Book baseline,String owner,String operationId,
      ApprovalVerifier approval,Clock clock,Duration duration){return new JournalQuarterAuthority(binding,baseline,owner,operationId,approval,clock,duration);}
  synchronized void requireContext(ManualQuarterMaintenance.Binding target,String expectedOwner){
    check();if(!binding.equals(target)||!owner.equals(expectedOwner)){unknown();throw new Rejected();}
  }
  synchronized void requireContext(ManualQuarterMaintenance.Binding target,Book exactClosed,String expectedOwner,String expectedOperation){
    requireContext(target,expectedOwner);
    if(!operationId.equals(expectedOperation)||!baselineToken.equals(JournalSessionAuthority.fullToken(exactClosed))||phase!=0||state!=State.READY){unknown();throw new Rejected();}
  }
  synchronized String verifiedClosedToken(){
    check(true);if(state!=State.COMPLETE)throw new Rejected();return JournalSessionAuthority.fullToken(confirmed);
  }
  synchronized State state(){return state;}
  synchronized int verifiedPhases(){return phase;}
  private void check(){check(false);}
  private void check(boolean finalRead){
    try{Instant now=clock.instant();if(state==State.UNKNOWN||(!finalRead&&state==State.COMPLETE)||now.isBefore(last)||!now.isBefore(expires)||System.nanoTime()>=deadline||!QuarterlyRetention.currentQuarterStart(now).equals(quarter))throw new Rejected();
      last=now;approval.verify(binding,quarter,baselineToken,owner,operationId);
    }catch(RuntimeException failure){unknown();throw failure;}
  }
  synchronized void requireTransport(ManualQuarterMaintenance.Binding target,boolean read){
    check(read);if(!binding.equals(target)){unknown();throw new Rejected();}
  }
  synchronized void requireInFlight(JournalPlan plan){check();if(state!=State.IN_FLIGHT||pending!=plan)throw new Rejected();}
  synchronized JournalPlan nextPlan(){
    check();if(state!=State.READY)throw new Rejected();
    try{
      pending=plans.next(confirmed,phase);state=State.PREPARED;return pending;
    }catch(RuntimeException invalid){unknown();throw invalid;}
  }
  /** Caller must consume JournalPlan.dispatchOnce separately. Exact object and wire are fixed by this grant. */
  synchronized void beforeDispatch(JournalPlan plan){check();if(state!=State.PREPARED||plan!=pending)throw new Rejected();state=State.IN_FLIGHT;}
  synchronized void verified(Book fresh){
    check();try{if(state!=State.IN_FLIGHT||pending==null||!pending.matchesAfter(fresh))throw new Rejected();NativeCommitJournal.verify(binding,fresh,clock);
      confirmed=fresh.copy();pending=null;phase++;
      if(phase==5){if(JournalQuarterProtocol.inspect(binding,fresh,clock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();state=State.COMPLETE;}else state=State.READY;
    }catch(RuntimeException invalid){unknown();throw invalid;}
  }
  synchronized void unknown(){state=State.UNKNOWN;pending=null;}
}
