package com.bridge.quartercore;
import java.time.*;import java.util.*;import java.util.function.Supplier;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** One exact metadata initialization. No flags or Sheet markers grant authority. */
final class InitialCutoverAuthority implements GoogleDispatchAuthority {
 enum State { READY, EXECUTING, ARMED, SENT, ACKNOWLEDGED, VERIFIED, UNKNOWN }
 static final class VerifiedResult {
  private final GoogleSheetsBinding binding;private final String beforeToken,afterToken;private final Instant verifiedAt;
  private VerifiedResult(GoogleSheetsBinding b,String before,String after,Instant at){binding=b;beforeToken=before;afterToken=after;verifiedAt=at;}
  GoogleSheetsBinding binding(){return binding;}String beforeToken(){return beforeToken;}String afterToken(){return afterToken;}Instant verifiedAt(){return verifiedAt;}
 }
 private final GoogleSheetsBinding binding;private final CutoverAssessment.Plan plan;private final Clock clock;private final Instant created;
 private final long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();private State state=State.READY;private VerifiedResult result;private Instant last;
 InitialCutoverAuthority(GoogleSheetsBinding b,CutoverAssessment.Plan p,Clock c){binding=Objects.requireNonNull(b);plan=Objects.requireNonNull(p);clock=Objects.requireNonNull(c);created=c.instant();last=created;plan.requireScopeTime();if(!b.manualBinding().equals(p.targetBinding()))throw new Rejected();}
 private void time(){plan.requireScopeTime();Instant n=clock.instant();if(n.isBefore(last)||!n.isBefore(created.plusSeconds(300))||System.nanoTime()>=deadline)throw new Rejected();last=n;}
 String unchangedAnchorGuard(){return NativeCommitJournal.canonical(NarrowQuarterPoc.guard(plan.expected().sheet("retention_ledger").id,"retention_ledger"));}
 synchronized State state(){return state;}
 public synchronized void requireSession(GoogleSheetsBinding b){time();if(!binding.equals(b)||state==State.UNKNOWN||state==State.VERIFIED)throw new Rejected();}
 public void requireReadSession(GoogleSheetsBinding b){requireSession(b);}
 public synchronized void requireReady(String target){requireSession(binding);if(state!=State.ARMED||!target.equals(binding.path()+":batchUpdate"))throw new Rejected();}
 /** Only execute's validated plan callback can arm. No public arm or arbitrary wire setter. */
 Book execute(Supplier<Book> freshBefore,JournalPlan.Sink sink,Supplier<Book> freshAfter){
  synchronized(this){requireSession(binding);if(state!=State.READY)throw new Rejected();state=State.EXECUTING;}
  try{
   Book before=freshBefore.get();
   plan.dispatchOnce(before,wire->{synchronized(this){time();if(state!=State.EXECUTING||!wire.equals(plan.exactWire()))throw new Rejected();state=State.ARMED;}sink.dispatchExact(wire);});
   synchronized(this){time();if(state!=State.ACKNOWLEDGED)throw new Rejected();}
   Book observed=freshAfter.get();
   synchronized(this){time();if(state!=State.ACKNOWLEDGED||plan.reconcile(observed)!=CutoverAssessment.Plan.Readback.EXACT_APPLIED)throw new Rejected();result=new VerifiedResult(binding,plan.beforeToken(),JournalSessionAuthority.fullToken(observed),clock.instant());state=State.VERIFIED;return observed.copy();}
  }catch(RuntimeException|Error bad){closeUnknown();throw bad;}
 }
 public Dispatch beforeDispatch(String target,String wire){
  try{return prepareDispatch(target,wire);}catch(RuntimeException|Error bad){closeUnknown();throw bad;}
 }
 private Dispatch prepareDispatch(String target,String wire){
  synchronized(this){requireReady(target);if(!plan.exactWire().equals(wire))throw new Rejected();}
  // External freeze verifier must not hold the capability monitor across I/O.
  plan.revalidate();
  synchronized(this){requireReady(target);if(!plan.exactWire().equals(wire))throw new Rejected();state=State.SENT;}
  return new Dispatch(){public void acknowledged(){synchronized(InitialCutoverAuthority.this){try{time();if(state!=State.SENT)throw new Rejected();state=State.ACKNOWLEDGED;}catch(RuntimeException bad){closeUnknown();throw bad;}}}public void unknown(){closeUnknown();}};
 }
 synchronized VerifiedResult verifiedResult(){if(state!=State.VERIFIED||result==null)throw new Rejected();return result;}
 public synchronized void closeUnknown(){if(state!=State.VERIFIED)state=State.UNKNOWN;}
 @Override public String toString(){return "InitialCutoverAuthority[REDACTED]";}
}
