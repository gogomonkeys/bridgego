package com.bridge.room;

import com.bridge.persistence.QuarterMaintenanceGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomSocketNativeShutdownTest {
  static final class ObservedService extends RoomService {
    final AtomicInteger freezes = new AtomicInteger();
    final CountDownLatch frozen = new CountDownLatch(1);
    volatile Thread freezeThread;
    @Override public synchronized void beginMaintenance() {
      freezeThread=Thread.currentThread();freezes.incrementAndGet();
      super.beginMaintenance();frozen.countDown();
    }
  }
  @Test void nativeEventAndDestroyReturnWhileDomainLockHeldAndFreezeOnlyOnce() throws Exception {
    var service=new ObservedService();
    var socket=new RoomSocket(service,new ObjectMapper(),System::nanoTime,Runnable::run,
        false,QuarterMaintenanceGate.disabled(),true);
    var executor=Executors.newSingleThreadExecutor();
    try {
      synchronized(service) {
        executor.submit(socket::contextClosing).get(1,TimeUnit.SECONDS);
        executor.submit(socket::shutdown).get(1,TimeUnit.SECONDS);
        assertEquals(0,service.freezes.get());
        var peer=new RoomSocketTest.Socket();
        executor.submit(()->socket.afterConnectionEstablished(peer)).get(1,TimeUnit.SECONDS);
        assertEquals(1012,peer.closed.getCode());
        executor.submit(socket::heartbeat).get(1,TimeUnit.SECONDS);
        executor.submit(()->socket.handleTextMessage(peer,new TextMessage("{}"))).get(1,TimeUnit.SECONDS);
      }
      assertTrue(service.frozen.await(1,TimeUnit.SECONDS));
      assertEquals(1,service.freezes.get());assertTrue(service.freezeThread.isDaemon());
      assertEquals("native-room-socket-shutdown",service.freezeThread.getName());
    } finally {executor.shutdownNow();socket.shutdown();}
  }
  @Test void legacyEventStillFreezesSynchronouslyOnCallingThread() {
    var service=new ObservedService();
    var socket=new RoomSocket(service,new ObjectMapper(),System::nanoTime,Runnable::run,
        false,QuarterMaintenanceGate.disabled());
    socket.contextClosing();
    assertEquals(1,service.freezes.get());assertSame(Thread.currentThread(),service.freezeThread);
    assertTrue(service.stopping());socket.shutdown();assertEquals(1,service.freezes.get());
  }
  @Test void nativePeerCloseDoesNotWaitForDomainOrCreateForfeit() throws Exception {
    var service=new ObservedService();
    var socket=new RoomSocket(service,new ObjectMapper(),System::nanoTime,Runnable::run,
        false,QuarterMaintenanceGate.disabled(),true);
    var peer=new RoomSocketTest.Socket();socket.afterConnectionEstablished(peer);
    var executor=Executors.newSingleThreadExecutor();
    try {
      synchronized(service) {
        executor.submit(socket::contextClosing).get(1,TimeUnit.SECONDS);
        executor.submit(()->socket.afterConnectionClosed(peer,CloseStatus.NORMAL)).get(1,TimeUnit.SECONDS);
        assertEquals(0,service.freezes.get());
      }
      assertTrue(service.frozen.await(1,TimeUnit.SECONDS));
    } finally {executor.shutdownNow();socket.shutdown();}
  }
  @Test void nativeDomainLatchIsNonblockingAndLateQuarterResumeCannotReopen() throws Exception {
    var service=new RoomService();var executor=Executors.newSingleThreadExecutor();
    try {
      assertTrue(service.freezeForQuarterMaintenance());
      synchronized(service) {
        executor.submit(service::requestNativeShutdown).get(1,TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class,service::finishQuarterMaintenance);
        assertTrue(service.quarterMaintenance());
        assertThrows(RuntimeException.class,()->service.createSession("late"));
        assertFalse(service.emptyForStartupRouting());
      }
    } finally {executor.shutdownNow();}
  }
  @Test void nativeDomainLatchStopsDisconnectAndSubscriptionSweepWithoutChangingPresence() {
    var service=new RoomService();var player=service.createSession("Stop");
    var room=service.create(player).roomId();service.connect(room,player,"synthetic-connection");
    assertTrue(service.hasConnection(room,player.playerId(),"synthetic-connection"));
    service.requestNativeShutdown();
    service.disconnect(room,player.playerId(),"synthetic-connection");
    service.expireInitialSubscriptions();
    assertTrue(service.hasConnection(room,player.playerId(),"synthetic-connection"));
    assertThrows(IllegalStateException.class,service::finishQuarterMaintenance);
  }
}
