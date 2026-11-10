package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** All inputs and operator stores are synthetic in-memory fixtures. */
class DealTimingEnvelopeTest {
  static final String RULE = "elapsed-including-pauses-v1";
  static ObjectNode timed(FinalMatchEnvelope original, int bots) {
    var root = MatchCategoryEnvelopeTest.marked(original, bots);
    ((ObjectNode)root.path("match")).put("deal_timing_rule", RULE);
    root.path("completed_deals").forEach(d -> ((ObjectNode)d).put("elapsed_ms", 123456L));
    return root;
  }
  static ObjectNode match(ObjectNode root) { return (ObjectNode)root.path("match"); }
  static ObjectNode deal(ObjectNode root) { return (ObjectNode)root.path("completed_deals").get(0); }
  static FinalMatchEnvelope envelope(ObjectNode root) { return MatchCategoryEnvelopeTest.envelope(root); }

  @Test void oldUntimedRecordsRemainValidAndTimedCategoriesKeepExactMilliseconds() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    assertDoesNotThrow(() -> envelope((ObjectNode)original.payload()));
    for (int bots : List.of(0, 1, 2, 3)) {
      var root = timed(original, bots); assertTrue(root.path("completed_deals").size() > 0);
      var saved = envelope(root);
      assertEquals(123456L, saved.payload().path("completed_deals").get(0).path("elapsed_ms").longValue());
      deal(root).put("elapsed_ms", 0L); assertDoesNotThrow(() -> envelope(root));
      deal(root).put("elapsed_ms", 9_007_199_254_740_991L); assertDoesNotThrow(() -> envelope(root));
    }
  }

  @Test void missingWrongTypedNegativeOrUnsafeTimingValuesCannotBeAccepted() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    List<Consumer<ObjectNode>> invalid = List.of(
        root -> deal(root).remove("elapsed_ms"),
        root -> deal(root).putNull("elapsed_ms"),
        root -> deal(root).put("elapsed_ms", "100"),
        root -> deal(root).put("elapsed_ms", true),
        root -> deal(root).put("elapsed_ms", -1L),
        root -> deal(root).put("elapsed_ms", 1.5),
        root -> deal(root).put("elapsed_ms", 9_007_199_254_740_992L),
        root -> deal(root).put("elapsed_ms", Long.MAX_VALUE),
        root -> match(root).put("deal_timing_rule", "unknown"),
        root -> match(root).putNull("deal_timing_rule"),
        root -> match(root).put("deal_timing_rule", true),
        root -> match(root).remove("deal_timing_rule")
    );
    for (int index = 0; index < invalid.size(); index++) {
      var root = timed(original, 1); invalid.get(index).accept(root);
      assertThrows(IllegalArgumentException.class, () -> envelope(root), "case " + index);
    }
  }

  @Test void timingRuleIsIndependentOfStatisticsCategoryButCannotBeInferred() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired; var root = timed(original, 0);
    match(root).put("statistics_rule", FinalMatchEnvelope.LEGACY_STATISTICS_RULE).remove("match_category");
    root.path("participants").forEach(person -> ((ObjectNode)person).remove("settlement_control"));
    assertDoesNotThrow(() -> envelope(root));
    match(root).remove("deal_timing_rule");
    root.path("completed_deals").forEach(deal -> ((ObjectNode)deal).remove("elapsed_ms"));
    assertDoesNotThrow(() -> envelope(root));
    assertFalse(envelope(root).payload().path("completed_deals").get(0).has("elapsed_ms"));
  }

  @Test void quarterMaintenancePreservesTimingForAllRetainedCategories() {
    var storage = new ManualQuarterMaintenanceTest.Storage();
    for (int row = 1; row < storage.f.tables.get("envelopes").size(); row++) {
      var original = ManualQuarterMaintenance.decoded(storage.f.tables).get(storage.f.tables.get("commits").get(row).get(0));
      MatchCategoryEnvelopeTest.replace(storage.f.tables, row, envelope(timed(original, row % 2 == 0 ? 0 : 1)));
    }
    var expected = new TreeMap<>(ManualQuarterMaintenance.decoded(storage.f.tables)); expected.remove(storage.f.expired.commitId());
    var service = storage.service(); var preview = ManualQuarterMaintenanceTest.preview(storage, service);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, ManualQuarterMaintenanceTest.execute(service, preview).outcome());
    assertEquals(expected, ManualQuarterMaintenance.decoded(storage.f.tables));
  }

  @Test void accountErasurePreservesTimingExactlyWhileRemovingIdentity() throws Exception {
    var fixture = new OperatorAccountErasureTest.Fixture(); var originals = new ArrayList<FinalMatchEnvelope>();
    for (int row = 0; row < fixture.originals.size(); row++) {
      var record = envelope(timed(fixture.originals.get(row), row % 2 == 0 ? 0 : 1));
      originals.add(record); MatchCategoryEnvelopeTest.replace(fixture.db.tabs, row + 1, record);
    }
    var service = fixture.service(true); var preview = fixture.preview(service);
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED, fixture.execute(service, preview, "owner"));
    var after = SheetsStatsStore.readVerified(fixture.db);
    for (var original : originals) {
      var result = after.get(original.commitId());
      assertEquals(original.payload().path("match"), result.payload().path("match"));
      assertEquals(original.payload().path("completed_deals"), result.payload().path("completed_deals"));
      assertFalse(result.canonicalJson().contains(fixture.account));
    }
  }
}
