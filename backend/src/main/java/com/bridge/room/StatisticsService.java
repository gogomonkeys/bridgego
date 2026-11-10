package com.bridge.room;

import com.bridge.persistence.FinalMatchEnvelope;
import com.bridge.persistence.LifetimeStatistics;
import com.bridge.persistence.QuarterlyRetention;
import com.bridge.persistence.StatsStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read-only, account-scoped current-quarter statistics. No raw records leave this service. */
@Service
public final class StatisticsService {
  public static final String CONSENT_VERSION = "statistics-v4";
  public record Personal(long matches, long completedDeals, long wins, long losses, long draws, long totalScore,
      long scoredMatches, long forfeitMatches, long interruptedMatches) {}
  public record Overall(long matches, long completedDeals, long draws,
      long scoredMatches, long forfeitMatches, long interruptedMatches) {}
  // Existing personal/overall fields stay formal-only for older readers.
  public record Report(String status, String quarter, String asOf, String ruleVersion,
      Personal personal, Overall overall, Personal casualPersonal, Overall casualOverall,
      String quarterEndsAt, Personal lifetimePersonal, Personal lifetimeCasualPersonal,
      String lifetimeCoverage, String lifetimeTrackedFromQuarter) {}

  private static final class Counters {
    long matches, deals, draws, mine, myDeals, wins, losses, myDraws, score;
    long scored, forfeits, interrupted, myScored, myForfeits, myInterrupted;
    Personal personal() { return new Personal(mine, myDeals, wins, losses, myDraws, score, myScored, myForfeits, myInterrupted); }
    Overall overall() { return new Overall(matches, deals, draws, scored, forfeits, interrupted); }
  }
  private final RoomService rooms;
  private final StatsStore store;
  private final Clock clock;
  private final Semaphore reader = new Semaphore(1);

  @Autowired public StatisticsService(RoomService rooms, StatsStore store) { this(rooms, store, Clock.systemUTC()); }
  StatisticsService(RoomService rooms, StatsStore store, Clock clock) {
    this.rooms = rooms; this.store = store; this.clock = clock;
  }

  public Report read(RoomService.Identity who) {
    String account = rooms.statisticsAccount(who); // Guests cannot learn even overall totals.
    if (!rooms.statisticsEnabled()) return unavailable("DISABLED", clock.instant());
    if (!reader.tryAcquire()) return unavailable("UNAVAILABLE", clock.instant());
    Report report;
    try {
      // External I/O is outside the room/gameplay lock. Committed export enforces
      // erasure and current-quarter COMPLETE watermarks. No stale cache survives deletion.
      StatsStore.StatisticsData committed = store.exportStatistics(account);
      Instant now = clock.instant();
      if (committed.asOf() != null && (committed.asOf().isAfter(now)
          || !QuarterlyRetention.currentQuarterStart(committed.asOf()).equals(QuarterlyRetention.currentQuarterStart(now))))
        throw new IllegalArgumentException("Statistics boundary changed during read");
      report = project(committed.current(), committed.lifetime(), account, now);
    } catch (RuntimeException failure) {
      report = unavailable("UNAVAILABLE", clock.instant());
    } finally {
      reader.release();
    }
    // A logout/account switch during I/O must never receive the prior account's data.
    rooms.requireStatisticsAccountUnchanged(who, account);
    return rooms.statisticsEnabled() ? report : unavailable("DISABLED", clock.instant());
  }

  private static Report unavailable(String status, Instant now) {
    return new Report(status, QuarterlyRetention.quarter(now), now.toString(), SettlementEligibility.RULE_VERSION,
        null, null, null, null, quarterEndsAt(now), null, null, "RECORDED_SINCE_FEATURE", null);
  }

  static Report project(List<FinalMatchEnvelope> envelopes, String account, Instant now) {
    return project(envelopes, null, account, now);
  }

  static Report project(List<FinalMatchEnvelope> envelopes, LifetimeStatistics.Carry carry, String account, Instant now) {
    Objects.requireNonNull(envelopes); Objects.requireNonNull(account); Objects.requireNonNull(now);
    Map<String, FinalMatchEnvelope> unique = new HashMap<>();
    for (var envelope : envelopes) {
      var previous = unique.putIfAbsent(envelope.matchId(), envelope);
      if (previous != null && !previous.equals(envelope)) throw new IllegalArgumentException("Conflicting statistics matches");
    }
    Instant boundary = QuarterlyRetention.currentQuarterStart(now);
    var formal = new Counters(); var casual = new Counters();
    for (var envelope : unique.values()) {
      var payload = envelope.payload(); var match = payload.path("match");
      // Never infer historical eligibility from HUMAN, MIXED or app version.
      String category = envelope.statisticsCategory();
      if (category == null || !MatchPersistence.SCORE_VERSION.equals(match.path("score_version").asText())) continue;
      var counts = "FORMAL".equals(category) ? formal : casual;
      Instant finish = Instant.parse(match.path("finished_at").asText());
      Instant start = Instant.parse(match.path("started_at").asText());
      if (start.isAfter(finish)) throw new IllegalArgumentException("Reversed statistics timestamps");
      if (finish.isAfter(now)) throw new IllegalArgumentException("Future statistics settlement");
      if (finish.isBefore(boundary)) continue;
      String outcome = match.path("outcome").asText();
      boolean fixed = FinalMatchEnvelope.STATISTICS_RULE.equals(match.path("statistics_rule").asText());
      if (!(fixed ? Set.of("SCORED", "FORFEIT", "NO_CONTEST").contains(outcome) : "SCORED".equals(outcome)))
        throw new IllegalArgumentException("Invalid statistics outcome");
      int completed = integer(match, "completed_deals"), planned = integer(match, "planned_deals");
      if (!Set.of(1, 3, 5).contains(planned) || completed < 0 || completed > planned
          || "SCORED".equals(outcome) && completed != planned)
        throw new IllegalArgumentException("Incomplete statistics match");
      var facts = new SettlementStatistics.Facts(envelope.matchId(), finish, MatchPersistence.SCORE_VERSION,
          SettlementEligibility.RULE_VERSION, "FORMAL".equals(category)
              ? SettlementEligibility.Result.ELIGIBLE : SettlementEligibility.Result.COMPUTER_AT_SETTLEMENT,
          completed, outcome,
          match.path("winner_team_id").isNull() ? null : match.path("winner_team_id").asText(),
          integer(match, "ns_total"), integer(match, "ew_total"));
      counts.matches++; counts.deals = Math.addExact(counts.deals, facts.completedDeals());
      if ("SCORED".equals(outcome)) { counts.scored++; if (facts.winnerTeamId() == null) counts.draws++; }
      else if ("FORFEIT".equals(outcome)) counts.forfeits++;
      else counts.interrupted++;
      String team = null;
      for (var person : payload.path("participants")) {
        if (account.equals(person.path("account_id").asText())) {
          if (team != null) throw new IllegalArgumentException("Duplicate personal account");
          team = person.path("team_id").asText();
        }
      }
      if (team == null) continue;
      counts.mine++; counts.myDeals = Math.addExact(counts.myDeals, facts.completedDeals());
      if ("SCORED".equals(outcome)) counts.myScored++;
      else if ("FORFEIT".equals(outcome)) counts.myForfeits++;
      else counts.myInterrupted++;
      if ("SCORED".equals(outcome) && facts.winnerTeamId() == null) counts.myDraws++;
      else if (facts.winnerTeamId() != null) {
        if (team.equals(facts.winnerTeamId())) counts.wins++;
        else counts.losses++;
      }
      counts.score = Math.addExact(counts.score, team.equals("NS") ? facts.nsTotal() : facts.ewTotal());
    }
    if (carry != null && (carry.through() == null ? !carry.equals(LifetimeStatistics.Carry.empty()) : carry.through().isAfter(boundary)))
      throw new IllegalArgumentException("Invalid lifetime cutoff");
    var currentCareer = LifetimeStatistics.fromEnvelopes(account, unique.values().stream()
        .filter(e -> !QuarterlyRetention.finishedAt(e).isBefore(boundary)).toList());
    var savedFormal = carry == null ? LifetimeStatistics.Counts.zero() : carry.formal();
    var savedCasual = carry == null ? LifetimeStatistics.Counts.zero() : carry.casual();
    var liveFormal = currentCareer == null ? LifetimeStatistics.Counts.zero() : currentCareer.formal();
    var liveCasual = currentCareer == null ? LifetimeStatistics.Counts.zero() : currentCareer.casual();
    String trackedFrom = earliestQuarter(carry == null ? null : carry.trackedFromQuarter(),
        currentCareer == null ? null : currentCareer.trackedFromQuarter());
    return new Report("AVAILABLE", QuarterlyRetention.quarter(now), now.toString(), SettlementEligibility.RULE_VERSION,
        formal.personal(), formal.overall(), casual.personal(), casual.overall(), quarterEndsAt(now),
        personal(savedFormal.plus(liveFormal)), personal(savedCasual.plus(liveCasual)),
        "RECORDED_SINCE_FEATURE", trackedFrom);
  }

  private static String quarterEndsAt(Instant now) {
    return QuarterlyRetention.currentQuarterStart(now).atZone(QuarterlyRetention.TAIPEI).plusMonths(3).toInstant().toString();
  }
  private static String earliestQuarter(String left, String right) {
    return left == null ? right : right == null || left.compareTo(right) <= 0 ? left : right;
  }
  private static Personal personal(LifetimeStatistics.Counts c) {
    return new Personal(c.matches(), c.completedDeals(), c.wins(), c.losses(), c.draws(), c.totalScore(),
        c.scoredMatches(), c.forfeitMatches(), c.interruptedMatches());
  }

  private static int integer(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Invalid statistics number");
    return value.intValue();
  }
}
