package com.bridge.persistence;
import java.util.List;
public interface StatsStore {
    enum SaveStatus { UNKNOWN, DISABLED, PENDING, CONFIRMED, CONFLICT, FAILED, REJECTED }
    SaveStatus submit(FinalMatchEnvelope envelope);
    SaveStatus status(String commitId);
    List<FinalMatchEnvelope> exportCommitted();
    /** One validated account-scoped statistics read; carry contains no raw identities or match detail. */
    record StatisticsData(List<FinalMatchEnvelope> current, LifetimeStatistics.Carry lifetime, java.time.Instant asOf) {
        public StatisticsData { current = List.copyOf(current); }
        public StatisticsData(List<FinalMatchEnvelope> current, LifetimeStatistics.Carry lifetime) { this(current, lifetime, null); }
    }
    /** Internal-only account projections. Emails, Google identities and raw profile rows are excluded. */
    record LeaderboardAccount(String accountId, String nickname, LifetimeStatistics.Carry lifetime) {}
    record LeaderboardData(List<FinalMatchEnvelope> current, List<LeaderboardAccount> accounts, java.time.Instant asOf) {
        public LeaderboardData { current = List.copyOf(current); accounts = List.copyOf(accounts); }
    }
    default LeaderboardData exportLeaderboard(String requestingAccount) {
        throw new UnsupportedOperationException("Leaderboard storage unavailable");
    }
    default StatisticsData exportStatistics(String accountId) {
        return new StatisticsData(exportCommitted(), null);
    }
}
