package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class JournalSessionRenewalTest {
  static final GoogleSheetsBinding BINDING=JournalSessionAuthorityTest.BINDING;
  static class Time extends Clock {Instant now=JournalSessionAuthorityTest.CLOCK.instant();public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}}
  @Test void renewReplacesAuthorityAndRevokesEveryOldReferenceWithoutWriting()throws Exception {
    Book initial=JournalSessionAuthorityTest.clean();var old=JournalSessionAuthorityTest.authority(initial,JournalSessionAuthorityTest.CLOCK,Duration.ofHours(1));
    var claim=JournalSessionAuthorityTest.claim(initial);old.arm(claim);var dispatch=old.beforeDispatch(BINDING.path()+":batchUpdate",claim.wire());dispatch.acknowledged();old.verified(claim.expected());
    var fresh=old.renew(claim.expected(),Duration.ofHours(1));assertNotSame(old,fresh);assertEquals(JournalSessionAuthority.State.CLOSED,old.state());
    var next=JournalSessionAuthorityTest.profile(claim.expected(),"renewed");
    assertThrows(RuntimeException.class,()->old.arm(next));assertThrows(RuntimeException.class,()->old.beforeDispatch(BINDING.path()+":batchUpdate",claim.wire()));assertThrows(RuntimeException.class,dispatch::acknowledged);
    JournalSessionAuthorityTest.apply(fresh,next);assertEquals(JournalSessionAuthority.State.READY,fresh.state());
  }
  @Test void unknownPendingAndRevokedEvidenceCannotRenew()throws Exception {
    Book initial=JournalSessionAuthorityTest.clean();var clock=new Time();var revoked=new AtomicBoolean();String original=JournalSessionAuthority.fullToken(initial);
    JournalSessionAuthority.ApprovedCutoverVerifier verify=(b,o,s,a,t)->{if(revoked.get()||!t.equals(original))throw new Rejected();};
    var old=JournalSessionAuthority.provision(BINDING,initial,JournalSessionAuthorityTest.OWNER,"source","approval",verify,clock,Duration.ofHours(1));
    var claim=JournalSessionAuthorityTest.claim(initial);old.arm(claim);assertThrows(RuntimeException.class,()->old.renew(initial,Duration.ofHours(1)));
    old.beforeDispatch(BINDING.path()+":batchUpdate",claim.wire()).unknown();assertThrows(RuntimeException.class,()->old.renew(claim.expected(),Duration.ofHours(1)));
    for(boolean revoke:new boolean[]{false,true}){
      clock.now=JournalSessionAuthorityTest.CLOCK.instant();revoked.set(false);
      var session=JournalSessionAuthority.provision(BINDING,initial,JournalSessionAuthorityTest.OWNER,"source","approval",verify,clock,Duration.ofHours(1));JournalSessionAuthorityTest.apply(session,JournalSessionAuthorityTest.claim(initial));
      if(revoke)revoked.set(true);else clock.now=clock.now.plus(Duration.ofHours(1));
      if(revoke){assertThrows(RuntimeException.class,()->session.renew(claim.expected(),Duration.ofHours(1)));assertEquals(JournalSessionAuthority.State.UNKNOWN,session.state());}
      else {var renewed=session.renew(claim.expected(),Duration.ofHours(1));assertEquals(JournalSessionAuthority.State.CLOSED,session.state());assertEquals(JournalSessionAuthority.State.READY,renewed.state());}
    }
  }
  @Test void batchLimitRenewsOnlyFromFreshConfirmedJournal()throws Exception {
    Book initial=JournalSessionAuthorityTest.clean();var clock=JournalSessionAuthorityTest.CLOCK;
    var old=JournalSessionAuthorityTest.authority(initial,clock,Duration.ofHours(1));var claim=JournalSessionAuthorityTest.claim(initial);JournalSessionAuthorityTest.apply(old,claim);Book current=claim.expected();
    for(int i=1;i<128;i++){var p=JournalSessionAuthorityTest.profile(current,"p"+i);JournalSessionAuthorityTest.apply(old,p);current=p.expected();}
    assertTrue(old.renewalDue());var next=JournalSessionAuthorityTest.profile(current,"after128");assertThrows(RuntimeException.class,()->old.arm(next));
    var renewed=old.renew(current.copy(),Duration.ofHours(1));assertFalse(renewed.renewalDue());JournalSessionAuthorityTest.apply(renewed,next);
    assertThrows(RuntimeException.class,()->old.arm(next));
  }
  @Test void backendRenewsAtOperationBoundaryWithoutExtraBatchThenWritesNormally()throws Exception {
    verifyBackendRenewal(Duration.ofMinutes(59));verifyBackendRenewal(Duration.ofHours(2));
  }
  private void verifyBackendRenewal(Duration idle)throws Exception {
    Book initial=JournalSessionAuthorityTest.clean();var time=new Time();var old=JournalSessionAuthorityTest.authority(initial,time,Duration.ofHours(1));var slot=new JournalAuthoritySlot(old);
    Book[] actual={initial};var writes=new AtomicInteger();var scopeCount=new AtomicInteger();
    NarrowSheetsTransport sink=new NarrowSheetsTransport(){
      public Scope operation(String label,Root r,Set<String> tabs,Duration d){scopeCount.incrementAndGet();return ()->{};}
      public Book snapshot(){return actual[0].copy();}public List<Metadata> control(){return new ArrayList<>(actual[0].metadata.values());}
      public List<List<String>> related(String tab){return copyRows(actual[0].sheet(tab).rows);}public boolean unsupportedStructure(){return false;}
      public void batch(String wire){var dispatch=slot.beforeDispatch(BINDING.path()+":batchUpdate",wire);writes.incrementAndGet();actual[0]=simulate(actual[0],wire);dispatch.acknowledged();}
    };
    try(var handle=new JournalGoogleFactory.Handle(sink,()->{},slot,BINDING)){
      var transport=new JournaledNativeTransport(handle.transport(),BINDING.manualBinding(),time);
      try(var service=new Service(transport,time,BINDING.manualBinding(),JournalSessionAuthorityTest.OWNER,Limits.defaults())){
        service.start();assertEquals(State.OPEN,service.state());var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
        try(var backend=new NativeStoresBackend(service,transport,time,gate)){
          time.now=time.now.plus(idle);var identity=actual[0].sheet("auth_identities").rows.get(1);
          assertEquals(identity.get(2),backend.findOrCreateAccount(identity.get(0),identity.get(1),"Renewed backend","renew@example.com"));
          assertEquals(JournalSessionAuthority.State.CLOSED,old.state());assertNotSame(old,slot.current());assertEquals(2,writes.get());assertTrue(scopeCount.get()>0);
          var match=NativeCategoryIdentitySafetyTest.envelope(actual[0],"FORMAL");backend.submit(match);
          assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,NativeHandleBackendCompositionTest.await(backend,match.commitId()));assertEquals(3,writes.get());
        }
      }
    }
    assertEquals(JournalSessionAuthority.State.CLOSED,slot.current().state());
  }
  @Test void renewalFailureKeepsQueuedPermitsAccountedAndPreventsBothWrites()throws Exception {
    Book initial=JournalSessionAuthorityTest.clean();var time=new Time();var revoked=new AtomicBoolean();String baseline=JournalSessionAuthority.fullToken(initial);
    var old=JournalSessionAuthority.provision(BINDING,initial,JournalSessionAuthorityTest.OWNER,"source","approval",(b,o,s,a,t)->{if(revoked.get()||!t.equals(baseline))throw new Rejected();},time,Duration.ofHours(1));
    var slot=new JournalAuthoritySlot(old);Book[] actual={initial};var writes=new AtomicInteger();var hold=new AtomicBoolean();
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    NarrowSheetsTransport sink=new NarrowSheetsTransport(){
      public Book snapshot(){if(hold.compareAndSet(true,false)){entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new Unknown();}catch(InterruptedException e){throw new Unknown();}}return actual[0].copy();}
      public List<Metadata> control(){return new ArrayList<>(actual[0].metadata.values());}public List<List<String>> related(String tab){return copyRows(actual[0].sheet(tab).rows);}public boolean unsupportedStructure(){return false;}
      public void batch(String wire){var d=slot.beforeDispatch(BINDING.path()+":batchUpdate",wire);writes.incrementAndGet();actual[0]=simulate(actual[0],wire);d.acknowledged();}
    };
    try(var handle=new JournalGoogleFactory.Handle(sink,()->{},slot,BINDING)){
      var transport=new JournaledNativeTransport(handle.transport(),BINDING.manualBinding(),time);
      try(var service=new Service(transport,time,BINDING.manualBinding(),JournalSessionAuthorityTest.OWNER,Limits.defaults())){
        service.start();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
        try(var backend=new NativeStoresBackend(service,transport,time,gate)){
          time.now=time.now.plus(Duration.ofMinutes(59));var first=NativeCategoryIdentitySafetyTest.envelope(actual[0],"FORMAL");var second=NativeCategoryIdentitySafetyTest.envelope(actual[0],"CASUAL");
          hold.set(true);backend.submit(first);assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));backend.submit(second);assertEquals(2,gate.activeOperations());
          revoked.set(true);release.countDown();
          assertEquals(com.bridge.persistence.StatsStore.SaveStatus.UNKNOWN,NativeHandleBackendCompositionTest.await(backend,first.commitId()));
          assertEquals(com.bridge.persistence.StatsStore.SaveStatus.UNKNOWN,NativeHandleBackendCompositionTest.await(backend,second.commitId()));
          long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);while(gate.activeOperations()!=0&&System.nanoTime()<end)Thread.sleep(5);
          assertEquals(0,gate.activeOperations());assertFalse(gate.isOpen());assertEquals(1,writes.get());
        }finally{release.countDown();}
      }
    }
  }
  @Test void pendingInflightAndVerifyingExpiryRemainUnknownNotClean()throws Exception {
    for(int phase=1;phase<=3;phase++){
      var time=new Time();Book initial=JournalSessionAuthorityTest.clean();var authority=JournalSessionAuthorityTest.authority(initial,time,Duration.ofSeconds(1));var claim=JournalSessionAuthorityTest.claim(initial);
      authority.arm(claim);JournalSessionAuthority.Dispatch dispatched=null;
      if(phase>=2)dispatched=authority.beforeDispatch(BINDING.path()+":batchUpdate",claim.wire());
      if(phase==3)dispatched.acknowledged();
      time.now=time.now.plusSeconds(1);
      assertThrows(RuntimeException.class,()->authority.requireReadSession(BINDING));assertEquals(JournalSessionAuthority.State.UNKNOWN,authority.state());
      assertThrows(RuntimeException.class,()->authority.renew(claim.expected(),Duration.ofSeconds(1)));
      if(dispatched!=null){var old=dispatched;assertThrows(RuntimeException.class,old::acknowledged);}
    }
  }
  @Test void cleanExpiryIsReadOnlyAndStaleFreshReadCannotReauthorize()throws Exception {
    var time=new Time();Book initial=JournalSessionAuthorityTest.clean();var old=JournalSessionAuthorityTest.authority(initial,time,Duration.ofSeconds(1));var claim=JournalSessionAuthorityTest.claim(initial);JournalSessionAuthorityTest.apply(old,claim);
    time.now=time.now.plusSeconds(2);old.requireReadSession(BINDING);assertEquals(JournalSessionAuthority.State.EXPIRED_CLEAN,old.state());
    var plan=JournalSessionAuthorityTest.profile(claim.expected(),"new");assertThrows(RuntimeException.class,()->old.arm(plan));
    assertThrows(RuntimeException.class,()->old.beforeDispatch(BINDING.path()+":batchUpdate",plan.wire()));
    assertThrows(RuntimeException.class,()->old.renew(initial,Duration.ofSeconds(1)));assertEquals(JournalSessionAuthority.State.UNKNOWN,old.state());
  }
}
