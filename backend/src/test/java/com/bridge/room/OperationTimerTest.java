package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

class OperationTimerTest {
  RoomServiceTest.MutableClock clock;
  RoomService service;
  RoomService.Identity[] players;
  String room;

  @BeforeEach
  void setup() {
    clock = new RoomServiceTest.MutableClock();
    service = new RoomService(clock);
    players = new RoomService.Identity[4];
    for (int i = 0; i < 4; i++) players[i] = service.createSession("P" + i);
    room = service.create(players[0]).roomId();
    for (int i = 0; i < 4; i++) {
      if (i > 0) service.join(room, players[i]);
      service.connect(room, players[i], "socket-" + i);
      service.ready(room, players[i], true);
    }
  }

  static String action() {
    return UUID.randomUUID().toString();
  }

  RoomService.Snapshot view(int seat) {
    return service.get(room, players[seat]);
  }

  RoomService.Snapshot start() {
    service.start(room, players[0], action(), view(0).revision());
    KeepDealFixture.keep(service, room, players);
    return view(0);
  }

  RoomService.Snapshot vote(int seat, boolean use) {
    var s = view(seat);
    return service
        .privilege(
            room, players[seat], s.game().gameId(), s.game().dealId(), action(), s.revision(), use)
        .room();
  }

  RoomService.Snapshot call(int seat, String type, Integer level, String strain) {
    var s = view(seat);
    return service
        .call(
            room,
            players[seat],
            s.game().gameId(),
            s.game().dealId(),
            action(),
            s.revision(),
            type,
            level,
            strain)
        .room();
  }

  void bidding() {
    start();
    vote(0, true);
    vote(1, true);
  }

  void failure(String code, Executable command) {
    assertEquals(code, assertThrows(RoomService.Failure.class, command).code);
  }

  @Test
  void sharedDeadlineAndTimeoutVotes() {
    start();
    clock.now.addAndGet(12_000);
    vote(0, true);
    assertEquals(8_000, view(1).game().operationTimer().remainingMs());
    clock.now.addAndGet(7_999);
    assertEquals("PRIVILEGE_DECISION", view(1).phase());
    clock.now.incrementAndGet();
    var s = view(1);
    assertEquals("BIDDING", s.phase());
    assertEquals("TIMEOUT", s.game().privilege().yourChoiceSource());
    assertEquals("MANUAL", view(0).game().privilege().yourChoiceSource());
    assertEquals(Map.of("NS", true, "EW", false), s.game().privilege().usedByTeam());
    assertEquals(20_000, s.game().operationTimer().remainingMs());
  }

  @Test
  void timeoutOneTurnAndNoCatchup() {
    bidding();
    clock.now.addAndGet(100_000);
    var s = view(0);
    assertEquals(1, s.game().auction().calls().size());
    assertEquals("TIMEOUT", s.game().auction().calls().get(0).source());
    assertEquals(20_000, s.game().operationTimer().remainingMs());
    assertEquals(1, view(0).game().auction().calls().size());
  }

  @Test
  void allPassRedealsFreshTimerAndClearsAtContract() {
    bidding();
    String oldDeal = view(0).game().dealId();
    for (int i = 0; i < 4; i++) {
      clock.now.addAndGet(20_000);
      view(0);
    }
    KeepDealFixture.keep(service, room, players);
    var s = view(0);
    assertNotEquals(oldDeal, s.game().dealId());
    assertEquals("PRIVILEGE", s.game().operationTimer().kind());
    assertEquals(20_000, s.game().operationTimer().remainingMs());
    vote(0, true);
    vote(1, true);
    call(0, "BID", 1, "CLUB");
    assertEquals("MANUAL", view(0).game().auction().calls().get(0).source());
    for (int i = 0; i < 3; i++) {
      clock.now.addAndGet(20_000);
      view(0);
    }
    assertEquals("PLAYING", view(0).phase());
    assertEquals("PLAY_TURN", view(0).game().operationTimer().kind());
  }

  @Test
  void pauseAcrossMultipleMembersAndRefreshDoesNotReset() {
    bidding();
    clock.now.addAndGet(7_000);
    service.disconnect(room, players[0].playerId(), "socket-0");
    clock.now.addAndGet(2_000);
    service.disconnect(room, players[1].playerId(), "socket-1");
    service.connect(room, players[0], "new-0");
    clock.now.addAndGet(3_000);
    assertEquals(13_000, view(2).game().operationTimer().remainingMs());
    assertTrue(view(2).game().operationTimer().paused());
    service.connect(room, players[1], "new-1");
    service.disconnect(room, players[0].playerId(), "socket-0");
    clock.now.addAndGet(12_999);
    assertEquals(1, view(2).game().operationTimer().remainingMs());
    clock.now.incrementAndGet();
    assertEquals(1, view(2).game().auction().calls().size());
  }

  @Test
  void allHumansOfflineTerminationCancelsTimer() {
    start();
    for (int i=0;i<4;i++) service.disconnect(room, players[i].playerId(), "socket-"+i);
    clock.now.addAndGet(10_000);
    assertEquals("MATCH_RESULT", view(1).phase());
    assertNull(view(1).game().operationTimer());
  }

  @Test
  void exactBoundaryManualCannotBeatTimeoutAndReplayCannotReset() {
    bidding();
    var before = view(0);
    String id = action();
    var result =
        service.call(
            room,
            players[0],
            before.game().gameId(),
            before.game().dealId(),
            id,
            before.revision(),
            "BID",
            1,
            "CLUB");
    clock.now.addAndGet(5_000);
    var replay =
        service.call(
            room,
            players[0],
            before.game().gameId(),
            before.game().dealId(),
            id,
            before.revision(),
            "BID",
            1,
            "CLUB");
    assertEquals("REPLAYED", replay.outcome());
    assertEquals(15_000, replay.room().game().operationTimer().remainingMs());
    var due = view(1);
    clock.now.addAndGet(15_000);
    assertThrows(
        RoomService.Failure.class,
        () ->
            service.call(
                room,
                players[1],
                due.game().gameId(),
                due.game().dealId(),
                action(),
                due.revision(),
                "BID",
                2,
                "CLUB"));
    assertEquals(2, view(0).game().auction().calls().size());
    assertEquals("TIMEOUT", view(0).game().auction().calls().get(1).source());
  }

  @Test
  void independentMonotonicTickerIgnoresWallClockJump() {
    var mono = new java.util.concurrent.atomic.AtomicLong(100);
    var g = new GameState(new java.security.SecureRandom(), mono::get);
    KeepDealFixture.keep(g);
    clock.now.addAndGet(1_000_000);
    assertFalse(g.expireOperation());
    mono.addAndGet(20_000);
    assertTrue(g.expireOperation());
    assertEquals("BIDDING", g.phase);
  }

  @Test
  void concurrentExpiryAndManualCommandApplyOnlyOnce() throws Exception {
    bidding();
    var before = view(0);
    clock.now.addAndGet(20_000);
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                try {
                  gate.await();
                  service.expireInitialSubscriptions();
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              });
      var b =
          pool.submit(
              () -> {
                try {
                  gate.await();
                  service.call(
                      room,
                      players[0],
                      before.game().gameId(),
                      before.game().dealId(),
                      action(),
                      before.revision(),
                      "BID",
                      1,
                      "CLUB");
                } catch (RoomService.Failure expected) {
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              });
      gate.countDown();
      a.get();
      b.get();
      assertEquals(1, view(0).game().auction().calls().size());
      assertEquals("TIMEOUT", view(0).game().auction().calls().get(0).source());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ownVoteProvenanceOnlyAndRedealClearsIt() throws Exception {
    start();
    vote(0, true);
    assertEquals("MANUAL", view(0).game().privilege().yourChoiceSource());
    assertNull(view(1).game().privilege().yourChoiceSource());
    assertNull(view(2).game().privilege().yourChoiceSource());
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(view(1));
    assertFalse(json.contains("MANUAL"));
    clock.now.addAndGet(20_000);
    view(0);
    for (int i = 0; i < 4; i++) {
      clock.now.addAndGet(20_000);
      view(0);
    }
    assertNull(view(0).game().privilege().yourChoiceSource());
  }
}
