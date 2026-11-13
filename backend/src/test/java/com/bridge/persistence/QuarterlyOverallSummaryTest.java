package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QuarterlyOverallSummaryTest {
 @Test void canonicalRoundTripAndHistoricalMissingIsNotZero(){
  var s=new QuarterlyOverallSummary.Summary(new QuarterlyOverallSummary.Counts(2,4,1,1,1,0),QuarterlyOverallSummary.Counts.zero());
  var row=QuarterlyOverallSummary.encodeRow("2026-Q3",s);
  assertEquals(s,QuarterlyOverallSummary.decodeRow(row).counts().orElseThrow());
  assertTrue(QuarterlyOverallSummary.decodeRow(List.of("2026-Q3","SUPPRESSED","","","1")).counts().isEmpty());
  assertTrue(QuarterlyOverallSummary.decodeRow(List.of("2026-Q3","CANDIDATE_COUNTS","50","100","1")).counts().isEmpty());
 }
 @Test void unknownFieldsIdsNegativeFractionDuplicateTrailingAndInconsistentReject(){
  var row=QuarterlyOverallSummary.encodeRow("2026-Q3",new QuarterlyOverallSummary.Summary(new QuarterlyOverallSummary.Counts(1,1,0,1,0,0),QuarterlyOverallSummary.Counts.zero()));
  String j=row.get(2);
  for(String bad:List.of(j.replace("\"version\":2","\"version\":2,\"account_id\":\"person\""),j.replace("\"matches\":1","\"matches\":-1"),j.replace("\"matches\":1","\"matches\":1.0"),j.replace("\"matches\":1","\"matches\":2"),j.replace("\"version\":2","\"version\":2,\"version\":2"),j+" {}"))
   assertThrows(RuntimeException.class,()->QuarterlyOverallSummary.decodeRow(List.of("2026-Q3","INTERNAL_COUNTS",bad,"","2")));
  assertThrows(RuntimeException.class,()->QuarterlyOverallSummary.decodeRow(List.of("2026-Q3","SUPPRESSED","1","","1")));
  assertThrows(RuntimeException.class,()->new QuarterlyOverallSummary.Counts(1,6,0,1,0,0));
 }
 @Test void bothCategoriesPreserveAllSixOutcomeCountersWithDistinctMatches(){
  var records=new ArrayList<FinalMatchEnvelope>();
  // Formal: one forfeit, one no-contest, one scored draw. Casual: no-contest + scored draw.
  for(String[] spec:List.of(new String[]{"FORMAL","FORFEIT"},new String[]{"FORMAL","NO_CONTEST"},new String[]{"FORMAL","SCORED"},new String[]{"CASUAL","NO_CONTEST"},new String[]{"CASUAL","SCORED"})){
   var root=FixedRoomEnvelopeTest.record(new LocalManualQuarterRehearsal.Fixture().expired,spec[0],spec[1]);
   if(spec[1].equals("SCORED")){
    var m=FixedRoomEnvelopeTest.match(root);m.put("planned_deals",1).put("reason","PLANNED_DEALS_COMPLETE").put("ns_total",0).put("ew_total",0).putNull("winner_team_id");
    var deal=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("completed_deals").get(0);deal.put("ns_total",0).put("ew_total",0);
   }
   records.add(FixedRoomEnvelopeTest.envelope(root));
  }
  assertEquals(5,records.stream().map(FinalMatchEnvelope::matchId).distinct().count());
  var finish=QuarterlyRetention.finishedAt(records.get(0));String q=QuarterlyRetention.quarter(finish);
  var result=QuarterlyOverallSummary.summarize(q,records,finish.plusSeconds(1));
  assertEquals(new QuarterlyOverallSummary.Counts(3,3,1,1,1,1),result.formal());
  assertEquals(new QuarterlyOverallSummary.Counts(2,2,1,1,0,1),result.casual());
  assertEquals(result,QuarterlyOverallSummary.decodeRow(QuarterlyOverallSummary.encodeRow(q,result)).counts().orElseThrow());
 }
 @Test void categoryEligibilityDeduplicatesAndNeverRequiresLifetimeOrElapsed(){
  var original=new LocalManualQuarterRehearsal.Fixture().expired;
  var root=MatchCategoryEnvelopeTest.marked(original,3);
  MatchCategoryEnvelopeTest.match(root).put("score_version","bridgego-custom-v0.11-1").put("planned_deals",1);
  var e=MatchCategoryEnvelopeTest.envelope(root);var finish=QuarterlyRetention.finishedAt(e);String q=QuarterlyRetention.quarter(finish);
  var s=QuarterlyOverallSummary.summarize(q,List.of(e,e),finish.plusSeconds(1));
  assertEquals(1,s.casual().matches());assertEquals(0,s.formal().matches());
  assertFalse(QuarterlyOverallSummary.encodeRow(q,s).get(2).contains(e.matchId()));
  var changed=MatchCategoryEnvelopeTest.marked(original,0);MatchCategoryEnvelopeTest.match(changed).put("score_version","bridgego-custom-v0.11-1").put("planned_deals",1);
  assertThrows(RuntimeException.class,()->QuarterlyOverallSummary.summarize(q,List.of(e,MatchCategoryEnvelopeTest.envelope(changed)),finish.plusSeconds(1)));
  assertThrows(RuntimeException.class,()->QuarterlyOverallSummary.summarize(q,List.of(e),finish.minusSeconds(1)));
  MatchCategoryEnvelopeTest.match(root).put("score_version","unknown-version");
  assertEquals(0,QuarterlyOverallSummary.summarize(q,List.of(MatchCategoryEnvelopeTest.envelope(root)),finish.plusSeconds(1)).casual().matches());
 }
}
