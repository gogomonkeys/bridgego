package com.bridge.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class LeaderboardExportTest {
 @Test void snapshotReadsThreeBatchesAndNeverWritesOrExposesEmail() {
  var f=BatchedStatisticsExportTest.fake();String id=BatchedStatisticsExportTest.account(f);int writes=f.writes;
  f.tabs.get("accounts").set(1,List.of(id,"2","Monkey","private@example.test",LifetimeStatistics.encode(LifetimeStatsExportTest.carry())));
  try(var store=new SheetsStatsStore(BatchedStatisticsExportTest.bound(f),1,LifetimeStatsExportTest.CLOCK)) {
   var data=store.exportLeaderboard(id);assertEquals(1,data.accounts().size());assertEquals("Monkey",data.accounts().get(0).nickname());
   assertEquals(1,data.current().size());assertEquals(3,f.batches);assertEquals(writes,f.writes);
   assertFalse(data.toString().contains("private@example.test"));
  }
 }
 @Test void unrelatedProfileChangeInvalidatesWholeLeaderboardUnlikePersonalRead() {
  var f=BatchedStatisticsExportTest.fake();String id=BatchedStatisticsExportTest.account(f),other=UUID.randomUUID().toString();
  f.tabs.get("accounts").add(List.of(other,"2","Other",""));
  f.beforeBatch=n->{if(n==3)f.tabs.get("accounts").set(2,List.of(other,"2","Changed",""));};
  try(var store=new SheetsStatsStore(BatchedStatisticsExportTest.bound(f),1,LifetimeStatsExportTest.CLOCK)) {
   assertThrows(RuntimeException.class,()->store.exportLeaderboard(id));assertEquals(3,f.batches);
  }
 }
 @Test void missingProfileDoesNotInventDisplayNameAndPendingRetentionBlocksRead() {
  var f=BatchedStatisticsExportTest.fake();String id=BatchedStatisticsExportTest.account(f);
  try(var store=new SheetsStatsStore(BatchedStatisticsExportTest.bound(f),1,LifetimeStatsExportTest.CLOCK)) {
   assertTrue(store.exportLeaderboard(id).accounts().isEmpty());
   var row=new ArrayList<>(f.tabs.get("retention_ledger").get(1));row.set(2,"PENDING");row.set(3,"");f.tabs.get("retention_ledger").set(1,row);
   assertThrows(RuntimeException.class,()->store.exportLeaderboard(id));
  }
 }
}
