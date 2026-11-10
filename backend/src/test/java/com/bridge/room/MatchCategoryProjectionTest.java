package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.FinalMatchEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Pure synthetic records. No adapters, accounts, credentials or external services. */
class MatchCategoryProjectionTest {
  static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  static final String ACCOUNT = UUID.randomUUID().toString();
  static final ObjectMapper JSON = new ObjectMapper();

  static FinalMatchEnvelope record(int bots, String rule, boolean linked, int ns, int ew) {
    String id = UUID.randomUUID().toString();
    var match = MatchPersistence.row("match_id", id, "revision", 1, "schema_version", 1,
        "score_version", MatchPersistence.SCORE_VERSION, "app_version", "synthetic",
        "started_at", NOW.minusSeconds(60).toString(), "finished_at", NOW.toString(),
        "planned_deals", 1, "completed_deals", 1, "outcome", "SCORED",
        "winner_team_id", ns == ew ? null : ns > ew ? "NS" : "EW", "ns_total", ns, "ew_total", ew);
    boolean current = FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE.equals(rule);
    if (rule != null) match.put("statistics_rule", rule);
    if (current) match.put("match_category", bots == 0 ? "FORMAL" : "CASUAL");
    List<Map<String,Object>> people = new ArrayList<>();
    for (int seat = 0; seat < 4; seat++) {
      boolean bot = seat > 0 && seat >= 4 - bots;
      var person = MatchPersistence.row("match_id", id, "seat", seat, "team_id", seat % 2 == 0 ? "NS" : "EW",
          "player_type", bot ? "BOT" : "HUMAN", "control_type", bot ? "BOT" : "HUMAN",
          "account_id", seat == 0 && linked ? ACCOUNT : null, "guest_id", null);
      if (current) person.put("settlement_control", bot ? "BOT" : "HUMAN");
      people.add(person);
    }
    return FinalMatchEnvelope.create(id, 1, MatchPersistence.row("match", match, "participants", people,
        "completed_deals", List.of(MatchPersistence.row("match_id", id, "deal_id", UUID.randomUUID().toString(), "deal_index", 1))));
  }
  static FinalMatchEnvelope changed(FinalMatchEnvelope before, java.util.function.Consumer<ObjectNode> mutation) {
    var root = (ObjectNode)before.payload(); mutation.accept(root);
    return FinalMatchEnvelope.create(before.matchId(), before.revision(), JSON.convertValue(root, Map.class));
  }
  static StatisticsService.Report project(FinalMatchEnvelope... records) {
    return StatisticsService.project(List.of(records), ACCOUNT, NOW);
  }

  @Test void rosterClassificationRequiresAtLeastOneLiveHumanAndKeepsIdentityGuards() {
    for (int bots = 1; bots <= 3; bots++) {
      var members = SettlementEligibilityTest.humans();
      for (int seat = 4 - bots; seat < 4; seat++) {
        members.get(seat).computer = true; members.get(seat).connections.clear();
      }
      assertEquals("CASUAL", SettlementEligibility.category(SettlementEligibility.evaluate(members, Map.of())));
      members.get(0).connections.clear();
      assertNull(SettlementEligibility.category(SettlementEligibility.evaluate(members, Map.of())));
    }
    var allBots = SettlementEligibilityTest.humans();
    allBots.forEach(member -> member.computer = true);
    assertEquals(SettlementEligibility.Result.NO_HUMAN_AT_SETTLEMENT, SettlementEligibility.evaluate(allBots, Map.of()));
    var duplicate = SettlementEligibilityTest.humans(); duplicate.get(3).computer = true;
    var accounts = Map.of(duplicate.get(0).identity.playerId(), ACCOUNT, duplicate.get(1).identity.playerId(), ACCOUNT);
    assertEquals(SettlementEligibility.Result.DUPLICATE_ACCOUNT, SettlementEligibility.evaluate(duplicate, accounts));
    assertNull(SettlementEligibility.category(SettlementEligibility.evaluate(duplicate, accounts)));
  }

  @Test void legacyMarkedFormalAndCurrentCategoriesCoexistWithoutInferringUnmarkedHistory() {
    var old = record(0, FinalMatchEnvelope.LEGACY_STATISTICS_RULE, true, 4, 0);
    var formal = record(0, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, 0, 2);
    var casual = record(1, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, 3, 3);
    var unknown = record(0, null, true, 99, 0);
    var oldBots = record(3, null, true, 99, 0);
    var result = project(old, formal, casual, unknown, oldBots, old, casual);
    assertEquals("FORMAL", old.statisticsCategory()); assertNull(unknown.statisticsCategory());
    assertEquals(2, result.personal().matches()); assertEquals(2, result.overall().matches());
    assertEquals(1, result.personal().wins()); assertEquals(1, result.personal().losses());
    assertEquals(4, result.personal().totalScore());
    assertEquals(1, result.casualPersonal().matches()); assertEquals(1, result.casualOverall().matches());
    assertEquals(1, result.casualPersonal().draws()); assertEquals(3, result.casualPersonal().totalScore());
  }

  @Test void eachOfOneTwoThreeBotsCountsOnlyCasualAndGuestsHaveNoPersonalCredit() {
    for (int bots = 1; bots <= 3; bots++) {
      var saved = record(bots, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, 5, -2);
      var result = project(saved, saved);
      assertEquals(0, result.personal().matches()); assertEquals(0, result.overall().matches());
      assertEquals(1, result.casualPersonal().matches()); assertEquals(1, result.casualOverall().matches());
      assertEquals(1, result.casualPersonal().wins()); assertEquals(5, result.casualPersonal().totalScore());
      var guest = record(bots, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, false, -2, 5);
      var visitorResult = project(guest);
      assertEquals(0, visitorResult.casualPersonal().matches()); assertEquals(1, visitorResult.casualOverall().matches());
    }
  }

  @Test void erasureRemovesPersonalOnlyInBothCategories() {
    for (int bots : List.of(0, 1, 3)) {
      var original = record(bots, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, 3, 1);
      var erased = changed(original, root -> ((ObjectNode)root.path("participants").get(0))
          .putNull("account_id").put("erased_marker", UUID.randomUUID().toString()));
      var result = project(erased);
      assertEquals(0, result.personal().matches()); assertEquals(0, result.casualPersonal().matches());
      assertEquals(bots == 0 ? 1 : 0, result.overall().matches());
      assertEquals(bots == 0 ? 0 : 1, result.casualOverall().matches());
    }
  }

  @Test void casualConflictingCommitFailsAndOtherQuarterOrScoreCannotCount() {
    var casual = record(2, FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE, true, 1, 0);
    var altered = changed(casual, root -> ((ObjectNode)root.path("match")).put("app_version", "changed"));
    assertThrows(IllegalArgumentException.class, () -> project(casual, altered));
    var other = changed(casual, root -> ((ObjectNode)root.path("match")).put("score_version", "other"));
    assertEquals(0, project(other).casualOverall().matches());
    var nextQuarter = Instant.parse("2026-12-31T16:00:00Z");
    assertEquals(0, StatisticsService.project(List.of(casual), ACCOUNT, nextQuarter).casualOverall().matches());
  }

  @Test void historicalMixedControlStillFormalOnlyWhenVerifiedV1MarkerExists() {
    var old = record(0, FinalMatchEnvelope.LEGACY_STATISTICS_RULE, true, 1, 0);
    var mixed = changed(old, root -> ((ObjectNode)root.path("participants").get(0)).put("control_type", "MIXED"));
    assertEquals(1, project(mixed).overall().matches()); assertEquals(0, project(mixed).casualOverall().matches());
    var unmarked = changed(mixed, root -> ((ObjectNode)root.path("match")).remove("statistics_rule"));
    assertEquals(0, project(unmarked).overall().matches());
  }
}
