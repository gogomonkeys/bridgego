package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.TextMessage;

class GameFlowTest {
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
  void manualHostStartRequiresFourReadyConnectedAndNeverAutoStarts() {
    assertEquals("WAITING", view(0).phase());
    assertTrue(view(0).canStart());
    assertFalse(view(1).canStart());
    failure("HOST_ONLY", () -> service.start(room, players[1], action(), view(1).revision()));
    service.ready(room, players[3], false);
    failure("START_NOT_READY", () -> service.start(room, players[0], action(), view(0).revision()));
    service.ready(room, players[3], true);
    service.disconnect(room, players[3].playerId(), "socket-3");
    failure("START_NOT_READY", () -> service.start(room, players[0], action(), view(0).revision()));
    service.join(room, players[3]);
    service.ready(room, players[3], true);
    failure("START_NOT_READY", () -> service.start(room, players[0], action(), view(0).revision()));
    service.connect(room, players[3], "new3");
    assertEquals("PRIVILEGE_DECISION", start().phase());
  }

  @Test
  void waitingHostTransfersToLowestRemainingSeat() {
    service.leave(room, players[0]);
    assertEquals(players[1].playerId(), view(1).hostPlayerId());
  }

  @Test
  void startIsIdempotentAndActionReuseRejected() {
    String id = action();
    long rev = view(0).revision();
    var first = service.start(room, players[0], id, rev);
    var replay = service.start(room, players[0], id, rev);
    assertEquals("REPLAYED", replay.outcome());
    assertEquals(first.room().game().dealId(), replay.room().game().dealId());
    assertEquals(first.room().you().ownHand(), replay.room().you().ownHand());
    assertEquals(first.appliedRevision(), replay.appliedRevision());
    failure(
        "ACTION_ID_REUSED",
        () ->
            service.privilege(
                room,
                players[0],
                first.room().game().gameId(),
                first.room().game().dealId(),
                id,
                replay.room().revision(),
                true));
    failure("WRONG_PHASE", () -> service.start(room, players[0], action(), view(0).revision()));
  }

  @Test
  void concurrentStartCommitsOnlyOneDeal() throws Exception {
    long rev = view(0).revision();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      var tasks = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    service.start(room, players[0], action(), rev);
                    return true;
                  } catch (RoomService.Failure e) {
                    assertEquals("STALE_REVISION", e.code);
                    return false;
                  }
                }));
      gate.countDown();
      int accepted = 0;
      for (var f : tasks) if (f.get(2, TimeUnit.SECONDS)) accepted++;
      assertEquals(1, accepted);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cardsArePrivateDisjointAndPublicTransitionContainsNoHand() throws Exception {
    List<RoomService.Snapshot> publicEvents = new ArrayList<>();
    service.publisher(publicEvents::add);
    start();
    Set<GameState.Card> all = new HashSet<>();
    ObjectMapper json = new ObjectMapper();
    for (int i = 0; i < 4; i++) {
      var s = view(i);
      assertEquals(players[i].playerId(), s.you().playerId());
      assertEquals(13, s.you().ownHand().size());
      all.addAll(s.you().ownHand());
      assertEquals(i % 2 == 0 ? "NS" : "EW", s.you().teamId());
      var tree = json.valueToTree(s);
      assertEquals(1, tree.findValues("ownHand").size());
      assertFalse(tree.has("hands"));
    }
    assertEquals(52, all.size());
    assertTrue(all.stream().allMatch(c -> c.rank() >= 2 && c.rank() <= 14));
    assertNull(publicEvents.get(0).you());
    assertNull(publicEvents.get(0).game().privilege().yourChoice());
    failure("NOT_MEMBER", () -> service.get(room, service.createSession("other")));
  }

  @Test
  void websocketTransitionsArePersonalizedBeforeBeingQueued() throws Exception {
    ObjectMapper json = new ObjectMapper();
    RoomSocket sockets = new RoomSocket(service, json, System::nanoTime);
    var clients = new RoomSocketTest.Socket[4];
    for (int i = 0; i < 4; i++) {
      clients[i] = new RoomSocketTest.Socket();
      sockets.afterConnectionEstablished(clients[i]);
      sockets.handleTextMessage(
          clients[i],
          new TextMessage(
              json.writeValueAsString(
                  Map.of("type", "subscribe", "token", players[i].token(), "roomId", room))));
    }
    start();
    vote(0, true);
    for (int i = 0; i < 4; i++) {
      var last = (TextMessage) clients[i].sent.get(clients[i].sent.size() - 1);
      var tree = json.readTree(last.getPayload()).path("room");
      assertEquals(players[i].playerId(), tree.path("you").path("playerId").asText());
      assertEquals(13, tree.path("you").path("ownHand").size());
      assertEquals(i == 0, tree.path("game").path("privilege").path("yourChoice").asBoolean(false));
      assertTrue(tree.path("game").path("privilege").path("usedByTeam").isNull());
    }
  }

  @Test
  void privilegeIsImmutableTeamOrAndSimultaneouslyRevealed() {
    start();
    vote(0, true);
    assertNull(view(1).game().privilege().usedByTeam());
    assertNull(view(2).game().privilege().yourChoice());
    assertFalse(view(2).game().privilege().canChoose());
    failure("CHOICE_LOCKED", () -> vote(2, false));
    failure("CHOICE_LOCKED", () -> vote(0, false));
    vote(1, false);
    assertEquals("PRIVILEGE_DECISION", view(0).phase());
    var s = vote(3, false);
    assertEquals("BIDDING", s.phase());
    assertEquals(Map.of("NS", true, "EW", false), s.game().privilege().usedByTeam());
    assertEquals(0, s.game().auction().nextSeat());
  }

  @Test
  void wrongTurnLowerBidDoubleAndInvalidShapesRejectWithoutMutation() {
    bidding();
    long rev = view(0).revision();
    failure("NOT_YOUR_TURN", () -> call(1, "BID", 1, "CLUB"));
    assertEquals(rev, view(0).revision());
    failure("INVALID_CALL", () -> call(0, "DOUBLE", null, null));
    failure("INVALID_CALL", () -> call(0, "BID", 1, null));
    failure("INVALID_CALL", () -> call(0, "PASS", 1, null));
    call(0, "BID", 2, "HEART");
    failure("ILLEGAL_CALL", () -> call(1, "BID", 2, "DIAMOND"));
    call(1, "BID", 2, "SPADE");
    call(2, "BID", 2, "NO_TRUMP");
    call(3, "BID", 3, "CLUB");
    assertEquals(0, view(0).game().auction().nextSeat());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
  void legalCallsRespectLevelStrainOrdering(int level) {
    bidding();
    for (String strain : GameState.STRAINS) {
      var g = new GameState(new java.security.SecureRandom());
      KeepDealFixture.keep(g);
      g.choose(0, true);
      g.choose(1, true);
      g.call(0, "BID", level, strain);
      int rank = (level - 1) * 5 + GameState.STRAINS.indexOf(strain);
      assertEquals(35 - rank, g.legalCalls(1, false).size());
      assertTrue(g.legalCalls(0, false).isEmpty());
      assertTrue(g.legalCalls(1, true).isEmpty());
    }
  }

  @Test
  void threePassesAfterBidBeginPlayWithoutPrematureScores() {
    bidding();
    call(0, "BID", 2, "HEART");
    call(1, "PASS", null, null);
    call(2, "PASS", null, null);
    var s = call(3, "PASS", null, null);
    assertEquals("PLAYING", s.phase());
    assertEquals(2, s.game().auction().result().level());
    assertEquals("NS", s.game().auction().result().teamId());
    assertEquals(Map.of("NS", 0, "EW", 0), s.game().teamTotals());
    assertTrue(s.game().completedDealResults().isEmpty());
    assertEquals(0, s.game().completedDeals());
    assertEquals(3, s.game().plannedDeals());
    assertTrue(s.you().legalCalls().isEmpty());
  }

  @Test
  void allPassRedealsWithoutAdvancingDealAndRejectsStaleDeal() {
    bidding();
    var old = view(0);
    for (int i = 0; i < 4; i++) call(i, "PASS", null, null);
    KeepDealFixture.keep(service, room, players);
    var fresh = view(0);
    assertEquals(old.game().gameId(), fresh.game().gameId());
    assertNotEquals(old.game().dealId(), fresh.game().dealId());
    assertEquals(1, fresh.game().dealIndex());
    assertEquals(0, fresh.game().completedDeals());
    assertEquals(1, fresh.game().redealCount());
    assertEquals("PRIVILEGE_DECISION", fresh.phase());
    assertNull(fresh.game().privilege().yourChoice());
    assertNull(fresh.game().privilege().usedByTeam());
    assertEquals(13, fresh.you().ownHand().size());
    failure(
        "STALE_DEAL",
        () ->
            service.privilege(
                room,
                players[0],
                old.game().gameId(),
                old.game().dealId(),
                action(),
                fresh.revision(),
                true));
  }

  @Test
  void staleRevisionAndRetriedCallsNeverApplyTwice() {
    bidding();
    var s = view(0);
    String id = action();
    var first =
        service.call(
            room,
            players[0],
            s.game().gameId(),
            s.game().dealId(),
            id,
            s.revision(),
            "BID",
            1,
            "CLUB");
    var retry =
        service.call(
            room,
            players[0],
            s.game().gameId(),
            s.game().dealId(),
            id,
            s.revision(),
            "BID",
            1,
            "CLUB");
    assertEquals("REPLAYED", retry.outcome());
    assertEquals(1, retry.room().game().auction().calls().size());
    assertEquals(first.appliedRevision(), retry.appliedRevision());
    failure(
        "STALE_REVISION",
        () ->
            service.call(
                room,
                players[1],
                s.game().gameId(),
                s.game().dealId(),
                action(),
                s.revision(),
                "PASS",
                null,
                null));
  }

  @Test
  void disconnectPausesAndReconnectBeforeDeadlineRestoresExactHand() {
    bidding();
    var before = view(0);
    service.disconnect(room, players[0].playerId(), "socket-0");
    var paused = view(1);
    assertTrue(paused.game().paused());
    assertEquals(clock.millis() + 10000, paused.players().get(0).reconnectDeadline());
    assertTrue(paused.you().legalCalls().isEmpty());
    failure("GAME_PAUSED", () -> call(0, "PASS", null, null));
    clock.now.addAndGet(9999);
    var resumed = service.connect(room, players[0], "resumed");
    assertFalse(resumed.game().paused());
    assertEquals(before.you().ownHand(), resumed.you().ownHand());
    assertEquals(before.game().dealId(), resumed.game().dealId());
    assertTrue(resumed.players().get(0).ready());
    service.disconnect(room, players[0].playerId(), "socket-0");
    clock.now.addAndGet(10000);
    service.expireInitialSubscriptions();
    assertEquals("BIDDING", view(0).phase());
  }

  @Test
  void exactlyTenSecondsTakesOverWithoutSettlementAndOriginalCanReclaim() {
    start();
    service.disconnect(room, players[0].playerId(), "socket-0");
    failure("GAME_IN_PROGRESS", () -> service.join(room, service.createSession("fifth")));
    clock.now.addAndGet(10000);
    service.expireInitialSubscriptions();
    var s = view(1);
    assertEquals("PRIVILEGE_DECISION", s.phase());
    assertEquals("BOT_TAKEOVER", s.players().get(0).controlMode());
    assertEquals(4, s.players().size());
    assertEquals(room, service.me(players[0]).roomId());
    assertTrue(s.game().completedDealResults().isEmpty());
    assertEquals(Map.of("NS", 0, "EW", 0), s.game().teamTotals());
    assertEquals("PRIVILEGE_DECISION", service.connect(room, players[0], "late").phase());
  }

  @Test
  void activeManualLeaveReplacesSeatAndPreservesOtherOfflineGrace() {
    start();
    service.disconnect(room, players[2].playerId(), "socket-2");
    service.leave(room, players[0]);
    var s = view(1);
    assertEquals("PRIVILEGE_DECISION", s.phase());
    assertEquals("BOT", s.players().get(0).playerType());
    assertTrue(s.game().paused());
    assertNull(service.me(players[0]).roomId());
    assertEquals(room, service.me(players[2]).roomId());
    assertEquals(0, s.game().completedDeals());
  }

  @Test
  void activeMultiTabAndIndependentReconnectDeadlines() {
    start();
    service.connect(room, players[0], "tab2");
    service.disconnect(room, players[0].playerId(), "socket-0");
    assertFalse(view(0).game().paused());
    service.disconnect(room, players[0].playerId(), "tab2");
    clock.now.addAndGet(1000);
    service.disconnect(room, players[1].playerId(), "socket-1");
    service.connect(room, players[0], "back");
    assertTrue(view(0).game().paused());
    service.connect(room, players[1], "back1");
    assertFalse(view(0).game().paused());
  }

  @Test
  void startPublicationBackpressureRetainsAcceptedActionAndCommittedRevision() throws Exception {
    Queue<Runnable> tasks = new ArrayDeque<>();
    ObjectMapper json = new ObjectMapper();
    RoomSocket sockets = new RoomSocket(service, json, System::nanoTime, tasks::add, false);
    RoomSocketTest.Socket socket = new RoomSocketTest.Socket();
    sockets.afterConnectionEstablished(socket);
    sockets.handleTextMessage(
        socket,
        new TextMessage(
            json.writeValueAsString(
                Map.of("type", "subscribe", "token", players[0].token(), "roomId", room))));
    service.disconnect(room, players[0].playerId(), "socket-0");
    // One initial snapshot +62 ready changes +one legacy pong fill the64-slot queue.
    for (int i = 0; i < 62; i++) service.ready(room, players[0], i % 2 != 0);
    sockets.handleTextMessage(socket, new TextMessage("{\"type\":\"ping\"}"));
    String id = action();
    long expected = view(0).revision();
    var accepted = service.start(room, players[0], id, expected);
    assertEquals("APPLIED", accepted.outcome());
    assertTrue(accepted.room().game().paused());
    assertEquals(expected + 1, accepted.appliedRevision());
    assertTrue(accepted.room().revision() > accepted.appliedRevision());
    assertEquals(13, accepted.room().you().ownHand().size());
    var replay = service.start(room, players[0], id, expected);
    assertEquals("REPLAYED", replay.outcome());
    assertEquals(accepted.appliedRevision(), replay.appliedRevision());
    assertEquals(accepted.room().game().dealId(), replay.room().game().dealId());
    while (!tasks.isEmpty()) tasks.remove().run();
    assertEquals("OUTPUT_BACKPRESSURE", socket.closed.getReason());
    clock.now.addAndGet(10_000);
    service.expireInitialSubscriptions();
    assertEquals("REPLAYED", service.start(room, players[0], id, expected).outcome());
    // Backpressure pauses the initial phase before optional KEEP can be sent.
    assertEquals(accepted.room().phase(), view(1).phase());
    assertTrue(view(1).game().completedDealResults().isEmpty());
  }
}
