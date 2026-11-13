package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SheetsStatsStoreTest {
    static class Fake implements SheetsGateway {
        final Map<String,List<List<String>>> tabs=new ConcurrentHashMap<>(); int writes; int failStatus; boolean timeoutAfter; CountDownLatch block;
        Fake(){tabs.put("quarterly_aggregates",new ArrayList<>(List.of(QuarterlyRetention.AGGREGATE_HEADER)));Instant b=QuarterlyRetention.currentQuarterStart(Instant.now());tabs.put("retention_ledger",new ArrayList<>(List.of(RetentionLedger.HEADER,List.of(QuarterlyRetention.quarter(b),b.toString(),"COMPLETE",Instant.now().toString(),"1"))));tabs.put("erasure_ledger",new ArrayList<>(List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL)));}
        public synchronized List<List<String>> read(String tab){return new ArrayList<>(tabs.getOrDefault(tab,List.of()));}
        public void appendAtomic(Map<String,List<List<Object>>> rows){
            if(block!=null)try{block.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(0,0);}
            synchronized(this){writes++;if(failStatus!=0){int status=failStatus;failStatus=0;throw new Failure(status,0);}rows.forEach((tab,values)->{for(List<Object> r:values)tabs.computeIfAbsent(tab,k->new ArrayList<>()).add(r.stream().map(Object::toString).toList());});if(timeoutAfter){timeoutAfter=false;throw new Failure(0,0);}}
        }
    }
    static Map<String,Object> payload(String id) {
        List<Map<String,Object>> people=new ArrayList<>();for(int i=0;i<4;i++)people.add(Map.of("seat",i,"match_id",id));
        return new LinkedHashMap<>(Map.of("match",Map.of("match_id",id,"revision",1,"schema_version",1,"score_version","test-1","app_version","test","completed_deals",1,"started_at",Instant.now().minusMillis(1).toString(),"finished_at",Instant.now().toString()),"participants",people,"completed_deals",List.of(Map.of("match_id",id,"deal_id",UUID.randomUUID().toString(),"deal_index",1,"ns_total",-3)),"name","=danger"));
    }
    static FinalMatchEnvelope envelope(){String id=UUID.randomUUID().toString();return FinalMatchEnvelope.create(id,1,payload(id));}
    static StatsStore.SaveStatus done(SheetsStatsStore store,String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(store.status(id)==StatsStore.SaveStatus.PENDING&&System.nanoTime()<end)Thread.sleep(10);return store.status(id);}
    @Test void timeoutAfterCommitVerifiesAndDoesNotAppendAgain()throws Exception{
        Fake fake=new Fake();fake.timeoutAfter=true;var e=envelope();try(var store=new SheetsStatsStore(fake,2)){store.submit(e);assertEquals(StatsStore.SaveStatus.CONFIRMED,done(store,e.commitId()));assertEquals(1,fake.writes);assertEquals(1,store.exportCommitted().size());assertTrue(store.exportCommitted().get(0).payload().path("completed_deals").get(0).path("ns_total").isIntegralNumber());assertEquals(-3,store.exportCommitted().get(0).payload().path("completed_deals").get(0).path("ns_total").asInt());}
    }
    @Test void immutableCanonicalAndConflict()throws Exception{
        String id=UUID.randomUUID().toString();Map<String,Object> payload=payload(id);payload.put("z",1);var e=FinalMatchEnvelope.create(id,1,payload);payload.put("z",4);assertEquals(1,e.payload().path("z").asInt());
        Fake fake=new Fake();try(var store=new SheetsStatsStore(fake,2)){store.submit(e);assertEquals(StatsStore.SaveStatus.CONFIRMED,done(store,e.commitId()));assertEquals(StatsStore.SaveStatus.CONFLICT,store.submit(FinalMatchEnvelope.create(id,1,payload)));}
    }
    @Test void invalidPayloadRejected(){String id=UUID.randomUUID().toString();assertThrows(IllegalArgumentException.class,()->FinalMatchEnvelope.create(id,1,Map.of()));assertThrows(IllegalArgumentException.class,()->FinalMatchEnvelope.create(id,1,payload(UUID.randomUUID().toString())));Map<String,Object> p=payload(id);p.put("participants",List.of());assertThrows(IllegalArgumentException.class,()->FinalMatchEnvelope.create(id,1,p));}
    @Test void corruptionAndPartialRowsFailClosed()throws Exception{
        Fake fake=new Fake();var e=envelope();try(var store=new SheetsStatsStore(fake,2)){store.submit(e);assertEquals(StatsStore.SaveStatus.CONFIRMED,done(store,e.commitId()));fake.tabs.get("commits").clear();assertThrows(SheetsGateway.Conflict.class,store::exportCommitted);}
        fake=new Fake();fake.tabs.put("commits",new ArrayList<>(List.of(List.of(e.commitId(),e.payloadHash(),"1",e.matchId(),"1"))));try(var store=new SheetsStatsStore(fake,2)){assertThrows(SheetsGateway.Conflict.class,store::exportCommitted);}
    }
    @Test void logicalDuplicatesAcceptedButDifferentPayloadRejected()throws Exception{
        Fake fake=new Fake();var e=envelope();try(var store=new SheetsStatsStore(fake,2)){store.submit(e);assertEquals(StatsStore.SaveStatus.CONFIRMED,done(store,e.commitId()));fake.tabs.get("commits").add(fake.tabs.get("commits").get(0));fake.tabs.get("envelopes").add(fake.tabs.get("envelopes").get(0));assertEquals(1,store.exportCommitted().size());List<String> bad=new ArrayList<>(fake.tabs.get("envelopes").get(0));bad.set(4,"{}");fake.tabs.get("envelopes").add(bad);assertThrows(SheetsGateway.Conflict.class,store::exportCommitted);}
    }
    @Test void permissionStopsAndTransientRetries()throws Exception{
        for(int code:new int[]{403,429,503}){Fake fake=new Fake();fake.failStatus=code;var e=envelope();try(var store=new SheetsStatsStore(fake,2)){store.submit(e);assertEquals(code==403?StatsStore.SaveStatus.FAILED:StatsStore.SaveStatus.CONFIRMED,done(store,e.commitId()));assertEquals(code==403?1:2,fake.writes);}}
    }
    @Test void queueFullIsRejected()throws Exception{
        Fake fake=new Fake();fake.block=new CountDownLatch(1);try(var store=new SheetsStatsStore(fake,1)){var first=envelope();store.submit(first);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(store.status(first.commitId())!=StatsStore.SaveStatus.PENDING&&System.nanoTime()<until)Thread.sleep(1);Thread.sleep(40);store.submit(envelope());assertEquals(StatsStore.SaveStatus.REJECTED,store.submit(envelope()));fake.block.countDown();assertEquals(StatsStore.SaveStatus.CONFIRMED,done(store,first.commitId()));}finally{fake.block.countDown();}
    }
    @Test void durableIdentityReadbackAndTimeoutReuse(){Fake fake=new Fake();fake.timeoutAfter=true;var store=new SheetsIdentityStore(fake);String account=store.findOrCreateAccount("https://accounts.google.com","opaque-subject");assertEquals(account,new SheetsIdentityStore(fake).findOrCreateAccount("https://accounts.google.com","opaque-subject"));assertEquals(1,fake.writes);assertEquals(1,fake.tabs.get("accounts").size());}
    @Test void restartHasNoDurableOutboxButCommittedMatchCanBeVerified() throws Exception {
        Fake fake = new Fake();
        var started = new CountDownLatch(1);
        var hold = new CountDownLatch(1);
        SheetsGateway blocked = new SheetsGateway() {
            public List<List<String>> read(String tab) { return fake.read(tab); }
            public void appendAtomic(Map<String,List<List<Object>>> rows) {
                started.countDown();
                try { hold.await(); } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt(); throw new Failure(0,0);
                }
                fake.appendAtomic(rows);
            }
        };
        var lost = envelope();
        var old = new SheetsStatsStore(blocked, 1);
        old.submit(lost);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        old.close();
        try (var restarted = new SheetsStatsStore(fake, 1)) {
            assertEquals(StatsStore.SaveStatus.UNKNOWN, restarted.status(lost.commitId()));
            assertTrue(restarted.exportCommitted().isEmpty());
            assertEquals(0, fake.writes); // restart has no saved payload to resume
            var saved = envelope(); restarted.submit(saved);
            assertEquals(StatsStore.SaveStatus.CONFIRMED, done(restarted, saved.commitId()));
            try (var next = new SheetsStatsStore(fake, 1)) {
                assertEquals(StatsStore.SaveStatus.UNKNOWN, next.status(saved.commitId()));
                next.submit(saved); // same authoritative envelope supplied explicitly, not browser scores
                assertEquals(StatsStore.SaveStatus.CONFIRMED, done(next, saved.commitId()));
                assertEquals(1, fake.writes);
            }
        }
    }
    static class MutableClock extends Clock {
        Instant now;MutableClock(String value){now=Instant.parse(value);}public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return now;}
    }
    static FinalMatchEnvelope timed(String start,String finish) {
        String id=UUID.randomUUID().toString();Map<String,Object> p=payload(id);Map<String,Object> match=new LinkedHashMap<>((Map<String,Object>)p.get("match"));match.put("started_at",start);match.put("finished_at",finish);p.put("match",match);return FinalMatchEnvelope.create(id,1,p);
    }
    static void ready(Fake fake,Instant instant) {Instant boundary=QuarterlyRetention.currentQuarterStart(instant);fake.tabs.put("retention_ledger",new ArrayList<>(List.of(RetentionLedger.HEADER,List.of(QuarterlyRetention.quarter(boundary),boundary.toString(),"COMPLETE",instant.toString(),"1"))));}
    @Test void taiwanBoundaryStopsCollectionAndExcludesExpiredRawButMaintenanceCanRead()throws Exception {
        Fake fake=new Fake();MutableClock clock=new MutableClock("2026-09-30T15:59:59Z");ready(fake,clock.instant());
        var old=timed("2026-09-30T15:59:00Z","2026-09-30T15:59:58Z");
        try(var store=new SheetsStatsStore(fake,2,clock)) {
            store.submit(old);assertEquals(StatsStore.SaveStatus.CONFIRMED,done(store,old.commitId()));assertEquals(1,store.exportCommitted().size());
            clock.now=Instant.parse("2026-09-30T16:00:00Z");assertEquals(StatsStore.SaveStatus.REJECTED,store.submit(old));assertThrows(IllegalStateException.class,store::exportCommitted);
            var current=timed("2026-09-30T16:00:00Z","2026-09-30T16:00:00Z");store.submit(current);assertEquals(StatsStore.SaveStatus.FAILED,done(store,current.commitId()));assertEquals(1,fake.writes);
            assertThrows(IllegalStateException.class,()->new SheetsIdentityStore(fake,clock).findOrCreateAccount("https://accounts.google.com","new-person"));
            ready(fake,clock.instant());assertTrue(store.exportCommitted().isEmpty());assertEquals(1,SheetsStatsStore.readVerified(fake).size());
            store.submit(current);assertEquals(StatsStore.SaveStatus.CONFIRMED,done(store,current.commitId()));assertEquals(1,store.exportCommitted().size());
        }
    }

}
