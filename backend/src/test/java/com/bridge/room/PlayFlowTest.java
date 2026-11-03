package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

class PlayFlowTest {
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

  RoomService.Snapshot playing() {
    start();
    return auctionToPlay();
  }

  RoomService.Snapshot auctionToPlay() {
    KeepDealFixture.keep(service, room, players);
    vote(0, true);
    vote(1, true);
    int dealer = view(0).game().dealerSeat();
    call(dealer, "BID", 1, "NO_TRUMP");
    for (int i = 1; i < 4; i++) call((dealer + i) % 4, "PASS", null, null);
    return view(0);
  }

  RoomService.CommandResult play(
      int seat, GameState.Card card, String id, long revision, String deal) {
    return service.play(room, players[seat], view(seat).game().gameId(), deal, id, revision, card);
  }

  void playRemainingDeal() {
    String deal = view(0).game().dealId();
    while ((view(0).phase().equals("PLAYING") || view(0).phase().equals("TRICK_RESULT"))
        && view(0).game().dealId().equals(deal)) {
      if (view(0).phase().equals("TRICK_RESULT")) {
        finishTrickDisplay();
        continue;
      }
      int seat = view(0).game().play().nextSeat();
      var s = view(seat);
      play(seat, s.you().legalCards().get(0), action(), s.revision(), deal);
    }
  }

  void finishTrickDisplay() {
    assertEquals("TRICK_RESULT", view(0).phase());
    clock.now.addAndGet(GameState.TRICK_RESULT_MS);
    service.expireInitialSubscriptions();
  }

  void advanceResult() {
    for (int seat = 0; seat < 4; seat++) {
      var s = view(seat);
      service.acknowledgeResult(
          room, players[seat], s.game().gameId(), s.game().dealId(), action(), s.revision());
    }
  }

  @Test
  void completeThreeDealsScoresExactlyOnceAndRotatesDealer() {
    playing();
    Map<String, Integer> totals = new HashMap<>(Map.of("NS", 0, "EW", 0));
    for (int index = 1; index <= 3; index++) {
      assertEquals(index - 1, view(0).game().dealerSeat());
      playRemainingDeal();
      var s = view(0);
      assertEquals(index, s.game().completedDeals());
      var result = s.game().completedDealResults().get(index - 1);
      assertEquals(13, result.completedTricks().size());
      assertEquals(
          52,
          result.completedTricks().stream()
              .flatMap(t -> t.plays().stream())
              .map(TrickEngine.Play::card)
              .distinct()
              .count());
      for (String team : List.of("NS", "EW")) {
        var score = result.scores().get(team);
        assertEquals(
            score.base() + score.exactTargetBonus() + score.penalty() + score.privilegeAdjustment(),
            score.total());
        totals.put(team, totals.get(team) + score.total());
      }
      assertEquals(totals, s.game().teamTotals());
      if (index < 3) {
        assertEquals("DEAL_RESULT", s.phase());
        assertTrue(s.you().ownHand().isEmpty());
        advanceResult();
        KeepDealFixture.keep(service, room, players);
        s = view(0);
        assertEquals("PRIVILEGE_DECISION", s.phase());
        assertEquals(13, s.you().ownHand().size());
        assertEquals(20_000, s.game().operationTimer().remainingMs());
        auctionToPlay();
      }
    }
    var last = view(0);
    assertEquals("MATCH_RESULT", last.phase());
    assertEquals("SCORED", last.game().matchResult().outcome());
    assertNull(last.game().operationTimer());
    assertTrue(last.you().legalCards().isEmpty());
    var result = last.game().matchResult();
    service.disconnect(room, players[0].playerId(), "socket-0");
    clock.now.addAndGet(99_999);
    assertEquals(result, service.connect(room, players[0], "returned").game().matchResult());
  }

  @Test
  void finalPlayReplayAfterImmediateDealTransitionCannotScoreAgain() {
    playing();
    while (view(0).game().play().completedTricks().size() < 12
        || view(0).game().play().currentTrick().size() < 3) {
      int seat = view(0).game().play().nextSeat();
      var s = view(seat);
      play(seat, s.you().legalCards().get(0), action(), s.revision(), s.game().dealId());
      if (view(0).phase().equals("TRICK_RESULT")) finishTrickDisplay();
    }
    int seat = view(0).game().play().nextSeat();
    var s = view(seat);
    String id = action();
    var card = s.you().legalCards().get(0);
    var applied = play(seat, card, id, s.revision(), s.game().dealId());
    finishTrickDisplay();
    advanceResult();
    var replay = play(seat, card, id, s.revision(), s.game().dealId());
    assertEquals("REPLAYED", replay.outcome());
    assertEquals(applied.appliedRevision(), replay.appliedRevision());
    assertEquals(1, replay.room().game().completedDeals());
    assertEquals(applied.room().game().teamTotals(), replay.room().game().teamTotals());
    assertNotEquals(s.game().dealId(), replay.room().game().dealId());
  }

  @Test
  void playPrivacyWrongTurnAndForeignCard() {
    playing();
    int seat = view(0).game().play().nextSeat();
    var s = view(seat);
    int other = (seat + 1) % 4;
    assertTrue(view(other).you().legalCards().isEmpty());
    assertThrows(
        RoomService.Failure.class,
        () ->
            play(
                other,
                view(other).you().ownHand().get(0),
                action(),
                s.revision(),
                s.game().dealId()));
    assertThrows(
        RoomService.Failure.class,
        () ->
            play(
                seat,
                view(other).you().ownHand().get(0),
                action(),
                s.revision(),
                s.game().dealId()));
    var card = s.you().legalCards().get(0);
    play(seat, card, action(), s.revision(), s.game().dealId());
    assertEquals(card, view(other).game().play().currentTrick().get(0).card());
    assertEquals(12, view(seat).you().ownHand().size());
    assertEquals(13, view(other).you().ownHand().size());
  }

  @Test
  void playingTimeoutPauseResumeAndStaleClose() {
    playing();
    clock.now.addAndGet(7_000);
    service.disconnect(room, players[0].playerId(), "socket-0");
    assertEquals(13_000, view(1).game().operationTimer().remainingMs());
    clock.now.addAndGet(9_000);
    assertTrue(view(1).game().play().currentTrick().isEmpty());
    service.connect(room, players[0], "back");
    service.disconnect(room, players[0].playerId(), "socket-0");
    clock.now.addAndGet(13_000);
    assertEquals("TIMEOUT", view(0).game().play().currentTrick().get(0).source());
    assertEquals(20_000, view(0).game().operationTimer().remainingMs());
  }

  @Test
  void completedScoresSurviveMidSecondDealReplacement() {
    playing();
    playRemainingDeal();
    var before = view(0);
    advanceResult();
    auctionToPlay();
    int seat = view(0).game().play().nextSeat();
    var current = view(seat);
    play(
        seat,
        current.you().legalCards().get(0),
        action(),
        current.revision(),
        current.game().dealId());
    service.leave(room, players[0]);
    var after = view(1);
    assertEquals(before.game().completedDealResults(), after.game().completedDealResults());
    assertEquals(before.game().teamTotals(), after.game().teamTotals());
    assertNull(after.game().matchResult());
    assertEquals("PLAYING", after.phase());
  }

  @Test
  void allHumansGonePreservesCompletedScoreWithoutRevealingSecondDeal() {
    playing();playRemainingDeal();advanceResult();auctionToPlay();
    var before=view(0);for(int i=0;i<4;i++)service.disconnect(room,players[i].playerId(),"socket-"+i);
    clock.now.addAndGet(10000);var after=view(0);
    assertEquals("NO_CONTEST",after.game().matchResult().outcome());
    assertEquals(before.game().teamTotals(),after.game().teamTotals());
    assertEquals(1,after.game().completedDealResults().size());
    assertEquals(1,after.lastMatch().completedDealResults().size());
    assertNotEquals(after.game().dealId(),after.lastMatch().completedDealResults().get(0).dealId());
  }

  @Test
  void controlledThreeDealTieAndNoOvercount() {
    var g = new GameState(new java.security.SecureRandom(), clock::millis);
    for (int deal = 0; deal < 3; deal++) {
      KeepDealFixture.keep(g);
      for (int seat = 0; seat < 4; seat++) {
        var cards = new ArrayList<GameState.Card>();
        for (int rank = 2; rank <= 14; rank++)
          cards.add(new GameState.Card(GameState.STRAINS.get(seat), rank));
        g.hands.put(seat, List.copyOf(cards));
      }
      for (int seat = 0; seat < 4; seat++) g.choose(seat, false);
      int level = deal == 1 ? 2 : 1;
      g.call(deal, "BID", level, "NO_TRUMP");
      for (int i = 1; i < 4; i++) g.call((deal + i) % 4, "PASS", null, null);
      for (int i = 0; i < 52; i++) {
        clock.now.addAndGet(20_000);
        assertTrue(g.expireOperation());
        if (g.phase.equals("TRICK_RESULT")) {
          clock.now.addAndGet(GameState.TRICK_RESULT_MS);
          assertTrue(g.expireOperation());
        }
      }
      if (deal < 2) for (int seat = 0; seat < 4; seat++) g.acknowledge(seat);
    }
    var s = g.view(0, false);
    assertEquals("MATCH_RESULT", g.phase);
    assertNull(s.matchResult().winnerTeamId());
    assertEquals(Map.of("NS", -2, "EW", -2), s.teamTotals());
    assertEquals(3, s.completedDeals());
    clock.now.addAndGet(100_000);
    assertFalse(g.expireOperation());
    assertEquals(s.completedDealResults(), g.view(0, false).completedDealResults());
  }

  @Test
  void leadingTeamStillLosesForfeitWithoutChangingCompletedScore() {
    var g = new GameState(new java.security.SecureRandom(), clock::millis);
    for (int seat = 0; seat < 4; seat++) {
      var cards = new ArrayList<GameState.Card>();
      for (int rank = 2; rank <= 14; rank++)
        cards.add(new GameState.Card(GameState.STRAINS.get(seat), rank));
      g.hands.put(seat, List.copyOf(cards));
    }
    KeepDealFixture.keep(g);
    g.choose(0, true);
    g.choose(1, true);
    g.call(0, "BID", 1, "CLUB");
    for (int seat = 1; seat < 4; seat++) g.call(seat, "PASS", null, null);
    for (int i = 0; i < 52; i++) {
      clock.now.addAndGet(20_000);
      g.expireOperation();
      if (g.phase.equals("TRICK_RESULT")) {
        clock.now.addAndGet(GameState.TRICK_RESULT_MS);
        assertTrue(g.expireOperation());
      }
    }
    var before = g.view(0, false);
    assertTrue(before.teamTotals().get("NS") > before.teamTotals().get("EW"));
    g.recordForfeit("synthetic", 0, "MANUAL_EXIT");
    g.finishForfeit("MANUAL_EXIT", Set.of("NS"));
    var after = g.view(0, false);
    assertEquals("EW", after.matchResult().winnerTeamId());
    assertEquals(before.teamTotals(), after.teamTotals());
    assertEquals(1, after.completedDeals());
    assertEquals(before.completedDealResults(), after.completedDealResults());
  }

  @Test
  void concurrentFinalCardScoresOnlyOnce() throws Exception {
    playing();
    while (view(0).game().play().completedTricks().size() < 12
        || view(0).game().play().currentTrick().size() < 3) {
      int seat = view(0).game().play().nextSeat();
      var s = view(seat);
      play(seat, s.you().legalCards().get(0), action(), s.revision(), s.game().dealId());
      if (view(0).phase().equals("TRICK_RESULT")) finishTrickDisplay();
    }
    int seat = view(0).game().play().nextSeat();
    var s = view(seat);
    var card = s.you().legalCards().get(0);
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    java.util.concurrent.Callable<Boolean> action =
        () -> {
          gate.await();
          try {
            play(seat, card, action(), s.revision(), s.game().dealId());
            return true;
          } catch (RoomService.Failure expected) {
            return false;
          }
        };
    try {
      var a = pool.submit(action);
      var b = pool.submit(action);
      gate.countDown();
      assertNotEquals(a.get(), b.get());
      assertEquals(1, view(0).game().completedDeals());
    } finally {
      pool.shutdownNow();
    }
  }
}
