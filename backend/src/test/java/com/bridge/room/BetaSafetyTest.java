package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;

class BetaSafetyTest {
  RoomServiceTest.MutableClock clock = new RoomServiceTest.MutableClock();
  BetaLimits limits = new BetaLimits(2, 8, 1000, 2000, 8, 2, 20, 10, 2, 3);
  RoomService service = new RoomService(clock, clock::millis, limits);
  RoomService.Identity[] players;
  String room;

  void start() {
    players = new RoomService.Identity[4];
    for (int i=0;i<4;i++) players[i]=service.createSession("P"+i);
    room=service.create(players[0]).roomId();
    for(int i=0;i<4;i++) {
      if(i>0) service.join(room,players[i]);
      service.connect(room,players[i],"s"+i);
      service.ready(room,players[i],true);
    }
    service.start(room,players[0],UUID.randomUUID().toString(),service.get(room,players[0]).revision());
  }

  @Test void concurrentTableAdmissionAndEmptyCleanup() throws Exception {
    var guests = List.of(service.createSession("A"),service.createSession("B"),service.createSession("C"));
    var pool=Executors.newFixedThreadPool(3);
    var gate=new CountDownLatch(1);
    try {
      List<Future<String>> results=new ArrayList<>();
      for(var p:guests) results.add(pool.submit(()->{
        gate.await();
        try {return service.create(p).roomId();}
        catch(RoomService.Failure e){assertEquals("ROOM_LIMIT_REACHED",e.code);return null;}
      }));
      gate.countDown();
      int won=0;for(var f:results) if(f.get(5,TimeUnit.SECONDS)!=null)won++;
      assertEquals(2,won);
      for(var p:guests) if(service.me(p).roomId()!=null) service.leave(service.me(p).roomId(),p);
      assertNotNull(service.create(guests.get(0)));
    } finally {pool.shutdownNow();}
  }

  @Test void sessionCapacityIsDistinctFromSeatsAndExpiresIdleGuests() {
    var first=service.createSession("A");
    for(int i=1;i<8;i++)service.createSession("P"+i);
    assertEquals("SESSION_CAPACITY",assertThrows(RoomService.Failure.class,()->service.createSession("X")).code);
    clock.now.addAndGet(1000);
    assertNotNull(service.createSession("New"));
    assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,()->service.authenticate(first.token())).code);
  }

  @Test void seatedAutomaticPlayersNeverExpireForLackOfManualInput() {
    start();
    for(int turn=0;turn<10;turn++) {
      clock.now.addAndGet(20000);
      service.expireInitialSubscriptions();
      for(var p:players)assertEquals(p,service.authenticate(p.token()));
    }
    assertEquals(room,service.me(players[0]).roomId());
  }

  @Test void offlineTerminalRoomRetainsThenExpiresAndFreesCapacity() {
    start();
    var other=service.createSession("Other");
    service.connect(service.create(other).roomId(),other,"other");
    for(int i=0;i<4;i++)service.disconnect(room,players[i].playerId(),"s"+i);
    clock.now.addAndGet(10000);service.expireInitialSubscriptions();
    assertEquals("MATCH_RESULT",service.get(room,players[0]).phase());
    var newGuest=service.createSession("New");
    assertNotNull(service.create(newGuest));
    service.leave(service.me(newGuest).roomId(),newGuest);
    clock.now.addAndGet(1999);service.expireInitialSubscriptions();
    assertEquals(room,service.me(players[0]).roomId());
    clock.now.incrementAndGet();service.expireInitialSubscriptions();
    assertNull(service.me(players[0]).roomId());
    assertNotNull(service.create(service.createSession("Fresh")));
  }

  @Test void maintenanceFreezesTimersDisconnectAndRejectsMutations() {
    start();
    service.disconnect(room,players[0].playerId(),"s0");
    var before=service.get(room,players[1]);
    service.beginMaintenance();
    for(int i=1;i<4;i++)service.disconnect(room,players[i].playerId(),"s"+i);
    clock.now.addAndGet(100000);service.expireInitialSubscriptions();
    var after=service.get(room,players[1]);
    assertEquals(before.phase(),after.phase());
    assertNull(after.game().matchResult());
    assertEquals(before.revision(),after.revision());
    assertEquals("SERVER_MAINTENANCE",assertThrows(RoomService.Failure.class,()->service.leave(room,players[1])).code);
    assertEquals("SERVER_MAINTENANCE",assertThrows(RoomService.Failure.class,()->service.createSession("New")).code);
  }

  @Test void healthHasOnlyPublicEpochAndReadiness() {
    var health=new ServiceHealthController(service,"0.7.0");
    assertEquals(Set.of("instanceEpoch","version","status"),((Map<?,?>)health.ready().getBody()).keySet());
    assertEquals("no-store",health.ready().getHeaders().getFirst("Cache-Control"));
    assertNotEquals(service.instanceEpoch(),new RoomService().instanceEpoch());
    service.beginMaintenance();
    assertEquals(503,health.ready().getStatusCode().value());
    assertEquals(200,health.live().getStatusCode().value());
  }

  @Test void perGuestSocketLimitDoesNotReplaceExistingConnections() {
    var p=service.createSession("A");var r=service.create(p).roomId();
    service.connect(r,p,"1");service.connect(r,p,"2");
    assertEquals("CONNECTION_LIMIT",assertThrows(RoomService.Failure.class,()->service.connect(r,p,"3")).code);
    assertTrue(service.hasConnection(r,p.playerId(),"1"));
    assertNotNull(service.connect(r,p,"2"));
  }

  @Test void socketAdmissionBoundAndMaintenanceNotice() throws Exception {
    var handler=new RoomSocket(service,new ObjectMapper(),()->clock.millis()*1000000);
    var sockets=new ArrayList<RoomSocketTest.Socket>();
    for(int i=0;i<9;i++){var s=new RoomSocketTest.Socket();sockets.add(s);handler.afterConnectionEstablished(s);}
    assertEquals(1008,sockets.get(8).closed.getCode());
    handler.shutdown();
    assertTrue(service.stopping());
    assertEquals(1012,sockets.get(0).closed.getCode());
    assertTrue(sockets.get(0).sent.stream().anyMatch(m->m.getPayload().toString().contains("\"type\":\"maintenance\"")));
  }

  @Test void socketTextRateIsBoundedWithoutLoggingCredentials() throws Exception {
    var p=service.createSession("A");var r=service.create(p).roomId();
    var handler=new RoomSocket(service,new ObjectMapper(),()->clock.millis()*1000000);
    var socket=new RoomSocketTest.Socket();handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(socket,new TextMessage(new ObjectMapper().writeValueAsString(Map.of("type","subscribe","token",p.token(),"roomId",r))));
    for(int i=0;i<3;i++)handler.handleTextMessage(socket,new TextMessage("{\"type\":\"ping\"}"));
    assertNotNull(socket.closed);
    assertTrue(socket.sent.stream().anyMatch(m->m.getPayload().toString().contains("RATE_LIMITED")));
  }
  @Test void ownedExecutorCompletesCloseBeforeShutdown() {
    var executor=Executors.newFixedThreadPool(2);
    var closedBeforeStop=new java.util.concurrent.atomic.AtomicBoolean();
    var socket=new RoomSocketTest.Socket() {
      @Override public void close(org.springframework.web.socket.CloseStatus status) {
        closedBeforeStop.set(!executor.isShutdown());
        super.close(status);
      }
    };
    var handler=new RoomSocket(service,new ObjectMapper(),System::nanoTime,executor,true);
    handler.afterConnectionEstablished(socket);
    handler.shutdown();
    assertTrue(closedBeforeStop.get());
    assertEquals(1012,socket.closed.getCode());
    assertTrue(executor.isShutdown());
  }

  @Test void blockedTransportCannotHangMaintenanceOrForfeitGame() throws Exception {
    start();
    var executor=Executors.newFixedThreadPool(2);
    var release=new CountDownLatch(1);
    var socket=new RoomSocketTest.Socket() {
      @Override public void close(org.springframework.web.socket.CloseStatus status) {
        try { release.await(); } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        super.close(status);
      }
    };
    var handler=new RoomSocket(service,new ObjectMapper(),System::nanoTime,executor,true);
    handler.afterConnectionEstablished(socket);
    long began=System.nanoTime();
    try {
      handler.shutdown();
      long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
      assertTrue(elapsed<5000,"Shutdown wait must be bounded");
      assertTrue(service.stopping());
      clock.now.addAndGet(100000);service.expireInitialSubscriptions();
      assertNull(service.get(room,players[0]).game().matchResult());
    } finally { release.countDown();executor.shutdownNow(); }
  }

}
