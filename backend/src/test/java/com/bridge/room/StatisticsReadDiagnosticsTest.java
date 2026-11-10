package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StatisticsReadDiagnosticsTest {
  static class Capture implements AutoCloseable {
    final Logger logger=(Logger)LoggerFactory.getLogger(StatisticsReadDiagnostics.class);
    final ListAppender<ILoggingEvent> appender=new ListAppender<>();
    Capture(){appender.start();logger.addAppender(appender);}
    public void close(){logger.detachAppender(appender);appender.stop();}
  }
  @Test void logsOnlyFixedCodesNotExceptionMessagesCausesOrIdentifiers() {
    try(var capture=new Capture()){
      var time=new AtomicLong();var diag=new StatisticsReadDiagnostics(capture.logger,time::get);
      var failures=List.of(new SheetsGateway.Failure(429,999),new SheetsGateway.Conflict(),
          new IllegalArgumentException("SECRET_PERSON_EMAIL_TOKEN"),new IllegalStateException("SECRET_SHEET_ID"),
          new RuntimeException("SECRET_PAYLOAD",new RuntimeException("SECRET_CAUSE")));
      for(var failure:failures)diag.failed(StatisticsReadDiagnostics.Stage.STORAGE,failure,2_000_000);
      assertEquals(5,capture.appender.list.size());
      for(var event:capture.appender.list){
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("SECRET"));
        assertTrue(event.getFormattedMessage().matches(
            "statistics_read_unavailable stage=STORAGE reason=[A-Z_]+ storageStatus=[0-9]+ elapsedMs=2"));
      }
      assertTrue(capture.appender.list.get(0).getFormattedMessage().contains("storageStatus=429"));
    }
  }
  @Test void sameDiagnosticIsRateLimitedButDifferentCausesRemainVisible() {
    try(var capture=new Capture()){
      var time=new AtomicLong();var diag=new StatisticsReadDiagnostics(capture.logger,time::get);
      diag.busy();diag.busy();
      diag.failed(StatisticsReadDiagnostics.Stage.STORAGE,new SheetsGateway.Failure(429,0),0);
      diag.failed(StatisticsReadDiagnostics.Stage.STORAGE,new SheetsGateway.Failure(429,0),0);
      assertEquals(2,capture.appender.list.size());
      time.addAndGet(TimeUnit.SECONDS.toNanos(60));diag.busy();
      assertEquals(3,capture.appender.list.size());
      diag.failed(StatisticsReadDiagnostics.Stage.PROJECTION,new SheetsGateway.Failure(123456,0),-1);
      assertTrue(capture.appender.list.get(3).getFormattedMessage().endsWith("storageStatus=0 elapsedMs=0"));
    }
  }
  @Test void loggingBackendFailureCannotBreakTheSafeFallback() {
    var calls=new AtomicInteger();
    var logger=(org.slf4j.Logger)java.lang.reflect.Proxy.newProxyInstance(org.slf4j.Logger.class.getClassLoader(),
        new Class<?>[]{org.slf4j.Logger.class},(proxy,method,args)->{
          if(method.getName().equals("warn")){calls.incrementAndGet();throw new IllegalStateException("SECRET_LOGGER_FAILURE");}
          return method.getReturnType()==boolean.class?false:null;
        });
    var diag=new StatisticsReadDiagnostics(logger,()->0L);
    assertDoesNotThrow(diag::busy);
    assertDoesNotThrow(()->diag.failed(StatisticsReadDiagnostics.Stage.STORAGE,new SheetsGateway.Failure(429,0),0));
    assertEquals(2,calls.get());
  }
  static RoomService rooms() {
    var rooms=new RoomService();rooms.experimentPolicy(ManagedCollectionTest.policy());rooms.statisticsPolicy(true,true);return rooms;
  }
  @Test void storageAndProjectionFailuresKeepPublicResponseUnchangedAndAreDistinguished() {
    var rooms=rooms();var who=rooms.createSession("Test");rooms.linkAccountOutsideRoom(who,UUID.randomUUID().toString(),true);
    try(var capture=new Capture()){
      var broken=new MatchPersistenceTest.RecordingStore(){
        @Override public List<FinalMatchEnvelope> exportCommitted(){throw new SheetsGateway.Failure(503,0);}
      };
      var report=new StatisticsService(rooms,broken).read(who);
      assertEquals("UNAVAILABLE",report.status());assertNull(report.personal());
      assertTrue(capture.appender.list.get(0).getFormattedMessage().contains("stage=STORAGE reason=STORAGE_HTTP storageStatus=503"));
      var future=LifetimeStatisticsServiceTest.record("CASUAL","SCORED",3,-4,1,"EW");
      var store=new MatchPersistenceTest.RecordingStore(){@Override public List<FinalMatchEnvelope> exportCommitted(){return List.of(future);}};
      var early=Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"),ZoneOffset.UTC);
      assertEquals("UNAVAILABLE",new StatisticsService(rooms,store,early).read(who).status());
      assertTrue(capture.appender.list.get(1).getFormattedMessage().contains("stage=PROJECTION reason=INVALID_DATA"));
    }
  }
  @Test void overlappingReadReportsBusyThenRecoversWithoutCachingOrExtraStorageWork() throws Exception {
    var rooms=rooms();var who=rooms.createSession("Test");rooms.linkAccountOutsideRoom(who,UUID.randomUUID().toString(),true);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
    var store=new MatchPersistenceTest.RecordingStore(){
      @Override public List<FinalMatchEnvelope> exportCommitted(){
        calls.incrementAndGet();entered.countDown();
        try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test wait");}
        catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IllegalStateException(stop);}
        return List.of();
      }
    };
    var service=new StatisticsService(rooms,store);var pool=Executors.newSingleThreadExecutor();
    try(var capture=new Capture()){
      var first=pool.submit(()->service.read(who));assertTrue(entered.await(2,TimeUnit.SECONDS));
      assertEquals("UNAVAILABLE",service.read(who).status());assertEquals(1,calls.get());
      assertTrue(capture.appender.list.get(0).getFormattedMessage().contains("stage=ADMISSION reason=BUSY"));
      release.countDown();assertEquals("AVAILABLE",first.get(2,TimeUnit.SECONDS).status());
      assertEquals("AVAILABLE",service.read(who).status());assertEquals(2,calls.get());
    }finally{release.countDown();pool.shutdownNow();}
  }
}
