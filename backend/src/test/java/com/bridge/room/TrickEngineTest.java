package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TrickEngineTest {
  GameState.Card c(String suit, int rank) {
    return new GameState.Card(suit, rank);
  }

  Map<Integer, List<GameState.Card>> hands(Map<Integer, List<GameState.Card>> forced) {
    var result = new HashMap<Integer, List<GameState.Card>>();
    var used = new HashSet<GameState.Card>();
    for (int i = 0; i < 4; i++) {
      result.put(i, new ArrayList<>(forced.getOrDefault(i, List.of())));
      used.addAll(result.get(i));
    }
    for (String suit : GameState.STRAINS.subList(0, 4))
      for (int rank = 2; rank <= 14; rank++) {
        var card = c(suit, rank);
        if (used.contains(card)) continue;
        for (int i = 0; i < 4; i++)
          if (result.get(i).size() < 13) {
            result.get(i).add(card);
            break;
          }
      }
    return result;
  }

  TrickEngine engine(String strain, Map<Integer, List<GameState.Card>> forced) {
    return new TrickEngine(hands(forced), 1, strain);
  }

  @Test
  void rightOfBidderAndRightwardForEverySeat() {
    for (int bidder = 0; bidder < 4; bidder++) {
      var e = new TrickEngine(hands(Map.of()), bidder, "NO_TRUMP");
      for (int offset = 1; offset <= 4; offset++) {
        int expected = (bidder + 3 * offset) % 4;
        assertEquals(expected, e.nextSeat());
        assertEquals((bidder + 3) % 4, e.leaderSeat());
        e.timeout();
      }
      var completed = e.completedTricks().get(0);
      assertEquals(completed.winnerSeat(), e.nextSeat());
      e.timeout();
      assertEquals((completed.winnerSeat() + 3) % 4, e.nextSeat());
    }
  }

  @Test
  void mustFollowSuitEvenHoldingTrump() {
    var e =
        engine(
            "SPADE", Map.of(0, List.of(c("HEART", 2)), 3, List.of(c("HEART", 3), c("SPADE", 14))));
    e.play(0, c("HEART", 2));
    assertTrue(e.legalCards(3).stream().allMatch(c -> c.suit().equals("HEART")));
    assertThrows(IllegalArgumentException.class, () -> e.play(3, c("SPADE", 14)));
    assertEquals(13, e.hand(3).size());
    e.play(3, c("HEART", 3));
    assertEquals(12, e.hand(3).size());
  }

  @Test
  void highestLedSuitWinsAndWinnerLeads() {
    var e =
        engine(
            "NO_TRUMP",
            Map.of(
                0,
                List.of(c("HEART", 10)),
                1,
                List.of(c("HEART", 14)),
                2,
                List.of(c("HEART", 13)),
                3,
                List.of(c("HEART", 2))));
    e.play(0, c("HEART", 10));
    e.play(3, c("HEART", 2));
    e.play(2, c("HEART", 13));
    e.play(1, c("HEART", 14));
    assertEquals(1, e.nextSeat());
    assertEquals(1, e.completedTricks().get(0).winnerSeat());
    assertEquals(1, e.tricksByTeam().get("EW"));
    assertEquals(2, e.trickNumber());
  }

  @Test
  void trumpBeatsLedAceAndHighestTrumpWins() {
    // Whole-suit hands guarantee the nonleaders are void in clubs.
    var h = new HashMap<Integer, List<GameState.Card>>();
    for (int seat = 0; seat < 4; seat++) {
      var list = new ArrayList<GameState.Card>();
      for (int r = 2; r <= 14; r++) list.add(c(GameState.STRAINS.get(seat), r));
      h.put(seat, list);
    }
    var e = new TrickEngine(h, 1, "SPADE");
    e.play(0, c("CLUB", 14));
    e.play(3, c("SPADE", 2));
    e.play(2, c("HEART", 14));
    e.play(1, c("DIAMOND", 14));
    assertEquals(3, e.nextSeat());
    var nt = new TrickEngine(h, 1, "NO_TRUMP");
    nt.play(0, c("CLUB", 2));
    nt.play(3, c("SPADE", 14));
    nt.play(2, c("HEART", 14));
    nt.play(1, c("DIAMOND", 14));
    assertEquals(0, nt.nextSeat());
  }

  @Test
  void highestOfMultipleTrumps() {
    // Lead spades, so following spades also tests highest trump selection.
    var e =
        engine(
            "SPADE",
            Map.of(
                0,
                List.of(c("SPADE", 2)),
                1,
                List.of(c("SPADE", 13)),
                2,
                List.of(c("SPADE", 14)),
                3,
                List.of(c("SPADE", 3))));
    e.play(0, c("SPADE", 2));
    e.play(3, c("SPADE", 3));
    e.play(2, c("SPADE", 14));
    e.play(1, c("SPADE", 13));
    assertEquals(2, e.nextSeat());
  }

  @Test
  void wrongSeatForeignCardAndDuplicatePlayRejected() {
    var e = engine("NO_TRUMP", Map.of());
    var card = e.hand(0).get(0);
    assertThrows(IllegalArgumentException.class, () -> e.play(1, card));
    assertThrows(IllegalArgumentException.class, () -> e.play(0, e.hand(1).get(0)));
    e.play(0, card);
    assertThrows(IllegalArgumentException.class, () -> e.play(0, card));
    assertEquals(1, e.currentTrick().size());
  }

  @Test
  void timeoutLegalLowestRankThenSuitAndImmutableViews() {
    var e = engine("NO_TRUMP", Map.of(0, List.of(c("SPADE", 2), c("HEART", 2))));
    e.timeout();
    assertEquals(c("CLUB", 2), e.currentTrick().get(0).card());
    assertEquals("TIMEOUT", e.currentTrick().get(0).source());
    assertThrows(UnsupportedOperationException.class, () -> e.hand(0).clear());
    assertThrows(UnsupportedOperationException.class, () -> e.currentTrick().clear());
  }

  @Test
  void all52TimeoutPlaysProduce13TricksNoDuplicates() {
    for (String strain : GameState.STRAINS) {
      var e = engine(strain, Map.of());
      for (int i = 0; i < 52; i++) e.timeout();
      assertTrue(e.finished());
      assertNull(e.nextSeat());
      assertEquals(13, e.completedTricks().size());
      assertEquals(
          52,
          e.completedTricks().stream()
              .flatMap(t -> t.plays().stream())
              .map(TrickEngine.Play::card)
              .distinct()
              .count());
      assertEquals(13, e.tricksByTeam().values().stream().mapToInt(Integer::intValue).sum());
      for (int seat = 0; seat < 4; seat++) {
        assertTrue(e.hand(seat).isEmpty());
        assertTrue(e.legalCards(seat).isEmpty());
      }
      assertThrows(IllegalStateException.class, e::timeout);
    }
  }

  @Test
  void invalidDealAndContractRejected() {
    assertThrows(IllegalArgumentException.class, () -> new TrickEngine(hands(Map.of()), 4, "CLUB"));
    assertThrows(IllegalArgumentException.class, () -> new TrickEngine(hands(Map.of()), 0, null));
    assertThrows(IllegalArgumentException.class, () -> new TrickEngine(Map.of(), 0, "CLUB"));
    var h = hands(Map.of());
    h.get(1).set(0, h.get(0).get(0));
    assertThrows(IllegalArgumentException.class, () -> new TrickEngine(h, 0, "CLUB"));
  }
}
