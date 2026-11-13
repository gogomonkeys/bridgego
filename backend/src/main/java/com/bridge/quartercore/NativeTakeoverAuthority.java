package com.bridge.quartercore;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Single OPEN ownership-CAS capability. No business data mutation, renewal or replay. */
final class NativeTakeoverAuthority implements GooglePlanAuthority {
  @FunctionalInterface interface NativeSourceEvidence {
    /** Prove this target has completed legacy exclusion: every remaining possible writer
     * uses the native receipt CAS. Bind source authorization and fixed full OPEN snapshot;
     * instance count, a local env flag, or OPEN/journal alone are insufficient. */
    void verify(GoogleSheetsBinding binding,String fullToken,String previousOwner,String newOwner);
  }
  enum State { READY, EXECUTING, PREPARED, IN_FLIGHT, ACKNOWLEDGED, VERIFIED, UNKNOWN }
  private final GoogleSheetsBinding binding;private final Clock clock;private final NativeSourceEvidence evidence;
  private final Book before;private Book after;private final String token,oldOwner,newOwner;private final JournalPlan plan;
  private final Instant quarter,expires;private final long deadline;private Instant last;private State state=State.READY;private boolean closed;
  private NativeTakeoverAuthority(GoogleSheetsBinding binding,Book before,String newOwner,NativeSourceEvidence evidence,Clock clock,Duration duration){
    this.binding=Objects.requireNonNull(binding);this.clock=Objects.requireNonNull(clock);this.evidence=Objects.requireNonNull(evidence);
    if(duration==null||duration.isNegative()||duration.isZero()||duration.compareTo(Duration.ofMinutes(5))>0)throw new Rejected();
    this.before=before.copy();token=JournalSessionAuthority.fullToken(before);oldOwner=root(new ArrayList<>(before.metadata.values())).owner();this.newOwner=newOwner;
    last=clock.instant();quarter=QuarterlyRetention.currentQuarterStart(last);expires=last.plus(duration);deadline=System.nanoTime()+duration.toNanos();
    plan=NativeTakeoverPlan.create(binding.manualBinding(),before,newOwner,clock);check(true);
  }
  static NativeTakeoverAuthority provision(GoogleSheetsBinding binding,Book before,String newOwner,NativeSourceEvidence evidence,Clock clock,Duration duration){return new NativeTakeoverAuthority(binding,before,newOwner,evidence,clock,duration);}
  synchronized State state(){return state;}
  synchronized JournalPlan plan(){check(false);if(state!=State.READY)throw new Rejected();return plan;}
  private void check(boolean read){
    try{Instant now=clock.instant();if(closed||state==State.UNKNOWN||(!read&&state==State.VERIFIED)||now.isBefore(last)||!now.isBefore(expires)||System.nanoTime()>=deadline||!quarter.equals(QuarterlyRetention.currentQuarterStart(now)))throw new Rejected();last=now;evidence.verify(binding,token,oldOwner,newOwner);}
    catch(RuntimeException failure){closeUnknown();throw failure;}
  }
  public synchronized void requireReadSession(GoogleSheetsBinding requested){check(true);if(!binding.equals(requested)){closeUnknown();throw new Rejected();}}
  public synchronized void requireSession(GoogleSheetsBinding requested){check(false);if(!binding.equals(requested)){closeUnknown();throw new Rejected();}}
  public synchronized void arm(JournalPlan requested){check(false);if(state!=State.EXECUTING||requested!=plan)throw new Rejected();state=State.PREPARED;}
  public synchronized void requireReady(String target){check(false);if(state!=State.PREPARED||!target.equals(binding.path()+":batchUpdate"))throw new Rejected();}
  public synchronized Dispatch beforeDispatch(String target,String wire){
    requireReady(target);if(!plan.wire().equals(wire))throw new Rejected();state=State.IN_FLIGHT;
    return new Dispatch(){private boolean ended;
      public void acknowledged(){synchronized(NativeTakeoverAuthority.this){check(false);if(ended||state!=State.IN_FLIGHT)throw new Rejected();ended=true;state=State.ACKNOWLEDGED;}}
      public void unknown(){synchronized(NativeTakeoverAuthority.this){if(ended)return;ended=true;closeUnknown();}}
    };
  }
  public synchronized void finishDispatch(){check(false);if(state!=State.ACKNOWLEDGED)throw new Rejected();}
  void execute(NarrowSheetsTransport transport){
    synchronized(this){check(false);if(state!=State.READY)throw new Rejected();state=State.EXECUTING;}
    try(var scope=transport.operation("native-open-takeover",root(new ArrayList<>(before.metadata.values())),BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){
      Book fresh=transport.snapshot();if(!JournalSessionAuthority.fullToken(fresh).equals(token))throw new Rejected();
      synchronized(this){check(false);}
      if(transport instanceof JournalPlanTransport sink)sink.dispatchPlan(plan);
      else {arm(plan);plan.dispatchOnce(wire->{var dispatch=beforeDispatch(binding.path()+":batchUpdate",wire);try{transport.batch(wire);dispatch.acknowledged();}catch(RuntimeException|Error failure){dispatch.unknown();throw failure;}});finishDispatch();}
      Book actual=transport.snapshot();
      synchronized(this){check(false);if(state!=State.ACKNOWLEDGED||!plan.matchesAfter(actual))throw new Rejected();NativeCommitJournal.verify(binding.manualBinding(),actual,clock);after=actual.copy();state=State.VERIFIED;}
    }catch(RuntimeException|Error failure){closeUnknown();throw failure;}
  }
  synchronized Book verifiedBook(){check(true);if(state!=State.VERIFIED)throw new Rejected();return after.copy();}
  /** Immutable proof of the already acknowledged/full-read-verified result only.
   * Survives handle revocation, but grants no read/write/adoption permission itself. */
  record OwnershipEvidence(GoogleSheetsBinding binding,String owner,String token) {}
  synchronized OwnershipEvidence ownershipEvidence(){
    return new OwnershipEvidence(binding,newOwner,verifiedOwnershipToken());
  }
  synchronized String verifiedOwnershipToken(){
    if(state!=State.VERIFIED||after==null)throw new Rejected();return JournalSessionAuthority.fullToken(after);
  }
  public synchronized void closeUnknown(){closed=true;if(state!=State.VERIFIED)state=State.UNKNOWN;}
}
