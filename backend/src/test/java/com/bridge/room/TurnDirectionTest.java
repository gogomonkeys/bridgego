package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TurnDirectionTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void auctionMovesLeftButOpeningAndAllPlayMoveRight(int bidder) {
    var clock = new RoomServiceTest.MutableClock();
    var game = new GameState(new SecureRandom(), clock::millis);
    KeepDealFixture.keep(game);
    for (int seat = 0; seat < 4; seat++) game.choose(seat, false);
    assertEquals(0, game.view(0, false).dealerSeat());
    for (int seat = 0; seat < bidder; seat++) {
      assertEquals(seat, game.view(0, false).auction().nextSeat());
      game.call(seat, "PASS", null, null);
    }
    game.call(bidder, "BID", 1, "NO_TRUMP");
    for (int offset = 1; offset <= 3; offset++) {
      int expected = (bidder + offset) % 4;
      assertEquals(expected, game.view(0, false).auction().nextSeat());
      game.call(expected, "PASS", null, null);
    }
    int leader = (bidder + 3) % 4;
    for (int trick = 1; trick <= 13; trick++) {
      assertEquals("PLAYING", game.phase);
      assertEquals(leader, game.view(0, false).play().leaderSeat());
      for (int offset = 0; offset < 4; offset++) {
        int actor = (leader + 3 * offset) % 4;
        assertEquals(actor, game.view(0, false).play().nextSeat());
        int wrong = (actor + 1) % 4;
        assertEquals("NOT_YOUR_TURN", assertThrows(RoomService.Failure.class,
            () -> game.play(wrong, game.legalCards(actor, false).get(0))).code);
        if (offset % 2 == 0) game.play(actor, game.legalCards(actor, false).get(0));
        else {
          clock.now.addAndGet(20_000);
          assertTrue(game.expireOperation());
        }
      }
      assertEquals("TRICK_RESULT", game.phase);
      var result = game.view(0, false).play().currentTrick();
      assertEquals(List.of(leader, (leader + 3) % 4, (leader + 2) % 4, (leader + 1) % 4),
          result.stream().map(TrickEngine.Play::seat).toList());
      // The result view exposes the winner without revealing previous hidden card faces.
      leader = game.view(0, false).play().completedTricks().get(trick - 1).winnerSeat();
      clock.now.addAndGet(GameState.TRICK_RESULT_MS);
      assertTrue(game.expireOperation());
    }
    assertEquals("DEAL_RESULT", game.phase);
  }
}
