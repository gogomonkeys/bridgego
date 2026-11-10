package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LifetimeStatisticsServiceTest {
  static final String ACCOUNT=FixedRoomStatisticsTest.ACCOUNT;
  static final Instant NOW=FixedRoomStatisticsTest.NOW;
  static FinalMatchEnvelope record(String type,String outcome,int deals,int ns,int ew,String winner) {
    return MatchCategoryProjectionTest.changed(FixedRoomStatisticsTest.record(type,outcome,deals,ns,ew,winner),
        r->((ObjectNode)r.path("match")).put("lifetime_rule",LifetimeStatistics.RULE));
  }
  static LifetimeStatistics.Carry previous() {
    return new LifetimeStatistics.Carry(Instant.parse("2026-09-30T16:00:00Z"),"2026-Q3",
        new LifetimeStatistics.Counts(2,3,1,1,0,7,2,0,0),LifetimeStatistics.Counts.zero());
  }

  @Test void careerAddsArchivedCountsAndCurrentMarkedRecordsWithoutCombiningModesOrScores() {
    var win=record("FORMAL","SCORED",3,2,0,"NS");
    var forfeit=record("FORMAL","FORFEIT",1,4,0,"EW");
    var interrupted=record("FORMAL","NO_CONTEST",0,0,0,null);
    var casual=record("CASUAL","SCORED",3,-1,4,"EW");
    var report=StatisticsService.project(List.of(win,win,forfeit,interrupted,casual),previous(),ACCOUNT,NOW);
    assertEquals(3,report.personal().matches());assertEquals(6,report.personal().totalScore());
    var all=report.lifetimePersonal();
    assertEquals(5,all.matches());assertEquals(7,all.completedDeals());assertEquals(13,all.totalScore());
    assertEquals(2,all.wins());assertEquals(2,all.losses());assertEquals(0,all.draws());
    assertEquals(3,all.scoredMatches());assertEquals(1,all.forfeitMatches());assertEquals(1,all.interruptedMatches());
    assertEquals(4,all.wins()+all.losses()+all.draws()); // 50%, not 2/5; interruption excluded
    assertEquals(1,report.lifetimeCasualPersonal().matches());assertEquals(-1,report.lifetimeCasualPersonal().totalScore());
    assertEquals("2026-Q3",report.lifetimeTrackedFromQuarter());
    assertEquals("RECORDED_SINCE_FEATURE",report.lifetimeCoverage());
  }

  @Test void oldUnmarkedConsentIsNotRetroactivelyConvertedToCareerAndGuestsHaveNoAttribution() {
    var old=FixedRoomStatisticsTest.record("FORMAL","SCORED",3,2,0,"NS");
    var modern=record("FORMAL","SCORED",3,3,0,"NS");
    var guest=MatchCategoryProjectionTest.changed(modern,r->((ObjectNode)r.path("participants").get(0)).putNull("account_id"));
    var report=StatisticsService.project(List.of(old,guest),ACCOUNT,NOW);
    assertEquals(1,report.personal().matches());assertEquals(2,report.overall().matches());
    assertEquals(0,report.lifetimePersonal().matches());assertNull(report.lifetimeTrackedFromQuarter());
  }

  @Test void emptyCareerIsZeroWithNoInventedHistoryAndQuarterEndUsesTaiwanExclusiveBoundary() {
    for (var entry:Map.of("2026-03-31T15:59:59Z","2026-03-31T16:00:00Z",
        "2026-03-31T16:00:00Z","2026-06-30T16:00:00Z",
        "2026-12-31T15:59:59Z","2026-12-31T16:00:00Z",
        "2026-12-31T16:00:00Z","2027-03-31T16:00:00Z").entrySet()) {
      var report=StatisticsService.project(List.of(),ACCOUNT,Instant.parse(entry.getKey()));
      assertEquals(entry.getValue(),report.quarterEndsAt());assertEquals(0,report.lifetimePersonal().matches());
      assertNull(report.lifetimeTrackedFromQuarter());
    }
  }

  @Test void incompleteOrFutureCarryFailsClosedAndLifetimeMarkerCannotClaimLegacyConsent() {
    var future=new LifetimeStatistics.Carry(Instant.parse("2026-12-31T16:00:00Z"),"2026-Q3",
        previous().formal(),LifetimeStatistics.Counts.zero());
    assertThrows(IllegalArgumentException.class,()->StatisticsService.project(List.of(),future,ACCOUNT,NOW));
    var marked=record("FORMAL","SCORED",3,1,0,"NS");
    for(String bad:List.of("", "future"))assertThrows(IllegalArgumentException.class,()->
        MatchCategoryProjectionTest.changed(marked,r->((ObjectNode)r.path("match")).put("lifetime_rule",bad)));
    assertThrows(IllegalArgumentException.class,()->MatchCategoryProjectionTest.changed(marked,r->{
      var m=(ObjectNode)r.path("match");m.put("statistics_rule",FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE);m.remove("room_type");
    }));
  }

  @Test void liveGameConsentEmitsLifetimeMarkerAndReadUsesAccountScopedCombinedSnapshot() {
    var backing=new MatchPersistenceTest.RecordingStore();var f=StatisticsCollectionFlowTest.fixture(1,4,backing);
    f.playRemainingDeal();var saved=backing.writes.get(0);
    assertEquals(LifetimeStatistics.RULE,saved.payload().path("match").path("lifetime_rule").asText());
    var store=new MatchPersistenceTest.RecordingStore(){
      @Override public StatisticsData exportStatistics(String account){
        assertEquals(f.service.accountId(f.players[0]),account);return new StatisticsData(backing.writes,previous());
      }
    };
    var report=new StatisticsService(f.service,store,StatisticsServiceTest.clock(f)).read(f.players[0]);
    assertEquals("AVAILABLE",report.status());assertEquals(3,report.lifetimePersonal().matches());
    assertEquals(1,report.personal().matches());
    var broken=new MatchPersistenceTest.RecordingStore(){
      @Override public StatisticsData exportStatistics(String account){throw new IllegalStateException("Unverified carry");}
    };
    var unavailable=new StatisticsService(f.service,broken,StatisticsServiceTest.clock(f)).read(f.players[0]);
    assertEquals("UNAVAILABLE",unavailable.status());assertNull(unavailable.personal());assertNull(unavailable.lifetimePersonal());
  }
  @Test void storageReadFinishingAcrossQuarterBoundaryNeverReturnsOldCarryWithNewQuarter() {
    var rooms=new RoomService();rooms.statisticsPolicy(true,true);rooms.experimentPolicy(ManagedCollectionTest.policy());
    var who=rooms.createSession("P");rooms.linkAccountOutsideRoom(who,ACCOUNT,true);
    var store=new MatchPersistenceTest.RecordingStore(){
      @Override public StatisticsData exportStatistics(String account) {
        return new StatisticsData(List.of(),null,Instant.parse("2026-12-31T15:59:59Z"));
      }
    };
    var report=new StatisticsService(rooms,store,Clock.fixed(Instant.parse("2026-12-31T16:00:00Z"),ZoneOffset.UTC)).read(who);
    assertEquals("UNAVAILABLE",report.status());assertNull(report.lifetimePersonal());
    assertEquals("2027-Q1",report.quarter());assertEquals("2027-03-31T16:00:00Z",report.quarterEndsAt());
  }

  @Test void accountSwitchAfterFormalExitCannotRewriteOrSuppressOriginalCareerAttribution() {
    var store=new MatchPersistenceTest.RecordingStore();var f=FixedRoomStatisticsTest.flow("FORMAL",5,store);
    String original=f.service.accountId(f.players[0]),other=f.service.accountId(f.players[1]);
    f.service.leave(f.room,f.players[0]);
    f.service.unlinkAccountOutsideRoom(f.players[0]);f.service.linkAccountOutsideRoom(f.players[0],other,true);
    f.clock.now.addAndGet(1_000);f.service.expireInitialSubscriptions();
    assertEquals(1,store.writes.size());
    assertEquals(original,store.writes.get(0).payload().path("participants").get(0).path("account_id").asText());
    var report=StatisticsService.project(store.writes,original,f.clock.instant());
    assertEquals(1,report.lifetimePersonal().losses());assertEquals(0,report.lifetimePersonal().totalScore());
  }

}
