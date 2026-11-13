package com.bridge.persistence;

import java.time.Instant;
import java.util.*;

/** Pure, identity-free inventory. No gateway, credentials, writes or deletion permission.
 * Counts unique matches, not duplicate physical rows. Eligibility is only a rule-marker
 * precheck: account existence, carry state and complete native-sheet verification remain required.
 */
public record RetentionCompatibilityReport(Instant cutoff, int expiredMatches, int retainedMatches,
    int lifetimeRuleEligibleExpired, int missingLifetimeRuleExpired, int unsupportedScoreExpired) {
  /** Conflicting revisions/snapshots fail closed, exactly as LifetimeStatistics does. */
  public static RetentionCompatibilityReport inspect(Collection<FinalMatchEnvelope> verifiedRecords, Instant cutoff) {
    if (verifiedRecords == null || cutoff == null
        || !QuarterlyRetention.currentQuarterStart(cutoff).equals(cutoff)) throw new SheetsGateway.Conflict();
    var unique = new LinkedHashMap<String,FinalMatchEnvelope>();
    for (var record : verifiedRecords) {
      if (record == null) throw new SheetsGateway.Conflict();
      var previous = unique.putIfAbsent(record.matchId(),record);
      if (previous != null && !previous.equals(record)) throw new SheetsGateway.Conflict();
    }
    int expired=0, retained=0, eligible=0, missing=0, unsupported=0;
    var expiredRecords = new ArrayList<FinalMatchEnvelope>();
    for (var record : unique.values()) {
      Instant finished = QuarterlyRetention.finishedAt(record);
      if (!finished.isBefore(cutoff)) { retained++; continue; }
      expired++; expiredRecords.add(record);
      var match = record.payload().path("match");
      // Match the ordered eligibility rules, never infer a marker from old scores/types.
      if (!match.has("lifetime_rule")) { missing++; continue; }
      if (!LifetimeStatistics.RULE.equals(match.path("lifetime_rule").textValue())) throw new SheetsGateway.Conflict();
      if (!LifetimeStatistics.SCORE_VERSION.equals(match.path("score_version").textValue())) { unsupported++; continue; }
      eligible++;
    }
    // Delegate rule/category/time checks to the real eligibility implementation. This
    // sentinel is never stored or returned and is not used to invent account statistics.
    LifetimeStatistics.fromEnvelopes("00000000-0000-0000-0000-000000000000", expiredRecords);
    return new RetentionCompatibilityReport(cutoff,expired,retained,eligible,missing,unsupported);
  }
  public int excludedFromLifetimeExpired() { return missingLifetimeRuleExpired + unsupportedScoreExpired; }
  /** Not a declaration of cleanup readiness or authorization. */
  public boolean historicalPolicyDecisionRequired() { return excludedFromLifetimeExpired() != 0; }
}
