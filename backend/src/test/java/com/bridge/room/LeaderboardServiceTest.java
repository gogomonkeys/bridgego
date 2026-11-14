package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.bridge.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class LeaderboardServiceTest {
 @Test void defaultDisabledNeverReadsAndGuestsForbidden() {
  var f=new StatisticsRateLimitTest.Fixture();
  var store=new MatchPersistenceTest.RecordingStore(){@Override public LeaderboardData exportLeaderboard(String a){fail("Must not read");return null;}};
  var service=new StatisticsService(f.rooms,store);
  assertEquals("DISABLED",service.readLeaderboard(f.who,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL).status());
  assertThrows(RoomService.Failure.class,()->service.readLeaderboard(f.rooms.createSession("Guest"),Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL));
 }
 @Test void sharedCooldownStopsBothEndpointsWithoutRetry() {
  var f=new StatisticsRateLimitTest.Fixture();
  var store=new MatchPersistenceTest.RecordingStore(){@Override public LeaderboardData exportLeaderboard(String a){f.calls.incrementAndGet();throw new SheetsGateway.Failure(429,0);}};
  var service=new StatisticsService(f.rooms,store,f.clock,f.nanos::get,true);
  assertEquals("RATE_LIMITED",service.readLeaderboard(f.who,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL).unavailableReason());
  for(int i=0;i<5;i++){assertEquals("RATE_LIMITED",service.read(f.who).unavailableReason());assertEquals(60,service.readLeaderboard(f.other,Leaderboard.Period.LIFETIME,Leaderboard.Category.FORMAL).retryAfterSeconds());}
  assertEquals(1,f.calls.get());
 }
 @Test void logoutDuringReadDoesNotLeakAndFailureDoesNotLookEmpty() {
  var f=new StatisticsRateLimitTest.Fixture();
  var store=new MatchPersistenceTest.RecordingStore(){@Override public LeaderboardData exportLeaderboard(String a){f.rooms.unlinkAccountOutsideRoom(f.who);return new LeaderboardData(List.of(),List.of(),f.clock.instant());}};
  var service=new StatisticsService(f.rooms,store,f.clock,f.nanos::get,true);
  assertThrows(RoomService.Failure.class,()->service.readLeaderboard(f.who,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL));
  var broken=new StatisticsService(f.rooms,new MatchPersistenceTest.RecordingStore(),f.clock,f.nanos::get,true);
  assertEquals("UNAVAILABLE",broken.readLeaderboard(f.other,Leaderboard.Period.SEASON,Leaderboard.Category.FORMAL).status());
 }
 @Test void httpRequiresLoginValidFiltersAndNoStore() throws Exception {
  var f=new StatisticsRateLimitTest.Fixture();var store=new MatchPersistenceTest.RecordingStore(){
   @Override public LeaderboardData exportLeaderboard(String account){return new LeaderboardData(List.of(),List.of(),f.clock.instant());}
  };
  var service=new StatisticsService(f.rooms,store,f.clock,f.nanos::get,true);
  var mvc=MockMvcBuilders.standaloneSetup(new StatisticsController(f.rooms,service,ManagedCollectionTest.policy(),StatisticsHttpTest.ORIGIN,new MockEnvironment())).addFilters(new BetaRequestFilter(f.rooms)).build();
  mvc.perform(get("/api/statistics/leaderboard")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/statistics/leaderboard").header("Authorization","Bearer "+f.who.token()).param("period","bad")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/statistics/leaderboard").header("Authorization","Bearer "+f.who.token()).param("category","CASUAL")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/statistics/leaderboard").header("Authorization","Bearer "+f.who.token())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.entries").isEmpty()).andExpect(header().string("Cache-Control","no-store"));
 }
}
