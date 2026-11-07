package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ExperimentStorageTest {
  static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"),ZoneOffset.UTC);
  static final ExperimentStorage.Settings SETTINGS=new ExperimentStorage.Settings("isolated_experiment_sheet", "1e8a4c40-31d5-4a48-a5cf-903be066446d", "https://accounts.google.com","synthetic-owner",CLOCK.instant().plusSeconds(3600));
  static class Fake implements ExperimentGateway {
    final Map<String,List<List<String>>> tables=new TreeMap<>();int writes;boolean failBefore,failAfter,readFailure;
    Fake(){ExperimentStorage.HEADERS.forEach((k,v)->tables.put(k,List.of(v)));tables.put("dataset",List.of(ExperimentStorage.HEADERS.get("dataset"),List.of(ExperimentStorage.PURPOSE,SETTINGS.datasetId())));tables.put("erasure_ledger",List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL));}
    public synchronized Snapshot readComplete(){if(readFailure)throw new IllegalStateException();return new Snapshot(SETTINGS.spreadsheetId(),true,tables);}
    public synchronized void claimAtomic(String guard,List<List<String>> rows,Map<String,List<List<Object>>> append){writes++;if(failBefore)throw new SheetsGateway.Failure(429,0);if(tables.containsKey(guard))throw new SheetsGateway.Failure(400,0);var next=new TreeMap<>(tables);next.put(guard,rows);append.forEach((k,v)->{var all=new ArrayList<>(next.get(k));v.forEach(r->all.add(r.stream().map(Object::toString).toList()));next.put(k,all);});tables.clear();tables.putAll(next);if(failAfter)throw new SheetsGateway.Failure(0,0);}
    ExperimentStorage store(){return new ExperimentStorage(SETTINGS,this,CLOCK);}
  }
  static void bootstrap(ExperimentStorage s){assertEquals("VERIFIED",s.initializeEmpty(s.previewEmptyDigest(),true,true));}
  static String link(ExperimentStorage s){return s.findOrCreateAccount(SETTINGS.issuer(),SETTINGS.subject());}
  static FinalMatchEnvelope envelope(String account,String suffix) throws Exception {
    var f=new LocalManualQuarterRehearsal.Fixture();
    var raw=ManualQuarterMaintenance.decoded(f.before).values().stream().filter(e->!QuarterlyRetention.finishedAt(e).isBefore(Instant.parse("2026-10-01T00:00:00Z"))).findFirst().orElseThrow();
    ObjectNode payload=raw.payload().deepCopy();String match=UUID.nameUUIDFromBytes(suffix.getBytes()).toString();
    ((ObjectNode)payload.get("match")).put("match_id",match).put("planned_deals",1).put("started_at",CLOCK.instant().minusSeconds(120).toString()).put("finished_at",CLOCK.instant().minusSeconds(1).toString());
    int n=0;for(JsonNode person:payload.path("participants")){ObjectNode p=(ObjectNode)person;p.put("match_id",match);p.remove("erased_marker");p.putNull("guest_id");if(n++==0){p.put("player_type","HUMAN").put("control_type","HUMAN").put("account_id",account);}else{p.put("player_type","BOT").put("control_type","BOT").putNull("account_id");}}
    for(JsonNode d:payload.path("completed_deals"))((ObjectNode)d).put("match_id",match);
    return FinalMatchEnvelope.create(match,1,new ObjectMapper().convertValue(payload,Map.class));
  }
  static StatsStore.SaveStatus waitStatus(ExperimentStorage s,FinalMatchEnvelope e)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(s.status(e.commitId())==StatsStore.SaveStatus.PENDING&&System.nanoTime()<end)Thread.sleep(5);return s.status(e.commitId());}
  @Test void bootstrapRequiresTrueEmptyExplicitAuthorizationAndCurrentDigest(){var f=new Fake();try(var s=f.store()){assertEquals("NOT_APPLIED",s.initializeEmpty(s.previewEmptyDigest(),false,true));assertEquals(0,f.writes);assertEquals("NOT_APPLIED",s.initializeEmpty("wrong",true,true));bootstrap(s);assertEquals(1,f.writes);assertThrows(RuntimeException.class,s::previewEmptyDigest);assertEquals("UNCERTAIN",s.initializeEmpty("wrong",true,true));assertEquals(1,f.writes);}}
  @Test void bootstrapCannotAcceptSyntheticHistoryAsEmpty(){var f=new Fake();f.tables.put("accounts",List.of(ExperimentStorage.HEADERS.get("accounts"),List.of(UUID.randomUUID().toString(),"1")));try(var s=f.store()){assertThrows(RuntimeException.class,s::previewEmptyDigest);assertEquals(0,f.writes);}}
  @Test void bootstrapUnknownNeverRetries(){var f=new Fake();try(var s=f.store()){String digest=s.previewEmptyDigest();f.failAfter=true;assertEquals("UNCERTAIN",s.initializeEmpty(digest,true,true));assertEquals("UNCERTAIN",s.initializeEmpty(digest,true,true));assertEquals(1,f.writes);}}
  @Test void exactOwnerOnlyAndNoImplicitBootstrap(){var f=new Fake();try(var s=f.store()){assertThrows(RuntimeException.class,()->link(s));assertEquals(0,f.writes);}try(var s=f.store()){bootstrap(s);assertThrows(RuntimeException.class,()->s.findOrCreateAccount(SETTINGS.issuer(),"other"));assertEquals(1,f.writes);}}
  @Test void oneIdentityAcrossNewSessionsAndRestart(){var f=new Fake();try(var s=f.store()){bootstrap(s);assertNotNull(link(s));assertThrows(RuntimeException.class,()->link(s));}try(var restarted=f.store()){assertThrows(RuntimeException.class,()->link(restarted));}assertEquals(2,f.writes);}
  @Test void unknownIdentityWritesCannotReissueAfterRestart(){var f=new Fake();try(var s=f.store()){bootstrap(s);f.failAfter=true;assertThrows(RuntimeException.class,()->link(s));assertThrows(RuntimeException.class,()->link(s));}f.failAfter=false;try(var restarted=f.store()){assertThrows(RuntimeException.class,()->link(restarted));}assertEquals(2,f.writes);}
  @Test void finalSaveSingleClaimReadbackAndNoSecondMatch()throws Exception{var f=new Fake();try(var s=f.store()){bootstrap(s);String a=link(s);var e=envelope(a,"first");s.submit(e);assertEquals(StatsStore.SaveStatus.CONFIRMED,waitStatus(s,e));assertEquals(StatsStore.SaveStatus.CONFIRMED,s.submit(e));assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(envelope(a,"second")));assertEquals(3,f.writes);assertEquals(1,s.exportCommitted().size());}}
  @Test void unknownFinalOutcomeNeverReplays()throws Exception{var f=new Fake();try(var s=f.store()){bootstrap(s);String a=link(s);var e=envelope(a,"first");f.failAfter=true;s.submit(e);assertEquals(StatsStore.SaveStatus.UNKNOWN,waitStatus(s,e));assertEquals(StatsStore.SaveStatus.UNKNOWN,s.submit(e));assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(envelope(a,"second")));assertEquals(3,f.writes);}}
  @Test void foreignOwnerGuestOrMixedControlNeverPersists()throws Exception{var f=new Fake();try(var s=f.store()){bootstrap(s);String a=link(s);assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(envelope(UUID.randomUUID().toString(),"foreign")));var e=envelope(a,"guest");ObjectNode p=e.payload().deepCopy();((ObjectNode)p.path("participants").get(0)).put("guest_id","fake-guest").putNull("account_id");assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(FinalMatchEnvelope.create(e.matchId(),1,new ObjectMapper().convertValue(p,Map.class))));((ObjectNode)p.path("participants").get(0)).putNull("guest_id").put("account_id",a).put("control_type","MIXED");assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(FinalMatchEnvelope.create(e.matchId(),1,new ObjectMapper().convertValue(p,Map.class))));assertEquals(2,f.writes);}}
  @Test void expiryBlocksAllWritesAndExport(){var f=new Fake();try(var s=new ExperimentStorage(SETTINGS,f,Clock.fixed(SETTINGS.expiresAt(),ZoneOffset.UTC))){assertFalse(s.enabled());assertThrows(RuntimeException.class,()->link(s));assertThrows(RuntimeException.class,s::exportCommitted);assertEquals(0,f.writes);}}
  @Test void concurrentIndependentProcessesOnlyOneIdentityClaimWins()throws Exception{var f=new Fake();try(var initializer=f.store()){bootstrap(initializer);}var pool=Executors.newFixedThreadPool(2);try(var a=f.store();var b=f.store()){var results=pool.invokeAll(List.of(()->{try{link(a);return true;}catch(Exception e){return false;}},()->{try{link(b);return true;}catch(Exception e){return false;}}));int winners=0;for(var r:results)if((Boolean)r.get())winners++;assertEquals(1,winners);assertEquals(2,f.tables.get("accounts").size());}finally{pool.shutdownNow();}}
  @Test void moreThanOnePlannedDealIsRejectedBeforeWrite()throws Exception{var f=new Fake();try(var s=f.store()){bootstrap(s);String a=link(s);var e=envelope(a,"two-deals");ObjectNode p=e.payload().deepCopy();((ObjectNode)p.path("match")).put("planned_deals",2);assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(FinalMatchEnvelope.create(e.matchId(),1,new ObjectMapper().convertValue(p,Map.class))));assertEquals(2,f.writes);}}
  @Test void settingsNeverExposeSubjectInDiagnostic(){assertFalse(SETTINGS.toString().contains(SETTINGS.subject()));}
}
