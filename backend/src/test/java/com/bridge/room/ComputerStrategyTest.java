package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerStrategyTest {
  GameState.Card c(String suit, int rank) { return new GameState.Card(suit, rank); }
  TrickEngine.Play p(String suit, int rank) { return new TrickEngine.Play(0, c(suit, rank), "MANUAL"); }

  @Test void leadRandomHeldSuitThenHighest() {
    var hand = List.of(c("CLUB", 2), c("CLUB", 14), c("HEART", 6));
    Set<GameState.Card> seen = new HashSet<>();
    Random random = new Random(1);
    for (int i = 0; i < 100; i++) seen.add(ComputerStrategy.play(1, hand, List.of(), "HEART", random));
    assertEquals(Set.of(c("CLUB", 14), c("HEART", 6)), seen);
  }
  @Test void followSmallestWhenTrumpAlreadyWins() {
    var hand = List.of(c("CLUB", 2), c("CLUB", 14), c("HEART", 13));
    assertEquals(c("CLUB", 2), ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 10), p("HEART", 9)), "HEART", new Random()));
  }
  @Test void cutWithSmallestThatBeatsAllTrumpsNotJustLastCard() {
    var hand = List.of(c("HEART", 3), c("HEART", 10), c("HEART", 14), c("SPADE", 2));
    assertEquals(c("HEART", 10), ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 12), p("HEART", 9), p("HEART", 4)), "HEART", new Random()));
    assertEquals(c("HEART", 3), ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 12)), "HEART", new Random()));
  }
  @Test void fallbackUsesGlobalMinimumAndRandomTiesAlsoForNoTrump() {
    var hand = List.of(c("HEART", 2), c("SPADE", 2), c("DIAMOND", 5));
    Set<GameState.Card> seen = new HashSet<>();
    var random = new Random(2);
    for (int i = 0; i < 100; i++) seen.add(ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 9), p("HEART", 14)), "HEART", random));
    assertEquals(Set.of(c("HEART", 2), c("SPADE", 2)), seen);
    for (int i = 0; i < 100; i++) assertEquals(2, ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 9)), "NO_TRUMP", random).rank());
  }
  @Test void losingFollowUsesSmallestButWinningFollowStillUsesLargest() {
    var hand = List.of(c("CLUB", 2), c("CLUB", 10), c("HEART", 3));
    assertEquals(c("CLUB", 2), ComputerStrategy.play(1, hand, List.of(p("CLUB", 14)), "NO_TRUMP", new Random()));
    assertEquals(c("CLUB", 10), ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 9), p("SPADE", 14)), "NO_TRUMP", new Random()));
  }
  @Test void fourthPartnerWinningFollowsSmallestForEverySeat() {
    for (int seat = 0; seat < 4; seat++) {
      var table = List.of(new TrickEngine.Play((seat+3)%4,c("CLUB",3),"BOT"),
          new TrickEngine.Play((seat+2)%4,c("CLUB",10),"BOT"),
          new TrickEngine.Play((seat+1)%4,c("SPADE",14),"BOT"));
      assertEquals(c("CLUB",2), ComputerStrategy.play(seat,
          List.of(c("CLUB",2),c("CLUB",14),c("HEART",3)),table,"HEART",new Random()));
    }
  }
  @Test void fourthPartnerWinningVoidPrefersNonTrumpExceptWhenOnlyTrumpsRemain() {
    var table = List.of(new TrickEngine.Play(3,c("CLUB",14),"BOT"),
        new TrickEngine.Play(2,c("HEART",5),"BOT"),new TrickEngine.Play(1,c("CLUB",12),"BOT"));
    assertEquals(c("SPADE",2), ComputerStrategy.play(0,
        List.of(c("SPADE",2),c("HEART",7)),table,"HEART",new Random()));
    assertEquals(c("HEART",7), ComputerStrategy.play(0,
        List.of(c("HEART",7),c("HEART",14)),table,"HEART",new Random()));
    // Preserve partner's trick rather than cut with a lower-ranked trump.
    assertEquals(c("SPADE",9), ComputerStrategy.play(0,
        List.of(c("HEART",2),c("SPADE",9)),table,"HEART",new Random()));
  }
  @Test void fourthOpponentWinningStillCutsAndEarlierPartnerWinningStillPlaysLargest() {
    var table = List.of(new TrickEngine.Play(3,c("CLUB",14),"BOT"),
        new TrickEngine.Play(2,c("CLUB",13),"BOT"),new TrickEngine.Play(1,c("HEART",5),"BOT"));
    assertEquals(c("HEART",7), ComputerStrategy.play(0,
        List.of(c("SPADE",2),c("HEART",7),c("HEART",14)),table,"HEART",new Random()));
    assertEquals(c("CLUB",14), ComputerStrategy.play(0,
        List.of(c("CLUB",2),c("CLUB",14)),List.of(new TrickEngine.Play(2,c("CLUB",10),"BOT")),"HEART",new Random()));
  }
  @Test void fourthPartnerWinningMinimumTiesRemainRandom() {
    var table = List.of(new TrickEngine.Play(3,c("CLUB",9),"BOT"),
        new TrickEngine.Play(2,c("CLUB",14),"BOT"),new TrickEngine.Play(1,c("SPADE",13),"BOT"));
    Set<GameState.Card> seen = new HashSet<>();
    var random = new Random(3);
    for (int i=0;i<100;i++) seen.add(ComputerStrategy.play(0,
        List.of(c("HEART",2),c("SPADE",2),c("DIAMOND",9)),table,"NO_TRUMP",random));
    assertEquals(Set.of(c("HEART",2),c("SPADE",2)),seen);
  }
  @Test void longestSuitOnlyAndTiesRandom() {
    var hand = List.of(c("CLUB", 2), c("CLUB", 8), c("SPADE", 4), c("SPADE", 5), c("HEART", 14));
    Set<String> seen = new HashSet<>();
    var random = new Random(3);
    for (int i = 0; i < 100; i++) seen.add(ComputerStrategy.longestSuit(hand, random));
    assertEquals(Set.of("CLUB", "SPADE"), seen);
  }
  @Test void minimumLegalLevelUpToTwoAndPartnersPassAllSeats() {
    for (int seat = 0; seat < 4; seat++) {
      assertEquals(new GameState.LegalCall("BID", 1, "HEART"), ComputerStrategy.call(seat, "HEART", null));
      assertEquals(new GameState.LegalCall("BID", 1, "HEART"), ComputerStrategy.call(seat, "HEART", new GameState.Bid((seat+1)%4,1,"CLUB")));
      assertEquals(new GameState.LegalCall("BID", 2, "HEART"), ComputerStrategy.call(seat, "HEART", new GameState.Bid((seat+1)%4,1,"SPADE")));
      assertEquals("PASS", ComputerStrategy.call(seat,"HEART",new GameState.Bid((seat+1)%4,2,"SPADE")).type());
      assertEquals("PASS", ComputerStrategy.call(seat,"SPADE",new GameState.Bid((seat+2)%4,1,"CLUB")).type());
      assertEquals("PASS", ComputerStrategy.call(seat,"SPADE",new GameState.Bid((seat+1)%4,7,"NO_TRUMP")).type());
    }
  }
}
