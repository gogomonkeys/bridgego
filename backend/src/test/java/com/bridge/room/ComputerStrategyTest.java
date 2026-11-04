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
  @Test void fallbackPrefersMinimumNonTrumpAndRandomTiesAlsoForNoTrump() {
    var hand = List.of(c("HEART", 2), c("SPADE", 5), c("DIAMOND", 5));
    Set<GameState.Card> seen = new HashSet<>();
    var random = new Random(2);
    for (int i = 0; i < 100; i++) seen.add(ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 9), p("HEART", 14)), "HEART", random));
    assertEquals(Set.of(c("SPADE", 5), c("DIAMOND", 5)), seen);
    for (int i = 0; i < 100; i++) assertEquals(2, ComputerStrategy.play(1, hand,
        List.of(p("CLUB", 9)), "NO_TRUMP", random).rank());
  }
  @Test void opponentWinningCannotOvertrumpDiscardsNonTrumpEvenWhenHigherRank() {
    var table = List.of(new TrickEngine.Play(1,c("DIAMOND",6),"BOT"),
        new TrickEngine.Play(0,c("DIAMOND",10),"BOT"),new TrickEngine.Play(3,c("SPADE",8),"BOT"));
    assertEquals(c("CLUB",9), ComputerStrategy.play(2,
        List.of(c("SPADE",3),c("CLUB",9)),table,"SPADE",new Random()));
    assertEquals(c("SPADE",3), ComputerStrategy.play(2,
        List.of(c("SPADE",3),c("SPADE",5)),table,"SPADE",new Random()));
    assertEquals(c("DIAMOND",2), ComputerStrategy.play(2,
        List.of(c("DIAMOND",2),c("DIAMOND",14),c("CLUB",9)),table,"SPADE",new Random()));
    assertEquals(c("SPADE",9), ComputerStrategy.play(2,
        List.of(c("SPADE",3),c("SPADE",9),c("SPADE",14),c("CLUB",2)),table,"SPADE",new Random()));
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
  List<TrickEngine.Play> thirdPlayerTable(int seat, String suit, int partnerRank, int opponentRank) {
    return List.of(new TrickEngine.Play((seat+2)%4,c(suit,partnerRank),"BOT"),
        new TrickEngine.Play((seat+1)%4,c(suit,opponentRank),"BOT"));
  }
  @Test void thirdPartnerTrumpKingPreservesAceAndFollowsSmallestForEverySeat() {
    for (int seat = 0; seat < 4; seat++) {
      assertEquals(c("DIAMOND",3), ComputerStrategy.play(seat,
          List.of(c("DIAMOND",14),c("DIAMOND",7),c("DIAMOND",3),c("CLUB",2)),
          thirdPlayerTable(seat,"DIAMOND",13,8),"DIAMOND",new Random(1)));
    }
  }
  @Test void thirdPartnerNoTrumpKingIsSafeWhenAceIsInOwnHand() {
    for (String trump : Arrays.asList("NO_TRUMP", null)) {
      assertEquals(c("DIAMOND",3), ComputerStrategy.play(0,
          List.of(c("DIAMOND",14),c("DIAMOND",3)),
          thirdPlayerTable(0,"DIAMOND",13,8),trump,new Random(1)));
    }
  }
  @Test void thirdPartnerQueenIsSafeOnlyWhenEveryHigherCardIsKnown() {
    var table = thirdPlayerTable(0,"DIAMOND",12,8);
    assertEquals(c("DIAMOND",3), ComputerStrategy.play(0,
        List.of(c("DIAMOND",14),c("DIAMOND",13),c("DIAMOND",3)),table,"DIAMOND",new Random(1)));
    // The unseen king might be in fourth hand: keep the existing high-card play.
    assertEquals(c("DIAMOND",14), ComputerStrategy.play(0,
        List.of(c("DIAMOND",14),c("DIAMOND",3)),table,"DIAMOND",new Random(1)));
  }
  @Test void thirdPartnerSideSuitKingIsNotGuaranteedAgainstUnseenTrumps() {
    assertEquals(c("DIAMOND",14), ComputerStrategy.play(0,
        List.of(c("DIAMOND",14),c("DIAMOND",3),c("HEART",14)),
        thirdPlayerTable(0,"DIAMOND",13,8),"HEART",new Random(1)));
  }
  @Test void thirdOpponentWinningStillPlaysHigh() {
    assertEquals(c("DIAMOND",14), ComputerStrategy.play(0,
        List.of(c("DIAMOND",14),c("DIAMOND",3)),
        thirdPlayerTable(0,"DIAMOND",12,13),"DIAMOND",new Random(1)));
  }
  @Test void thirdPartnerSafeDoesNotAllowDiscardWhenAceIsOnlyFollowingCard() {
    assertEquals(c("DIAMOND",14), ComputerStrategy.play(0,
        List.of(c("DIAMOND",14),c("CLUB",2)),
        thirdPlayerTable(0,"DIAMOND",13,8),"DIAMOND",new Random(1)));
  }
  @Test void thirdPartnerAceVoidDiscardsUnlessSideSuitCanBeRuffed() {
    var table = thirdPlayerTable(0,"DIAMOND",14,8);
    var hand = List.of(c("HEART",5),c("CLUB",9));
    assertEquals(c("HEART",5), ComputerStrategy.play(0,hand,table,"NO_TRUMP",new Random(1)));
    assertEquals(c("HEART",5), ComputerStrategy.play(0,hand,table,"DIAMOND",new Random(1)));
    // No guarantee in a side suit: the ordinary cutting strategy still applies.
    assertEquals(c("HEART",5), ComputerStrategy.play(0,hand,table,"HEART",new Random(1)));
  }
  @Test void strategyRemainsLegalThroughSeededCompleteDeals() {
    var random = new Random(912);
    for (String trump : GameState.STRAINS) {
      for (int deal = 0; deal < 20; deal++) {
        List<GameState.Card> deck = new ArrayList<>();
        for (String suit : GameState.STRAINS.subList(0,4))
          for (int rank = 2; rank <= 14; rank++) deck.add(c(suit,rank));
        Collections.shuffle(deck,random);
        Map<Integer,List<GameState.Card>> hands = new HashMap<>();
        for (int seat = 0; seat < 4; seat++) hands.put(seat,List.copyOf(deck.subList(seat*13,(seat+1)*13)));
        var engine = new TrickEngine(hands,deal%4,trump);
        while (!engine.finished()) {
          int seat = engine.nextSeat();
          var card = ComputerStrategy.play(seat,engine.hand(seat),engine.currentTrick(),trump,random);
          assertTrue(engine.legalCards(seat).contains(card));
          engine.play(seat,card,"BOT");
        }
        assertEquals(13,engine.completedTricks().size());
      }
    }
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
