package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class TrickResultTest {
  PlayFlowTest f;

  @BeforeEach
  void setup() {
    f = new PlayFlowTest();
    f.setup();
    f.playing();
  }

  RoomService.CommandResult playOne() {
    var s = f.view(0);
    int seat = s.game().play().nextSeat();
    s = f.view(seat);
    return f.play(seat, s.you().legalCards().get(0), PlayFlowTest.action(),
        s.revision(), s.game().dealId());
  }

  void finishTrick() {
    for (int i = 0; i < 4; i++) playOne();
  }

  void reachFinalCard() {
    for (int trick = 0; trick < 12; trick++) {
      finishTrick();
      f.finishTrickDisplay();
    }
    for (int i = 0; i < 3; i++) playOne();
  }

  @Test
  void allFourCardsAndWinnerRetainedForEveryViewerWithNoLegalPlay() {
    finishTrick();
    var result = f.view(0);
    assertEquals("TRICK_RESULT", result.phase());
    var trick = result.game().play().completedTricks().get(0);
    for (int seat = 0; seat < 4; seat++) {
      var s = f.view(seat);
      assertTrue(trick.plays().isEmpty());
      assertFalse(trick.cardsVisible());
      assertEquals(4, s.game().play().currentTrick().size());
      assertEquals(trick.leaderSeat(), s.game().play().leaderSeat());
      assertEquals(1, s.game().play().trickNumber());
      assertNull(s.game().play().nextSeat());
      assertTrue(s.you().legalCards().isEmpty());
      assertFalse(s.you().canAcknowledgeResult());
      assertEquals("TRICK_RESULT", s.game().operationTimer().kind());
      assertNull(s.game().operationTimer().turnSeat());
      assertEquals(2_000, s.game().operationTimer().remainingMs());
      final int player = seat;
      assertThrows(RoomService.Failure.class,
          () -> f.play(player, s.you().ownHand().get(0), PlayFlowTest.action(),
              s.revision(), s.game().dealId()));
    }
    f.clock.now.addAndGet(1_999);
    assertEquals("TRICK_RESULT", f.view(0).phase());
    f.clock.now.incrementAndGet();
    var next = f.view(0);
    assertEquals("PLAYING", next.phase());
    assertEquals(trick.winnerSeat(), next.game().play().nextSeat());
    assertTrue(next.game().play().currentTrick().isEmpty());
    assertEquals(2, next.game().play().trickNumber());
    assertEquals(20_000, next.game().operationTimer().remainingMs());
  }

  @Test
  void replayAndRefreshCannotRestartResultBudget() {
    for (int i = 0; i < 3; i++) playOne();
    int seat = f.view(0).game().play().nextSeat();
    var s = f.view(seat);
    String action = PlayFlowTest.action();
    var card = s.you().legalCards().get(0);
    var applied = f.play(seat, card, action, s.revision(), s.game().dealId());
    f.clock.now.addAndGet(750);
    var replay = f.play(seat, card, action, s.revision(), s.game().dealId());
    assertEquals("REPLAYED", replay.outcome());
    assertEquals(applied.appliedRevision(), replay.appliedRevision());
    assertEquals(1_250, replay.room().game().operationTimer().remainingMs());
    assertEquals(1, replay.room().game().play().completedTricks().size());
    assertEquals(1_250, f.view(seat).game().operationTimer().remainingMs());
  }

  @Test
  void pausedResultReconnectPreservesCardsAndRemainingBudget() {
    finishTrick();
    var cards = f.view(0).game().play().currentTrick();
    f.clock.now.addAndGet(650);
    f.service.disconnect(f.room, f.players[0].playerId(), "socket-0");
    f.clock.now.addAndGet(9_000);
    var paused = f.view(1);
    assertTrue(paused.game().paused());
    assertEquals(1_350, paused.game().operationTimer().remainingMs());
    assertEquals(cards, paused.game().play().currentTrick());
    var returned = f.service.connect(f.room, f.players[0], "returned");
    assertEquals(cards, returned.game().play().currentTrick());
    assertEquals(1_350, returned.game().operationTimer().remainingMs());
    f.service.disconnect(f.room, f.players[0].playerId(), "socket-0");
    f.clock.now.addAndGet(1_350);
    assertEquals("PLAYING", f.view(0).phase());
    assertEquals(20_000, f.view(0).game().operationTimer().remainingMs());
  }

  @Test
  void timeoutFourthCardAlsoShowsResultAndLateSweepOnlyAdvancesOnce() {
    for (int i = 0; i < 3; i++) playOne();
    f.clock.now.addAndGet(20_000);
    var result = f.view(0);
    assertEquals("TRICK_RESULT", result.phase());
    assertEquals("TIMEOUT", result.game().play().currentTrick().get(3).source());
    f.clock.now.addAndGet(100_000);
    var next = f.view(0);
    assertEquals("PLAYING", next.phase());
    assertTrue(next.game().play().currentTrick().isEmpty());
    assertEquals(20_000, next.game().operationTimer().remainingMs());
    assertEquals(1, next.game().play().completedTricks().size());
  }

  @Test
  void finalTrickScoresImmediatelyButCannotAcknowledgeUntilDisplayEnds() {
    reachFinalCard();
    playOne();
    var result = f.view(0);
    assertEquals("TRICK_RESULT", result.phase());
    assertEquals(13, result.game().play().trickNumber());
    assertEquals(4, result.game().play().currentTrick().size());
    assertEquals(1, result.game().completedDeals());
    assertTrue(result.you().ownHand().isEmpty());
    assertFalse(result.you().canAcknowledgeResult());
    assertThrows(RoomService.Failure.class,
        () -> f.service.acknowledgeResult(f.room, f.players[0], result.game().gameId(),
            result.game().dealId(), PlayFlowTest.action(), result.revision()));
    f.finishTrickDisplay();
    var settlement = f.view(0);
    assertEquals("DEAL_RESULT", settlement.phase());
    assertEquals(90_000, settlement.game().operationTimer().remainingMs());
    assertEquals(result.game().teamTotals(), settlement.game().teamTotals());
    assertEquals(result.game().completedDealResults().get(0).scores(), settlement.game().completedDealResults().get(0).scores());
    assertFalse(result.game().completedDealResults().get(0).completedTricks().get(0).cardsVisible());
    assertTrue(settlement.game().completedDealResults().get(0).completedTricks().get(0).cardsVisible());
  }

  @Test
  void finalTrickFirstDealExitPreservesCompletedScore() {
    reachFinalCard();
    playOne();
    var before = f.view(0);
    f.service.leave(f.room, f.players[0]);
    var after = f.view(1);
    assertEquals("MATCH_RESULT", after.phase());
    assertEquals("FORFEIT", after.game().matchResult().outcome());
    assertEquals(before.game().teamTotals(), after.game().teamTotals());
    assertEquals(before.game().completedDealResults().get(0).scores(), after.game().completedDealResults().get(0).scores());
    assertTrue(after.game().completedDealResults().get(0).completedTricks().get(0).cardsVisible());
    assertNull(after.game().operationTimer());
  }

  @Test
  void finalMatchIsLockedDuringResultDisplayAndOfflineCannotPauseIt() {
    for (int deal = 0; deal < 2; deal++) {
      f.playRemainingDeal();
      f.advanceResult();
      f.auctionToPlay();
    }
    reachFinalCard();
    playOne();
    var before = f.view(0);
    assertEquals("TRICK_RESULT", before.phase());
    assertEquals("SCORED", before.game().matchResult().outcome());
    assertFalse(before.canRematch());
    assertNotNull(before.lastMatch());
    assertThrows(RoomService.Failure.class, () -> f.service.rematch(
        f.room, f.players[0], before.game().gameId(), PlayFlowTest.action(), before.revision()));
    f.service.leave(f.room, f.players[0]);
    for (int seat = 1; seat < 4; seat++)
      f.service.disconnect(f.room, f.players[seat].playerId(), "socket-" + seat);
    var offline = f.view(1);
    assertEquals(3, offline.players().size());
    assertFalse(offline.game().paused());
    assertFalse(offline.game().operationTimer().paused());
    assertTrue(offline.players().stream().allMatch(p -> p.reconnectDeadline() == null));
    f.clock.now.addAndGet(2_000);
    var terminal = f.view(1);
    assertEquals("MATCH_RESULT", terminal.phase());
    assertEquals(before.game().matchResult(), terminal.game().matchResult());
    assertEquals(before.game().teamTotals(), terminal.game().teamTotals());
    assertEquals(before.lastMatch().teamTotals(), terminal.lastMatch().teamTotals());
    assertFalse(before.lastMatch().completedDealResults().get(2).completedTricks().get(0).cardsVisible());
    assertTrue(terminal.lastMatch().completedDealResults().get(2).completedTricks().get(0).cardsVisible());
    assertTrue(terminal.game().forfeitEvents().isEmpty());
    assertNull(terminal.game().operationTimer());
    assertTrue(f.service.connect(f.room, f.players[1], "returned").canRematch());
  }
}
