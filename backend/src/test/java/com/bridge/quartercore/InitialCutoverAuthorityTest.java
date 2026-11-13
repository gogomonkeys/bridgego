package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class InitialCutoverAuthorityTest {
 static final Clock CLOCK=Clock.systemUTC();
 static final GoogleSheetsBinding B=new GoogleSheetsBinding(RecoveredFixture.ID,RecoveredFixture.DATASET,"bridgego-restricted-test","test@project.iam.gserviceaccount.com");
 static Book legacy()throws Exception{Book b=RecoveredFixture.book();b.metadata.clear();return b;}
 static CutoverAssessment.Plan plan(Book b,CutoverAssessment.FreezeVerifier verifier){return CutoverAssessment.plan(B.manualBinding(),b,CutoverAssessment.token(B.manualBinding(),b),new CutoverAssessment.FreezeEvidence("approved","source",CLOCK.instant().plusSeconds(60)),verifier,CLOCK);}
 static InitialCutoverAuthority authority(Book b){return new InitialCutoverAuthority(B,plan(b,(t,s,e)->{}),CLOCK);}
 @Test void exactAckAndFullReadbackOnlyCreateProofAndPreserveTables()throws Exception{
  Book b=legacy();Book[] remote={b};var a=authority(b);var executor=Executors.newSingleThreadExecutor();
  try{Book result=a.execute(()->b,w->{try{executor.submit(()->{var ticket=a.beforeDispatch(B.path()+":batchUpdate",w);remote[0]=simulate(b,w);ticket.acknowledged();}).get(2,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}},()->remote[0]);
   assertEquals(b.tables(),result.tables());assertEquals(InitialCutoverAuthority.State.VERIFIED,a.state());assertEquals(JournalSessionAuthority.fullToken(result),a.verifiedResult().afterToken());a.closeUnknown();assertNotNull(a.verifiedResult());
   assertThrows(RuntimeException.class,()->a.execute(()->b,w->fail(),()->b));
  }finally{executor.shutdownNow();}
 }
 @Test void directDispatchAndWrongBindingReject()throws Exception{
  Book b=legacy();var p=plan(b,(t,s,e)->{});var a=new InitialCutoverAuthority(B,p,CLOCK);
  assertThrows(RuntimeException.class,()->a.beforeDispatch(B.path()+":batchUpdate",p.exactWire()));
  var wrong=new GoogleSheetsBinding("other-spreadsheet",B.datasetId(),B.purpose(),B.serviceAccountIssuer());
  assertThrows(RuntimeException.class,()->new InitialCutoverAuthority(wrong,p,CLOCK));
 }
 @Test void unchangedOrAppliedUnknownCannotBeRetriedOrCertified()throws Exception{
  for(boolean apply:new boolean[]{false,true}){Book b=legacy();Book[] remote={b};var a=authority(b);
   assertThrows(RuntimeException.class,()->a.execute(()->b,w->{var ticket=a.beforeDispatch(B.path()+":batchUpdate",w);if(apply)remote[0]=simulate(b,w);ticket.unknown();throw new Unknown();},()->remote[0]));
   assertEquals(InitialCutoverAuthority.State.UNKNOWN,a.state());assertThrows(RuntimeException.class,a::verifiedResult);assertThrows(RuntimeException.class,()->a.execute(()->remote[0],w->fail(),()->remote[0]));
  }
 }
 @Test void changedBaselineWrongWireAndMissingAckNeverVerify()throws Exception{
  Book b=legacy();Book changed=b.copy();changed.sheet("accounts").rows.get(1).set(2,"changed");
  var a=authority(b);assertThrows(RuntimeException.class,()->a.execute(()->changed,w->fail(),()->b));
  var w=authority(b);assertThrows(RuntimeException.class,()->w.execute(()->b,wire->w.beforeDispatch(B.path()+":batchUpdate",wire+" "),()->b));
  var noAck=authority(b);assertThrows(RuntimeException.class,()->noAck.execute(()->b,wire->{},()->b));
 }
 @Test void lateReadbackCannotResurrectCancelledAttempt()throws Exception{
  Book b=legacy();Book[] remote={b};var a=authority(b);
  assertThrows(RuntimeException.class,()->a.execute(()->b,w->{var d=a.beforeDispatch(B.path()+":batchUpdate",w);remote[0]=simulate(b,w);d.acknowledged();},()->{a.closeUnknown();return remote[0];}));
  assertEquals(InitialCutoverAuthority.State.UNKNOWN,a.state());
 }
 @Test void revokedFreezeRejectsBeforeFirstDispatch()throws Exception{
  Book b=legacy();AtomicInteger checks=new AtomicInteger();var a=new InitialCutoverAuthority(B,plan(b,(t,s,e)->{if(checks.incrementAndGet()>1)throw new Rejected();}),CLOCK);
  assertThrows(RuntimeException.class,()->a.execute(()->b,w->fail(),()->b));assertEquals(InitialCutoverAuthority.State.UNKNOWN,a.state());
 }
}
