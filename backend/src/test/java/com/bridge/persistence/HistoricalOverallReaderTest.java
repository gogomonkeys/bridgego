package com.bridge.persistence;
import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HistoricalOverallReaderTest {
 static QuarterlyOverallSummary.Summary summary(){return new QuarterlyOverallSummary.Summary(new QuarterlyOverallSummary.Counts(1,1,0,1,0,0),QuarterlyOverallSummary.Counts.zero());}
 @Test void observedHistoryIsSeparateAndUnavailableNeverBecomesZero(){
  var f=BatchedStatisticsExportTest.fake();f.tabs.get("quarterly_aggregates").add(QuarterlyOverallSummary.encodeRow("2026-Q3",summary()));f.tabs.get("quarterly_aggregates").add(List.of("2026-Q2","SUPPRESSED","","","1"));f.tabs.get("quarterly_aggregates").add(List.of("2026-Q1","CANDIDATE_COUNTS","50","100","1"));int writes=f.writes;
  try(var store=new SheetsStatsStore(BatchedStatisticsExportTest.bound(f),1,LifetimeStatsExportTest.CLOCK)){
   var data=store.exportStatistics(BatchedStatisticsExportTest.account(f));assertEquals(1,data.current().size());assertEquals(LifetimeStatsExportTest.carry(),data.lifetime());
   var h=data.historicalOverall();assertTrue(h.observed());assertEquals(QuarterlyRetention.currentQuarterStart(LifetimeStatsExportTest.NOW),h.beforeExclusive());assertEquals(summary(),h.countsFor("2026-Q3").orElseThrow());
   assertTrue(h.quarters().containsKey("2026-Q2"));assertTrue(h.countsFor("2026-Q2").isEmpty());assertTrue(h.countsFor("2026-Q1").isEmpty());assertFalse(h.quarters().containsKey("2025-Q4"));assertTrue(h.countsFor("2025-Q4").isEmpty());assertEquals(3,f.batches);assertEquals(writes,f.writes);
  }
  assertFalse(new StatsStore.StatisticsData(List.of(),null).historicalOverall().observed());
 }
 @Test void invalidDuplicateCurrentFutureMissingOrChangedRowsFailClosed(){
  for(String mode:List.of("DUPLICATE","CURRENT","FUTURE","INVALID","MISSING","CHANGED")){
   var f=BatchedStatisticsExportTest.fake();var rows=f.tabs.get("quarterly_aggregates");rows.add(QuarterlyOverallSummary.encodeRow("2026-Q3",summary()));
   switch(mode){
    case "DUPLICATE" -> rows.add(rows.get(1));
    case "CURRENT" -> rows.add(QuarterlyOverallSummary.encodeRow("2026-Q4",summary()));
    case "FUTURE" -> rows.add(QuarterlyOverallSummary.encodeRow("2027-Q1",summary()));
    case "INVALID" -> rows.set(1,List.of("2026-Q3","INTERNAL_COUNTS","{}","","2"));
    case "MISSING" -> f.tabs.remove("quarterly_aggregates");
    case "CHANGED" -> f.beforeBatch=n->{if(n==3)rows.remove(1);};
   }
   try(var store=new SheetsStatsStore(BatchedStatisticsExportTest.bound(f),1,LifetimeStatsExportTest.CLOCK)){assertThrows(RuntimeException.class,()->store.exportStatistics(BatchedStatisticsExportTest.account(f)),mode);}
  }
 }
 @Test void expiredRawCannotOverlapHistoricalQuarter(){
  var f=BatchedStatisticsExportTest.fake();var old=new LocalManualQuarterRehearsal.Fixture().expired;
  f.tabs.get("quarterly_aggregates").add(QuarterlyOverallSummary.encodeRow(QuarterlyRetention.quarter(QuarterlyRetention.finishedAt(old)),summary()));
  f.appendAtomic(Map.of("envelopes",List.of(List.of(old.commitId(),old.payloadHash(),0,1,old.canonicalJson())),"commits",List.of(List.of(old.commitId(),old.payloadHash(),1,old.matchId(),old.revision()))));
  try(var store=new SheetsStatsStore(BatchedStatisticsExportTest.bound(f),1,LifetimeStatsExportTest.CLOCK)){assertThrows(RuntimeException.class,()->store.exportStatistics(BatchedStatisticsExportTest.account(f)));}
 }
}
