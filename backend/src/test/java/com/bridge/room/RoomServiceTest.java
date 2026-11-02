package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class RoomServiceTest {
  static class MutableClock extends Clock {
    AtomicLong now = new AtomicLong(100_000);

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now.get());
    }

    public long millis() {
      return now.get();
    }
  }

  MutableClock clock;
  RoomService service;

  @BeforeEach
  void setup() {
    clock = new MutableClock();
    service = new RoomService(clock);
  }

  RoomService.Identity player(String n) {
    return service.createSession(n);
  }

  String create(RoomService.Identity p) {
    String r = service.create(p).roomId();
    service.connect(r, p, p.playerId());
    return r;
  }

  void join(String r, RoomService.Identity p) {
    service.join(r, p);
    service.connect(r, p, p.playerId());
  }

  @Test
  void fourPlayersFifthRejectedAndNoAutomaticGame() {
    var a = player("A");
    String r = create(a);
    var players = new ArrayList<RoomService.Identity>();
    players.add(a);
    for (int i = 1; i < 4; i++) {
      var p = player("P" + i);
      players.add(p);
      join(r, p);
    }
    var full = assertThrows(RoomService.Failure.class, () -> service.join(r, player("fifth")));
    assertEquals("ROOM_FULL", full.code);
    players.forEach(p -> service.ready(r, p, true));
    var s = service.get(r, a);
    assertEquals(4, s.players().size());
    assertEquals(4, s.players().stream().map(RoomService.PlayerView::seat).distinct().count());
    assertTrue(s.allReady());
    assertEquals("WAITING", s.phase());
  }

  @Test
  void concurrentLastSeatIsAtomic() throws Exception {
    var a = player("A");
    String r = create(a);
    join(r, player("B"));
    join(r, player("C"));
    var x = player("X");
    var y = player("Y");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (var p : List.of(x, y))
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    service.join(r, p);
                    return true;
                  } catch (RoomService.Failure e) {
                    assertEquals("ROOM_FULL", e.code);
                    return false;
                  }
                }));
      gate.countDown();
      int won = 0;
      for (var f : results) if (f.get(5, TimeUnit.SECONDS)) won++;
      assertEquals(1, won);
      assertEquals(4, service.get(r, a).players().size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void readyAndJoinAreIdempotent() {
    var p = player("A");
    String r = create(p);
    long v = service.ready(r, p, true).revision();
    assertEquals(v, service.ready(r, p, true).revision());
    assertEquals(v, service.join(r, p).revision());
  }

  @Test
  void membershipAndRoomIsolation() {
    var a = player("A");
    var b = player("B");
    String r = create(a), other = create(b);
    assertEquals(
        "NOT_MEMBER", assertThrows(RoomService.Failure.class, () -> service.get(r, b)).code);
    assertThrows(RoomService.Failure.class, () -> service.ready(r, b, true));
    assertThrows(RoomService.Failure.class, () -> service.connect(r, b, "bad"));
    assertThrows(RoomService.Failure.class, () -> service.join(other, a));
    List<RoomService.Snapshot> events = new ArrayList<>();
    service.publisher(events::add);
    service.ready(r, a, true);
    assertEquals(List.of(r), events.stream().map(RoomService.Snapshot::roomId).toList());
  }

  @Test
  void lastSocketLossImmediatelyReleasesSeatAndClearsReady() {
    var a = player("A");
    String r = create(a);
    var b = player("B");
    join(r, b);
    service.ready(r, b, true);
    service.disconnect(r, b.playerId(), b.playerId());
    assertNull(service.me(b).roomId());
    assertEquals(1, service.get(r, a).players().size());
    assertThrows(RoomService.Failure.class, () -> service.connect(r, b, "without-join"));
    service.join(r, b);
    var rejoined = service.connect(r, b, "fresh").players().get(1);
    assertFalse(rejoined.ready());
    assertNull(rejoined.reconnectDeadline());
  }

  @Test
  void replacementMayTakeReleasedSeatAndOldPlayerGetsRoomFull() {
    var a = player("A");
    String r = create(a);
    var b = player("B");
    join(r, b);
    join(r, player("C"));
    join(r, player("D"));
    service.disconnect(r, b.playerId(), b.playerId());
    var replacement = player("New");
    join(r, replacement);
    assertEquals(
        "ROOM_FULL", assertThrows(RoomService.Failure.class, () -> service.join(r, b)).code);
    assertNull(service.me(b).roomId());
  }

  @Test
  void staleDisconnectCannotRemoveFreshlyRejoinedClient() {
    var a = player("A");
    String r = create(a);
    var p = player("B");
    join(r, p);
    service.disconnect(r, p.playerId(), p.playerId());
    service.join(r, p);
    service.connect(r, p, "new");
    service.disconnect(r, p.playerId(), p.playerId());
    assertTrue(service.get(r, p).players().get(1).connected());
    assertEquals(r, service.me(p).roomId());
  }

  @Test
  void multiTabRetainsSeatUntilLastSocketCloses() {
    var p = player("A");
    String r = create(p);
    service.connect(r, p, "tab2");
    service.disconnect(r, p.playerId(), p.playerId());
    clock.now.addAndGet(20_000);
    assertTrue(service.get(r, p).players().get(0).connected());
    service.disconnect(r, p.playerId(), "tab2");
    assertNull(service.me(p).roomId());
    assertTrue(service.list().isEmpty());
  }

  @Test
  void manualLeaveImmediateAndOldConnectionCannotAffectRejoin() {
    var a = player("A");
    String r = create(a);
    var b = player("B");
    join(r, b);
    service.ready(r, b, true);
    service.leave(r, b);
    service.leave(r, b);
    assertNull(service.me(b).roomId());
    service.join(r, b);
    service.connect(r, b, "new");
    service.disconnect(r, b.playerId(), b.playerId());
    assertFalse(service.get(r, b).players().get(1).ready());
    assertTrue(service.get(r, b).players().get(1).connected());
  }

  @Test
  void initialSubscriptionHasFiveSecondProvisionalDeadlineNotReconnectGrace() {
    var a = player("same");
    var b = player("same");
    assertNotEquals(a.playerId(), b.playerId());
    assertThrows(RoomService.Failure.class, () -> service.authenticate(a.playerId()));
    String r = service.create(a).roomId();
    assertEquals(
        clock.millis() + 5000, service.get(r, a).players().get(0).initialSubscriptionDeadline());
    assertNull(service.get(r, a).players().get(0).reconnectDeadline());
    clock.now.addAndGet(4999);
    assertEquals(r, service.me(a).roomId());
    clock.now.addAndGet(1);
    service.expireInitialSubscriptions();
    assertNull(service.me(a).roomId());
    assertTrue(service.list().isEmpty());
  }

  @Test
  void successfulInitialSubscriptionCancelsProvisionalDeadline() {
    var a = player("A");
    String r = service.create(a).roomId();
    clock.now.addAndGet(4999);
    service.connect(r, a, "first");
    clock.now.addAndGet(86_400_000L);
    service.expireInitialSubscriptions();
    var snapshot = service.get(r, a);
    assertNull(snapshot.players().get(0).initialSubscriptionDeadline());
    assertTrue(snapshot.players().get(0).connected());
  }

  @Test
  void provisionalReadyPlayerCannotMakeAllReadyAndRemovalNeverStartsGame() {
    var a = player("A");
    String r = create(a);
    var players = new ArrayList<RoomService.Identity>();
    players.add(a);
    for (int i = 1; i < 3; i++) {
      var p = player("P" + i);
      players.add(p);
      join(r, p);
    }
    var provisional = player("P3");
    players.add(provisional);
    service.join(r, provisional);
    players.forEach(p -> service.ready(r, p, true));
    assertFalse(service.get(r, a).allReady());
    assertTrue(service.connect(r, provisional, "connected").allReady());
    service.disconnect(r, provisional.playerId(), "connected");
    assertFalse(service.get(r, a).allReady());
    assertEquals(3, service.get(r, a).players().size());
    assertEquals("WAITING", service.get(r, a).phase());
  }
}
