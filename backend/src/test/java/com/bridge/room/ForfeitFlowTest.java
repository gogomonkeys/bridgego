package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class ForfeitFlowTest {
  RoomServiceTest.MutableClock clock;
  RoomService service;
  RoomService.Identity[] players;
  String room;

  @BeforeEach
  void setup() {
    clock = new RoomServiceTest.MutableClock();
    service = new RoomService(clock);
    startRoom();
  }

  void startRoom() {
    players = new RoomService.Identity[4];
    for (int i = 0; i < 4; i++) players[i] = service.createSession("P" + i);
    room = service.create(players[0]).roomId();
    for (int i = 0; i < 4; i++) {
      if (i > 0) service.join(room, players[i]);
      service.connect(room, players[i], "socket-" + i);
      service.ready(room, players[i], true);
    }
    service.start(room, players[0], UUID.randomUUID().toString(), view(0).revision());
  }

  RoomService.Snapshot view(int seat) {
    return service.get(room, players[seat]);
  }

  void offline(int seat) {
    service.disconnect(room, players[seat].playerId(), "socket-" + seat);
  }

  void reconnect(int seat) {
    service.connect(room, players[seat], "resumed-" + seat);
  }

  void advance(long ms) {
    clock.now.addAndGet(ms);
    service.expireInitialSubscriptions();
  }

  void pending() {
    offline(0);
    advance(3000);
    offline(1);
    advance(7000);
    assertEquals("FORFEIT_PENDING", view(2).phase());
  }

  @Test
  void manualExitForfeitsImmediatelyAndPreservesRoster() {
    service.leave(room, players[0]);
    var s = view(1);
    assertEquals("MATCH_RESULT", s.phase());
    assertEquals("FORFEIT", s.game().matchResult().outcome());
    assertEquals("EW", s.game().matchResult().winnerTeamId());
    assertEquals("MANUAL_EXIT", s.game().matchResult().reason());
    assertEquals(4, s.game().roster().size());
    assertEquals("P0", s.game().roster().get(0).displayName());
    assertEquals(0, s.game().completedDeals());
    assertEquals(Map.of("NS", 0, "EW", 0), s.game().teamTotals());
    assertNull(s.game().operationTimer());
    assertNull(service.me(players[0]).roomId());
  }

  @Test
  void loneTimeoutForfeitsAtExactDeadlineAndOfflineCanInspectResult() {
    offline(0);
    advance(9999);
    assertEquals("PRIVILEGE_DECISION", view(1).phase());
    advance(1);
    assertEquals("MATCH_RESULT", view(1).phase());
    assertEquals("EW", view(1).game().matchResult().winnerTeamId());
    assertEquals(room, service.me(players[0]).roomId());
    reconnect(0);
    assertEquals("RECONNECT_TIMEOUT", view(0).game().matchResult().reason());
    assertNull(view(0).players().get(0).reconnectDeadline());
  }

  @Test
  void simultaneousBothTeamTimeoutsAreNoContest() {
    offline(0);
    offline(1);
    advance(10000);
    assertEquals("NO_CONTEST", view(2).game().matchResult().outcome());
    assertNull(view(2).game().matchResult().winnerTeamId());
    assertEquals("BOTH_TEAMS_FAILED", view(2).game().matchResult().reason());
  }

  @Test
  void staggeredOpponentGetsEntireOwnGraceAndTimelyReturnWins() {
    pending();
    assertEquals(List.of(1), view(2).game().pendingForfeit().waitingSeats());
    assertNull(view(2).game().operationTimer());
    advance(2999);
    reconnect(1);
    assertEquals("MATCH_RESULT", view(1).phase());
    assertEquals("EW", view(1).game().matchResult().winnerTeamId());
    assertNull(view(1).game().pendingForfeit());
  }

  @Test
  void staggeredOpponentExactDeadlineReturnCannotEraseFailure() {
    pending();
    advance(3000);
    reconnect(1);
    assertEquals("NO_CONTEST", view(1).game().matchResult().outcome());
  }

  @Test
  void failedTeamLateReturnCannotEraseFailure() {
    pending();
    reconnect(0);
    assertEquals("FORFEIT_PENDING", view(0).phase());
    reconnect(1);
    assertEquals("EW", view(0).game().matchResult().winnerTeamId());
  }

  @Test
  void pendingOpponentManualExitMakesBothFailed() {
    pending();
    service.leave(room, players[1]);
    assertEquals("NO_CONTEST", view(2).game().matchResult().outcome());
  }

  @Test
  void pendingAlreadyFailedTeamExitDoesNotChangeOtherDeadline() {
    pending();
    service.leave(room, players[0]);
    assertEquals("FORFEIT_PENDING", view(2).phase());
    advance(2999);
    reconnect(1);
    assertEquals("EW", view(2).game().matchResult().winnerTeamId());
  }

  @Test
  void newDisconnectCannotCreateOrExtendPendingGrace() {
    pending();
    advance(1000);
    offline(3);
    assertEquals(List.of(1), view(2).game().pendingForfeit().waitingSeats());
    assertNull(
        view(2).players().stream()
            .filter(p -> p.seat() == 3)
            .findFirst()
            .orElseThrow()
            .reconnectDeadline());
    reconnect(1);
    assertEquals("MATCH_RESULT", view(2).phase());
    assertEquals("EW", view(2).game().matchResult().winnerTeamId());
  }

  @Test
  void terminalDisconnectLeaveAndLateCallbacksNeverRewriteResult() {
    service.leave(room, players[0]);
    var result = view(1).game().matchResult();
    offline(1);
    advance(100_000);
    assertEquals(result, view(2).game().matchResult());
    assertEquals(room, service.me(players[1]).roomId());
    reconnect(1);
    service.leave(room, players[1]);
    assertEquals(result, view(2).game().matchResult());
    service.disconnect(room, players[1].playerId(), "resumed-1");
    assertEquals(result, view(3).game().matchResult());
  }

  @Test
  void anotherLiveTabPreventsOfflineFailure() {
    service.connect(room, players[0], "second-tab");
    offline(0);
    advance(10000);
    assertEquals("PRIVILEGE_DECISION", view(0).phase());
    assertFalse(view(0).game().paused());
    assertNull(view(0).players().get(0).reconnectDeadline());
  }

  @Test
  void pendingRejectsCommandsWithoutResettingFrozenDeadlines() {
    pending();
    var s = view(2);
    var failure =
        assertThrows(
            RoomService.Failure.class,
            () ->
                service.privilege(
                    room,
                    players[2],
                    s.game().gameId(),
                    s.game().dealId(),
                    UUID.randomUUID().toString(),
                    s.revision(),
                    true));
    assertEquals("GAME_PAUSED", failure.code);
    advance(3000);
    assertEquals("NO_CONTEST", view(2).game().matchResult().outcome());
  }

  @Test
  void reconnectAuthorityIgnoresForwardAndBackwardWallClockJumps() {
    var tick = new java.util.concurrent.atomic.AtomicLong(50_000);
    service = new RoomService(clock, tick::get);
    startRoom();
    offline(0);
    long shownDeadline = view(1).players().get(0).reconnectDeadline();
    clock.now.addAndGet(1_000_000);
    assertEquals("PRIVILEGE_DECISION", view(1).phase());
    assertEquals(shownDeadline, view(1).players().get(0).reconnectDeadline());
    tick.addAndGet(9_999);
    clock.now.addAndGet(-2_000_000);
    assertEquals("PRIVILEGE_DECISION", view(1).phase());
    tick.incrementAndGet();
    assertEquals("MATCH_RESULT", view(1).phase());
    assertEquals("EW", view(1).game().matchResult().winnerTeamId());
  }

  @Test
  void capturedOpponentGraceUsesMonotonicTime() {
    var tick = new java.util.concurrent.atomic.AtomicLong(50_000);
    service = new RoomService(clock, tick::get);
    startRoom();
    offline(0);
    tick.addAndGet(3_000);
    offline(1);
    tick.addAndGet(7_000);
    assertEquals("FORFEIT_PENDING", view(2).phase());
    clock.now.addAndGet(1_000_000);
    assertEquals("FORFEIT_PENDING", view(2).phase());
    tick.addAndGet(2_999);
    clock.now.addAndGet(-2_000_000);
    assertEquals("FORFEIT_PENDING", view(2).phase());
    tick.incrementAndGet();
    assertEquals("NO_CONTEST", view(2).game().matchResult().outcome());
  }

  @Test
  void timeoutAndManualAttributionIsSafeDeduplicatedAndTerminalFrozen() {
    pending();
    var initial = view(2).game().forfeitEvents();
    assertEquals(1, initial.size());
    assertEquals(players[0].playerId(), initial.get(0).playerId());
    assertEquals(0, initial.get(0).seat());
    assertEquals("RECONNECT_TIMEOUT", initial.get(0).cause());
    for (int i = 0; i < 5; i++) service.expireInitialSubscriptions();
    assertEquals(1, view(2).game().forfeitEvents().size());
    service.leave(room, players[1]);
    var events = view(2).game().forfeitEvents();
    assertEquals(2, events.size());
    assertEquals("MANUAL_EXIT", events.get(1).cause());
    service.leave(room, players[0]);
    assertEquals(events, view(2).game().forfeitEvents());
  }
}
