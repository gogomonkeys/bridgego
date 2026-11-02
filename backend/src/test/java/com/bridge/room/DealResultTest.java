package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class DealResultTest {
  PlayFlowTest f;

  @BeforeEach
  void setup() {
    f = new PlayFlowTest();
    f.setup();
    f.playing();
    f.playRemainingDeal();
  }

  RoomService.CommandResult ack(int seat) {
    var s = f.view(seat);
    return f.service.acknowledgeResult(
        f.room,
        f.players[seat],
        s.game().gameId(),
        s.game().dealId(),
        GameFlowTest.action(),
        s.revision());
  }

  @Test
  void resultHasNoNextCardsOrPrivilegeClock() {
    var s = f.view(0);
    assertEquals("DEAL_RESULT", s.phase());
    assertEquals(1, s.game().dealIndex());
    assertEquals(1, s.game().completedDeals());
    assertEquals(0, s.game().dealerSeat());
    assertEquals("DEAL_RESULT", s.game().operationTimer().kind());
    assertNull(s.game().operationTimer().turnSeat());
    assertEquals(90_000, s.game().operationTimer().remainingMs());
    for (int seat = 0; seat < 4; seat++) {
      var v = f.view(seat);
      assertTrue(v.you().ownHand().isEmpty());
      assertTrue(v.you().legalCards().isEmpty());
      assertTrue(v.you().legalCalls().isEmpty());
      assertTrue(v.you().canAcknowledgeResult());
    }
  }

  @Test
  void partialAndDuplicateAcknowledgmentsNeverResetBudget() {
    f.clock.now.addAndGet(5_000);
    ack(0);
    ack(0);
    var s = f.view(0);
    assertEquals(List.of(0), s.game().resultAcknowledgedSeats());
    assertFalse(s.you().canAcknowledgeResult());
    assertEquals(85_000, s.game().operationTimer().remainingMs());
    ack(1);
    ack(2);
    assertEquals("DEAL_RESULT", f.view(0).phase());
  }

  @Test
  void previousTwentySecondDeadlineDoesNotAdvanceSettlement() {
    var initial = f.view(0);
    f.clock.now.addAndGet(20_000);
    var waiting = f.view(0);
    assertEquals("DEAL_RESULT", waiting.phase());
    assertEquals(initial.game().dealId(), waiting.game().dealId());
    assertEquals(70_000, waiting.game().operationTimer().remainingMs());
    assertTrue(waiting.you().ownHand().isEmpty());
    assertTrue(waiting.you().canAcknowledgeResult());
  }

  @Test
  void secondSettlementGetsNinetySecondsButFinalMatchHasNoTimer() {
    f.advanceResult();
    f.auctionToPlay();
    f.playRemainingDeal();
    var second = f.view(0);
    assertEquals("DEAL_RESULT", second.phase());
    assertEquals(2, second.game().dealIndex());
    assertEquals(90_000, second.game().operationTimer().remainingMs());
    f.advanceResult();
    f.auctionToPlay();
    f.playRemainingDeal();
    var finalResult = f.view(0);
    assertEquals("MATCH_RESULT", finalResult.phase());
    assertNull(finalResult.game().operationTimer());
  }

  @Test
  void fourthAckAdvancesOnceAndRetryIsHarmless() {
    ack(0);
    ack(1);
    ack(2);
    var old = f.view(3);
    String id = GameFlowTest.action();
    var result =
        f.service.acknowledgeResult(
            f.room, f.players[3], old.game().gameId(), old.game().dealId(), id, old.revision());
    assertEquals("PRIVILEGE_DECISION", result.room().phase());
    assertEquals(2, result.room().game().dealIndex());
    assertEquals(1, result.room().game().dealerSeat());
    assertNotEquals(old.game().dealId(), result.room().game().dealId());
    assertEquals(13, result.room().you().ownHand().size());
    assertTrue(result.room().game().resultAcknowledgedSeats().isEmpty());
    var retry =
        f.service.acknowledgeResult(
            f.room, f.players[3], old.game().gameId(), old.game().dealId(), id, old.revision());
    assertEquals("REPLAYED", retry.outcome());
    assertEquals(result.appliedRevision(), retry.appliedRevision());
    assertEquals(result.room().game().dealId(), retry.room().game().dealId());
    assertEquals(1, retry.room().game().completedDeals());
  }

  @Test
  void deadlineWinsExactlyAndDelayedSweepDoesNotBurnNextBudget() {
    var old = f.view(0);
    f.clock.now.addAndGet(89_999);
    assertEquals("DEAL_RESULT", f.view(0).phase());
    f.clock.now.incrementAndGet();
    assertThrows(
        RoomService.Failure.class,
        () ->
            f.service.acknowledgeResult(
                f.room,
                f.players[0],
                old.game().gameId(),
                old.game().dealId(),
                GameFlowTest.action(),
                old.revision()));
    assertEquals("PRIVILEGE_DECISION", f.view(0).phase());
    assertEquals(20_000, f.view(0).game().operationTimer().remainingMs());
  }

  @Test
  void largeClockAdvanceOnlyCreatesNextPrivilegePhase() {
    f.clock.now.addAndGet(100_000);
    var s = f.view(0);
    assertEquals("PRIVILEGE_DECISION", s.phase());
    assertEquals(20_000, s.game().operationTimer().remainingMs());
    assertEquals(1, s.game().completedDeals());
  }

  @Test
  void offlinePausesAcknowledgmentAndResumesRemaining() {
    f.clock.now.addAndGet(6_000);
    ack(1);
    f.service.disconnect(f.room, f.players[0].playerId(), "socket-0");
    f.clock.now.addAndGet(9_000);
    assertEquals(84_000, f.view(1).game().operationTimer().remainingMs());
    assertFalse(f.view(1).you().canAcknowledgeResult());
    assertThrows(RoomService.Failure.class, () -> ack(2));
    f.service.connect(f.room, f.players[0], "returned");
    f.service.disconnect(f.room, f.players[0].playerId(), "socket-0");
    assertEquals(List.of(1), f.view(0).game().resultAcknowledgedSeats());
    f.clock.now.addAndGet(84_000);
    assertEquals("PRIVILEGE_DECISION", f.view(0).phase());
  }

  @Test
  void graceExpiryDuringResultPreservesOnlyCompletedScore() {
    var before = f.view(0);
    f.service.disconnect(f.room, f.players[0].playerId(), "socket-0");
    f.clock.now.addAndGet(10_000);
    var s = f.view(1);
    assertEquals("MATCH_RESULT", s.phase());
    assertEquals("FORFEIT", s.game().matchResult().outcome());
    assertEquals(before.game().completedDealResults(), s.game().completedDealResults());
    assertEquals(before.game().teamTotals(), s.game().teamTotals());
    assertEquals(before.game().dealId(), s.game().dealId());
    assertNull(s.game().operationTimer());
  }

  @Test
  void concurrentFourthAckAndDeadlineAdvanceOnlyOnce() throws Exception {
    ack(0);
    ack(1);
    ack(2);
    var old = f.view(3);
    f.clock.now.addAndGet(90_000);
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () -> {
                try {
                  gate.await();
                  f.service.expireInitialSubscriptions();
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              });
      var b =
          pool.submit(
              () -> {
                try {
                  gate.await();
                  f.service.acknowledgeResult(
                      f.room,
                      f.players[3],
                      old.game().gameId(),
                      old.game().dealId(),
                      GameFlowTest.action(),
                      old.revision());
                } catch (RoomService.Failure expected) {
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              });
      gate.countDown();
      a.get();
      b.get();
      assertEquals(2, f.view(0).game().dealIndex());
      assertEquals(1, f.view(0).game().completedDeals());
      assertEquals(20_000, f.view(0).game().operationTimer().remainingMs());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void outsiderCannotAcknowledge() {
    var s = f.view(0);
    var outsider = f.service.createSession("other");
    assertThrows(
        RoomService.Failure.class,
        () ->
            f.service.acknowledgeResult(
                f.room,
                outsider,
                s.game().gameId(),
                s.game().dealId(),
                GameFlowTest.action(),
                s.revision()));
    assertTrue(f.view(0).game().resultAcknowledgedSeats().isEmpty());
  }
}
