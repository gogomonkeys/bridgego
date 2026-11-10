package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.FinalMatchEnvelope;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FixedRoomStatisticsTest {
  static final String ACCOUNT = MatchCategoryProjectionTest.ACCOUNT;
  static final Instant NOW = MatchCategoryProjectionTest.NOW;
  static FinalMatchEnvelope record(String type, String outcome, int completed, int ns, int ew, String winner) {
    var old = MatchCategoryProjectionTest.record(0, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, ns, ew);
    return MatchCategoryProjectionTest.changed(old, root -> {
      var match = (ObjectNode)root.path("match");
      match.put("statistics_rule", FinalMatchEnvelope.STATISTICS_RULE).put("room_type", type).put("match_category", type)
          .put("deal_timing_rule", FinalMatchEnvelope.DEAL_TIMING_RULE).put("planned_deals", 3)
          .put("completed_deals", completed).put("outcome", outcome).put("reason", outcome.equals("SCORED") ? "THREE_DEALS_COMPLETE" : outcome.equals("FORFEIT") ? "MANUAL_EXIT"
              : type.equals("FORMAL") ? "BOTH_TEAMS_FAILED" : "NO_HUMANS_REMAIN");
      if (winner == null) match.putNull("winner_team_id"); else match.put("winner_team_id", winner);
      var deals = root.putArray("completed_deals");
      for (int index = 1; index <= completed; index++) deals.addObject()
          .put("match_id", old.matchId()).put("deal_id", UUID.randomUUID().toString()).put("deal_index", index)
          .put("elapsed_ms", 1000L * index).put("ns_total", index == 1 ? ns : 0).put("ew_total", index == 1 ? ew : 0);
    });
  }
  static StatisticsService.Report project(FinalMatchEnvelope... records) {
    return StatisticsService.project(List.of(records), ACCOUNT, NOW);
  }

  @Test void v1V2AndFixedV3RetainTheirMeaningWithoutBackfillingUnknownHistory() {
    var v1 = MatchCategoryProjectionTest.record(0, FinalMatchEnvelope.LEGACY_STATISTICS_RULE, true, 1, 0);
    var v2f = MatchCategoryProjectionTest.record(0, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, 1, 0);
    var v2c = MatchCategoryProjectionTest.record(1, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, 1, 0);
    var unknown = MatchCategoryProjectionTest.record(0, null, true, 50, 0);
    var formal = record("FORMAL", "SCORED", 3, 1, 0, "NS");
    var casualHuman = record("CASUAL", "SCORED", 3, 1, 0, "NS");
    var result = project(v1, v2f, v2c, unknown, formal, casualHuman, formal);
    assertEquals(3, result.personal().matches()); assertEquals(2, result.casualPersonal().matches());
    assertEquals(3, result.personal().scoredMatches()); assertEquals(2, result.casualPersonal().scoredMatches());
    assertEquals(5, result.personal().completedDeals()); assertEquals(4, result.casualPersonal().completedDeals());
    assertEquals("CASUAL", casualHuman.statisticsCategory());
  }

  @Test void forfeitUsesDeclaredWinnerNotScoreAndNoContestIsNotDraw() {
    for (String type : List.of("FORMAL")) {
      var scored = record(type, "SCORED", 3, 2, 2, null);
      var forfeit = record(type, "FORFEIT", 1, 20, -5, "EW");
      var interrupted = record(type, "NO_CONTEST", 2, 7, 1, null);
      var empty = record(type, "NO_CONTEST", 0, 0, 0, null);
      var result = project(scored, forfeit, interrupted, empty, interrupted);
      var personal = type.equals("FORMAL") ? result.personal() : result.casualPersonal();
      var overall = type.equals("FORMAL") ? result.overall() : result.casualOverall();
      assertEquals(4, personal.matches()); assertEquals(6, personal.completedDeals()); assertEquals(29, personal.totalScore());
      assertEquals(0, personal.wins()); assertEquals(1, personal.losses()); assertEquals(1, personal.draws());
      assertEquals(1, personal.scoredMatches()); assertEquals(1, personal.forfeitMatches()); assertEquals(2, personal.interruptedMatches());
      assertEquals(4, overall.matches()); assertEquals(1, overall.draws());
      assertEquals(1, overall.scoredMatches()); assertEquals(1, overall.forfeitMatches()); assertEquals(2, overall.interruptedMatches());
    }
  }

  @Test void originalAccountErasureRemovesOnlyPersonalContributionForEveryTerminalOutcome() {
    for (String outcome : List.of("SCORED", "FORFEIT", "NO_CONTEST")) {
      var original = record("FORMAL", outcome, outcome.equals("SCORED") ? 3 : 1, 4, 1, outcome.equals("NO_CONTEST") ? null : "NS");
      var erased = MatchCategoryProjectionTest.changed(original, root -> ((ObjectNode)root.path("participants").get(0))
          .putNull("account_id").put("erased_marker", UUID.randomUUID().toString()));
      var result = project(erased);
      assertEquals(0, result.personal().matches()); assertEquals(1, result.overall().matches());
    }
  }

  static PlayFlowTest flow(String type, int planned, MatchPersistenceTest.RecordingStore store) {
    var f = new PlayFlowTest(); f.clock = new RoomServiceTest.MutableClock(); f.clock.now.set(NOW.toEpochMilli());
    f.service = new RoomService(f.clock); f.service.statsStore(store);
    f.service.experimentPolicy(ManagedCollectionTest.policy()); f.service.statisticsPolicy(true, true);
    f.players = new RoomService.Identity[4];
    for (int seat = 0; seat < 4; seat++) {
      f.players[seat] = f.service.createSession("P" + seat);
      f.service.statisticsConsent(f.players[seat], true, StatisticsService.CONSENT_VERSION);
      f.service.linkAccountOutsideRoom(f.players[seat], UUID.randomUUID().toString(), true);
    }
    f.room = f.service.create(f.players[0], planned, type).roomId();
    for (int seat = 0; seat < 4; seat++) {
      if (seat > 0) f.service.join(f.room, f.players[seat]);
      f.service.connect(f.room, f.players[seat], "socket-" + seat); f.service.ready(f.room, f.players[seat], true);
    }
    f.start(); f.auctionToPlay(); return f;
  }

  @Test void formalPartialForfeitAndOppositeDepartureSaveOnlyFinishedDealsAndTheirTiming() {
    for (boolean both : List.of(false, true)) {
      var store = new MatchPersistenceTest.RecordingStore(); var f = flow("FORMAL", 5, store);
      f.playRemainingDeal(); var completed = f.view(0).game().completedDealResults().get(0);
      f.advanceResult(); f.auctionToPlay(); // Interrupted second deal has no persisted row.
      f.service.leave(f.room, f.players[0]);
      if (both) f.service.leave(f.room, f.players[1]);
      f.clock.now.addAndGet(1000); f.service.expireInitialSubscriptions();
      assertEquals(1, store.writes.size()); var saved = store.writes.get(0); var match = saved.payload().path("match");
      assertEquals("FORMAL", saved.statisticsCategory()); assertEquals("FORMAL", match.path("room_type").asText());
      assertEquals(both ? "NO_CONTEST" : "FORFEIT", match.path("outcome").asText());
      assertEquals(1, saved.payload().path("completed_deals").size());
      assertEquals(completed.elapsedMs(), saved.payload().path("completed_deals").get(0).path("elapsed_ms").longValue());
      assertEquals(completed.scores().get("NS").total(), match.path("ns_total").asInt());
      assertEquals(completed.scores().get("EW").total(), match.path("ew_total").asInt());
      var result = StatisticsService.project(store.writes, f.service.accountId(f.players[0]), f.clock.instant());
      assertEquals(1, result.personal().matches()); assertEquals(1, result.personal().completedDeals());
      assertEquals(both ? 0 : 1, result.personal().losses()); assertEquals(0, result.personal().draws());
      assertEquals(both ? 1 : 0, result.personal().interruptedMatches());
      f.clock.now.addAndGet(10000); f.view(2); assertEquals(List.of(saved), store.writes);
    }
  }

  @Test void casualAllHumansNeverBecomesFormalAndAllDeparturesPreserveCompletedTiming() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = flow("CASUAL", 3, store);
    f.playRemainingDeal(); var result = f.view(0).game().completedDealResults().get(0);
    f.advanceResult(); f.auctionToPlay();
    for (var player : f.players) f.service.leave(f.room, player);
    assertEquals(1, store.writes.size()); var saved = store.writes.get(0);
    assertEquals("CASUAL", saved.statisticsCategory());
    assertEquals("NO_CONTEST", saved.payload().path("match").path("outcome").asText());
    assertEquals(1, saved.payload().path("completed_deals").size());
    assertEquals(result.elapsedMs(), saved.payload().path("completed_deals").get(0).path("elapsed_ms").longValue());
    assertTrue(saved.payload().path("match").path("winner_team_id").isNull());
    for (var person : saved.payload().path("participants")) assertEquals("BOT", person.path("settlement_control").asText());
  }

  @Test void zeroCompletedFormalLossAndInterruptionStillHaveZeroScoresAndNoFakeDeals() {
    for (boolean both : List.of(false, true)) {
      var store = new MatchPersistenceTest.RecordingStore(); var f = flow("FORMAL", 1, store);
      f.service.leave(f.room, f.players[0]); if (both) f.service.leave(f.room, f.players[1]);
      f.clock.now.addAndGet(1000); f.service.expireInitialSubscriptions();
      assertEquals(1, store.writes.size()); var saved = store.writes.get(0);
      assertEquals(0, saved.payload().path("completed_deals").size());
      assertEquals(0, saved.payload().path("match").path("ns_total").asInt());
      assertEquals(0, saved.payload().path("match").path("ew_total").asInt());
      var report = StatisticsService.project(store.writes, f.service.accountId(f.players[0]), f.clock.instant());
      assertEquals(1, report.personal().matches()); assertEquals(0, report.personal().completedDeals());
      assertEquals(both ? 0 : 1, report.personal().losses()); assertEquals(0, report.personal().draws());
    }
  }
}
