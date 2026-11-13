package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;import java.time.*;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NarrowQuarterPoc.*;
class NativeShutdownHandoffTest {
 @TempDir Path dir;
 static class F implements AutoCloseable {
  final Clock clock;final JournaledNativeWriterTest.Memory m;final com.bridge.persistence.QuarterMaintenanceGate gate;final NativeStoresBackend backend;final Service service;
  F(JournaledNativeWriterTest.Memory memory){this(memory,JournaledNativeWriterTest.CLOCK);}
  F(JournaledNativeWriterTest.Memory memory,Clock clock){m=memory;this.clock=clock;var tx=new JournaledNativeTransport(m,JournaledNativeWriterTest.BINDING,clock);service=new Service(tx,clock,JournaledNativeWriterTest.BINDING,"shutdown-writer",Limits.defaults());service.start();gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);gate.openVerified(QuarterlyRetention.currentQuarterStart(clock.instant()));backend=new NativeStoresBackend(service,tx,clock,gate);}
  NativeShutdownHandoff handoff(java.util.function.BooleanSupplier domain){return new NativeShutdownHandoff(backend,gate,domain,JournaledNativeWriterTest.BINDING,clock);}
  public void close(){backend.close();}
 }
 @Test void cleanClosePersistsVerifiedEvidenceAndTwoFreshJvmsDoNotInferAuthority()throws Exception{
  try(var f=new F(JournaledNativeWriterTest.initial())){var before=f.m.b.tables();int writes=f.m.writes;var h=f.handoff(()->true);var result=h.attempt(Duration.ofSeconds(2));assertEquals(NativeShutdownHandoff.State.COMPLETE,result.state());assertEquals(writes+1,f.m.writes);assertEquals(before,f.m.b.tables());assertEquals(JournalSessionAuthority.fullToken(f.m.b),result.closedToken());assertTrue(f.service.closed);assertThrows(RuntimeException.class,()->h.attempt(Duration.ofSeconds(2)));
   var probe=new JournalRestartProcessTest();probe.dir=dir;probe.freshProcess(f.m.b,null,"CLEAN_CURRENT");probe.freshProcess(f.m.b,null,"CLEAN_CURRENT");
  }
 }
 @Test void idleShutdownAtQuarterBoundaryClosesButLeavesQuarterDue()throws Exception{
  var time=new java.util.concurrent.atomic.AtomicReference<>(JournaledNativeWriterTest.CLOCK.instant());
  Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}};
  try(var f=new F(JournaledNativeWriterTest.initial(),clock)){
   var before=f.m.b.tables();int writes=f.m.writes;
   time.set(QuarterlyRetention.currentQuarterStart(time.get()).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant());
   assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.FINISHING,f.gate.state());
   var result=f.handoff(()->true).attempt(Duration.ofSeconds(2));
   assertEquals(NativeShutdownHandoff.State.COMPLETE,result.state());assertEquals(writes+1,f.m.writes);
   assertEquals(before,f.m.b.tables());assertEquals("CLOSED",root(new ArrayList<>(f.m.b.metadata.values())).state());
   assertEquals(JournalQuarterProtocol.RecoveryState.READY_DUE,JournalQuarterProtocol.inspect(JournaledNativeWriterTest.BINDING,f.m.b,clock).state());
   assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.MAINTENANCE,f.gate.state());assertFalse(f.gate.acceptsNewGames());assertTrue(f.service.closed);
  }
 }
 @Test void activeDomainRegisteredMatchOrPermitNeverManufactureCleanClose()throws Exception{
  for(int scenario=0;scenario<3;scenario++)try(var f=new F(JournaledNativeWriterTest.initial())){int n=scenario;com.bridge.persistence.QuarterMaintenanceGate.Permit permit=null;
   if(scenario==1)f.gate.registerMatch("still-playing");if(scenario==2)permit=f.gate.enterWrite();int writes=f.m.writes;
   assertEquals(NativeShutdownHandoff.State.REFUSED,f.handoff(()->n!=0).attempt(Duration.ofSeconds(1)).state());assertEquals(writes,f.m.writes);assertEquals("OPEN",root(new ArrayList<>(f.m.b.metadata.values())).state());if(permit!=null){permit.success();permit.close();}
  }
 }
 @Test void unsettledAcceptedAndQueueAreNotDiscardedToClose()throws Exception{
  for(boolean accepted:List.of(false,true))try(var f=new F(JournaledNativeWriterTest.initial())){
   if(accepted)((Set<String>)org.springframework.test.util.ReflectionTestUtils.getField(f.backend,"unsettledAccepted")).add("pending");
   else{var queue=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(f.backend,"queue");queue.getQueue().add(()->{});}
   int writes=f.m.writes;assertEquals(NativeShutdownHandoff.State.UNKNOWN,f.handoff(()->true).attempt(Duration.ofSeconds(1)).state());assertEquals(writes,f.m.writes);
   if(!accepted)((ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(f.backend,"queue")).getQueue().clear();
  }
 }
 @Test void priorUnknownNeverSubmitsClose()throws Exception{
  try(var f=new F(JournaledNativeWriterTest.initial())){f.m.delay=true;var row=f.m.b.sheet("auth_identities").rows.get(1);assertEquals(NativeWriterAdapter.Outcome.UNKNOWN,new NativeWriterAdapter(f.service).identity("SHUTDOWN-UNKNOWN",row.get(0),row.get(1),"Synthetic","test@example.com").outcome());int writes=f.m.writes;
   assertEquals(NativeShutdownHandoff.State.UNKNOWN,f.handoff(()->true).attempt(Duration.ofSeconds(1)).state());assertEquals(writes,f.m.writes);
  }
 }
 @Test void callerTimeoutDoesNotInterruptSerialAndLateCloseCannotReviveResult()throws Exception{
  var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var completed=new CountDownLatch(1);
  var original=JournaledNativeWriterTest.initial();var memory=new JournaledNativeWriterTest.Memory(original.b){volatile boolean block;
   public void batch(String wire){if(wire.contains("CLOSE-shutdown-writer")){entered.countDown();try{if(!resume.await(4,TimeUnit.SECONDS))throw new NativeSheetsModel.Unknown();}catch(InterruptedException e){throw new AssertionError("must not interrupt serial",e);}super.batch(wire);completed.countDown();}else super.batch(wire);}
  };
  try(var f=new F(memory)){var released=new CountDownLatch(1);var releases=new java.util.concurrent.atomic.AtomicInteger();org.springframework.test.util.ReflectionTestUtils.setField(f.backend,"replacementRelease",(Runnable)()->{releases.incrementAndGet();released.countDown();});var h=f.handoff(()->true);long start=System.nanoTime();var result=h.attempt(Duration.ofMillis(100));assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals(NativeShutdownHandoff.State.UNKNOWN,result.state());assertTrue(System.nanoTime()-start<Duration.ofSeconds(2).toNanos());int writes=f.m.writes;
   assertEquals(0,releases.get());resume.countDown();assertTrue(completed.await(2,TimeUnit.SECONDS));assertTrue(released.await(2,TimeUnit.SECONDS));Thread.sleep(30);assertEquals(1,releases.get());f.backend.close();assertEquals(1,releases.get());assertEquals(NativeShutdownHandoff.State.UNKNOWN,h.result().state());assertEquals(writes+1,f.m.writes);assertThrows(RuntimeException.class,()->h.attempt(Duration.ofSeconds(1)));
   var probe=new JournalRestartProcessTest();probe.dir=dir;probe.freshProcess(f.m.b,null,"CLEAN_CURRENT");assertEquals("",h.result().closedToken());
  }finally{resume.countDown();}
 }
}
