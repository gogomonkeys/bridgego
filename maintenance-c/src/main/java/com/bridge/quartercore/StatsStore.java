package com.bridge.quartercore;
import java.util.List;
public interface StatsStore {
    enum SaveStatus { QUEUED, ACTIVE, UNKNOWN, DISABLED, PENDING, CONFIRMED, CONFLICT, FAILED, REJECTED }
    /** Opt-in contract: PENDING/CONFIRMED from submit or status acknowledges durable
     * acceptance of the exact envelope. QUEUED/ACTIVE are memory-only, not acceptance. UNKNOWN and all other statuses do not.
     * This does not promise recovery of a game before acceptance. */
    default boolean requiresDurableAcceptance() { return false; }
    SaveStatus submit(FinalMatchEnvelope envelope);
    SaveStatus status(String commitId);
    /** Exact-envelope reconciliation for hosts that require durable acceptance. */
    default SaveStatus status(FinalMatchEnvelope envelope) { return status(envelope.commitId()); }
    List<FinalMatchEnvelope> exportCommitted();
    /** One validated account-scoped statistics read; carry contains no raw identities or match detail. */
    record StatisticsData(List<FinalMatchEnvelope> current, LifetimeStatistics.Carry lifetime, java.time.Instant asOf) {
        public StatisticsData { current = List.copyOf(current); }
        public StatisticsData(List<FinalMatchEnvelope> current, LifetimeStatistics.Carry lifetime) { this(current, lifetime, null); }
    }
    default StatisticsData exportStatistics(String accountId) {
        return new StatisticsData(exportCommitted(), null);
    }
}
