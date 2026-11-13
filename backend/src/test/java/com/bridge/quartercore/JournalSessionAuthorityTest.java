package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class JournalSessionAuthorityTest {
  static final String OWNER="session-writer";
  static final Clock CLOCK=JournalQuarterProtocolTest.CLOCK;
  static final GoogleSheetsBinding BINDING=new GoogleSheetsBinding(RecoveredFixture.ID,RecoveredFixture.DATASET,"bridgego-restricted-test","bridgego-sheets@bridgego-513805.iam.gserviceaccount.com");
  static Book clean()throws Exception {
    var m=new JournalQuarterProtocolTest.Memory(JournalQuarterProtocolTest.baseline());
    new JournalQuarterProtocol(BINDING.manualBinding(),m,CLOCK,"seed-quarter").start();return m.book;
  }
  static JournalSessionAuthority authority(Book before,Clock clock,Duration duration) {
    String baseline=JournalSessionAuthority.fullToken(before);
    return JournalSessionAuthority.provision(BINDING,before,OWNER,"test-source-v1","test-explicit-cutover",(b,o,s,a,t)->{
      if(!b.equals(BINDING)||!o.equals(OWNER)||!s.equals("test-source-v1")||!a.equals("test-explicit-cutover")||!t.equals(baseline))throw new Rejected();
    },clock,duration);
  }
  static JournalPlan claim(Book before){
    Root a=root(new ArrayList<>(before.metadata.values()));Root b=new Root(a.dataset(),a.epoch(),a.anchor(),a.nextSheet(),a.next()+1,OWNER,"OPEN","CLAIM-"+OWNER,hash(OWNER));
    return NativeCommitJournal.append(BINDING.manualBinding(),before,QuarterPlan.phaseWire(a,b,List.of()),CLOCK);
  }
  static JournalPlan profile(Book before,String name){
    Root a=root(new ArrayList<>(before.metadata.values()));Root b=new Root(a.dataset(),a.epoch(),a.anchor(),a.nextSheet(),a.next()+1,OWNER,"OPEN","PROFILE-"+name,hash(name));
    return NativeCommitJournal.append(BINDING.manualBinding(),before,QuarterPlan.phaseWire(a,b,List.of(update(before.sheet("accounts").id,1,2,2,3,List.of(List.of(name))))),CLOCK);
  }
  static void apply(JournalSessionAuthority authority,JournalPlan plan){
    authority.arm(plan);var d=authority.beforeDispatch(BINDING.path()+":batchUpdate",plan.wire());d.acknowledged();authority.verified(plan.expected());
  }
  @Test void explicitCutoverNeededAndNoCapabilityFromFlagOrMissingApproval()throws Exception {
    Book before=clean();
    assertThrows(RuntimeException.class,()->JournalSessionAuthority.provision(BINDING,before,OWNER,"source","",(b,o,s,a,t)->{},CLOCK,Duration.ofMinutes(5)));
    assertThrows(RuntimeException.class,()->JournalSessionAuthority.provision(BINDING,before,OWNER,"source","approval",null,CLOCK,Duration.ofMinutes(5)));
    assertThrows(RuntimeException.class,()->JournalSessionAuthority.provision(BINDING,before,OWNER,"source","approval",(b,o,s,a,t)->{throw new Rejected();},CLOCK,Duration.ofMinutes(5)));
    AtomicInteger reads=new AtomicInteger();var cfg=new JournalGoogleFactory.Configuration(true,BINDING.spreadsheetId(),BINDING.datasetId(),BINDING.purpose(),BINDING.serviceAccountIssuer(),()->{reads.incrementAndGet();throw new AssertionError();});
    assertThrows(RuntimeException.class,()->JournalGoogleFactory.open(cfg,null));assertEquals(0,reads.get());
    var off=new JournalGoogleFactory.Configuration(BINDING.spreadsheetId(),BINDING.datasetId(),BINDING.purpose(),BINDING.serviceAccountIssuer(),cfg.credentials());
    assertThrows(RuntimeException.class,()->JournalGoogleFactory.open(off,authority(before,CLOCK,Duration.ofMinutes(5))));assertEquals(0,reads.get());
  }
  @Test void exactWireAndTargetConsumedOnceAckNeedsFreshJournalBeforeNextWrite()throws Exception {
    Book before=clean();var a=authority(before,CLOCK,Duration.ofMinutes(5));var claim=claim(before);a.arm(claim);
    assertThrows(RuntimeException.class,()->a.beforeDispatch("/v4/spreadsheets/wrong:batchUpdate",claim.wire()));
    assertThrows(RuntimeException.class,()->a.beforeDispatch(BINDING.path()+":batchUpdate",claim.wire()+" "));
    var dispatch=a.beforeDispatch(BINDING.path()+":batchUpdate",claim.wire());
    assertThrows(RuntimeException.class,()->a.beforeDispatch(BINDING.path()+":batchUpdate",claim.wire()));dispatch.acknowledged();
    var p=profile(claim.expected(),"Updated");assertThrows(RuntimeException.class,()->a.arm(p));a.verified(claim.expected());
    apply(a,p);assertEquals(JournalSessionAuthority.State.READY,a.state());
  }
  @Test void unknownAndWrongReadbackCannotRenewOrReplay()throws Exception {
    Book before=clean();var a=authority(before,CLOCK,Duration.ofMinutes(5));var plan=claim(before);a.arm(plan);
    a.beforeDispatch(BINDING.path()+":batchUpdate",plan.wire()).unknown();
    assertEquals(JournalSessionAuthority.State.UNKNOWN,a.state());assertThrows(RuntimeException.class,()->a.verified(plan.expected()));
    assertThrows(RuntimeException.class,()->a.arm(plan));
    var second=authority(before,CLOCK,Duration.ofMinutes(5));second.arm(plan);second.beforeDispatch(BINDING.path()+":batchUpdate",plan.wire()).acknowledged();
    assertThrows(RuntimeException.class,()->second.verified(before));assertEquals(JournalSessionAuthority.State.UNKNOWN,second.state());
  }
  @Test void expiryOrClockRollbackBlocksBeforeCredentialRead()throws Exception {
    class Time extends Clock {Instant now=CLOCK.instant();public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}}
    for(boolean rollback:new boolean[]{false,true}) {
      var time=new Time();var a=authority(clean(),time,Duration.ofSeconds(1));time.now=rollback?time.now.minusSeconds(1):time.now.plusSeconds(1);
      AtomicInteger reads=new AtomicInteger();var cfg=new JournalGoogleFactory.Configuration(true,BINDING.spreadsheetId(),BINDING.datasetId(),BINDING.purpose(),BINDING.serviceAccountIssuer(),()->{reads.incrementAndGet();throw new AssertionError();});
      assertThrows(RuntimeException.class,()->JournalGoogleFactory.open(cfg,a));assertEquals(0,reads.get());assertEquals(rollback?JournalSessionAuthority.State.UNKNOWN:JournalSessionAuthority.State.EXPIRED_CLEAN,a.state());
    }
  }
  @Test void ordinaryCapabilityRejectsMaintenanceAndHiddenReceiptDeletion()throws Exception {
    Book before=clean();var a=authority(before,CLOCK,Duration.ofMinutes(5));var claim=claim(before);apply(a,claim);Book opened=claim.expected();
    Root r=root(new ArrayList<>(opened.metadata.values()));Root pending=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,OWNER,"PENDING","maintenance",hash("maintenance"));
    var maintenance=NativeCommitJournal.append(BINDING.manualBinding(),opened,QuarterPlan.phaseWire(r,pending,List.of()),CLOCK);
    assertThrows(RuntimeException.class,()->a.arm(maintenance));
    var good=profile(opened,"good");var requests=new ArrayList<Object>();requests.add(deleteMetadata(r.next()));parse(good.wire()).path("requests").forEach(x->requests.add(JSON.convertValue(x,Object.class)));
    String deceptive=wire(requests);Book expected=simulate(opened,deceptive);
    var fabricated=new JournalPlan(BINDING.manualBinding(),good.beforeDigest(),deceptive,expected,good.evidence(),CLOCK);
    assertThrows(RuntimeException.class,()->a.arm(fabricated));
  }
  @Test void realFactoryHandleCompositionUsesFinalPlanAndNeverRawBatch()throws Exception {
    Book before=clean();var a=authority(before,CLOCK,Duration.ofMinutes(5));Book[] actual={before};AtomicInteger writes=new AtomicInteger();
    NarrowSheetsTransport endpoint=new NarrowSheetsTransport(){
      public Book snapshot(){return actual[0].copy();}public List<Metadata> control(){return new ArrayList<>(actual[0].metadata.values());}
      public List<List<String>> related(String t){return copyRows(actual[0].sheet(t).rows);}public boolean unsupportedStructure(){return false;}
      public void batch(String w){var dispatch=a.beforeDispatch(BINDING.path()+":batchUpdate",w);writes.incrementAndGet();actual[0]=simulate(actual[0],w);dispatch.acknowledged();}
    };
    try(var handle=new JournalGoogleFactory.Handle(endpoint,()->{},a,BINDING)) {
      var transport=handle.transport();assertThrows(RuntimeException.class,()->transport.batch(claim(before).wire()));
      transport.dispatchPlan(claim(before));transport.dispatchPlan(profile(actual[0],"composed"));assertEquals(2,writes.get());
      assertEquals("composed",actual[0].sheet("accounts").rows.get(1).get(2));
    }
    assertEquals(JournalSessionAuthority.State.CLOSED,a.state());
  }
  @Test void providerAlwaysClosesWhenDelegateCloseFails()throws Exception {
    Book before=clean();var a=authority(before,CLOCK,Duration.ofMinutes(5));AtomicInteger providerCloses=new AtomicInteger(),delegateCloses=new AtomicInteger();
    class BrokenClose implements NarrowSheetsTransport,AutoCloseable {
      public Book snapshot(){return before.copy();}public List<Metadata> control(){return new ArrayList<>(before.metadata.values());}
      public List<List<String>> related(String t){return copyRows(before.sheet(t).rows);}public boolean unsupportedStructure(){return false;}
      public void batch(String wire){throw new Rejected();}public void close(){delegateCloses.incrementAndGet();throw new IllegalStateException();}
    }
    var handle=new JournalGoogleFactory.Handle(new BrokenClose(),providerCloses::incrementAndGet,a,BINDING);
    assertThrows(RuntimeException.class,handle::close);assertEquals(1,providerCloses.get());assertEquals(1,delegateCloses.get());
    handle.close();assertEquals(1,providerCloses.get());assertEquals(JournalSessionAuthority.State.CLOSED,a.state());
  }
  @Test void fixedGoogleHostPathPredicateIsNotWidened() {
    assertTrue(JournalGoogleOAuthExchange.allowedProductionRequest(BINDING,"POST","https://sheets.googleapis.com",BINDING.path()+":batchUpdate"));
    assertTrue(JournalGoogleOAuthExchange.allowedProductionRequest(BINDING,"POST","https://oauth2.googleapis.com","/token"));
    for(String host:List.of("http://sheets.googleapis.com","https://sheets.googleapis.com.evil","https://sheets.googleapis.com:444"))
      assertFalse(JournalGoogleOAuthExchange.allowedProductionRequest(BINDING,"POST",host,BINDING.path()+":batchUpdate"));
    for(String path:List.of(BINDING.path()+":batchUpdate?x=1","/v4/spreadsheets/other:batchUpdate",BINDING.path()+":batchUpdate\r\nInjected"))
      assertFalse(JournalGoogleOAuthExchange.allowedProductionRequest(BINDING,"POST","https://sheets.googleapis.com",path));
  }
}
