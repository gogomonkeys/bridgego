package com.bridge.room;

import com.bridge.persistence.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Pure projection of the existing statistics rules. It never writes or changes retention. */
public final class Leaderboard {
  public enum Period { SEASON, LIFETIME }
  public enum Category { FORMAL }
  public record Entry(int rank, String nickname, long totalScore, long matches,
      long wins, long losses, long draws, Double winRate, boolean mine) {}
  public record Report(String status, String quarter, String asOf, String quarterEndsAt,
      Period period, Category category, List<Entry> entries, String unavailableReason, Integer retryAfterSeconds) {}
  private record Candidate(String account, String nickname, StatisticsService.Personal counts) {}
  private Leaderboard() {}

  static Report unavailable(String status, Instant now, Period period, Category category, String reason, Integer retry) {
    return new Report(status, QuarterlyRetention.quarter(now), now.toString(), StatisticsService.quarterEndsAt(now),
        period, category, List.of(), reason, retry);
  }
  public static Report project(StatsStore.LeaderboardData data, String requester, Instant now, Period period, Category category) {
    Objects.requireNonNull(data); Objects.requireNonNull(requester); Objects.requireNonNull(now);
    Objects.requireNonNull(period); Objects.requireNonNull(category);
    if (data.asOf() != null && (data.asOf().isAfter(now)
        || !QuarterlyRetention.currentQuarterStart(data.asOf()).equals(QuarterlyRetention.currentQuarterStart(now))))
      throw new IllegalArgumentException("Leaderboard boundary changed");
    Set<String> ids = new HashSet<>(); var rows = new ArrayList<Candidate>();
    for (var person : data.accounts()) {
      if (!ids.add(person.accountId()) || !AccountProfileRows.validNickname(person.nickname()))
        throw new IllegalArgumentException("Invalid leaderboard profile");
      var report = StatisticsService.project(data.current(), person.lifetime(), person.accountId(), now);
      // Ranking deliberately excludes casual matches, even when they are retained in private statistics.
      var counts = period == Period.SEASON ? report.personal() : report.lifetimePersonal();
      if (counts.matches() > 0) rows.add(new Candidate(person.accountId(), person.nickname(), counts));
    }
    rows.sort((a, b) -> { int rank = compare(a, b); return rank != 0 ? rank : a.account().compareTo(b.account()); });
    var entries = new ArrayList<Entry>(); Candidate previous = null; int rank = 0;
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i); if (previous == null || compare(previous, row) != 0) rank = i + 1;
      var c = row.counts(); long decided = decided(c);
      // Top 100 plus the requesting player's position. IDs are used only internally.
      if (i < 100 || row.account().equals(requester))
        entries.add(new Entry(rank, row.nickname(), c.totalScore(), c.matches(), c.wins(), c.losses(), c.draws(),
            decided == 0 ? null : (double)c.wins() / decided, row.account().equals(requester)));
      previous = row;
    }
    return new Report("AVAILABLE", QuarterlyRetention.quarter(now), now.toString(), StatisticsService.quarterEndsAt(now),
        period, category, List.copyOf(entries), null, null);
  }
  private static long decided(StatisticsService.Personal counts) {
    return Math.addExact(Math.addExact(counts.wins(), counts.losses()), counts.draws());
  }
  private static int compare(Candidate a, Candidate b) {
    int score = Long.compare(b.counts().totalScore(), a.counts().totalScore());
    if (score != 0) return score;
    long ad = decided(a.counts()), bd = decided(b.counts());
    // No decided matches has no win rate and sorts after a defined rate.
    if (ad == 0 || bd == 0) return ad == bd ? 0 : ad == 0 ? 1 : -1;
    // Exact fractions, not rounded displayed percentages; big integers avoid overflow.
    return BigInteger.valueOf(b.counts().wins()).multiply(BigInteger.valueOf(ad))
        .compareTo(BigInteger.valueOf(a.counts().wins()).multiply(BigInteger.valueOf(bd)));
  }
}
