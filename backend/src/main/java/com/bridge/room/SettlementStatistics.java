package com.bridge.room;

import com.bridge.persistence.QuarterlyRetention;
import java.time.Instant;
import java.util.*;

/**
 * Minimal in-memory facts and pure reducer, not a persistence/publication API.
 * No names, account/guest IDs, roster, cards, auctions or action history.
 * Legacy persisted HUMAN/MIXED flags cannot establish settlement eligibility.
 */
final class SettlementStatistics {
  record Facts(String matchId, Instant finishedAt, String scoreVersion,
      String eligibilityRule, SettlementEligibility.Result eligibility,
      int completedDeals, String outcome, String winnerTeamId, int nsTotal, int ewTotal) {
    Facts {
      if (matchId == null || !UUID.fromString(matchId).toString().equals(matchId)
          || finishedAt == null || scoreVersion == null || scoreVersion.isBlank()
          || !SettlementEligibility.RULE_VERSION.equals(eligibilityRule)
          || eligibility == null || completedDeals < 0 || completedDeals > 5
          || !Set.of("SCORED", "FORFEIT", "NO_CONTEST").contains(outcome)
          || winnerTeamId != null && !Set.of("NS", "EW").contains(winnerTeamId))
        throw new IllegalArgumentException("Invalid statistics facts");
      if ("SCORED".equals(outcome)) {
        String expectedWinner = nsTotal == ewTotal ? null : nsTotal > ewTotal ? "NS" : "EW";
        if (completedDeals == 0 || !Objects.equals(expectedWinner, winnerTeamId))
          throw new IllegalArgumentException("Invalid scored result");
      }
      if ("NO_CONTEST".equals(outcome) && winnerTeamId != null
          || "FORFEIT".equals(outcome) && winnerTeamId == null)
        throw new IllegalArgumentException("Invalid terminal result");
    }
  }

  /** Internal diagnostic counters only, not the committed statistics API. */
  record Summary(String quarter, String scoreVersion, long matches, long completedDeals,
      long scoredMatches, long nsWins, long ewWins, long draws, long forfeits, long noContests) {}

  private SettlementStatistics() {}

  static Facts capture(GameState.MatchArchive archive, Instant finishedAt,
      SettlementEligibility.Result eligibility) {
    var result = archive.matchResult();
    return new Facts(archive.gameId(), finishedAt, MatchPersistence.SCORE_VERSION,
        SettlementEligibility.RULE_VERSION, eligibility, archive.completedDealResults().size(),
        result.outcome(), result.winnerTeamId(), archive.teamTotals().get("NS"), archive.teamTotals().get("EW"));
  }

  /**
   * Takes a complete authorized input set, never fetches data itself. Restricts to
   * one scoring version and Taiwan finished-at quarter. Duplicate identical
   * matches count once; conflicting snapshots fail closed rather than pick one.
   */
  static Summary summarizeCurrentQuarter(Collection<Facts> input, Instant now, String scoreVersion) {
    return summarizeCurrentQuarter(input, now, scoreVersion, "FORMAL");
  }

  static Summary summarizeCurrentQuarter(Collection<Facts> input, Instant now, String scoreVersion, String category) {
    if (!Set.of("FORMAL", "CASUAL").contains(category)) throw new IllegalArgumentException("Category required");
    Objects.requireNonNull(input); Objects.requireNonNull(now);
    if (scoreVersion == null || scoreVersion.isBlank()) throw new IllegalArgumentException("Score version required");
    Map<String, Facts> unique = new HashMap<>();
    for (Facts facts : input) {
      Objects.requireNonNull(facts);
      Facts prior = unique.putIfAbsent(facts.matchId(), facts);
      if (prior != null && !prior.equals(facts)) throw new IllegalArgumentException("Conflicting match snapshots");
      if (facts.finishedAt().isAfter(now)) throw new IllegalArgumentException("Future settlement");
    }
    Instant start = QuarterlyRetention.currentQuarterStart(now);
    long matches = 0, deals = 0, scored = 0, nsWins = 0, ewWins = 0, draws = 0, forfeits = 0, noContests = 0;
    for (Facts facts : unique.values()) {
      if (!category.equals(SettlementEligibility.category(facts.eligibility()))
          || facts.finishedAt().isBefore(start) || !facts.scoreVersion().equals(scoreVersion)) continue;
      matches++; deals += facts.completedDeals();
      switch (facts.outcome()) {
        case "SCORED" -> {
          scored++;
          if (facts.winnerTeamId() == null) draws++;
          else if (facts.winnerTeamId().equals("NS")) nsWins++;
          else ewWins++;
        }
        case "FORFEIT" -> {
          forfeits++;
          if ("NS".equals(facts.winnerTeamId())) nsWins++;
          else ewWins++;
        }
        case "NO_CONTEST" -> noContests++;
        default -> throw new IllegalArgumentException("Unknown outcome");
      }
    }
    return new Summary(QuarterlyRetention.quarter(now), scoreVersion,
        matches, deals, scored, nsWins, ewWins, draws, forfeits, noContests);
  }
}
