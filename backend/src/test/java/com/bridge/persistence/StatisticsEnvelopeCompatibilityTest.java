package com.bridge.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic memory-only coverage. No adapters, credentials, or external writes. */
class StatisticsEnvelopeCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode marked(FinalMatchEnvelope original) {
        ObjectNode root = (ObjectNode) original.payload();
        ((ObjectNode) root.path("match")).put("statistics_rule", FinalMatchEnvelope.STATISTICS_RULE).put("outcome", "SCORED");
        for (var node : root.path("participants")) {
            ObjectNode person = (ObjectNode) node;
            person.put("player_type", "HUMAN").put("control_type", "HUMAN")
                .put("team_id", person.path("seat").asInt() % 2 == 0 ? "NS" : "EW").putNull("guest_id");
        }
        return root;
    }
    private static FinalMatchEnvelope envelope(ObjectNode root) {
        return FinalMatchEnvelope.create(root.path("match").path("match_id").asText(), 1, JSON.convertValue(root, Map.class));
    }
    private static ObjectNode person(ObjectNode root, int seat) { return (ObjectNode) root.path("participants").get(seat); }
    private static ObjectNode match(ObjectNode root) { return (ObjectNode) root.path("match"); }
    private static void replace(Map<String,List<List<String>>> tables, int row, FinalMatchEnvelope e) {
        tables.get("envelopes").set(row, List.of(e.commitId(), e.payloadHash(), "0", "1", e.canonicalJson()));
        tables.get("commits").set(row, List.of(e.commitId(), e.payloadHash(), "1", e.matchId(), "1"));
    }

    @Test void legacyStillValidAndMarkedGuestsMixedControllersAndErasedSeatsAreValid() {
        var f = new LocalManualQuarterRehearsal.Fixture();
        assertDoesNotThrow(() -> envelope((ObjectNode) f.expired.payload()));
        var root = marked(f.expired);
        person(root, 0).put("control_type", "MIXED");
        var saved = assertDoesNotThrow(() -> envelope(root));
        assertEquals(FinalMatchEnvelope.STATISTICS_RULE, saved.payload().path("match").path("statistics_rule").asText());
        assertTrue(saved.payload().path("participants").get(2).has("erased_marker"));
        assertTrue(saved.payload().path("participants").get(3).path("account_id").isNull());
    }

    @Test void unknownOrWrongTypedRuleAndMalformedMarkedParticipantsAreRejected() {
        var original = new LocalManualQuarterRehearsal.Fixture().expired;
        List<Consumer<ObjectNode>> invalid = List.of(
            r -> match(r).put("statistics_rule", "four-humans-at-settlement-v2"),
            r -> match(r).put("statistics_rule", true),
            r -> match(r).put("statistics_rule", 1),
            r -> match(r).putNull("statistics_rule"),
            r -> match(r).putObject("statistics_rule"),
            r -> match(r).put("outcome", "ABORTED"),
            r -> person(r, 3).put("player_type", "BOT"),
            r -> person(r, 3).put("control_type", "BOT"),
            r -> person(r, 3).put("guest_id", UUID.randomUUID().toString()),
            r -> person(r, 3).remove("guest_id"),
            r -> person(r, 3).remove("account_id"),
            r -> person(r, 0).put("account_id", "1-1-1-1-1"),
            r -> person(r, 0).put("account_id", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
            r -> person(r, 0).put("account_id", true),
            r -> person(r, 1).put("account_id", person(r, 0).path("account_id").asText()),
            r -> person(r, 1).put("team_id", "NS"),
            r -> person(r, 2).put("erased_marker", true),
            r -> person(r, 0).put("erased_marker", UUID.randomUUID().toString())
        );
        for (int i = 0; i < invalid.size(); i++) {
            var root = marked(original); invalid.get(i).accept(root);
            assertThrows(IllegalArgumentException.class, () -> envelope(root), "Invalid case " + i);
        }
    }

    @Test void quarterMaintenancePreservesMarkedCurrentRecordsAndSuppression() {
        var s = new ManualQuarterMaintenanceTest.Storage();
        for (int row = 1; row < s.f.tables.get("envelopes").size(); row++) {
            var previous = ManualQuarterMaintenance.decoded(s.f.tables).get(s.f.tables.get("commits").get(row).get(0));
            replace(s.f.tables, row, envelope(marked(previous)));
        }
        var expected = new TreeMap<>(ManualQuarterMaintenance.decoded(s.f.tables)); expected.remove(s.f.expired.commitId());
        var service = s.service(); var preview = ManualQuarterMaintenanceTest.preview(s, service);
        assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, ManualQuarterMaintenanceTest.execute(service, preview).outcome());
        assertEquals(expected, ManualQuarterMaintenance.decoded(s.f.tables));
        for (String tab : ManualQuarterMaintenance.PRESERVED) assertEquals(s.f.before.get(tab), s.f.tables.get(tab));
        assertEquals("SUPPRESSED", s.f.tables.get("quarterly_aggregates").get(1).get(1));
    }

    @Test void accountErasureRetainsStatisticsMetadataAndRemovesOnlyAccountIdentity() throws Exception {
        var f = new OperatorAccountErasureTest.Fixture();
        var originals = new ArrayList<FinalMatchEnvelope>();
        for (int i = 0; i < f.originals.size(); i++) {
            var marked = envelope(marked(f.originals.get(i))); originals.add(marked); replace(f.db.tabs, i + 1, marked);
        }
        var service = f.service(true); var preview = f.preview(service);
        assertEquals(OperatorAccountErasure.Outcome.VERIFIED, f.execute(service, preview, "owner"));
        var erased = SheetsStatsStore.readVerified(f.db);
        for (var original : originals) {
            var result = erased.get(original.commitId());
            assertEquals(original.payload().path("match"), result.payload().path("match"));
            assertEquals(original.payload().path("completed_deals"), result.payload().path("completed_deals"));
            var seat = result.payload().path("participants").get(0);
            assertTrue(seat.path("account_id").isNull()); assertTrue(seat.path("guest_id").isNull()); assertTrue(seat.has("erased_marker"));
            for (int other = 1; other < 4; other++) assertEquals(original.payload().path("participants").get(other), result.payload().path("participants").get(other));
            assertFalse(result.canonicalJson().contains(f.account));
        }
    }
}
