package com.bridge.persistence;

/** One selected native source for identity, profile, matches and statistics; no legacy fallback.
 * Implementation must preserve current/lifetime, formal/casual, account erasure and timing semantics.
 */
public interface NativePersistenceBackend extends com.bridge.room.IdentityStore,StatsStore,AutoCloseable {
    @Override String findOrCreateAccount(String issuer,String subject,String nickname,String verifiedEmail);
    @Override StatisticsData exportStatistics(String accountId);
    @Override void close();
}
