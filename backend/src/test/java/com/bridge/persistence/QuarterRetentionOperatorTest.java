package com.bridge.persistence;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reconstructed tests: all gateways are local fakes, scripted phrases are not real consent. */
class QuarterRetentionOperatorTest {
    static class Script implements QuarterRetentionOperator.Terminal {
        final List<String> output=new ArrayList<>();String next=QuarterRetentionOperator.STOPPED_ACK;boolean cancel;
        public void print(String text){output.add(text);if(text.contains("confirmation: "))next=text.substring(text.indexOf("confirmation: ")+14);}
        public String readLine(){return cancel?"no":next;}
    }
    static class Store extends OperatorAccountErasureTest.Fake {
        int denyReplacement=-1;
        Store(){tabs.put("retention_ledger",new ArrayList<>(List.of(RetentionLedger.HEADER)));tabs.put("quarterly_aggregates",new ArrayList<>(List.of(QuarterlyRetention.AGGREGATE_HEADER)));tabs.put("dataset",List.of(List.of("purpose","dataset_id"),List.of("bridgego-restricted-test",UUID.randomUUID().toString())));}
        @Override public synchronized void replaceAtomic(Map<String,List<List<Object>>> rows){if(replacements+1==denyReplacement){replacements++;throw new Failure(403,0);}super.replaceAtomic(rows);}
    }
    static final QuarterRetentionOperator.Settings EXECUTE=new QuarterRetentionOperator.Settings(true,true,true);
    static int run(Store db,Clock clock,QuarterRetentionOperator.Settings settings){return QuarterRetentionOperator.run(new Script(),()->db,settings,clock);}
    static FinalMatchEnvelope match(String account,Instant start,Instant finish){
        String id=UUID.randomUUID().toString();var people=new ArrayList<Map<String,Object>>();for(int seat=0;seat<4;seat++){Map<String,Object> p=new LinkedHashMap<>();p.put("match_id",id);p.put("seat",seat);p.put("account_id",seat==0?account:null);p.put("guest_id",null);p.put("player_type","BOT");p.put("control_type","BOT");people.add(p);}
        return FinalMatchEnvelope.create(id,1,Map.of("match",Map.of("match_id",id,"revision",1,"schema_version",1,"score_version","test","app_version",QuarterRetentionOperator.SYNTHETIC_APP_VERSION,"completed_deals",0,"started_at",start.toString(),"finished_at",finish.toString()),"participants",people,"completed_deals",List.of()));
    }
    @Test void headerOnlyInitialWatermarkSyntheticMatchesQuarterCleanupAndRestart()throws Exception{
        Store db=new Store();var clock=new SheetsStatsStoreTest.MutableClock("2026-09-29T10:00:00Z");
        assertThrows(IllegalStateException.class,()->QuarterlyRetention.requireCollectionReady(db,clock));assertEquals(0,run(db,clock,new QuarterRetentionOperator.Settings(false,false,true)));assertEquals(0,db.replacements);assertEquals(1,db.read("retention_ledger").size());
        assertEquals(0,run(db,clock,EXECUTE));assertEquals(2,db.replacements);assertEquals(clock.instant().toString(),db.read("retention_ledger").get(1).get(3));
        String account=null;var old=match(account,clock.instant().minusSeconds(60),clock.instant());
        try(var store=new SheetsStatsStore(db,2,clock)){store.submit(old);assertEquals(StatsStore.SaveStatus.CONFIRMED,SheetsStatsStoreTest.done(store,old.commitId()));}
        clock.now=Instant.parse("2026-09-30T16:00:00Z");assertThrows(IllegalStateException.class,()->new SheetsIdentityStore(db,clock).findOrCreateAccount("https://accounts.google.com","new"));var accounts=db.read("accounts");assertEquals(0,run(db,clock,EXECUTE));assertEquals(accounts,db.read("accounts"));assertEquals(1,db.read("envelopes").size());assertEquals(List.of("2026-Q3","SUPPRESSED","","","1"),db.read("quarterly_aggregates").get(1));
        var current=match(account,clock.instant().minusSeconds(60),clock.instant());
        try(var restarted=new SheetsStatsStore(db,2,clock)){assertTrue(restarted.exportCommitted().isEmpty());assertEquals(StatsStore.SaveStatus.REJECTED,restarted.submit(old));restarted.submit(current);assertEquals(StatsStore.SaveStatus.CONFIRMED,SheetsStatsStoreTest.done(restarted,current.commitId()));assertEquals(1,restarted.exportCommitted().size());}
    }
    @Test void crossQuarterFinishBlockedUntilActualCleanupThenTrustedResubmission()throws Exception{
        Store db=new Store();var clock=new SheetsStatsStoreTest.MutableClock("2026-09-30T15:59:50Z");assertEquals(0,run(db,clock,EXECUTE));clock.now=Instant.parse("2026-09-30T16:00:10Z");var cross=match(null,clock.instant().minusSeconds(60),clock.instant());
        try(var stats=new SheetsStatsStore(db,2,clock)){stats.submit(cross);assertEquals(StatsStore.SaveStatus.FAILED,SheetsStatsStoreTest.done(stats,cross.commitId()));assertEquals(1,db.read("envelopes").size());}
        assertEquals(0,run(db,clock,EXECUTE));
        // Only the test retains the trusted snapshot: production has no durable outbox.
        try(var stats=new SheetsStatsStore(db,2,clock)){stats.submit(cross);assertEquals(StatsStore.SaveStatus.CONFIRMED,SheetsStatsStoreTest.done(stats,cross.commitId()));}
    }
    @Test void pendingNeverFabricatesCompleteAndFreshProcessResumes(){
        Store db=new Store();Clock clock=Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"),ZoneOffset.UTC);db.denyReplacement=2;assertEquals(3,run(db,clock,EXECUTE));assertEquals("PENDING",db.read("retention_ledger").get(1).get(2));assertEquals("",db.read("retention_ledger").get(1).get(3));assertThrows(IllegalStateException.class,()->QuarterlyRetention.requireCollectionReady(db,clock));db.denyReplacement=-1;assertEquals(0,run(db,clock,EXECUTE));assertEquals("COMPLETE",db.read("retention_ledger").get(1).get(2));
    }
    @Test void timeoutReadbackConfirmsButUnavailableReadbackDoesNot(){
        Clock clock=Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"),ZoneOffset.UTC);Store timeout=new Store();timeout.timeoutAfter=true;assertEquals(0,run(timeout,clock,EXECUTE));Store unknown=new Store();unknown.failReadback=true;Script s=new Script();assertEquals(3,QuarterRetentionOperator.run(s,()->unknown,EXECUTE,clock));assertTrue(s.output.stream().anyMatch(v->v.contains("Result=UNCERTAIN")));assertEquals("PENDING",unknown.tabs.get("retention_ledger").get(1).get(2));
    }
    @Test void guardsNeverAccessWithoutAcknowledgementAndNeverLeak(){
        var s=new Script();assertEquals(2,QuarterRetentionOperator.run(s,()->{throw new AssertionError();},new QuarterRetentionOperator.Settings(true,true,false),Clock.systemUTC()));s=new Script();s.cancel=true;assertEquals(2,QuarterRetentionOperator.run(s,()->{throw new AssertionError();},EXECUTE,Clock.systemUTC()));
        Script error=new Script();assertEquals(3,QuarterRetentionOperator.run(error,()->{throw new IllegalArgumentException("PRIVATE-KEY");},EXECUTE,Clock.systemUTC()));assertFalse(error.output.toString().contains("PRIVATE-KEY"));Store db=new Store();assertEquals(0,run(db,Clock.systemUTC(),new QuarterRetentionOperator.Settings(true,false,true)));assertEquals(0,db.replacements);
    }
    @Test void malformedHeadersFailClosed(){Store db=new Store();db.tabs.get("accounts").set(0,List.of("wrong","schema_version"));assertEquals(3,run(db,Clock.systemUTC(),EXECUTE));assertEquals(0,db.replacements);}
    @Test void wrongExecutionPhraseNeverWrites(){Store db=new Store();Script s=new Script(){@Override public String readLine(){return output.stream().anyMatch(t->t.contains("confirmation: "))?"yes":QuarterRetentionOperator.STOPPED_ACK;}};assertEquals(2,QuarterRetentionOperator.run(s,()->db,EXECUTE,Clock.systemUTC()));assertEquals(0,db.replacements);}
    @Test void syntheticOnlyRestrictionRejectsAccountsHumansMissingMarkerOrMoreThanTwo(){
        Clock clock=Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"),ZoneOffset.UTC);
        Store accounts=new Store();accounts.tabs.get("accounts").add(List.of(UUID.randomUUID().toString(),"1"));assertEquals(3,run(accounts,clock,EXECUTE));assertEquals(0,accounts.replacements);
        for(String field:List.of("player_type","account_id","app_version")){
            Store db=new Store();var base=match(null,clock.instant().minusSeconds(60),clock.instant());var tree=(com.fasterxml.jackson.databind.node.ObjectNode)base.payload();
            if(field.equals("app_version"))((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("match")).put(field,"real-game");
            else ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("participants").get(0)).put(field,field.equals("player_type")?"HUMAN":UUID.randomUUID().toString());
            var changed=FinalMatchEnvelope.create(base.matchId(),1,new com.fasterxml.jackson.databind.ObjectMapper().convertValue(tree,Map.class));seed(db,changed);assertEquals(3,run(db,clock,EXECUTE));assertEquals(0,db.replacements);
        }
        Store tooMany=new Store();for(int i=0;i<3;i++)seed(tooMany,match(null,clock.instant().minusSeconds(60),clock.instant()));assertEquals(3,run(tooMany,clock,EXECUTE));assertEquals(0,tooMany.replacements);
    }
    static void seed(Store db,FinalMatchEnvelope e){db.tabs.get("envelopes").add(List.of(e.commitId(),e.payloadHash(),"0","1",e.canonicalJson()));db.tabs.get("commits").add(List.of(e.commitId(),e.payloadHash(),"1",e.matchId(),"1"));}

}
