package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeShutdownLifecycleTest {
 static NativePersistenceSelection selection(StagedNativeBackend stage,QuarterMaintenanceGate gate){
  var env=new MockEnvironment().withProperty("BRIDGE_PERSISTENCE_DRIVER","native-journal-v1").withProperty("BRIDGE_SHEETS_ENABLED","true").withProperty("BRIDGE_QUARTER_GATE_ENABLED","true").withProperty("BRIDGE_TEST_SPREADSHEET_ID","synthetic-shutdown-table").withProperty("BRIDGE_TEST_DATASET_ID","12345678-1234-1234-1234-123456789abc");
  return NativePersistenceSelection.preinstalled(env,gate,stage,DatasetBinding.from(env));
 }
 static class Backend extends NativeSourceCutoverSafetyTest.Backend implements NativeOrderlyShutdown {
  AtomicInteger attempts=new AtomicInteger(),closes=new AtomicInteger();volatile boolean idle;
  public boolean orderlyShutdown(Duration budget,java.util.function.BooleanSupplier domain){attempts.incrementAndGet();idle=domain.getAsBoolean();return idle;}
  public void close(){closes.incrementAndGet();}
 }
 @Test void actualSpringLifecycleRunsBeforeDisposableCloseAndOnlyOnce(){
  var backend=new Backend();var stageRef=new AtomicReference<StagedNativeBackend>();
  new NativeSpringCutoverContextTest().base().withBean(NativePersistenceSelection.Factory.class,()->(request,gate)->{var stage=new StagedNativeBackend(gate);stageRef.set(stage);return stage;}).withUserConfiguration(PersistenceConfiguration.class).run(context->{
   assertNull(context.getStartupFailure());stageRef.get().activateOnce(()->new StagedNativeBackend.Activation(backend,QuarterMaintenanceCoordinator.DurableControl.unavailable()));
   var hook=context.getBean(NativeShutdownLifecycle.class);assertTrue(hook.isRunning());var done=new CountDownLatch(1);hook.stop(done::countDown);assertTrue(done.await(2,TimeUnit.SECONDS));
   hook.stop(()->{});assertEquals(1,backend.attempts.get());assertTrue(backend.idle);
  });
  for(int i=0;i<100&&backend.closes.get()==0;i++)try{Thread.sleep(5);}catch(InterruptedException e){throw new AssertionError(e);}
  assertEquals(1,backend.closes.get());
 }
 @Test void detachedStageCannotManufactureHandoffAndFinalizerIsNonblocking()throws Exception{
  var gate=new QuarterMaintenanceGate(true,Clock.systemUTC());var stage=new StagedNativeBackend(gate);var selected=selection(stage,gate);var domain=new AtomicInteger();
  assertFalse(stage.orderlyShutdown(Duration.ofSeconds(1),()->{domain.incrementAndGet();return true;}));assertEquals(0,domain.get());
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new AtomicInteger();
  var backend=new Backend(){public void close(){entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}closed.incrementAndGet();}};
  stage.activateOnce(()->new StagedNativeBackend.Activation(backend,QuarterMaintenanceCoordinator.DurableControl.unavailable()));
  long start=System.nanoTime();stage.closeAsynchronously();assertTrue(System.nanoTime()-start<Duration.ofSeconds(1).toNanos());assertTrue(entered.await(1,TimeUnit.SECONDS));stage.close();stage.closeAsynchronously();release.countDown();
  for(int i=0;i<100&&closed.get()==0;i++)Thread.sleep(5);assertEquals(1,closed.get());selected.close();assertEquals(1,closed.get());
 }
 @Test void schedulerStopAtomicallyExcludesNewTicksWithoutWaitingForActiveIo()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new AtomicInteger();var gate=new QuarterMaintenanceGate(true,Clock.systemUTC());
  var control=new QuarterMaintenanceCoordinator.DurableControl(){
   public QuarterMaintenanceCoordinator.Observation inspect(Instant quarter){reads.incrementAndGet();entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}return QuarterMaintenanceCoordinator.Observation.unavailable();}
   public void executeOnce(Instant q,QuarterMaintenanceCoordinator.Observation o,String op){fail("No maintenance dispatch");}
  };
  var scheduler=new QuarterMaintenanceScheduler(new QuarterMaintenanceCoordinator(gate,control,Clock.systemUTC()));
  Thread worker=new Thread(scheduler::tick);worker.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
  long now=System.nanoTime();assertFalse(scheduler.stopBeforeShutdown());assertTrue(System.nanoTime()-now<Duration.ofSeconds(1).toNanos());scheduler.tick();assertEquals(1,reads.get());
  release.countDown();worker.join(2000);assertFalse(worker.isAlive());scheduler.tick();assertEquals(1,reads.get());
 }

 @Test void lateQuarterCompletionCannotResumeDomainAfterShutdownBlock()throws Exception{
  var clock=Clock.systemUTC();var gate=new QuarterMaintenanceGate(true,clock);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var resumed=new AtomicInteger();var operation=new AtomicReference<String>();
  var control=new QuarterMaintenanceCoordinator.DurableControl(){
   public QuarterMaintenanceCoordinator.Observation inspect(Instant quarter){return new QuarterMaintenanceCoordinator.Observation(operation.get()==null?QuarterMaintenanceCoordinator.Phase.DUE:QuarterMaintenanceCoordinator.Phase.COMPLETE,operation.get()==null?quarter.atZone(ZoneId.of("Asia/Taipei")).minusMonths(3).toInstant():quarter,"verified",operation.get()==null?"":operation.get(),true,true,true,true);}
   public void executeOnce(Instant q,QuarterMaintenanceCoordinator.Observation o,String op){entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}operation.set(op);}
  };
  var coordinator=new QuarterMaintenanceCoordinator(gate,control,clock,()->true,resumed::incrementAndGet);
  var scheduler=new QuarterMaintenanceScheduler(coordinator);Thread worker=new Thread(scheduler::tick);worker.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
  assertFalse(scheduler.stopBeforeShutdown());gate.blockManual(QuarterMaintenanceGate.Reason.SHUTDOWN);release.countDown();worker.join(2000);
  assertFalse(worker.isAlive());assertEquals(0,resumed.get());assertEquals(QuarterMaintenanceGate.State.BLOCKED_MANUAL,gate.state());assertEquals(QuarterMaintenanceCoordinator.Status.BLOCKED_MANUAL,coordinator.status());
 }

}
