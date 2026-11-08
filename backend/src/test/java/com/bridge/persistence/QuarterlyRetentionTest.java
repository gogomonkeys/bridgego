package com.bridge.persistence;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuarterlyRetentionTest {
    static final Instant NOW=Instant.parse("2026-09-30T16:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static class MovingClock extends Clock {
        Instant now=NOW;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId ignored){return this;}
        public Instant instant(){return now;}
    }
    static class Fake implements SheetsGateway {
        final Map<String,List<List<String>>> tabs=new HashMap<>();int writes;int failMode;
        Fake(){
            tabs.put("envelopes",new ArrayList<>(List.of(List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk"))));
            tabs.put("commits",new ArrayList<>(List.of(List.of("commit_id","payload_hash","chunk_count","match_id","revision"))));
            tabs.put("accounts",new ArrayList<>(List.of(List.of("account_id","schema_version"),List.of("keep-account","1"))));
            tabs.put("auth_identities",new ArrayList<>(List.of(List.of("issuer","subject","account_id","schema_version"),List.of("issuer","keep-subject","keep-account","1"))));
            tabs.put("erasure_ledger",new ArrayList<>(List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL)));
            tabs.put("retention_ledger",new ArrayList<>(List.of(RetentionLedger.HEADER)));
            tabs.put("quarterly_aggregates",new ArrayList<>(List.of(QuarterlyRetention.AGGREGATE_HEADER)));
            tabs.put("dataset",List.of(List.of("purpose","dataset_id"),List.of("test","test")));
        }
        public List<List<String>> read(String tab){if(failMode==3&&writes>0)throw new Failure(503,0);return new ArrayList<>(tabs.get(tab));}
        public void appendAtomic(Map<String,List<List<Object>>> rows){throw new UnsupportedOperationException();}
        public void replaceAtomic(Map<String,List<List<Object>>> rows){writes++;if(failMode==1)throw new Failure(403,0);rows.forEach((k,v)->tabs.put(k,new ArrayList<>(v.stream().map(r->r.stream().map(Object::toString).toList()).toList())));if(failMode==2)throw new Failure(0,0);}
        void add(FinalMatchEnvelope e){tabs.get("envelopes").add(List.of(e.commitId(),e.payloadHash(),"0","1",e.canonicalJson()));tabs.get("commits").add(List.of(e.commitId(),e.payloadHash(),"1",e.matchId(),"1"));}
    }
    static FinalMatchEnvelope envelope(Instant start,Instant finish,int humanOffset){
        String id=UUID.randomUUID().toString();Map<String,Object> payload=SheetsStatsStoreTest.payload(id);payload.remove("name");
        Map<String,Object> match=new HashMap<>((Map<String,Object>)payload.get("match"));match.put("started_at",start.toString());match.put("finished_at",finish.toString());payload.put("match",match);
        List<Map<String,Object>> people=new ArrayList<>();for(int i=0;i<4;i++)people.add(Map.of("seat",i,"match_id",id,"account_id","human-"+(humanOffset+i)));payload.put("participants",people);return FinalMatchEnvelope.create(id,1,payload);
    }
    static FinalMatchEnvelope old(){return envelope(NOW.minusSeconds(120),NOW.minusNanos(1),0);}
    static QuarterlyRetention engine(Fake fake){return new QuarterlyRetention(fake,CLOCK,()->true,true,QuarterlyRetention.AggregatePolicy.suppressAll());}
    static QuarterlyRetention.Result run(QuarterlyRetention engine){var p=engine.preview();return engine.execute(p.planId(),p.confirmationText());}
    @Test void taipeiBoundariesAndYearRollover(){
        for(String boundary:List.of("2025-12-31T16:00:00Z","2026-03-31T16:00:00Z","2026-06-30T16:00:00Z","2026-09-30T16:00:00Z")){Instant t=Instant.parse(boundary);assertEquals(t,QuarterlyRetention.currentQuarterStart(t));assertTrue(QuarterlyRetention.currentQuarterStart(t.minusNanos(1)).isBefore(t));}
        assertEquals("2026-Q1",QuarterlyRetention.quarter(Instant.parse("2025-12-31T16:00:00Z")));
    }
    @Test void purgeKeepsAccountsNewRecordsAndTombstones(){
        Fake f=new Fake();var expired=old();var current=envelope(NOW,NOW,4);f.add(expired);f.add(current);var accounts=f.read("accounts");var identities=f.read("auth_identities");var erasures=f.read("erasure_ledger");
        assertThrows(IllegalStateException.class,()->QuarterlyRetention.requireCollectionReady(f,CLOCK));
        var result=run(engine(f));assertEquals(QuarterlyRetention.State.COMPLETE,result.state());assertEquals(NOW,result.completedAt());assertEquals(2,f.writes);
        assertEquals(accounts,f.read("accounts"));assertEquals(identities,f.read("auth_identities"));assertEquals(erasures,f.read("erasure_ledger"));assertEquals(Set.of(current.commitId()),SheetsStatsStore.readVerified(f).keySet());
        var ledger=new RetentionLedger(f.read("retention_ledger"));assertThrows(SheetsGateway.Conflict.class,()->ledger.requireAllowed(expired));assertDoesNotThrow(()->QuarterlyRetention.requireCurrent(current,CLOCK,ledger));assertDoesNotThrow(()->QuarterlyRetention.requireCollectionReady(f,CLOCK));
        assertEquals(List.of("2026-Q3","SUPPRESSED","","","1"),f.read("quarterly_aggregates").get(1));
        f.add(expired);assertThrows(SheetsGateway.Conflict.class,()->engine(f).preview());
    }
    @Test void boundaryRejectsOldRetryAndFuture(){
        var ledger=new RetentionLedger(List.of(RetentionLedger.HEADER));
        for(var envelope:List.of(old(),envelope(NOW,NOW.plusSeconds(1),0)))assertThrows(SheetsGateway.Conflict.class,()->QuarterlyRetention.requireCurrent(envelope,CLOCK,ledger));
    }
    @Test void approvedCrossQuarterAttributionUsesFinish(){assertDoesNotThrow(()->QuarterlyRetention.requireCurrent(envelope(NOW.minusSeconds(1),NOW,0),CLOCK,new RetentionLedger(List.of(RetentionLedger.HEADER))));}
    @Test void dryrunAndExclusiveProofAndFreshDigest(){
        Fake f=new Fake();f.add(old());var dry=new QuarterlyRetention(f,CLOCK,()->true);assertEquals(QuarterlyRetention.State.DRY_RUN,run(dry).state());assertEquals(0,f.writes);
        assertThrows(IllegalStateException.class,()->new QuarterlyRetention(f,CLOCK,()->false).preview());
        var engine=engine(f);var plan=engine.preview();assertThrows(IllegalArgumentException.class,()->engine.execute(plan.planId(),"yes"));f.add(old());assertThrows(IllegalStateException.class,()->engine.execute(plan.planId(),plan.confirmationText()));assertEquals(0,f.writes);
    }
    @Test void failureUnknownAndTimeoutReadback(){
        for(int mode:List.of(1,2,3)){Fake f=new Fake();f.add(old());f.failMode=mode;var result=run(engine(f));assertEquals(mode==1?QuarterlyRetention.State.FAILED:mode==2?QuarterlyRetention.State.COMPLETE:QuarterlyRetention.State.UNCERTAIN,result.state());if(mode!=2)assertNull(result.completedAt());assertEquals(mode==2?2:1,f.writes);}
    }
    @Test void directEngineNeverReplaysAnAttemptAfterFailureUncertaintyOrCompletion(){
        for(int mode:List.of(0,1,2,3)){
            Fake f=new Fake();f.add(old());f.failMode=mode;var e=engine(f);var p=e.preview();
            e.execute(p.planId(),p.confirmationText());int writes=f.writes;
            assertThrows(IllegalStateException.class,()->e.execute(p.planId(),p.confirmationText()));
            assertThrows(IllegalStateException.class,e::preview);assertEquals(writes,f.writes);
        }
    }
    @Test void freshCompletedQuarterIsNoopAndRetainsExistingAggregateRowsByteForByte(){
        Fake f=new Fake();f.add(old());
        var suppressed=List.of("2025-Q4","SUPPRESSED","","","1");
        var candidate=List.of("2026-Q1","CANDIDATE_COUNTS","100","400","1");
        f.tabs.get("quarterly_aggregates").add(suppressed);f.tabs.get("quarterly_aggregates").add(candidate);
        assertEquals(QuarterlyRetention.State.COMPLETE,run(engine(f)).state());
        var before=new TreeMap<String,List<List<String>>>();f.tabs.forEach((name,rows)->before.put(name,List.copyOf(rows)));
        var later=Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC);
        var again=new QuarterlyRetention(f,later,()->true,true,QuarterlyRetention.AggregatePolicy.suppressAll());
        var result=run(again);assertEquals(QuarterlyRetention.State.COMPLETE,result.state());assertEquals(NOW,result.completedAt());
        assertEquals(2,f.writes);assertEquals(before,f.tabs);
        assertEquals(suppressed,f.read("quarterly_aggregates").get(1));assertEquals(candidate,f.read("quarterly_aggregates").get(2));
    }
    @Test void secondPhaseFailureDoesNotBecomeSuccessOrReplayOnTheSamePlan(){
        Fake f=new Fake();f.add(old());
        SheetsGateway partial=new SheetsGateway(){int attempts;public List<List<String>> read(String tab){return f.read(tab);}
            public void appendAtomic(Map<String,List<List<Object>>> rows){throw new AssertionError();}
            public void replaceAtomic(Map<String,List<List<Object>>> rows){if(++attempts==2)throw new Failure(403,0);f.replaceAtomic(rows);}};
        var e=new QuarterlyRetention(partial,CLOCK,()->true,true,QuarterlyRetention.AggregatePolicy.suppressAll());var p=e.preview();
        assertEquals(QuarterlyRetention.State.PENDING,e.execute(p.planId(),p.confirmationText()).state());
        assertThrows(IllegalStateException.class,()->e.execute(p.planId(),p.confirmationText()));assertEquals(1,f.writes);
        assertThrows(IllegalStateException.class,()->QuarterlyRetention.requireCollectionReady(f,CLOCK));
    }
    @Test void directEngineExpiryOrQuarterRolloverDuringFirstWriteCannotFinalize(){
        for(Instant moved:List.of(NOW.plusSeconds(300),Instant.parse("2026-12-31T16:00:00Z"))){
            var clock=new MovingClock();
            Fake f=new Fake(){@Override public void replaceAtomic(Map<String,List<List<Object>>> rows){super.replaceAtomic(rows);clock.now=moved;}};
            f.add(old());var e=new QuarterlyRetention(f,clock,()->true,true,QuarterlyRetention.AggregatePolicy.suppressAll());
            var result=run(e);assertEquals(QuarterlyRetention.State.UNCERTAIN,result.state());assertNull(result.completedAt());
            assertEquals(1,f.writes);assertFalse(new RetentionLedger(f.read("retention_ledger")).complete());
        }
    }
    @Test void directEngineRolloverAfterFinalWriteDoesNotReportCurrentCompletion(){
        var clock=new MovingClock();
        Fake f=new Fake(){@Override public void replaceAtomic(Map<String,List<List<Object>>> rows){super.replaceAtomic(rows);if(writes==2)clock.now=Instant.parse("2026-12-31T16:00:00Z");}};
        f.add(old());var result=run(new QuarterlyRetention(f,clock,()->true,true,QuarterlyRetention.AggregatePolicy.suppressAll()));
        assertEquals(QuarterlyRetention.State.UNCERTAIN,result.state());assertNull(result.completedAt());assertEquals(2,f.writes);
        assertThrows(IllegalStateException.class,()->QuarterlyRetention.requireCollectionReady(f,clock));
    }
    @Test void directEngineLosingStoppedWriterProofAfterFirstWriteCannotFinalize(){
        var stopped=new java.util.concurrent.atomic.AtomicBoolean(true);
        Fake f=new Fake(){@Override public void replaceAtomic(Map<String,List<List<Object>>> rows){super.replaceAtomic(rows);stopped.set(false);}};
        f.add(old());var e=new QuarterlyRetention(f,CLOCK,stopped::get,true,QuarterlyRetention.AggregatePolicy.suppressAll());
        var result=run(e);assertEquals(QuarterlyRetention.State.UNCERTAIN,result.state());assertNull(result.completedAt());
        assertEquals(1,f.writes);assertFalse(new RetentionLedger(f.read("retention_ledger")).complete());
    }
    @Test void candidateCountsRequireBothMinimaAndExcludeIdentifiers(){
        for(int total:List.of(49,50)){Fake f=new Fake();for(int i=0;i<total;i++)f.add(envelope(NOW.minusSeconds(120),NOW.minusSeconds(1),i));var e=new QuarterlyRetention(f,CLOCK,()->true,true,new QuarterlyRetention.AggregatePolicy(true,50,20,10));assertEquals(QuarterlyRetention.State.COMPLETE,run(e).state());var row=f.read("quarterly_aggregates").get(1);assertEquals(total==49?"SUPPRESSED":"CANDIDATE_COUNTS",row.get(1));assertFalse(row.toString().contains("human-"));if(total==50){assertEquals("50",row.get(2));assertEquals("50",row.get(3));}}
        Fake f=new Fake();for(int i=0;i<50;i++)f.add(old());run(new QuarterlyRetention(f,CLOCK,()->true,true,new QuarterlyRetention.AggregatePolicy(true,50,20,10)));assertEquals("SUPPRESSED",f.read("quarterly_aggregates").get(1).get(1));
    }
    @Test void corruptMetadataFailsClosed(){
        Fake f=new Fake();f.tabs.get("quarterly_aggregates").add(List.of("2026-Q2","CANDIDATE_COUNTS","account-id","10","1"));assertThrows(SheetsGateway.Conflict.class,()->engine(f).preview());
        assertThrows(SheetsGateway.Conflict.class,()->new RetentionLedger(List.of(RetentionLedger.HEADER,List.of("2026-Q4",NOW.plusSeconds(1).toString(),"COMPLETE",NOW.toString(),"1"))));
    }
    @Test void pendingCleanupCanResumeButNeverCollect(){
        Fake f=new Fake();f.add(old());var e=engine(f);var p=e.preview();
        SheetsGateway partial=new SheetsGateway(){int n;public List<List<String>> read(String tab){return f.read(tab);}public void appendAtomic(Map<String,List<List<Object>>> rows){}public void replaceAtomic(Map<String,List<List<Object>>> rows){if(++n==2)throw new Failure(403,0);f.replaceAtomic(rows);}};
        var partialEngine=new QuarterlyRetention(partial,CLOCK,()->true,true,QuarterlyRetention.AggregatePolicy.suppressAll());assertEquals(QuarterlyRetention.State.PENDING,run(partialEngine).state());assertThrows(IllegalStateException.class,()->QuarterlyRetention.requireCollectionReady(f,CLOCK));assertEquals(QuarterlyRetention.State.COMPLETE,run(engine(f)).state());assertEquals(2,f.read("quarterly_aggregates").size());
    }
}
