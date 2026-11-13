package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionCompatibilityReportTest {
  static final Instant CUTOFF=Instant.parse("2026-09-30T16:00:00Z");
  static FinalMatchEnvelope marked() {
    return LifetimeStatisticsTest.marked(new LocalManualQuarterRehearsal.Fixture().expired,"FORMAL","FORFEIT");
  }
  @Test void countsMarkersWithoutGuessingAndDoesNotExposeIdentity() {
    var record=marked();
    var report=RetentionCompatibilityReport.inspect(List.of(record,record),CUTOFF);
    assertEquals(1,report.expiredMatches()); assertEquals(1,report.lifetimeRuleEligibleExpired());
    assertFalse(report.historicalPolicyDecisionRequired());
    assertFalse(report.toString().contains(record.matchId()));
    var missing=LifetimeStatisticsTest.changed(record,r->((ObjectNode)r.path("match")).remove("lifetime_rule"));
    var oldScore=LifetimeStatisticsTest.changed(record,r->((ObjectNode)r.path("match")).put("score_version","old"));
    assertEquals(1,RetentionCompatibilityReport.inspect(List.of(missing),CUTOFF).missingLifetimeRuleExpired());
    assertEquals(1,RetentionCompatibilityReport.inspect(List.of(oldScore),CUTOFF).unsupportedScoreExpired());
    assertTrue(RetentionCompatibilityReport.inspect(List.of(missing),CUTOFF).historicalPolicyDecisionRequired());
  }
  @Test void exactCutoffAndNewerRecordsAreRetainedNotFlaggedForDeletion() {
    var record=marked();
    for (var finish:List.of(CUTOFF,CUTOFF.plusSeconds(1))) {
      var kept=LifetimeStatisticsTest.changed(record,r->{var m=(ObjectNode)r.path("match");m.put("finished_at",finish.toString());m.remove("lifetime_rule");});
      var report=RetentionCompatibilityReport.inspect(List.of(kept),CUTOFF);
      assertEquals(1,report.retainedMatches()); assertEquals(0,report.expiredMatches());
      assertFalse(report.historicalPolicyDecisionRequired());
    }
  }
  @Test void conflictingRevisionsAndInvalidInputFailClosed() {
    var record=marked();
    var changed=LifetimeStatisticsTest.changed(record,r->((ObjectNode)r.path("match")).remove("lifetime_rule"));
    assertThrows(SheetsGateway.Conflict.class,()->RetentionCompatibilityReport.inspect(List.of(record,changed),CUTOFF));
    assertThrows(SheetsGateway.Conflict.class,()->RetentionCompatibilityReport.inspect(null,CUTOFF));
    assertThrows(SheetsGateway.Conflict.class,()->RetentionCompatibilityReport.inspect(List.of(record),CUTOFF.plusSeconds(1)));
    assertEquals(0,RetentionCompatibilityReport.inspect(List.of(),CUTOFF).expiredMatches());
  }
  @Test void missingMarkerTakesPrecedenceOverUnknownScoreAsInLifetimeRules() {
    var old=LifetimeStatisticsTest.changed(marked(),r->{var m=(ObjectNode)r.path("match");m.remove("lifetime_rule");m.put("score_version","old");});
    var report=RetentionCompatibilityReport.inspect(List.of(old),CUTOFF);
    assertEquals(1,report.excludedFromLifetimeExpired()); assertEquals(1,report.missingLifetimeRuleExpired());
    assertEquals(0,report.unsupportedScoreExpired());
  }
}
