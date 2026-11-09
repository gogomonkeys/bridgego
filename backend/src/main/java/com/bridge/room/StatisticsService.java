package com.bridge.room;

import com.bridge.persistence.FinalMatchEnvelope;
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
  public static final String CONSENT_VERSION = "statistics-v1";
  public record Personal(long matches, long completedDeals, long wins, long losses, long draws, long totalScore) {}
  public record Overall(long matches, long completedDeals, long draws) {}
  public record Report(String status, String quarter, String asOf, String ruleVersion, Personal personal, Overall overall) {}
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
      List<FinalMatchEnvelope> committed = store.exportCommitted();
      report = project(committed, account, clock.instant());
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
    return new Report(status, QuarterlyRetention.quarter(now), now.toString(), SettlementEligibility.RULE_VERSION, null, null);
  }

  static Report project(List<FinalMatchEnvelope> envelopes, String account, Instant now) {
    Objects.requireNonNull(envelopes); Objects.requireNonNull(account); Objects.requireNonNull(now);
    Map<String, FinalMatchEnvelope> unique = new HashMap<>();
    for (var envelope : envelopes) {
      var previous = unique.putIfAbsent(envelope.matchId(), envelope);
      if (previous != null && !previous.equals(envelope)) throw new IllegalArgumentException("Conflicting statistics matches");
    }
    Instant boundary = QuarterlyRetention.currentQuarterStart(now);
    long matches = 0, deals = 0, draws = 0, mine = 0, myDeals = 0, wins = 0, losses = 0, myDraws = 0, score = 0;
    for (var envelope : unique.values()) {
      var payload = envelope.payload(); var match = payload.path("match");
      // Never infer historical eligibility from HUMAN, MIXED or app version.
      if (!SettlementEligibility.RULE_VERSION.equals(match.path("statistics_rule").asText())
          || !MatchPersistence.SCORE_VERSION.equals(match.path("score_version").asText())) continue;
      Instant finish = Instant.parse(match.path("finished_at").asText());
      Instant start = Instant.parse(match.path("started_at").asText());
      if (start.isAfter(finish)) throw new IllegalArgumentException("Reversed statistics timestamps");
      if (finish.isAfter(now)) throw new IllegalArgumentException("Future statistics settlement");
      if (finish.isBefore(boundary)) continue;
      if (!"SCORED".equals(match.path("outcome").asText())) throw new IllegalArgumentException("Invalid statistics outcome");
      int completed = integer(match, "completed_deals"), planned = integer(match, "planned_deals");
      if (!Set.of(1, 3, 5).contains(planned) || completed != planned)
        throw new IllegalArgumentException("Incomplete statistics match");
      var facts = new SettlementStatistics.Facts(envelope.matchId(), finish, MatchPersistence.SCORE_VERSION,
          SettlementEligibility.RULE_VERSION, SettlementEligibility.Result.ELIGIBLE,
          completed, "SCORED",
          match.path("winner_team_id").isNull() ? null : match.path("winner_team_id").asText(),
          integer(match, "ns_total"), integer(match, "ew_total"));
      matches++; deals = Math.addExact(deals, facts.completedDeals());
      if (facts.winnerTeamId() == null) draws++;
      String team = null;
      for (var person : payload.path("participants")) {
        if (account.equals(person.path("account_id").asText())) {
          if (team != null) throw new IllegalArgumentException("Duplicate personal account");
          team = person.path("team_id").asText();
        }
      }
      if (team == null) continue;
      mine++; myDeals = Math.addExact(myDeals, facts.completedDeals());
      if (facts.winnerTeamId() == null) myDraws++;
      else if (team.equals(facts.winnerTeamId())) wins++;
      else losses++;
      score = Math.addExact(score, team.equals("NS") ? facts.nsTotal() : facts.ewTotal());
    }
    return new Report("AVAILABLE", QuarterlyRetention.quarter(now), now.toString(), SettlementEligibility.RULE_VERSION,
        new Personal(mine, myDeals, wins, losses, myDraws, score), new Overall(matches, deals, draws));
  }

  private static int integer(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Invalid statistics number");
    return value.intValue();
  }
}
