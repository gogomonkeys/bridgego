package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class OperatorAccountErasureTest {
    static final String ISSUER="https://accounts.google.com";
    static class Fake implements SheetsGateway {
        final Map<String,List<List<String>>> tabs=new TreeMap<>();int replacements;boolean timeoutAfter,notApplied,failReadback,failReads;
        Fake(){
            Instant boundary=QuarterlyRetention.currentQuarterStart(Instant.now());tabs.put("retention_ledger",new ArrayList<>(List.of(RetentionLedger.HEADER,List.of(QuarterlyRetention.quarter(boundary),boundary.toString(),"COMPLETE",Instant.now().toString(),"1"))));
            tabs.put("envelopes",new ArrayList<>(List.of(List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk"))));
            tabs.put("commits",new ArrayList<>(List.of(List.of("commit_id","payload_hash","chunk_count","match_id","revision"))));
            tabs.put("accounts",new ArrayList<>(List.of(List.of("account_id","schema_version"))));
            tabs.put("auth_identities",new ArrayList<>(List.of(List.of("issuer","subject","account_id","schema_version"))));
            tabs.put("erasure_ledger",new ArrayList<>(List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL)));
            tabs.put("dataset",new ArrayList<>(List.of(List.of("purpose","bridgego-restricted-test"),List.of("dataset_id","test-fixture"))));
        }
        public synchronized List<List<String>> read(String tab){if(failReads)throw new Failure(503,0);return new ArrayList<>(tabs.get(tab));}
        public synchronized void appendAtomic(Map<String,List<List<Object>>> rows){rows.forEach((k,v)->v.forEach(r->tabs.get(k).add(r.stream().map(Object::toString).toList())));}
        public synchronized void replaceAtomic(Map<String,List<List<Object>>> rows){replacements++;if(notApplied)throw new Failure(503,0);rows.forEach((k,v)->tabs.put(k,new ArrayList<>(v.stream().map(r->r.stream().map(Object::toString).toList()).toList())));if(failReadback)failReads=true;if(timeoutAfter){timeoutAfter=false;throw new Failure(0,0);}}
    }
    static class Fixture {
        final Fake db=new Fake();final String account=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();final List<FinalMatchEnvelope> originals=new ArrayList<>();
        final GoogleCredentialVerifier verifier=(credential,audience,nonce)-> { if(!audience.equals("expected-audience")||!credential.endsWith(":"+nonce))throw new IllegalArgumentException("secret must never propagate");return new GoogleCredentialVerifier.Principal(ISSUER,credential.substring(0,credential.indexOf(':')));};
        Fixture()throws Exception {
            db.tabs.get("accounts").addAll(List.of(List.of(account,"1"),List.of(other,"1")));
            db.tabs.get("auth_identities").addAll(List.of(List.of(ISSUER,"owner",account,"1"),List.of(ISSUER,"other",other,"1")));
            for(int n=0;n<2;n++) {
                String match=UUID.randomUUID().toString();List<Map<String,Object>> people=new ArrayList<>();
                for(int seat=0;seat<4;seat++){Map<String,Object> person=new LinkedHashMap<>();person.put("match_id",match);person.put("seat",seat);person.put("account_id",seat==0?account:seat==1?other:null);person.put("guest_id",seat>=2?UUID.randomUUID().toString():null);people.add(person);}
                var e=FinalMatchEnvelope.create(match,1,Map.of("match",Map.of("match_id",match,"revision",1,"schema_version",1,"score_version","rules1","app_version","test","completed_deals",0,"ns_total",7,"started_at",Instant.now().minusMillis(1).toString(),"finished_at",Instant.now().toString()),"participants",people,"completed_deals",List.of()));originals.add(e);
                db.tabs.get("envelopes").add(List.of(e.commitId(),e.payloadHash(),"0","1",e.canonicalJson()));db.tabs.get("commits").add(List.of(e.commitId(),e.payloadHash(),"1",match,"1"));
            }
        }
        OperatorAccountErasure service(boolean enabled){return new OperatorAccountErasure(db,verifier,"expected-audience",Clock.systemUTC(),()->true,enabled);}
        OperatorAccountErasure.Preview preview(OperatorAccountErasure service){var c=service.challenge();return service.preview("owner:"+c.nonce(),c.nonce());}
        OperatorAccountErasure.Outcome execute(OperatorAccountErasure service,OperatorAccountErasure.Preview plan,String who){var c=service.challenge();return service.execute(plan.planId(),plan.beforeDigest(),plan.confirmationText(),who+":"+c.nonce(),c.nonce());}
    }
    @Test void erasureRemovesProfileAndRetainsOtherAccountProfile() throws Exception {
        Fixture f=new Fixture();
        f.db.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.HEADER,
            List.of(f.account,"2","刪除玩家","erase@example.test"),
            List.of(f.other,"2","保留玩家","keep@example.test"))));
        var service=f.service(true); var plan=f.preview(service);
        assertEquals(OperatorAccountErasure.Outcome.VERIFIED,f.execute(service,plan,"owner"));
        assertEquals(List.of(AccountProfileRows.HEADER,List.of(f.other,"2","保留玩家","keep@example.test")),f.db.tabs.get("accounts"));
        assertFalse(f.db.tabs.toString().contains("erase@example.test"));
        assertFalse(f.db.tabs.toString().contains("刪除玩家"));
    }
    @Test void mixedParticipantsScoresAndHashPreservedAndReplayBlocked()throws Exception {
        Fixture f=new Fixture();var service=f.service(true);var plan=f.preview(service);assertEquals(2,plan.affectedSeats());assertEquals(2,plan.affectedMatches());assertEquals(0,f.db.replacements);
        assertEquals(OperatorAccountErasure.Outcome.VERIFIED,f.execute(service,plan,"owner"));
        try(var store=new SheetsStatsStore(f.db,2)){
            var envelopes=store.exportCommitted();Set<String> markers=new HashSet<>();
            for(int i=0;i<2;i++){
                var original=f.originals.get(i).payload();var erased=envelopes.get(i).payload();
                assertEquals(original.path("match"),erased.path("match"));assertEquals(original.path("completed_deals"),erased.path("completed_deals"));
                assertTrue(erased.path("participants").get(0).path("account_id").isNull());assertTrue(markers.add(erased.path("participants").get(0).path("erased_marker").asText()));
                for(int seat=1;seat<4;seat++)assertEquals(original.path("participants").get(seat),erased.path("participants").get(seat));
                assertFalse(envelopes.get(i).canonicalJson().contains(f.account));assertNotEquals(f.originals.get(i).payloadHash(),envelopes.get(i).payloadHash());
            }
            var old=f.originals.get(0);store.submit(old);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(store.status(old.commitId())==StatsStore.SaveStatus.PENDING&&System.nanoTime()<end)Thread.sleep(5);assertEquals(StatsStore.SaveStatus.CONFLICT,store.status(old.commitId()));
        }
        assertEquals(List.of(List.of("account_id","schema_version"),List.of(f.other,"1")),f.db.tabs.get("accounts"));
        assertThrows(SheetsGateway.Conflict.class,()->new SheetsIdentityStore(f.db).findOrCreateAccount(ISSUER,"owner"));
        assertFalse(f.db.tabs.toString().contains(f.account));
    }
    @Test void stalePreviewWrongOwnerAndNoConfirmationNeverWrite()throws Exception {
        Fixture f=new Fixture();var s=f.service(true);var plan=f.preview(s);
        assertThrows(IllegalArgumentException.class,()->f.execute(s,plan,"other"));assertEquals(0,f.db.replacements);
        var challenge=s.challenge();assertThrows(IllegalArgumentException.class,()->s.execute(plan.planId(),plan.beforeDigest(),"yes","owner:"+challenge.nonce(),challenge.nonce()));
        f.db.tabs.get("accounts").add(List.of(UUID.randomUUID().toString(),"1"));assertThrows(IllegalStateException.class,()->f.execute(s,plan,"owner"));assertEquals(0,f.db.replacements);
    }
    @Test void defaultIsDryRunAndStoppedGateRequired()throws Exception {
        Fixture f=new Fixture();var s=new OperatorAccountErasure(f.db,f.verifier,"expected-audience",Clock.systemUTC(),()->true);var plan=f.preview(s);assertThrows(IllegalStateException.class,()->f.execute(s,plan,"owner"));
        var running=new OperatorAccountErasure(f.db,f.verifier,"expected-audience",Clock.systemUTC(),()->false,true);assertThrows(IllegalStateException.class,()->f.preview(running));assertEquals(0,f.db.replacements);
    }
    @Test void timeoutReadbackAndIdempotentRepeat()throws Exception {
        Fixture f=new Fixture();f.db.timeoutAfter=true;var s=f.service(true);var plan=f.preview(s);assertEquals(OperatorAccountErasure.Outcome.VERIFIED,f.execute(s,plan,"owner"));assertEquals(1,f.db.replacements);
        assertEquals(OperatorAccountErasure.Outcome.VERIFIED,f.execute(s,plan,"owner"));assertEquals(1,f.db.replacements);
        var repeated=f.preview(s);assertTrue(repeated.alreadyErased());assertEquals(OperatorAccountErasure.Outcome.ALREADY_ERASED,f.execute(s,repeated,"owner"));assertEquals(1,f.db.replacements);
    }
    @Test void malformedSchemaAndMissingLedgerFailClosed()throws Exception {
        Fixture f=new Fixture();f.db.tabs.get("erasure_ledger").clear();assertThrows(SheetsGateway.Conflict.class,()->f.preview(f.service(true)));assertEquals(0,f.db.replacements);
    }
    @Test void proofErrorsAndPreviewDoNotLeakCredentialsOrIdentifiers()throws Exception {
        Fixture f=new Fixture();var s=f.service(true);var c=s.challenge();var error=assertThrows(IllegalArgumentException.class,()->s.preview("SECRET-TOKEN-WITH-NAME",c.nonce()));assertFalse(error.toString().contains("SECRET"));assertNull(error.getCause());var p=f.preview(s);assertFalse(p.toString().contains(f.account));assertFalse(p.toString().contains("owner"));assertThrows(IllegalArgumentException.class,()->s.preview("owner:"+c.nonce(),c.nonce()));
    }
    @Test void oldIdentifierCannotReplayUnderNewMatchId()throws Exception {
        Fixture f=new Fixture();var service=f.service(true);var plan=f.preview(service);f.execute(service,plan,"owner");
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)f.originals.get(0).payload();String id=UUID.randomUUID().toString();((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("match")).put("match_id",id);for(var p:payload.path("participants"))((com.fasterxml.jackson.databind.node.ObjectNode)p).put("match_id",id);
        var replay=FinalMatchEnvelope.create(id,1,new com.fasterxml.jackson.databind.ObjectMapper().convertValue(payload,Map.class));
        try(var store=new SheetsStatsStore(f.db,1)){store.submit(replay);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(store.status(replay.commitId())==StatsStore.SaveStatus.PENDING&&System.nanoTime()<end)Thread.sleep(5);assertEquals(StatsStore.SaveStatus.CONFLICT,store.status(replay.commitId()));assertEquals(2,store.exportCommitted().size());}
    }
    @Test void unknownMatchMetadataFailsWithoutLeakingOrMutating()throws Exception {
        Fixture f=new Fixture();var old=f.originals.get(0);var payload=(com.fasterxml.jackson.databind.node.ObjectNode)old.payload();((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("match")).put("secret_name", "PRIVATE-NAME");var altered=FinalMatchEnvelope.create(old.matchId(),1,new com.fasterxml.jackson.databind.ObjectMapper().convertValue(payload,Map.class));
        f.db.tabs.get("envelopes").set(1,List.of(altered.commitId(),altered.payloadHash(),"0","1",altered.canonicalJson()));f.db.tabs.get("commits").set(1,List.of(altered.commitId(),altered.payloadHash(),"1",altered.matchId(),"1"));
        var error=assertThrows(SheetsGateway.Conflict.class,()->f.preview(f.service(true)));assertFalse(error.toString().contains("PRIVATE"));assertEquals(0,f.db.replacements);
    }
    @Test void uncertainAndNotAppliedAreNeverBlindlyRetried()throws Exception {
        Fixture f=new Fixture();var service=f.service(true);var plan=f.preview(service);f.db.notApplied=true;assertEquals(OperatorAccountErasure.Outcome.NOT_APPLIED,f.execute(service,plan,"owner"));assertEquals(1,f.db.replacements);
        Fixture g=new Fixture();var second=g.service(true);var other=g.preview(second);g.db.failReadback=true;assertEquals(OperatorAccountErasure.Outcome.UNCERTAIN,g.execute(second,other,"owner"));assertEquals(1,g.db.replacements);
    }

}
