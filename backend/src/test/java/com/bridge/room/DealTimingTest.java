package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Deterministic monotonic timing only; no sleeps or external services. */
class DealTimingTest {
  static void prepare(GameState game) {
    KeepDealFixture.keep(game);
    for (int seat = 0; seat < 4; seat++) game.choose(seat, false);
    int dealer = game.view(0, false).dealerSeat();
    game.call(dealer, "BID", 1, "NO_TRUMP");
    for (int offset = 1; offset < 4; offset++) game.call((dealer + offset) % 4, "PASS", null, null);
    assertEquals("PLAYING", game.phase);
  }
  static GameState.DealResultView finish(GameState game, AtomicLong tick) {
    int wanted = game.view(0, false).completedDeals() + 1;
    for (int step = 0; step < 100; step++) {
      if (game.phase.equals("TRICK_RESULT")) {
        tick.addAndGet(GameState.TRICK_RESULT_MS); assertTrue(game.expireOperation());
      } else {
        int seat = game.view(0, false).play().nextSeat();
        tick.addAndGet(13); game.play(seat, game.legalCards(seat, false).get(0));
      }
      var results = game.view(0, false).completedDealResults();
      if (results.size() == wanted) return results.get(wanted - 1);
    }
    throw new AssertionError("Deal never finished");
  }

  @Test void eachOneThreeFiveDealDurationExcludesFinalDisplayAndInterDealWait() throws Exception {
    for (int planned : List.of(1, 3, 5)) {
      var tick = new AtomicLong(1000); var random = SecureRandom.getInstance("SHA1PRNG"); random.setSeed(44);
      var game = new GameState(random, tick::get, planned);
      long started = tick.get(); var durations = new ArrayList<Long>();
      for (int index = 1; index <= planned; index++) {
        tick.addAndGet(321); prepare(game);
        var result = finish(game, tick); long expected = tick.get() - started;
        assertEquals(expected, result.elapsedMs()); assertEquals(24997, expected);
        assertEquals(index, result.dealIndex()); durations.add(expected);
        tick.addAndGet(2000); assertTrue(game.expireOperation());
        assertEquals(expected, game.view(0, false).completedDealResults().get(index - 1).elapsedMs());
        if (index < planned) {
          assertEquals("DEAL_RESULT", game.phase);
          tick.addAndGet(17_000);
          for (int seat = 0; seat < 4; seat++) game.acknowledge(seat);
          started = tick.get();
        }
      }
      tick.addAndGet(999_999);
      assertEquals(durations, game.archive().completedDealResults().stream().map(GameState.DealResultView::elapsedMs).toList());
    }
  }

  @Test void handRedealAndAllPassKeepOriginalRoundStart() throws Exception {
    var tick = new AtomicLong(1000); var fixture = new RedealTest(); var game = fixture.gameWithEligible(tick);
    long started = tick.get(); String firstId = game.dealId;
    tick.addAndGet(4000); game.chooseRedeal(fixture.eligible(game), true);
    assertNotEquals(firstId, game.dealId);
    KeepDealFixture.keep(game); for (int seat = 0; seat < 4; seat++) game.choose(seat, false);
    tick.addAndGet(7000); for (int seat = 0; seat < 4; seat++) game.call(seat, "PASS", null, null);
    assertEquals(1, game.view(0, false).dealIndex()); assertEquals(2, game.redealCount);
    tick.addAndGet(3000); prepare(game); var result = finish(game, tick);
    assertEquals(tick.get() - started, result.elapsedMs()); assertEquals(38676, result.elapsedMs());
  }

  @Test void pausedOperationKeepsItsBudgetButElapsedIncludesPause() throws Exception {
    var tick = new AtomicLong(1000); var random = SecureRandom.getInstance("SHA1PRNG"); random.setSeed(88);
    var game = new GameState(random, tick::get, 1); prepare(game);
    tick.addAndGet(1000); game.pauseTimer(); long remaining = game.view(0, true).operationTimer().remainingMs();
    tick.addAndGet(8000); assertFalse(game.expireOperation());
    assertEquals(remaining, game.view(0, true).operationTimer().remainingMs());
    game.resumeTimer(); var result = finish(game, tick);
    assertEquals(tick.get() - 1000, result.elapsedMs()); assertEquals(33676, result.elapsedMs());
  }

  @Test void abortDoesNotInventDurationForAnUnfinishedDeal() throws Exception {
    var tick = new AtomicLong(); var random = SecureRandom.getInstance("SHA1PRNG"); random.setSeed(88);
    var game = new GameState(random, tick::get, 3); prepare(game); var first = finish(game, tick);
    tick.addAndGet(2000); game.expireOperation();
    for (int seat = 0; seat < 4; seat++) game.acknowledge(seat);
    tick.addAndGet(6000); game.finishWithoutHumans("NO_HUMANS_CONNECTED");
    assertEquals("NO_CONTEST", game.archive().matchResult().outcome());
    assertEquals(1, game.archive().completedDealResults().size());
    assertEquals(first.elapsedMs(), game.archive().completedDealResults().get(0).elapsedMs());
  }

  @Test void formalOneThreeFiveDealsPersistEachCompletedDuration() {
    for (int planned : List.of(1, 3, 5)) {
      var f = new PlayFlowTest(); f.clock = new RoomServiceTest.MutableClock();
      f.clock.now.set(Instant.parse("2026-11-10T00:00:00Z").toEpochMilli());
      f.service = new RoomService(f.clock); var store = new MatchPersistenceTest.RecordingStore();
      f.service.statsStore(store); f.service.experimentPolicy(ManagedCollectionTest.policy()); f.service.statisticsPolicy(true, true);
      f.players = new RoomService.Identity[4];
      for (int seat = 0; seat < 4; seat++) {
        f.players[seat] = f.service.createSession("T" + seat);
        f.service.statisticsConsent(f.players[seat], true, StatisticsService.CONSENT_VERSION);
      }
      f.room = f.service.create(f.players[0], planned, "FORMAL").roomId();
      for (int seat = 0; seat < 4; seat++) {
        if (seat > 0) f.service.join(f.room, f.players[seat]);
        f.service.connect(f.room, f.players[seat], "socket-" + seat); f.service.ready(f.room, f.players[seat], true);
      }
      f.start();
      for (int index = 0; index < planned; index++) {
        f.auctionToPlay(); f.playRemainingDeal();
        if (index < planned - 1) { f.clock.now.addAndGet(5000); f.advanceResult(); }
      }
      assertEquals(1, store.writes.size()); var saved = store.writes.get(0);
      assertEquals("FORMAL", saved.statisticsCategory());
      assertEquals("elapsed-including-pauses-v1", saved.payload().path("match").path("deal_timing_rule").asText());
      assertEquals(planned, saved.payload().path("completed_deals").size());
      for (int index = 0; index < planned; index++) {
        assertEquals(24000L, f.view(0).game().completedDealResults().get(index).elapsedMs());
        assertEquals(24000L, saved.payload().path("completed_deals").get(index).path("elapsed_ms").asLong());
      }
    }
  }

  @Test void roomUsesMonotonicTimingDespiteWallClockJumpAndFreezesSavedRows() {
    var tick = new AtomicLong(1000); var f = new PlayFlowTest();
    f.clock = new RoomServiceTest.MutableClock(); f.clock.now.set(Instant.parse("2026-11-10T00:00:00Z").toEpochMilli());
    f.service = new RoomService(f.clock, tick::get); var store = new MatchPersistenceTest.RecordingStore();
    f.service.statsStore(store); f.service.experimentPolicy(ManagedCollectionTest.policy()); f.service.statisticsPolicy(true, true);
    f.players = new RoomService.Identity[4];
    for (int seat = 0; seat < 4; seat++) {
      f.players[seat] = f.service.createSession("T" + seat);
      f.service.statisticsConsent(f.players[seat], true, StatisticsService.CONSENT_VERSION);
    }
    f.room = f.service.create(f.players[0], 1, "FORMAL").roomId();
    for (int seat = 0; seat < 4; seat++) {
      if (seat > 0) f.service.join(f.room, f.players[seat]);
      f.service.connect(f.room, f.players[seat], "socket-" + seat); f.service.ready(f.room, f.players[seat], true);
    }
    f.start(); long started = tick.get(); f.auctionToPlay();
    f.service.disconnect(f.room, f.players[1].playerId(), "socket-1");
    tick.addAndGet(4000); f.service.connect(f.room, f.players[1], "restored");
    f.clock.now.addAndGet(86_400_000); // Does not advance the independently injected operation clock.
    for (int step = 0; step < 100 && f.view(0).game().completedDeals() == 0; step++) {
      if (f.view(0).phase().equals("TRICK_RESULT")) {
        tick.addAndGet(2000); f.service.expireInitialSubscriptions();
      } else {
        int seat = f.view(0).game().play().nextSeat(); var s = f.view(seat); tick.addAndGet(13);
        f.play(seat, s.you().legalCards().get(0), PlayFlowTest.action(), s.revision(), s.game().dealId());
      }
    }
    assertEquals(1, store.writes.size()); var saved = store.writes.get(0);
    long elapsed = tick.get() - started;
    assertEquals(28676, elapsed);
    assertEquals(elapsed, f.view(0).game().completedDealResults().get(0).elapsedMs());
    assertEquals("elapsed-including-pauses-v1", saved.payload().path("match").path("deal_timing_rule").asText());
    assertEquals(elapsed, saved.payload().path("completed_deals").get(0).path("elapsed_ms").asLong());
    tick.addAndGet(20_000); f.service.expireInitialSubscriptions();
    f.service.disconnect(f.room, f.players[1].playerId(), "restored");
    tick.addAndGet(20_000); f.service.connect(f.room, f.players[1], "late");
    assertEquals(List.of(saved), store.writes);
    assertEquals(elapsed, f.view(0).game().completedDealResults().get(0).elapsedMs());
  }
}
