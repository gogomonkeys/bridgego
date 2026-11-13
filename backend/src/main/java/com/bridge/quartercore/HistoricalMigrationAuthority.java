package com.bridge.quartercore;
import java.time.*;import java.util.*;import java.util.function.Supplier;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Separate one-shot historical grant. Never a metadata-only or ordinary writer capability. */
final class HistoricalMigrationAuthority implements GoogleDispatchAuthority {
 enum State {READY,EXECUTING,ARMED,SENT,ACKNOWLEDGED,VERIFIED,UNKNOWN}
 static final class VerifiedResult {
  private final InitialCutoverAuthority.VerifiedResult bootstrap;private final GoogleSheetsBinding binding;private final String beforeToken,afterToken,sourceCommit,sourceHash,approvalReference;private final Instant verifiedAt;
  private VerifiedResult(HistoricalMigrationAuthority a,String after){bootstrap=a.bootstrap;binding=a.binding;beforeToken=a.beforeToken;afterToken=after;sourceCommit=a.approval.sourceCommit();sourceHash=a.approval.sourceHash();approvalReference=a.approval.approvalReference();verifiedAt=a.clock.instant();}
  InitialCutoverAuthority.VerifiedResult bootstrap(){return bootstrap;}GoogleSheetsBinding binding(){return binding;}String beforeToken(){return beforeToken;}String afterToken(){return afterToken;}String sourceCommit(){return sourceCommit;}String sourceHash(){return sourceHash;}String approvalReference(){return approvalReference;}Instant verifiedAt(){return verifiedAt;}
 }
 private final InitialCutoverAuthority.VerifiedResult bootstrap;private final GoogleSheetsBinding binding;private final HistoricalCasualMigrationPlan.Approval approval;private final JournalPlan plan;private final String beforeToken;private final Clock clock;private final Instant created;private Instant last;private final long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();private final CutoverAssessment.FreezeEvidence freeze;private final CutoverAssessment.FreezeVerifier verifier;private State state=State.READY;private VerifiedResult result;
 private HistoricalMigrationAuthority(GoogleSheetsBinding b,Book before,InitialCutoverAuthority.VerifiedResult proof,HistoricalCasualMigrationPlan.Approval approval,Clock clock,CutoverAssessment.FreezeEvidence freeze,CutoverAssessment.FreezeVerifier verifier){
  binding=Objects.requireNonNull(b);bootstrap=Objects.requireNonNull(proof);this.approval=Objects.requireNonNull(approval);this.clock=Objects.requireNonNull(clock);this.freeze=Objects.requireNonNull(freeze);this.verifier=Objects.requireNonNull(verifier);created=clock.instant();last=created;beforeToken=JournalSessionAuthority.fullToken(before);
  if(!b.equals(proof.binding())||!beforeToken.equals(proof.afterToken())||proof.verifiedAt().isAfter(created))throw new Rejected();plan=HistoricalCasualMigrationPlan.prepare(b.manualBinding(),before,approval,clock);checkFreeze();
 }
 static HistoricalMigrationAuthority approveFromBootstrap(GoogleSheetsBinding b,Book before,InitialCutoverAuthority.VerifiedResult proof,HistoricalCasualMigrationPlan.Approval approval,boolean explicitlyApproved,Clock clock,CutoverAssessment.FreezeEvidence freeze,CutoverAssessment.FreezeVerifier verifier){if(!explicitlyApproved)throw new Rejected();return new HistoricalMigrationAuthority(b,before,proof,approval,clock,freeze,verifier);}
 private synchronized void time(){Instant now=clock.instant();if(now.isBefore(last)||!now.isBefore(created.plusSeconds(300))||System.nanoTime()>=deadline)throw new Rejected();last=now;}
 private void checkFreeze(){synchronized(this){time();if(state==State.UNKNOWN||!clock.instant().isBefore(freeze.expiresAt()))throw new Rejected();}verifier.verify(binding.manualBinding(),beforeToken,freeze);synchronized(this){time();if(state==State.UNKNOWN||!clock.instant().isBefore(freeze.expiresAt()))throw new Rejected();}}
 synchronized void requireBootstrap(InitialCutoverAuthority.VerifiedResult proof){if(bootstrap!=proof)throw new Rejected();}
 synchronized State state(){return state;}public synchronized void requireSession(GoogleSheetsBinding b){time();if(!binding.equals(b)||!clock.instant().isBefore(freeze.expiresAt())||state==State.UNKNOWN||state==State.VERIFIED)throw new Rejected();}
 public void requireReadSession(GoogleSheetsBinding b){requireSession(b);}public synchronized void requireReady(String target){requireSession(binding);if(state!=State.ARMED||!target.equals(binding.path()+":batchUpdate"))throw new Rejected();}
 Book execute(Supplier<Book> freshBefore,JournalPlan.Sink sink,Supplier<Book> freshAfter){
  synchronized(this){requireSession(binding);if(state!=State.READY)throw new Rejected();state=State.EXECUTING;}
  try{Book observed=freshBefore.get();NativeCommitJournal.verify(binding.manualBinding(),observed,clock);if(!beforeToken.equals(JournalSessionAuthority.fullToken(observed)))throw new Rejected();checkFreeze();
   synchronized(this){time();if(state!=State.EXECUTING)throw new Rejected();state=State.ARMED;}plan.dispatchOnce(sink);
   synchronized(this){time();if(state!=State.ACKNOWLEDGED)throw new Rejected();}Book after=freshAfter.get();
   synchronized(this){time();if(state!=State.ACKNOWLEDGED||!plan.matchesAfter(after))throw new Rejected();result=new VerifiedResult(this,JournalSessionAuthority.fullToken(after));state=State.VERIFIED;return after.copy();}
  }catch(RuntimeException|Error e){closeUnknown();throw e;}
 }
 public Dispatch beforeDispatch(String target,String wire){try{
  synchronized(this){requireReady(target);if(!plan.wire().equals(wire))throw new Rejected();}checkFreeze();
  synchronized(this){requireReady(target);if(!clock.instant().isBefore(freeze.expiresAt())||!plan.wire().equals(wire))throw new Rejected();state=State.SENT;}
  return new Dispatch(){public void acknowledged(){synchronized(HistoricalMigrationAuthority.this){try{time();if(state!=State.SENT)throw new Rejected();state=State.ACKNOWLEDGED;}catch(RuntimeException e){closeUnknown();throw e;}}}public void unknown(){closeUnknown();}};
 }catch(RuntimeException|Error e){closeUnknown();throw e;}}
 synchronized VerifiedResult verifiedResult(){if(state!=State.VERIFIED||result==null)throw new Rejected();return result;}
 public synchronized void closeUnknown(){if(state!=State.VERIFIED)state=State.UNKNOWN;}
 @Override public String toString(){return "HistoricalMigrationAuthority[REDACTED]";}
}
