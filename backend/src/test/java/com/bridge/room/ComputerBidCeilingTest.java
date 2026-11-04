package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerBidCeilingTest {
  static List<GameState.Card> hand(String target, int length, int aces) {
    var cards = new ArrayList<GameState.Card>();
    for (int rank = 2; rank < 2 + length; rank++) cards.add(new GameState.Card(target, rank));
    var suits = new ArrayList<>(GameState.STRAINS.subList(0, 4));
    suits.remove(target);
    suits.add(target); // For 1–3 aces, all aces are outside the selected suit.
    for (int i = 0; i < aces; i++) {
      String suit = suits.get(i);
      if (suit.equals(target)) cards.remove(length - 1);
      cards.add(new GameState.Card(suit, 14));
    }
    for (int rank = 2; cards.size() < 13; rank++)
      for (String suit : suits.subList(0, 3))
        if (cards.size() < 13) cards.add(new GameState.Card(suit, rank));
    assertEquals(13, cards.size());
    assertEquals(13, new HashSet<>(cards).size());
    assertEquals(length, cards.stream().filter(c -> c.suit().equals(target)).count());
    assertEquals(aces, cards.stream().filter(c -> c.rank() == 14).count());
    return cards;
  }

  @Test void everySeatSuitLengthAceCountAndHighestBidUsesMinimumLegalCallWithinCeiling() {
    for (int seat = 0; seat < 4; seat++) for (String target : GameState.STRAINS.subList(0, 4))
      for (int length : new int[]{4, 5, 6, 7}) for (int aces = 0; aces <= 4; aces++) {
        var hand = hand(target, length, aces);
        assertEquals(new GameState.LegalCall("BID", 1, target), ComputerStrategy.call(seat, target, hand, null));
        int ceiling = aces >= 2 && length >= 6 ? 4 : aces >= 2 && length >= 5 ? 3 : aces >= 2 ? 2 : 1;
        for (int bidder = 0; bidder < 4; bidder++) for (int level = 1; level <= 7; level++)
          for (String strain : GameState.STRAINS) {
            var highest = new GameState.Bid(bidder, level, strain);
            var expected = new GameState.LegalCall("PASS", null, null);
            if (bidder % 2 != seat % 2)
              for (int candidate = 1; candidate <= ceiling; candidate++)
                if (candidate > level || candidate == level && GameState.STRAINS.indexOf(target) > GameState.STRAINS.indexOf(strain)) {
                  expected = new GameState.LegalCall("BID", candidate, target); break;
                }
            assertEquals(expected, ComputerStrategy.call(seat, target, hand, highest),
                "seat=" + seat + " target=" + target + " length=" + length + " aces=" + aces + " highest=" + highest);
          }
      }
  }

  static List<GameState.Card> pointHand(String target, int length, int points, boolean ace) {
    var cards = new ArrayList<>(hand(target, length, 0));
    int[] honors = ace ? switch (points) {
      case 7 -> new int[]{14, 13};
      case 8 -> new int[]{14, 13, 11};
      case 9 -> new int[]{14, 13, 12};
      default -> throw new IllegalArgumentException();
    } : switch (points) {
      case 7 -> new int[]{13, 12, 11, 11};
      case 8 -> new int[]{13, 12, 12, 11};
      case 9 -> new int[]{13, 13, 12, 11};
      default -> throw new IllegalArgumentException();
    };
    for (int rank : honors) {
      for (int i = cards.size() - 1; i >= 0; i--) {
        var card = cards.get(i);
        var replacement = new GameState.Card(card.suit(), rank);
        if (card.rank() <= 10 && !cards.contains(replacement)) {
          cards.set(i, replacement); break;
        }
      }
    }
    assertEquals(13, new HashSet<>(cards).size());
    assertEquals(points, GameState.highCardPoints(cards));
    assertEquals(ace ? 1 : 0, cards.stream().filter(c -> c.rank() == 14).count());
    return cards;
  }

  @Test void sevenEightNinePointBoundaryWithoutTwoAcesAcrossEveryAuction() {
    for (int seat = 0; seat < 4; seat++) for (String target : GameState.STRAINS.subList(0, 4))
      for (int length : new int[]{4, 5, 6, 7}) for (int points : new int[]{7, 8, 9})
        for (boolean ace : new boolean[]{false, true}) {
          var hand = pointHand(target, length, points, ace);
          assertEquals(new GameState.LegalCall("BID", 1, target), ComputerStrategy.call(seat, target, hand, null));
          for (int bidder = 0; bidder < 4; bidder++) for (int level = 1; level <= 7; level++)
            for (String strain : GameState.STRAINS) {
              var highest = new GameState.Bid(bidder, level, strain);
              var expected = new GameState.LegalCall("PASS", null, null);
              if (bidder % 2 != seat % 2)
                for (int candidate = 1; candidate <= (points >= 8 ? 2 : 1); candidate++)
                  if (candidate > level || candidate == level && GameState.STRAINS.indexOf(target) > GameState.STRAINS.indexOf(strain)) {
                    expected = new GameState.LegalCall("BID", candidate, target); break;
                  }
              assertEquals(expected, ComputerStrategy.call(seat, target, hand, highest),
                  "seat=" + seat + " points=" + points + " ace=" + ace + " length=" + length + " highest=" + highest);
            }
        }
  }

  @Test void selectedSuitCountNotAnotherLongSuitControlsCeiling() {
    var hand = hand("HEART", 5, 2);
    assertEquals("PASS", ComputerStrategy.call(0, "SPADE", hand, new GameState.Bid(1, 2, "NO_TRUMP")).type());
    assertEquals(new GameState.LegalCall("BID", 3, "HEART"),
        ComputerStrategy.call(0, "HEART", hand, new GameState.Bid(1, 2, "NO_TRUMP")));
  }
}
