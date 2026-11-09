package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SettlementStatisticsTest {
  static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
  static SettlementStatistics.Facts facts(String id, Instant finished, String version,
      SettlementEligibility.Result eligibility, String outcome, String winner, int ns, int ew) {
    return new SettlementStatistics.Facts(id, finished, version, SettlementEligibility.RULE_VERSION,
        eligibility, "NO_CONTEST".equals(outcome) ? 0 : 1, outcome, winner, ns, ew);
  }
  static SettlementStatistics.Facts scored(String winner, int ns, int ew) {
    return facts(UUID.randomUUID().toString(), NOW.minusSeconds(60), MatchPersistence.SCORE_VERSION,
        SettlementEligibility.Result.ELIGIBLE, "SCORED", winner, ns, ew);
  }
  static SettlementStatistics.Summary summary(Collection<SettlementStatistics.Facts> input) {
    return SettlementStatistics.summarizeCurrentQuarter(input, NOW, MatchPersistence.SCORE_VERSION);
  }

  @Test void countsIdenticalMatchOnceAndSeparatesDrawForfeitNoContest() {
    var ns = scored("NS", 5, -1); var ew = scored("EW", -1, 2); var draw = scored(null, 2, 2);
    var forfeit = facts(UUID.randomUUID().toString(), NOW, MatchPersistence.SCORE_VERSION,
        SettlementEligibility.Result.ELIGIBLE, "FORFEIT", "NS", 0, 3);
    var none = facts(UUID.randomUUID().toString(), NOW, MatchPersistence.SCORE_VERSION,
        SettlementEligibility.Result.ELIGIBLE, "NO_CONTEST", null, 0, 0);
    var result = summary(List.of(ns, ew, draw, ns, forfeit, none));
    assertEquals(5, result.matches()); assertEquals(4, result.completedDeals());
    assertEquals(3, result.scoredMatches()); assertEquals(1, result.nsWins());
    assertEquals(1, result.ewWins()); assertEquals(1, result.draws());
    assertEquals(1, result.forfeits()); assertEquals(1, result.noContests());
  }

  @Test void conflictingMatchSnapshotsFailRatherThanDoubleCount() {
    var original = scored("NS", 5, 0);
    var changed = facts(original.matchId(), original.finishedAt(), original.scoreVersion(),
        original.eligibility(), "SCORED", "EW", 0, 5);
    assertThrows(IllegalArgumentException.class, () -> summary(List.of(original, changed)));
  }

  @Test void excludesEveryIneligibleRosterAndOtherScoringVersions() {
    List<SettlementStatistics.Facts> all = new ArrayList<>();
    for (var reason : SettlementEligibility.Result.values())
      all.add(facts(UUID.randomUUID().toString(), NOW, MatchPersistence.SCORE_VERSION, reason, "SCORED", "NS", 1, 0));
    all.add(facts(UUID.randomUUID().toString(), NOW, "older-score-rules", SettlementEligibility.Result.ELIGIBLE,
        "SCORED", "NS", 1, 0));
    assertEquals(1, summary(all).matches());
  }

  @Test void usesTaipeiFinishQuarterAtAllFourBoundaries() {
    for (String boundary : List.of("2026-12-31T16:00:00Z", "2026-03-31T16:00:00Z",
        "2026-06-30T16:00:00Z", "2026-09-30T16:00:00Z")) {
      var start = Instant.parse(boundary); var old = scored("NS", 1, 0);
      var before = facts(old.matchId(), start.minusMillis(1), old.scoreVersion(), old.eligibility(), "SCORED", "NS", 1, 0);
      var on = facts(UUID.randomUUID().toString(), start, old.scoreVersion(), old.eligibility(), "SCORED", "EW", 0, 1);
      var result = SettlementStatistics.summarizeCurrentQuarter(List.of(before, on), start, old.scoreVersion());
      assertEquals(1, result.matches()); assertEquals(1, result.ewWins()); assertEquals(0, result.nsWins());
    }
  }

  @Test void futureRecordsAndMissingRuleFailClosed() {
    var future = facts(UUID.randomUUID().toString(), NOW.plusMillis(1), MatchPersistence.SCORE_VERSION,
        SettlementEligibility.Result.ELIGIBLE, "SCORED", "NS", 1, 0);
    assertThrows(IllegalArgumentException.class, () -> summary(List.of(future)));
    assertThrows(IllegalArgumentException.class, () -> new SettlementStatistics.Facts(UUID.randomUUID().toString(),
        NOW, MatchPersistence.SCORE_VERSION, null, SettlementEligibility.Result.ELIGIBLE, 1, "SCORED", "NS", 1, 0));
    assertThrows(IllegalArgumentException.class, () -> summary(List.of(scored("EW", 5, 0))));
  }

  @Test void summaryContainsNoMatchIdentityOrExactTimeAndZeroHasNoFabricatedRates() throws Exception {
    var one = scored("NS", 3, 0); String json = new ObjectMapper().writeValueAsString(summary(List.of(one)));
    assertFalse(json.contains(one.matchId())); assertFalse(json.contains(one.finishedAt().toString()));
    assertFalse(json.contains("account")); assertFalse(json.contains("guest")); assertFalse(json.contains("player"));
    var empty = summary(List.of()); assertEquals(0, empty.matches()); assertEquals(0, empty.scoredMatches());
    assertEquals("2026-Q4", empty.quarter());
  }
}
