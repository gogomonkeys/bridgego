package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class StatisticsRateLimitTest {
  static class Fixture {
    final RoomService rooms=new RoomService();
    final RoomService.Identity who,other;
    final AtomicLong nanos=new AtomicLong(-1_000_000_000L);
    final AtomicInteger calls=new AtomicInteger();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"),ZoneOffset.UTC);
    Fixture(){
      rooms.experimentPolicy(ManagedCollectionTest.policy());rooms.statisticsPolicy(true,true);
      who=rooms.createSession("Test");rooms.linkAccountOutsideRoom(who,UUID.randomUUID().toString(),true);
      other=rooms.createSession("Other");rooms.linkAccountOutsideRoom(other,UUID.randomUUID().toString(),true);
    }
    StatisticsService limited(long retry){
      var store=new MatchPersistenceTest.RecordingStore(){
        @Override public List<FinalMatchEnvelope> exportCommitted(){
          if(calls.incrementAndGet()==1)throw new SheetsGateway.Failure(429,retry);
          return List.of();
        }
      };
      return new StatisticsService(rooms,store,clock,nanos::get);
    }
    void advance(long millis){nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));}
  }
  @Test void quotaFailureStartsMinimumMinuteCooldownWithZeroAdditionalStorageRequests(){
    var f=new Fixture();var service=f.limited(0);
    var first=service.read(f.who);assertEquals("UNAVAILABLE",first.status());
    assertEquals("RATE_LIMITED",first.unavailableReason());assertEquals(60,first.retryAfterSeconds());assertNull(first.personal());
    for(int n=0;n<20;n++)assertEquals(60,service.read(f.who).retryAfterSeconds());
    assertEquals(1,f.calls.get());
    f.advance(59_999);assertEquals(1,service.read(f.who).retryAfterSeconds());assertEquals(1,f.calls.get());
    f.advance(1);assertEquals(1,f.calls.get()); // no automatic retry
    var recovered=service.read(f.who);assertEquals("AVAILABLE",recovered.status());assertNull(recovered.unavailableReason());
    assertNull(recovered.retryAfterSeconds());assertEquals(2,f.calls.get());
  }
  @Test void validLongRetryAfterIsHonoredAndDoesNotOverflow(){
    var f=new Fixture();var service=f.limited(90_001);
    assertEquals(91,service.read(f.who).retryAfterSeconds());f.advance(90_000);
    assertEquals(1,service.read(f.who).retryAfterSeconds());assertEquals(1,f.calls.get());
    f.advance(1);assertEquals("AVAILABLE",service.read(f.who).status());
    f=new Fixture();service=f.limited(Long.MAX_VALUE);
    assertEquals(3600,service.read(f.who).retryAfterSeconds());f.advance(3_600_000);
    assertEquals(3600,service.read(f.who).retryAfterSeconds());assertEquals(1,f.calls.get());
  }
  @Test void negativeRetryAfterUsesDefaultAndCooldownIsSharedWithoutSharingPersonalData(){
    var f=new Fixture();var service=f.limited(-1);
    assertEquals(60,service.read(f.who).retryAfterSeconds());
    var other=service.read(f.other);assertEquals("RATE_LIMITED",other.unavailableReason());
    assertNull(other.personal());assertNull(other.overall());assertNull(other.lifetimePersonal());assertEquals(1,f.calls.get());
    var guest=f.rooms.createSession("Guest");
    assertThrows(RoomService.Failure.class,()->service.read(guest));assertEquals(1,f.calls.get());
    f.rooms.statisticsPolicy(false,true);assertEquals("DISABLED",service.read(f.other).status());assertEquals(1,f.calls.get());
  }
  @Test void otherStorageFailuresRemainFailClosedWithoutFalseRateLimit(){
    for(int status:List.of(0,403,500)){
      var f=new Fixture();var store=new MatchPersistenceTest.RecordingStore(){
        @Override public List<FinalMatchEnvelope> exportCommitted(){f.calls.incrementAndGet();throw new SheetsGateway.Failure(status,0);}
      };
      var service=new StatisticsService(f.rooms,store,f.clock,f.nanos::get);
      for(int n=0;n<2;n++){var report=service.read(f.who);assertEquals("STORAGE_UNAVAILABLE",report.unavailableReason());assertNull(report.retryAfterSeconds());assertNull(report.personal());}
      assertEquals(2,f.calls.get());
    }
  }
  @Test void busyIsExplicitAndNeverStartsASecondStorageRead()throws Exception{
    var f=new Fixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var store=new MatchPersistenceTest.RecordingStore(){
      @Override public List<FinalMatchEnvelope> exportCommitted(){
        f.calls.incrementAndGet();entered.countDown();
        try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}
        catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IllegalStateException(stop);}
        return List.of();
      }
    };
    var service=new StatisticsService(f.rooms,store,f.clock,f.nanos::get);var pool=Executors.newSingleThreadExecutor();
    try{
      var first=pool.submit(()->service.read(f.who));assertTrue(entered.await(2,TimeUnit.SECONDS));
      var busy=service.read(f.other);assertEquals("BUSY",busy.unavailableReason());assertEquals(2,busy.retryAfterSeconds());assertEquals(1,f.calls.get());
      assertNull(busy.personal());release.countDown();assertEquals("AVAILABLE",first.get(2,TimeUnit.SECONDS).status());
      assertEquals("AVAILABLE",service.read(f.other).status());assertEquals(2,f.calls.get());
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void accountSwitchDuringQuotaFailureStillFailsAuthenticationInsteadOfReturningPriorData(){
    var f=new Fixture();var store=new MatchPersistenceTest.RecordingStore(){
      @Override public List<FinalMatchEnvelope> exportCommitted(){f.rooms.unlinkAccountOutsideRoom(f.who);throw new SheetsGateway.Failure(429,0);}
    };
    var service=new StatisticsService(f.rooms,store,f.clock,f.nanos::get);
    assertEquals("STATS_LOGIN_REQUIRED",assertThrows(RoomService.Failure.class,()->service.read(f.who)).code);
  }
}
