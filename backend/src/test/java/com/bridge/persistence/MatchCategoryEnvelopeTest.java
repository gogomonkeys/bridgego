package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Memory-only compatibility coverage for operator operations and category provenance. */
class MatchCategoryEnvelopeTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static ObjectNode match(ObjectNode root) { return (ObjectNode)root.path("match"); }
  static ObjectNode person(ObjectNode root, int seat) { return (ObjectNode)root.path("participants").get(seat); }
  static ObjectNode marked(FinalMatchEnvelope original, int bots) {
    var root = (ObjectNode)original.payload();
    match(root).put("statistics_rule", FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE).put("outcome", "SCORED")
        .put("match_category", bots == 0 ? "FORMAL" : "CASUAL");
    for (int seat = 0; seat < 4; seat++) {
      boolean bot = seat >= 4 - bots;
      var person = person(root, seat);
      person.put("player_type", bot ? "BOT" : "HUMAN").put("control_type", bot ? "BOT" : "HUMAN")
          .put("settlement_control", bot ? "BOT" : "HUMAN").put("team_id", seat % 2 == 0 ? "NS" : "EW").putNull("guest_id");
      if (bot) { person.putNull("account_id"); person.remove("erased_marker"); }
    }
    return root;
  }
  static FinalMatchEnvelope envelope(ObjectNode root) {
    return FinalMatchEnvelope.create(match(root).path("match_id").asText(), 1, JSON.convertValue(root, Map.class));
  }
  static void replace(Map<String,List<List<String>>> tables, int row, FinalMatchEnvelope record) {
    tables.get("envelopes").set(row, List.of(record.commitId(), record.payloadHash(), "0", "1", record.canonicalJson()));
    tables.get("commits").set(row, List.of(record.commitId(), record.payloadHash(), "1", record.matchId(), "1"));
  }

  @Test void bothCategoriesValidateAndCannotLieAboutControllerOrBotIdentity() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    for (int bots = 0; bots < 4; bots++) assertEquals(bots == 0 ? "FORMAL" : "CASUAL", envelope(marked(original, bots)).statisticsCategory());
    List<Consumer<ObjectNode>> invalid = List.of(
        root -> match(root).put("match_category", "FORMAL"),
        root -> match(root).put("match_category", "casual"),
        root -> match(root).putNull("match_category"),
        root -> match(root).remove("match_category"),
        root -> person(root, 3).put("account_id", UUID.randomUUID().toString()),
        root -> person(root, 3).put("erased_marker", UUID.randomUUID().toString()),
        root -> person(root, 3).put("guest_id", UUID.randomUUID().toString()),
        root -> person(root, 3).put("settlement_control", "HUMAN"),
        root -> person(root, 3).remove("settlement_control"),
        root -> person(root, 0).put("settlement_control", "BOT"),
        root -> person(root, 0).put("settlement_control", true)
    );
    for (int i = 0; i < invalid.size(); i++) {
      var root = marked(original, 1); invalid.get(i).accept(root);
      assertThrows(IllegalArgumentException.class, () -> envelope(root), "case " + i);
    }
    assertThrows(IllegalArgumentException.class, () -> envelope(marked(original, 4)));
    var falseCasual = marked(original, 0); match(falseCasual).put("match_category", "CASUAL");
    assertThrows(IllegalArgumentException.class, () -> envelope(falseCasual));
  }

  @Test void humanTakeoverCanRetainOriginalAccountButReclaimedSeatReturnsFormal() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    var root = marked(original, 0);
    person(root, 0).put("control_type", "MIXED").put("settlement_control", "BOT");
    match(root).put("match_category", "CASUAL");
    assertEquals("CASUAL", envelope(root).statisticsCategory());
    person(root, 0).put("settlement_control", "HUMAN"); match(root).put("match_category", "FORMAL");
    assertEquals("FORMAL", envelope(root).statisticsCategory());
  }

  @Test void legacyV1AllowsNoCategoryOrInventedSettlementProvenance() {
    var root = marked(new LocalManualQuarterRehearsal.Fixture().expired, 0);
    match(root).put("statistics_rule", FinalMatchEnvelope.LEGACY_STATISTICS_RULE).remove("match_category");
    root.path("participants").forEach(p -> ((ObjectNode)p).remove("settlement_control"));
    assertEquals("FORMAL", envelope(root).statisticsCategory());
    person(root, 0).put("settlement_control", "HUMAN");
    assertThrows(IllegalArgumentException.class, () -> envelope(root));
  }

  @Test void manualQuarterMaintenancePreservesCurrentMixedCategoriesAndErasesExpiredOnly() {
    var storage = new ManualQuarterMaintenanceTest.Storage();
    for (int row = 1; row < storage.f.tables.get("envelopes").size(); row++) {
      var original = ManualQuarterMaintenance.decoded(storage.f.tables).get(storage.f.tables.get("commits").get(row).get(0));
      replace(storage.f.tables, row, envelope(marked(original, row % 2 == 0 ? 0 : 1)));
    }
    var expected = new TreeMap<>(ManualQuarterMaintenance.decoded(storage.f.tables)); expected.remove(storage.f.expired.commitId());
    var service = storage.service(); var preview = ManualQuarterMaintenanceTest.preview(storage, service);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, ManualQuarterMaintenanceTest.execute(service, preview).outcome());
    assertEquals(expected, ManualQuarterMaintenance.decoded(storage.f.tables));
    for (String tab : ManualQuarterMaintenance.PRESERVED) assertEquals(storage.f.before.get(tab), storage.f.tables.get(tab));
    assertEquals("SUPPRESSED", storage.f.tables.get("quarterly_aggregates").get(1).get(1));
  }

  @Test void accountErasurePreservesFormalAndCasualClassificationAndBotPrivacy() throws Exception {
    var fixture = new OperatorAccountErasureTest.Fixture(); var originals = new ArrayList<FinalMatchEnvelope>();
    for (int row = 0; row < fixture.originals.size(); row++) {
      var record = envelope(marked(fixture.originals.get(row), row % 2 == 0 ? 0 : 1));
      originals.add(record); replace(fixture.db.tabs, row + 1, record);
    }
    var service = fixture.service(true); var preview = fixture.preview(service);
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED, fixture.execute(service, preview, "owner"));
    var after = SheetsStatsStore.readVerified(fixture.db);
    for (var original : originals) {
      var result = after.get(original.commitId());
      assertEquals(original.payload().path("match"), result.payload().path("match"));
      assertEquals(original.statisticsCategory(), result.statisticsCategory());
      var seat = result.payload().path("participants").get(0);
      assertTrue(seat.path("account_id").isNull()); assertTrue(seat.path("guest_id").isNull()); assertTrue(seat.has("erased_marker"));
      assertFalse(result.canonicalJson().contains(fixture.account));
      for (int other = 1; other < 4; other++) assertEquals(original.payload().path("participants").get(other), result.payload().path("participants").get(other));
    }
  }
}
