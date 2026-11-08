package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Synthetic-only recovery claims; no real Google endpoints, identifiers or credentials. */
class ExperimentRecoveryStorageTest {
  static final ExperimentStorage.Settings SETTINGS=ExperimentStorageTest.SETTINGS;
  static class Fixture {
    final ExperimentStorageTest.Fake wire=new ExperimentStorageTest.Fake();
    final String account,nonce;
    Fixture(){try(var s=wire.store()){ExperimentStorageTest.bootstrap(s);account=ExperimentStorageTest.link(s);}
      nonce=wire.tables.get(ExperimentStorage.IDENTITY).get(1).get(2);}
    String recover(ExperimentStorage s){return s.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),nonce);}
  }
  @Test void recoveryOnlyAddsPermanentMarkerAndReturnsSameAccount()throws Exception {
    var f=new Fixture();var before=new TreeMap<>(f.wire.tables);
    try(var s=f.wire.store()) {
      assertEquals(f.account,f.recover(s));
      var expected=new TreeMap<>(before);expected.put(ExperimentStorage.RECOVERY,f.wire.tables.get(ExperimentStorage.RECOVERY));
      assertEquals(expected,f.wire.tables);assertEquals(3,f.wire.writes);
      assertThrows(RuntimeException.class,()->f.recover(s));assertThrows(RuntimeException.class,()->ExperimentStorageTest.link(s));
      var e=ExperimentStorageTest.envelope(f.account,"recovered");s.submit(e);
      assertEquals(StatsStore.SaveStatus.CONFIRMED,ExperimentStorageTest.waitStatus(s,e));
      assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(ExperimentStorageTest.envelope(f.account,"another")));
    }
    try(var restarted=f.wire.store()){assertThrows(RuntimeException.class,()->f.recover(restarted));assertThrows(RuntimeException.class,()->ExperimentStorageTest.link(restarted));}
    assertEquals(4,f.wire.writes);
  }
  @Test void recoveryCannotFollowNormalAdmissionInSameProcess(){
    var wire=new ExperimentStorageTest.Fake();try(var s=wire.store()){
      ExperimentStorageTest.bootstrap(s);ExperimentStorageTest.link(s);
      String nonce=wire.tables.get(ExperimentStorage.IDENTITY).get(1).get(2);
      assertThrows(RuntimeException.class,()->s.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),nonce));assertEquals(2,wire.writes);
    }
  }
  @Test void normalAdmissionAttemptCannotBeFollowedByRecoveryInSameProcess(){
    var f=new Fixture();try(var s=f.wire.store()){
      assertThrows(RuntimeException.class,()->ExperimentStorageTest.link(s));assertThrows(RuntimeException.class,()->f.recover(s));assertEquals(2,f.wire.writes);
    }
  }
  @Test void mismatchedIdentityNonceRejectsWithoutWritingAndConsumesAttempt(){
    var f=new Fixture();try(var s=f.wire.store()){
      assertThrows(RuntimeException.class,()->s.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),UUID.randomUUID().toString()));
      assertThrows(RuntimeException.class,()->f.recover(s));assertEquals(2,f.wire.writes);
    }
  }
  @Test void invalidPrincipalOrMissingNonceNeverClaims(){
    for(String[] input:List.of(new String[]{"https://evil.invalid",SETTINGS.subject(),UUID.randomUUID().toString()},new String[]{SETTINGS.issuer(),"other",UUID.randomUUID().toString()},new String[]{SETTINGS.issuer(),SETTINGS.subject(),null},new String[]{SETTINGS.issuer(),SETTINGS.subject(),"bad"})) {
      var f=new Fixture();try(var s=f.wire.store()){assertThrows(RuntimeException.class,()->s.recoverAccount(input[0],input[1],input[2]));assertEquals(2,f.wire.writes);}
    }
  }
  @Test void onlyExistingAccountMayRecover(){
    var wire=new ExperimentStorageTest.Fake();try(var s=wire.store()){ExperimentStorageTest.bootstrap(s);}
    try(var s=wire.store()){assertThrows(RuntimeException.class,()->s.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),UUID.randomUUID().toString()));assertEquals(1,wire.writes);}
  }
  @Test void expiryAndClosedStoreNeverClaim(){
    var f=new Fixture();try(var expired=new ExperimentStorage(SETTINGS,f.wire,Clock.fixed(SETTINGS.expiresAt(),ZoneOffset.UTC))){assertThrows(RuntimeException.class,()->f.recover(expired));}
    var closed=f.wire.store();closed.close();assertThrows(RuntimeException.class,()->f.recover(closed));assertEquals(2,f.wire.writes);
  }
  @Test void failedReadOrWriteNeverRetriesInProcess(){
    for(String failure:List.of("read","before","after")){
      var f=new Fixture();try(var s=f.wire.store()){
        f.wire.readFailure=failure.equals("read");f.wire.failBefore=failure.equals("before");f.wire.failAfter=failure.equals("after");
        assertThrows(RuntimeException.class,()->f.recover(s));int writes=f.wire.writes;
        f.wire.readFailure=false;f.wire.failBefore=false;f.wire.failAfter=false;
        assertThrows(RuntimeException.class,()->f.recover(s));assertEquals(writes,f.wire.writes);
      }
      if(failure.equals("after"))try(var restarted=f.wire.store()){assertThrows(RuntimeException.class,()->f.recover(restarted));assertEquals(3,f.wire.writes);}
    }
  }
  @Test void successfulRecoveryClaimCannotReissueAfterRestartBeforeAnyMatch(){
    var f=new Fixture();try(var s=f.wire.store()){f.recover(s);}try(var restarted=f.wire.store()){assertThrows(RuntimeException.class,()->f.recover(restarted));}assertEquals(3,f.wire.writes);
  }
  @Test void existingResultPreventsRecovery()throws Exception{
    var wire=new ExperimentStorageTest.Fake();String nonce;
    try(var s=wire.store()){ExperimentStorageTest.bootstrap(s);String a=ExperimentStorageTest.link(s);nonce=wire.tables.get(ExperimentStorage.IDENTITY).get(1).get(2);var e=ExperimentStorageTest.envelope(a,"already-complete");s.submit(e);assertEquals(StatsStore.SaveStatus.CONFIRMED,ExperimentStorageTest.waitStatus(s,e));}
    try(var s=wire.store()){String n=nonce;assertThrows(RuntimeException.class,()->s.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),n));}assertEquals(3,wire.writes);
  }
  @Test void malformedOrChangedAccountNeverRecovers(){
    for(String mutation:List.of("account","identity","orphan-recovery","partial-envelope","erasure")){
      var f=new Fixture();
      if(mutation.equals("account"))f.wire.tables.put("accounts",List.of(ExperimentStorage.HEADERS.get("accounts")));
      if(mutation.equals("identity"))f.wire.tables.put("auth_identities",List.of(ExperimentStorage.HEADERS.get("auth_identities"),List.of(SETTINGS.issuer(),"other",f.account,"1")));
      if(mutation.equals("orphan-recovery")){f.wire.tables.put(ExperimentStorage.RECOVERY,f.wire.tables.get(ExperimentStorage.IDENTITY));f.wire.tables.remove(ExperimentStorage.IDENTITY);}
      if(mutation.equals("partial-envelope"))f.wire.tables.put("envelopes",List.of(ExperimentStorage.HEADERS.get("envelopes"),List.of("partial","partial","0","1","{}")));
      if(mutation.equals("erasure"))f.wire.tables.put("erasure_ledger",List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL,List.of("unexpected")));
      try(var s=f.wire.store()){assertThrows(RuntimeException.class,()->f.recover(s));assertEquals(2,f.wire.writes);}
    }
  }
  @Test void twoIndependentStoresOnlyOneRecoveryWins()throws Exception{
    var f=new Fixture();var pool=Executors.newFixedThreadPool(2);
    try(var a=f.wire.store();var b=f.wire.store()){
      var results=pool.invokeAll(List.<Callable<Boolean>>of(()->{try{f.recover(a);return true;}catch(RuntimeException denied){return false;}},()->{try{f.recover(b);return true;}catch(RuntimeException denied){return false;}}));
      int winners=0;for(var r:results)if(r.get())winners++;assertEquals(1,winners);assertNotNull(f.wire.tables.get(ExperimentStorage.RECOVERY));
      assertEquals(2,f.wire.tables.get("accounts").size());
    }finally{pool.shutdownNow();}
  }
  @Test void inconsistentReadbackNeverAdmitsOrRetries(){
    var initial=new Fixture();var changed=new ExperimentStorageTest.Fake(){
      @Override public synchronized void claimAtomic(String g,List<List<String>> rows,Map<String,List<List<Object>>> append){super.claimAtomic(g,rows,append);if(g.equals(ExperimentStorage.RECOVERY))tables.put(g,List.of(ExperimentStorage.GUARD_HEADER,List.of(SETTINGS.datasetId(),SETTINGS.expiresAt().toString(),UUID.randomUUID().toString())));}
    };
    changed.tables.clear();changed.tables.putAll(initial.wire.tables);
    try(var s=changed.store()){assertThrows(RuntimeException.class,()->initial.recover(s));assertThrows(RuntimeException.class,()->initial.recover(s));assertEquals(1,changed.writes);}
  }
  @Test void realAdapterUsesOneMarkerOnlyAtomicClaimAndSurvivesReadback(){
    var wire=new GoogleExperimentGatewayTest.Wire();String account,nonce;
    try(var s=new ExperimentStorage(SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){ExperimentStorageTest.bootstrap(s);account=ExperimentStorageTest.link(s);nonce=wire.f.tables.get(ExperimentStorage.IDENTITY).get(1).get(2);}
    var before=new TreeMap<>(wire.f.tables);
    try(var s=new ExperimentStorage(SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){assertEquals(account,s.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),nonce));}
    assertEquals(1900000004,wire.ids.get(ExperimentStorage.RECOVERY));assertEquals(3,wire.posts);
    var expected=new TreeMap<>(before);expected.put(ExperimentStorage.RECOVERY,wire.f.tables.get(ExperimentStorage.RECOVERY));assertEquals(expected,wire.f.tables);
    try(var s=new ExperimentStorage(SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){assertThrows(RuntimeException.class,()->s.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),nonce));}assertEquals(3,wire.posts);
  }
}
