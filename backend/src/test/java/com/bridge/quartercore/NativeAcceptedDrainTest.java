package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.util.*;import java.util.concurrent.*;import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class NativeAcceptedDrainTest {
 @Test void registeredAndAcceptedRejectedWorkCannotBeCalledDrained()throws Exception{
  var time=new CrossQuarterNativeSettlementTest.Time();var m=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(m,JournaledNativeWriterTest.BINDING,time);var service=new Service(tx,time,JournaledNativeWriterTest.BINDING,"drain-writer",Limits.defaults());service.start();
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
  try(var backend=new NativeStoresBackend(service,tx,time,gate)){
   var e=NativeCategoryIdentitySafetyTest.envelope(m.b,"FORMAL");gate.registerMatch(e.matchId());
   var field=NativeStoresBackend.class.getDeclaredField("queue");field.setAccessible(true);var queue=(ThreadPoolExecutor)field.get(backend);
   var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);queue.execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException x){Thread.currentThread().interrupt();}});assertTrue(blocked.await(2,TimeUnit.SECONDS));
   for(int i=0;i<128;i++)queue.execute(()->{});
   assertEquals(com.bridge.persistence.StatsStore.SaveStatus.REJECTED,backend.submit(e));release.countDown();
   for(int i=0;i<200&&(queue.getActiveCount()!=0||!queue.getQueue().isEmpty());i++)Thread.sleep(5);
   assertTrue(queue.getQueue().isEmpty());gate.discardUncollectedMatch(e.matchId()); // cannot erase accepted evidence
   gate.beginDrain();assertTrue(gate.beginExclusive());int writes=m.writes;
   assertThrows(IllegalStateException.class,backend::cleanCloseForQuarter);assertEquals(writes,m.writes);
  }
 }
 @Test void neverAdmittedRejectionDoesNotCreateUnsettledWork()throws Exception{
  var time=new CrossQuarterNativeSettlementTest.Time();var m=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(m,JournaledNativeWriterTest.BINDING,time);var service=new Service(tx,time,JournaledNativeWriterTest.BINDING,"unadmitted-writer",Limits.defaults());service.start();
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));gate.beginDrain();assertTrue(gate.beginExclusive());
  try(var backend=new NativeStoresBackend(service,tx,time,gate)){
   assertEquals(com.bridge.persistence.StatsStore.SaveStatus.REJECTED,backend.submit(NativeCategoryIdentitySafetyTest.envelope(m.b,"FORMAL")));
   assertEquals("CLOSED",root(new ArrayList<>(backend.cleanCloseForQuarter().metadata.values())).state());
  }
 }
}
