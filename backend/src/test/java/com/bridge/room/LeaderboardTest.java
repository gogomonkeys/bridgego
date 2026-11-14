package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class LeaderboardTest {
 static final java.time.Instant NOW=LifetimeStatisticsServiceTest.NOW;
 static String uuid(String id){return UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
 static StatsStore.LeaderboardAccount account(String id,long score,long wins,long losses) {
  var counts=new LifetimeStatistics.Counts(wins+losses,wins+losses,wins,losses,0,score,wins+losses,0,0);
  return new StatsStore.LeaderboardAccount(uuid(id),"Player "+id,new LifetimeStatistics.Carry(
   QuarterlyRetention.currentQuarterStart(NOW),wins+losses==0?null:"2026-Q3",counts,LifetimeStatistics.Counts.zero()));
 }
 static Leaderboard.Report project(List<StatsStore.LeaderboardAccount> accounts,String mine) {
  return Leaderboard.project(new StatsStore.LeaderboardData(List.of(),accounts,NOW),uuid(mine),NOW,Leaderboard.Period.LIFETIME,Leaderboard.Category.FORMAL);
 }
 @Test void scoreThenExactRateSharedRanksNegativeScoresAndPrivacy() throws Exception {
  var report=project(List.of(account("a",7,1,2),account("b",7,2,4),account("c",7,1,1),account("d",-2,9,1)),"b");
  assertEquals("Player c",report.entries().get(0).nickname());assertEquals("Player d",report.entries().get(3).nickname());
  assertEquals(List.of(1,2,2,4),report.entries().stream().map(Leaderboard.Entry::rank).toList());
  assertTrue(report.entries().stream().anyMatch(e->e.mine() && e.nickname().equals("Player b")));assertEquals(-2,report.entries().get(3).totalScore());
  String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report);
  assertFalse(json.contains("accountId"));assertFalse(json.contains("email"));
 }
 @Test void topHundredPlusOwnRankAndNoEmptyAccounts() {
  var accounts=new ArrayList<StatsStore.LeaderboardAccount>();
  for(int i=0;i<105;i++)accounts.add(account("id"+i,105-i,1,0));
  accounts.add(account("empty",0,0,0));
  var report=project(accounts,"id104");assertEquals(101,report.entries().size());
  assertEquals(105,report.entries().get(100).rank());assertTrue(report.entries().get(100).mine());
 }
 @Test void seasonDoesNotIncludeCarry() {
  var a=account("a",100,5,0);var data=new StatsStore.LeaderboardData(List.of(),List.of(a),NOW);
  assertTrue(Leaderboard.project(data,"a",NOW,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL).entries().isEmpty());
 }
 @Test void duplicateProfileAndQuarterCrossFailClosed() {
  var a=account("a",10,1,0);assertThrows(IllegalArgumentException.class,()->project(List.of(a,a),"a"));
  assertThrows(IllegalArgumentException.class,()->Leaderboard.project(new StatsStore.LeaderboardData(List.of(),List.of(a),NOW),"a",
   java.time.Instant.parse("2027-01-01T00:00:00Z"),Leaderboard.Period.LIFETIME,Leaderboard.Category.FORMAL));
 }
 @Test void currentProjectionUsesExistingOutcomeAndDuplicateRules() {
  var win=LifetimeStatisticsServiceTest.record("FORMAL","SCORED",3,2,0,"NS");
  var loss=LifetimeStatisticsServiceTest.record("FORMAL","FORFEIT",1,4,0,"EW");
  var account=new StatsStore.LeaderboardAccount(LifetimeStatisticsServiceTest.ACCOUNT,"Monkey",LifetimeStatistics.Carry.empty());
  var data=new StatsStore.LeaderboardData(List.of(win,win,loss),List.of(account),NOW);
  var row=Leaderboard.project(data,account.accountId(),NOW,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL).entries().get(0);
  assertEquals(2,row.matches());assertEquals(6,row.totalScore());assertEquals(1,row.wins());assertEquals(1,row.losses());assertEquals(.5,row.winRate());
 }
 @Test void rateComparisonDoesNotRoundOrOverflow() {
  var report=project(List.of(account("lower",5,4_000_000_000_000L,4_000_000_000_001L),account("higher",5,4_000_000_000_001L,4_000_000_000_000L)),"higher");
  assertEquals("Player higher",report.entries().get(0).nickname());assertEquals(2,report.entries().get(1).rank());
 }
 @Test void casualCurrentAndCarryNeverRankAndGuestsDoNotCreateEntries() {
  var casual=LifetimeStatisticsServiceTest.record("CASUAL","SCORED",3,99,0,"NS");
  var player=new StatsStore.LeaderboardAccount(LifetimeStatisticsServiceTest.ACCOUNT,"Monkey",
   new LifetimeStatistics.Carry(QuarterlyRetention.currentQuarterStart(NOW),"2026-Q3",LifetimeStatistics.Counts.zero(),
    new LifetimeStatistics.Counts(2,2,2,0,0,100,2,0,0)));
  for(var period:Leaderboard.Period.values()) {
   var result=Leaderboard.project(new StatsStore.LeaderboardData(List.of(casual),List.of(player),NOW),player.accountId(),NOW,period,Leaderboard.Category.FORMAL);
   assertTrue(result.entries().isEmpty());
  }
  var formal=LifetimeStatisticsServiceTest.record("FORMAL","SCORED",3,2,0,"NS");
  var guest=MatchCategoryProjectionTest.changed(formal,r->((com.fasterxml.jackson.databind.node.ObjectNode)r.path("participants").get(0)).putNull("account_id"));
  var result=Leaderboard.project(new StatsStore.LeaderboardData(List.of(guest),List.of(player),NOW),player.accountId(),NOW,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL);
  assertTrue(result.entries().isEmpty());
 }
 @Test void quarterResetKeepsLifetimeFormalRankingWithoutRawHistory() {
  var win=LifetimeStatisticsServiceTest.record("FORMAL","SCORED",3,2,0,"NS");
  String id=LifetimeStatisticsServiceTest.ACCOUNT;
  var before=Leaderboard.project(new StatsStore.LeaderboardData(List.of(win),List.of(new StatsStore.LeaderboardAccount(id,"Monkey",LifetimeStatistics.Carry.empty())),NOW),id,NOW,Leaderboard.Period.LIFETIME,Leaderboard.Category.FORMAL);
  var next=java.time.Instant.parse("2027-01-01T00:00:00Z");
  var carry=new LifetimeStatistics.Carry(QuarterlyRetention.currentQuarterStart(next),"2026-Q4",new LifetimeStatistics.Counts(1,3,1,0,0,2,1,0,0),LifetimeStatistics.Counts.zero());
  var after=new StatsStore.LeaderboardData(List.of(),List.of(new StatsStore.LeaderboardAccount(id,"Monkey",carry)),next);
  assertTrue(Leaderboard.project(after,id,next,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL).entries().isEmpty());
  assertEquals(before.entries(),Leaderboard.project(after,id,next,Leaderboard.Period.LIFETIME,Leaderboard.Category.FORMAL).entries());
 }
}
