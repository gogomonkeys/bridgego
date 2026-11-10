package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Fixed-room record validation and offline operator compatibility. */
class FixedRoomEnvelopeTest {
  static ObjectNode record(FinalMatchEnvelope original, String type, String outcome) {
    var root = MatchCategoryEnvelopeTest.marked(original, 0); var match = MatchCategoryEnvelopeTest.match(root);
    match.put("statistics_rule", FinalMatchEnvelope.STATISTICS_RULE).put("room_type", type).put("match_category", type)
        .put("deal_timing_rule", FinalMatchEnvelope.DEAL_TIMING_RULE).put("planned_deals", 3)
        .put("outcome", outcome).put("reason", outcome.equals("FORFEIT") ? "MANUAL_EXIT"
            : type.equals("FORMAL") ? "BOTH_TEAMS_FAILED" : "NO_HUMANS_REMAIN");
    int ns = 0, ew = 0;
    for (var deal : root.path("completed_deals")) {
      ((ObjectNode)deal).put("elapsed_ms", 4321L); ns += deal.path("ns_total").asInt(); ew += deal.path("ew_total").asInt();
    }
    match.put("ns_total", ns).put("ew_total", ew);
    if (outcome.equals("NO_CONTEST")) match.putNull("winner_team_id"); else match.put("winner_team_id", "EW");
    return root;
  }
  static ObjectNode match(ObjectNode root) { return MatchCategoryEnvelopeTest.match(root); }
  static ObjectNode person(ObjectNode root, int seat) { return MatchCategoryEnvelopeTest.person(root, seat); }
  static FinalMatchEnvelope envelope(ObjectNode root) { return MatchCategoryEnvelopeTest.envelope(root); }

  @Test void newMarkersRequireFixedRoomAndOutcomeIndependentOfCompletedScore() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    for (String type : List.of("FORMAL", "CASUAL")) for (String outcome : List.of("FORFEIT", "NO_CONTEST")) {
      if (type.equals("CASUAL") && outcome.equals("FORFEIT")) continue;
      var saved = envelope(record(original, type, outcome));
      assertEquals(type, saved.statisticsCategory()); assertEquals(1, saved.payload().path("completed_deals").size());
      assertEquals(7, saved.payload().path("match").path("ns_total").asInt());
      assertEquals(outcome.equals("FORFEIT") ? "EW" : "", saved.payload().path("match").path("winner_team_id").asText(""));
    }
  }

  @Test void fixedEnvelopeRejectsContradictoryTypesOutcomesAndInventedScores() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    List<Consumer<ObjectNode>> invalid = List.of(
        r -> match(r).remove("room_type"),
        r -> match(r).put("room_type", "CASUAL"),
        r -> match(r).put("room_type", "FORMAL "),
        r -> match(r).put("match_category", "CASUAL"),
        r -> person(r, 0).put("control_type", "MIXED"),
        r -> person(r, 3).put("player_type", "BOT").put("control_type", "BOT").put("settlement_control", "BOT"),
        r -> match(r).put("outcome", "SCORED"),
        r -> match(r).put("outcome", "ABORTED"),
        r -> match(r).putNull("winner_team_id"),
        r -> match(r).put("winner_team_id", "OTHER"),
        r -> match(r).put("planned_deals", 0),
        r -> match(r).put("completed_deals", 2),
        r -> match(r).put("ns_total", 900),
        r -> ((ObjectNode)r.path("completed_deals").get(0)).put("deal_index", 2),
        r -> ((ObjectNode)r.path("completed_deals").get(0)).remove("ns_total"),
        r -> match(r).remove("deal_timing_rule"),
        r -> match(r).putNull("reason"),
        r -> match(r).put("statistics_rule", FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE)
    );
    for (int index = 0; index < invalid.size(); index++) {
      var root = record(original, "FORMAL", "FORFEIT"); invalid.get(index).accept(root);
      assertThrows(IllegalArgumentException.class, () -> envelope(root), "case " + index);
    }
    var noContest = record(original, "FORMAL", "NO_CONTEST"); match(noContest).put("winner_team_id", "EW");
    assertThrows(IllegalArgumentException.class, () -> envelope(noContest));
  }

  @Test void casualCanFinishWithAllHumanOrAllReplacedButNeverPureComputerOrigin() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    var root = record(original, "CASUAL", "NO_CONTEST");
    assertDoesNotThrow(() -> envelope(root));
    for (int seat = 0; seat < 4; seat++) person(root, seat).put("control_type", "MIXED").put("settlement_control", "BOT");
    assertDoesNotThrow(() -> envelope(root));
    match(root).put("outcome", "FORFEIT").put("winner_team_id", "EW");
    assertThrows(IllegalArgumentException.class, () -> envelope(root));
    match(root).put("outcome", "NO_CONTEST").putNull("winner_team_id");
    for (int seat = 0; seat < 4; seat++) {
      person(root, seat).put("player_type", "BOT").put("control_type", "BOT").putNull("account_id").remove("erased_marker");
    }
    assertThrows(IllegalArgumentException.class, () -> envelope(root));
  }

  @Test void reasonMustMatchRoomTypeAndOutcomeAndOnlyScoredMayCompletePlannedCount() {
    var original = new LocalManualQuarterRehearsal.Fixture().expired;
    for (String type : List.of("FORMAL", "CASUAL")) {
      for (String outcome : List.of("FORFEIT", "NO_CONTEST")) {
        for (String reason : List.of("MANUAL_EXIT", "RECONNECT_TIMEOUT", "BOTH_TEAMS_FAILED",
            "NO_HUMANS_CONNECTED", "NO_HUMANS_REMAIN", "THREE_DEALS_COMPLETE", "PLANNED_DEALS_COMPLETE", "UNKNOWN")) {
          var root = record(original, type, outcome); match(root).put("reason", reason);
          boolean allowed = outcome.equals("FORFEIT") ? type.equals("FORMAL") && Set.of("MANUAL_EXIT", "RECONNECT_TIMEOUT").contains(reason)
              : type.equals("FORMAL") ? reason.equals("BOTH_TEAMS_FAILED") : Set.of("NO_HUMANS_CONNECTED", "NO_HUMANS_REMAIN").contains(reason);
          if (allowed) assertDoesNotThrow(() -> envelope(root));
          else assertThrows(IllegalArgumentException.class, () -> envelope(root));
        }
      }
    }
    for (String outcome : List.of("FORFEIT", "NO_CONTEST")) {
      var completed = record(original, "FORMAL", outcome); match(completed).put("planned_deals", 1);
      assertThrows(IllegalArgumentException.class, () -> envelope(completed));
    }
    var scored = record(original, "FORMAL", "NO_CONTEST");
    match(scored).put("outcome", "SCORED").put("planned_deals", 1).put("winner_team_id", "NS")
        .put("reason", "PLANNED_DEALS_COMPLETE");
    assertDoesNotThrow(() -> envelope(scored));
    match(scored).put("reason", "THREE_DEALS_COMPLETE");
    assertThrows(IllegalArgumentException.class, () -> envelope(scored));
    match(scored).put("reason", "MANUAL_EXIT");
    assertThrows(IllegalArgumentException.class, () -> envelope(scored));
  }

  @Test void quarterMaintenanceKeepsV3PartialScoresAndTimingForBothCategories() {
    var storage = new ManualQuarterMaintenanceTest.Storage();
    for (int row = 1; row < storage.f.tables.get("envelopes").size(); row++) {
      var original = ManualQuarterMaintenance.decoded(storage.f.tables).get(storage.f.tables.get("commits").get(row).get(0));
      MatchCategoryEnvelopeTest.replace(storage.f.tables, row,
          envelope(record(original, row % 2 == 0 ? "FORMAL" : "CASUAL", row % 2 == 0 ? "FORFEIT" : "NO_CONTEST")));
    }
    var expected = new TreeMap<>(ManualQuarterMaintenance.decoded(storage.f.tables)); expected.remove(storage.f.expired.commitId());
    var service = storage.service(); var preview = ManualQuarterMaintenanceTest.preview(storage, service);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, ManualQuarterMaintenanceTest.execute(service, preview).outcome());
    assertEquals(expected, ManualQuarterMaintenance.decoded(storage.f.tables));
  }

  @Test void accountErasurePreservesV3OutcomesRoomTypesPartialScoresAndTiming() throws Exception {
    var fixture = new OperatorAccountErasureTest.Fixture(); var originals = new ArrayList<FinalMatchEnvelope>();
    for (int row = 0; row < fixture.originals.size(); row++) {
      var saved = envelope(record(fixture.originals.get(row), row % 2 == 0 ? "FORMAL" : "CASUAL", row % 2 == 0 ? "FORFEIT" : "NO_CONTEST"));
      originals.add(saved); MatchCategoryEnvelopeTest.replace(fixture.db.tabs, row + 1, saved);
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
