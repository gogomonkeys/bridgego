package com.bridge.quartercore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Current production envelope codec through the real native journal writer, memory sink only. */
class NativeCategoryIdentitySafetyTest {
    static com.bridge.persistence.FinalMatchEnvelope envelope(Book book,String category) {
        var row=book.sheet("envelopes").rows.get(1);
        String oldId=parse(row.get(4)).path("match").path("match_id").asText();String id=UUID.randomUUID().toString();
        var payload=(ObjectNode)parse(row.get(4).replace(oldId,id));var match=(ObjectNode)payload.path("match");
        match.put("room_type",category).put("match_category",category).put("outcome","SCORED")
                .put("planned_deals",1).put("reason","PLANNED_DEALS_COMPLETE").put("winner_team_id","NS");
        if(category.equals("CASUAL"))((ObjectNode)payload.path("participants").get(3))
                .put("player_type","BOT").put("control_type","BOT").put("settlement_control","BOT").putNull("account_id");
        return com.bridge.persistence.FinalMatchEnvelope.create(id,1,JSON.convertValue(payload,Map.class));
    }
    @Test void formalAndCasualPreserveCanonicalPayloadTimingAndIndependentIdentity() throws Exception {
        var memory=JournaledNativeWriterTest.initial();
        var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
        try(var service=JournaledNativeWriterTest.service(transport)) {
            service.start();var adapter=new NativeWriterAdapter(service);
            var identities=copyRows(memory.b.sheet("auth_identities").rows);
            var profiles=copyRows(memory.b.sheet("accounts").rows);int writes=memory.writes;
            for(String category:List.of("FORMAL","CASUAL")) {
                var e=envelope(memory.b,category);
                assertEquals(NativeWriterAdapter.Outcome.VERIFIED,adapter.match("CATEGORY-"+category,e).outcome());
                var stored=memory.b.sheet("envelopes").rows.stream().filter(r->r.get(0).equals(e.commitId())).findFirst().orElseThrow();
                assertEquals(e.payloadHash(),stored.get(1));assertEquals(e.canonicalJson(),stored.get(4));
                assertEquals(category,e.statisticsCategory());
                assertTrue(e.payload().path("completed_deals").get(0).has("elapsed_ms"));
                assertTrue(e.payload().path("participants").get(2).path("account_id").isNull()); // Guest remains eligible; no guest account created.
                NativeCommitJournal.verify(JournaledNativeWriterTest.BINDING,memory.b,JournaledNativeWriterTest.CLOCK);
            }
            assertEquals(writes+2,memory.writes);assertEquals(identities,memory.b.sheet("auth_identities").rows);
            assertEquals(profiles,memory.b.sheet("accounts").rows);
        }
    }
    @Test void nicknameChangeUsesSameGoogleIdentityWithoutOverwritingLifetime() throws Exception {
        var memory=JournaledNativeWriterTest.initial();
        var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
        try(var service=JournaledNativeWriterTest.service(transport)) {
            service.start();var adapter=new NativeWriterAdapter(service);var identity=memory.b.sheet("auth_identities").rows.get(1);
            var before=copyRows(memory.b.sheet("accounts").rows);int count=before.size();
            var first=adapter.identity("NICK-ONE",identity.get(0),identity.get(1),"First nickname","one@example.com");
            var next=adapter.identity("NICK-TWO",identity.get(0),identity.get(1),"Second nickname","two@example.com");
            assertEquals(first.accountId(),next.accountId());assertEquals(NativeWriterAdapter.Outcome.VERIFIED,next.outcome());
            assertEquals(count,memory.b.sheet("accounts").rows.size());
            var changed=memory.b.sheet("accounts").rows.stream().filter(r->r.get(0).equals(first.accountId())).findFirst().orElseThrow();
            assertEquals("Second nickname",changed.get(2));assertEquals("two@example.com",changed.get(3));
            assertEquals(before.stream().filter(r->r.get(0).equals(first.accountId())).findFirst().orElseThrow().get(4),changed.get(4));
        }
    }
    @Test void unknownMatchCannotFallbackIntoIdentityOrAnotherMatch() throws Exception {
        var memory=JournaledNativeWriterTest.initial();
        var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
        try(var service=JournaledNativeWriterTest.service(transport)) {
            service.start();var adapter=new NativeWriterAdapter(service);var e=envelope(memory.b,"CASUAL");
            var identity=memory.b.sheet("auth_identities").rows.get(1);memory.delay=true;int count=memory.writes;
            assertEquals(NativeWriterAdapter.Outcome.UNKNOWN,adapter.match("UNKNOWN-MATCH",e).outcome());
            assertThrows(IllegalStateException.class,()->adapter.match("ANOTHER",e));
            assertThrows(IllegalStateException.class,()->adapter.identity("OTHER-IDENTITY",identity.get(0),identity.get(1),"Blocked","blocked@example.com"));
            assertEquals(NativeWriterAdapter.Outcome.UNKNOWN,adapter.reconcilePending().outcome());
            assertEquals(count+1,memory.writes);
            memory.b=simulate(memory.b,memory.delayed);
            assertEquals(NativeWriterAdapter.Outcome.VERIFIED,adapter.reconcilePending().outcome());
            assertEquals(count+1,memory.writes);
            assertEquals(1,memory.b.sheet("commits").rows.stream().filter(r->r.get(0).equals(e.commitId())).count());
        }
    }
    @Test void actualNativeBackendReadsCorrectSeasonAndLifetimeScoresForBothCategories() throws Exception {
        var memory=JournaledNativeWriterTest.initial();
        var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
        try(var service=JournaledNativeWriterTest.service(transport)) {
            service.start();
            try(var backend=new NativeStoresBackend(service,transport,JournaledNativeWriterTest.CLOCK,
                    new com.bridge.persistence.QuarterMaintenanceGate(false,JournaledNativeWriterTest.CLOCK))) {
                String account=memory.b.sheet("accounts").rows.get(1).get(0);
                var before=backend.exportStatistics(account);
                var beforeCurrent=com.bridge.persistence.LifetimeStatistics.fromEnvelopes(account,before.current());
                assertEquals(1,before.lifetime().formal().matches());assertEquals(7,before.lifetime().formal().totalScore());
                for(String category:List.of("FORMAL","CASUAL")) {
                    var record=envelope(memory.b,category);
                    assertEquals(com.bridge.persistence.StatsStore.SaveStatus.PENDING,backend.submit(record));
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    while(backend.status(record.commitId())==com.bridge.persistence.StatsStore.SaveStatus.PENDING && System.nanoTime()<deadline)
                        Thread.sleep(10);
                    assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,backend.status(record.commitId()));
                    assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,backend.submit(record)); // Same-process idempotency.
                }
                var after=backend.exportStatistics(account);
                var current=com.bridge.persistence.LifetimeStatistics.fromEnvelopes(account,after.current());
                assertEquals(beforeCurrent.formal().matches()+1,current.formal().matches());
                assertEquals(beforeCurrent.formal().wins()+1,current.formal().wins());
                assertEquals(beforeCurrent.formal().totalScore()+7,current.formal().totalScore());
                assertEquals(beforeCurrent.casual().matches()+1,current.casual().matches());
                assertEquals(beforeCurrent.casual().wins()+1,current.casual().wins());
                assertEquals(beforeCurrent.casual().totalScore()+7,current.casual().totalScore());
                assertEquals(before.lifetime(),after.lifetime()); // New-season saves never overwrite historical carry.
                assertEquals(14,after.lifetime().formal().plus(current.formal()).totalScore());
                assertEquals(14,after.lifetime().casual().plus(current.casual()).totalScore());
                String opponent=memory.b.sheet("accounts").rows.get(2).get(0);
                var ew=backend.exportStatistics(opponent);
                var ewCurrent=com.bridge.persistence.LifetimeStatistics.fromEnvelopes(opponent,ew.current());
                assertEquals(-6,ew.lifetime().formal().plus(ewCurrent.formal()).totalScore());
                assertEquals(-6,ew.lifetime().casual().plus(ewCurrent.casual()).totalScore());
                assertEquals(1,ewCurrent.formal().losses());assertEquals(1,ewCurrent.casual().losses());
                assertEquals(JournaledNativeWriterTest.CLOCK.instant(),after.asOf());
            }
        }
    }

}
