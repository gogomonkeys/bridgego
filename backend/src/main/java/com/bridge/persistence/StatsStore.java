package com.bridge.persistence;
import java.util.List;
public interface StatsStore {
    enum SaveStatus { UNKNOWN, DISABLED, PENDING, CONFIRMED, CONFLICT, FAILED, REJECTED }
    SaveStatus submit(FinalMatchEnvelope envelope);
    SaveStatus status(String commitId);
    List<FinalMatchEnvelope> exportCommitted();
    /** One validated account-scoped statistics read; carry contains no raw identities or match detail. */
    record StatisticsData(List<FinalMatchEnvelope> current, LifetimeStatistics.Carry lifetime, java.time.Instant asOf, com.bridge.persistence.HistoricalOverallStatistics historicalOverall) {
        public StatisticsData { current = List.copyOf(current); java.util.Objects.requireNonNull(historicalOverall); }
        public StatisticsData(List<FinalMatchEnvelope> current, LifetimeStatistics.Carry lifetime, java.time.Instant asOf) { this(current,lifetime,asOf,com.bridge.persistence.HistoricalOverallStatistics.notRead()); }
        public StatisticsData(List<FinalMatchEnvelope> current, LifetimeStatistics.Carry lifetime) { this(current, lifetime, null); }
    }
    default StatisticsData exportStatistics(String accountId) {
        return new StatisticsData(exportCommitted(), null);
    }
}
