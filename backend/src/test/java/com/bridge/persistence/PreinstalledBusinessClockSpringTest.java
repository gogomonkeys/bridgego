package com.bridge.persistence;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Real Spring bean assembly; only transport observations are synthetic, no reflection or env clock. */
class PreinstalledBusinessClockSpringTest {
 @Test void springCoordinatorFollowsSameTrustedGateBusinessClockAcrossQuarter(){
  var now=new AtomicReference<>(Instant.parse("2099-03-31T15:59:59Z"));
  Clock business=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}public Instant instant(){return now.get();}};
  var gate=new QuarterMaintenanceGate(true,business);var stage=new StagedNativeBackend(gate);
  var binding=new DatasetBinding("isolated-native-test-sheet","b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b",DatasetBinding.TEST_PURPOSE);
  var observed=new AtomicReference<Instant>();var completedThrough=new AtomicReference<>(Instant.parse("2098-12-31T16:00:00Z"));var completedOperation=new AtomicReference<>("");
  new NativeSpringCutoverContextTest().base().withBean(PreinstalledNativeRuntime.class,()->new PreinstalledNativeRuntime(gate,stage,binding))
   .withUserConfiguration(PersistenceConfiguration.class).run(context->{
    assertNull(context.getStartupFailure());assertSame(gate,context.getBean(QuarterMaintenanceGate.class));assertSame(business,gate.businessClockForCoordinator());
    stage.activateOnce(()->new StagedNativeBackend.Activation(new NativeSourceCutoverSafetyTest.Backend(),new QuarterMaintenanceCoordinator.DurableControl(){
     public QuarterMaintenanceCoordinator.Observation inspect(Instant quarter){observed.set(quarter);return new QuarterMaintenanceCoordinator.Observation(completedThrough.get().equals(quarter)?QuarterMaintenanceCoordinator.Phase.COMPLETE:QuarterMaintenanceCoordinator.Phase.DUE,completedThrough.get(),"synthetic-full-proof",completedOperation.get(),true,true,true,true);}
     public void executeOnce(Instant q,QuarterMaintenanceCoordinator.Observation e,String op){completedThrough.set(q);completedOperation.set(op);}
    }));
    var scheduler=context.getBean(QuarterMaintenanceScheduler.class);scheduler.tick();
    assertEquals(Instant.parse("2098-12-31T16:00:00Z"),observed.get());assertTrue(gate.acceptsNewGames());
    now.set(Instant.parse("2099-03-31T16:00:01Z"));assertEquals(QuarterMaintenanceGate.State.FINISHING,gate.state());scheduler.tick();
    assertEquals(Instant.parse("2099-03-31T16:00:00Z"),observed.get());assertTrue(gate.acceptsNewGames());
   });
 }
 @Test void ordinaryGateStillUsesRealUtcAndIgnoresUnrecognizedClockEnvironment(){
  var env=new org.springframework.mock.env.MockEnvironment().withProperty("BRIDGE_BUSINESS_TIME","2099-01-01T00:00:00Z");
  Instant before=Instant.now();var gate=new PersistenceConfiguration().quarterMaintenanceGate(env);Instant after=Instant.now();
  assertEquals(ZoneOffset.UTC,gate.businessClockForCoordinator().getZone());Instant actual=gate.businessClockForCoordinator().instant();
  assertFalse(actual.isBefore(before));assertTrue(actual.isBefore(after.plusSeconds(1)));assertFalse(gate.enabled());
 }
}
