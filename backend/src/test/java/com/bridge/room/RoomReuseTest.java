package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class RoomReuseTest {
  RoomServiceTest.MutableClock clock;
  RoomService service;
  RoomService.Identity[] players;
  String room;

  @BeforeEach
  void setup() {
    clock = new RoomServiceTest.MutableClock();
    service = new RoomService(clock);
    players = new RoomService.Identity[4];
    for (int i = 0; i < 4; i++) players[i] = service.createSession("Orig" + i);
    room = service.create(players[0]).roomId();
    for (int i = 0; i < 4; i++) {
      if (i > 0) service.join(room, players[i]);
      service.connect(room, players[i], "s" + i);
      service.ready(room, players[i], true);
    }
    service.start(room, players[0], action(), view(0).revision());
  }

  String action() {
    return UUID.randomUUID().toString();
  }

  RoomService.Snapshot view(int seat) {
    return service.get(room, players[seat]);
  }

  void finish() {
    service.leave(room, players[3]);
    assertEquals("MATCH_RESULT", view(0).phase());
  }

  RoomService.CommandResult rematch(int seat) {
    var s = view(seat);
    return service.rematch(room, players[seat], s.game().gameId(), action(), s.revision());
  }

  void offline(int seat) {
    service.disconnect(room, players[seat].playerId(), "s" + seat);
  }

  @Test
  void hostRematchResetsOnlyConnectedMembersAndKeepsArchive() {
    finish();
    offline(2);
    var previous = view(0);
    var archive = previous.lastMatch();
    var result = rematch(0).room();
    assertEquals("WAITING", result.phase());
    assertNull(result.game());
    assertFalse(result.canRematch());
    assertEquals(2, result.players().size());
    assertTrue(result.players().stream().noneMatch(RoomService.PlayerView::ready));
    assertEquals(
        List.of(0, 1), result.players().stream().map(RoomService.PlayerView::seat).toList());
    assertTrue(result.revision() > previous.revision());
    assertEquals(archive, result.lastMatch());
    assertNull(service.me(players[2]).roomId());
    assertEquals(archive, service.lastMatch(room, players[2]));
    assertTrue(
        result.players().stream()
            .allMatch(
                p -> p.reconnectDeadline() == null && p.initialSubscriptionDeadline() == null));
  }

  @Test
  void onlyConnectedTerminalHostCanRematch() {
    finish();
    var s = view(1);
    var e =
        assertThrows(
            RoomService.Failure.class,
            () -> service.rematch(room, players[1], s.game().gameId(), action(), s.revision()));
    assertEquals("HOST_ONLY", e.code);
    assertFalse(s.canRematch());
    assertTrue(view(0).canRematch());
  }

  @Test
  void activeCannotRematch() {
    var s = view(0);
    var e =
        assertThrows(
            RoomService.Failure.class,
            () -> service.rematch(room, players[0], s.game().gameId(), action(), s.revision()));
    assertEquals("WRONG_PHASE", e.code);
  }

  @Test
  void terminalHostLeavesOrDisconnectsTransfersLowestConnectedAndReelectsReturn() {
    finish();
    offline(0);
    assertEquals(players[1].playerId(), view(1).hostPlayerId());
    offline(1);
    assertEquals(players[2].playerId(), view(2).hostPlayerId());
    offline(2);
    assertNull(view(0).hostPlayerId());
    service.connect(room, players[1], "returned1");
    assertEquals(players[1].playerId(), view(1).hostPlayerId());
    service.connect(room, players[0], "returned0");
    assertEquals(players[1].playerId(), view(0).hostPlayerId());
    service.leave(room, players[1]);
    assertEquals(players[0].playerId(), view(0).hostPlayerId());
  }

  @Test
  void rematchReplayCannotResetNewGame() {
    finish();
    var s = view(0);
    String action = action(), game = s.game().gameId();
    service.rematch(room, players[0], game, action, s.revision());
    service.join(room, players[3]);
    service.connect(room, players[3], "s3again");
    for (int i = 0; i < 4; i++) service.ready(room, players[i], true);
    var next = service.start(room, players[0], action(), view(0).revision()).room();
    var replay = service.rematch(room, players[0], game, action, s.revision());
    assertEquals("REPLAYED", replay.outcome());
    assertEquals(next.game().gameId(), replay.room().game().gameId());
    assertEquals(next.revision(), replay.room().revision());
  }

  @Test
  void sameSeatReplacementNeverInheritsArchivePermission() throws Exception {
    finish();
    var archive = service.lastMatch(room, players[3]);
    rematch(0);
    var newcomer = service.createSession("New");
    var joined = service.join(room, newcomer);
    assertEquals(3, joined.you().seat());
    assertNull(joined.lastMatch());
    service.connect(room, newcomer, "guest");
    assertNull(service.get(room, newcomer).lastMatch());
    var e = assertThrows(RoomService.Failure.class, () -> service.lastMatch(room, newcomer));
    assertEquals("NOT_PARTICIPANT", e.code);
    assertEquals(archive, service.lastMatch(room, players[3]));
    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(archive);
    assertFalse(json.contains("ownHand"));
    assertFalse(json.contains("yourChoice"));
    for (var p : players) assertFalse(json.contains(p.token()));
  }

  @Test
  void originalLeaverCanReadArchiveUntilRoomDeleted() {
    finish();
    assertNotNull(service.lastMatch(room, players[3]));
    service.leave(room, players[0]);
    service.leave(room, players[1]);
    service.leave(room, players[2]);
    var e = assertThrows(RoomService.Failure.class, () -> service.lastMatch(room, players[3]));
    assertEquals("ROOM_NOT_FOUND", e.code);
  }

  @Test
  void summariesExposeActiveFinishedWaitingAndJoinability() {
    var active = service.list().get(0);
    assertEquals("ACTIVE", active.state());
    assertFalse(active.joinable());
    finish();
    var ended = service.list().get(0);
    assertEquals("FINISHED", ended.state());
    assertEquals("MATCH_RESULT", ended.phase());
    assertFalse(ended.joinable());
    rematch(0);
    var waiting = service.list().get(0);
    assertEquals("WAITING", waiting.state());
    assertTrue(waiting.joinable());
  }

  @Test
  void missingArchiveIs404AndOutsiderSnapshotRemainsForbidden() {
    var e = assertThrows(RoomService.Failure.class, () -> service.lastMatch(room, players[0]));
    assertEquals("NO_LAST_MATCH", e.code);
    finish();
    var outsider = service.createSession("Other");
    assertThrows(RoomService.Failure.class, () -> service.get(room, outsider));
    assertThrows(RoomService.Failure.class, () -> service.lastMatch(room, outsider));
  }

  @Test
  void serviceResultAcknowledgmentsAdvanceEarlyAndReplaySafely() {
    for (int seat = 0; seat < 2; seat++) {
      var s = view(seat);
      service.privilege(
          room, players[seat], s.game().gameId(), s.game().dealId(), action(), s.revision(), true);
    }
    for (int seat = 0; seat < 4; seat++) {
      var s = view(seat);
      service.call(
          room,
          players[seat],
          s.game().gameId(),
          s.game().dealId(),
          action(),
          s.revision(),
          seat == 0 ? "BID" : "PASS",
          seat == 0 ? 1 : null,
          seat == 0 ? "CLUB" : null);
    }
    for (int i = 0; i < 52; i++) {
      var s = view(0);
      int seat = s.game().play().nextSeat();
      s = view(seat);
      service.play(
          room,
          players[seat],
          s.game().gameId(),
          s.game().dealId(),
          action(),
          s.revision(),
          s.you().legalCards().get(0));
      if (view(0).phase().equals("TRICK_RESULT")) {
        clock.now.addAndGet(GameState.TRICK_RESULT_MS);
        service.expireInitialSubscriptions();
      }
    }
    assertEquals("DEAL_RESULT", view(0).phase());
    assertTrue(view(0).you().canAcknowledgeResult());
    String finalId = action();
    var before = view(3);
    for (int seat = 0; seat < 4; seat++) {
      var s = view(seat);
      if (seat == 3) before = s;
      service.acknowledgeResult(
          room,
          players[seat],
          s.game().gameId(),
          s.game().dealId(),
          seat == 3 ? finalId : action(),
          s.revision());
    }
    var next = view(0);
    assertEquals("PRIVILEGE_DECISION", next.phase());
    assertEquals(2, next.game().dealIndex());
    var replay =
        service.acknowledgeResult(
            room,
            players[3],
            before.game().gameId(),
            before.game().dealId(),
            finalId,
            before.revision());
    assertEquals("REPLAYED", replay.outcome());
    assertEquals(next.game().dealId(), replay.room().game().dealId());
  }

  @Test
  void concurrentTerminalHostLossAndRematchHaveOneAuthorizedOrdering() throws Exception {
    finish();
    var before = view(0);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try {
      var reset =
          pool.submit(
              () -> {
                gate.await();
                try {
                  service.rematch(
                      room, players[0], before.game().gameId(), action(), before.revision());
                  return true;
                } catch (RoomService.Failure rejected) {
                  assertTrue(Set.of("STALE_REVISION", "HOST_ONLY").contains(rejected.code));
                  return false;
                }
              });
      var close =
          pool.submit(
              () -> {
                gate.await();
                offline(0);
                return true;
              });
      gate.countDown();
      boolean resetWon = reset.get();
      close.get();
      var after = view(1);
      assertEquals(players[1].playerId(), after.hostPlayerId());
      assertEquals(before.lastMatch(), after.lastMatch());
      assertTrue(after.revision() > before.revision());
      if (resetWon) {
        assertEquals("WAITING", after.phase());
        assertNull(after.game());
        assertNull(service.me(players[0]).roomId());
        assertTrue(after.players().stream().noneMatch(RoomService.PlayerView::ready));
      } else {
        assertEquals("MATCH_RESULT", after.phase());
        assertEquals(before.game().gameId(), after.game().gameId());
        assertEquals(room, service.me(players[0]).roomId());
        assertTrue(after.canRematch());
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
