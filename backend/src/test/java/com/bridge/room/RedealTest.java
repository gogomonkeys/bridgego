package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RedealTest {
  GameState.Card c(int rank) { return new GameState.Card("CLUB", rank); }
  GameState gameWithEligible(AtomicLong time) throws Exception {
    for (int seed=0; seed<1000; seed++) {
      var random=SecureRandom.getInstance("SHA1PRNG"); random.setSeed(seed);
      var game=new GameState(random,time::get);
      if(game.phase.equals("REDEAL_DECISION"))return game;
    }
    throw new AssertionError("No seeded eligible deal");
  }
  int eligible(GameState g) { for(int seat=0;seat<4;seat++) if(g.canChooseRedeal(seat,false))return seat; throw new AssertionError(); }
  @Test void exactPointAndAceBoundaries() {
    assertEquals(10,GameState.highCardPoints(List.of(c(14),c(13),c(12),c(11),c(10))));
    assertTrue(GameState.qualifiesForRedeal(List.of(c(2))));
    assertTrue(GameState.qualifiesForRedeal(List.of(c(14))));
    assertTrue(GameState.qualifiesForRedeal(List.of(c(13),c(12))));
    assertFalse(GameState.qualifiesForRedeal(List.of(c(14),c(11))));
    assertFalse(GameState.qualifiesForRedeal(List.of(c(13),c(12),c(11))));
  }
  @Test void noEligibleDealSkipsWindowAndQualifiedFieldsArePrivate() throws Exception {
    boolean found=false;
    for(int seed=0;seed<1000;seed++) {
      var random=SecureRandom.getInstance("SHA1PRNG");random.setSeed(seed);
      var g=new GameState(random,()->0L);
      if(!g.phase.equals("REDEAL_DECISION")){assertEquals("PRIVILEGE_DECISION",g.phase);found=true;break;}
    }
    assertTrue(found);
    var g=gameWithEligible(new AtomicLong());
    for(int seat=0;seat<4;seat++) {
      assertEquals(GameState.highCardPoints(g.hands.get(seat)),g.view(seat,false).redeal().yourPoints());
      assertEquals(GameState.qualifiesForRedeal(g.hands.get(seat)),g.view(seat,false).redeal().eligible());
      assertFalse(g.view(seat,true).redeal().canChoose());
      assertFalse(g.view(seat,false,true).redeal().canChoose());
    }
    assertNull(g.view(null,false).redeal().yourPoints());
    assertFalse(g.view(null,false).redeal().eligible());
    assertNull(g.view(null,false).redeal().yourChoice());
  }
  @Test void redealIsSameRoundNewIdentityAndNoEmptyHistoryGrowth() throws Exception {
    var time=new AtomicLong();var g=gameWithEligible(time);var initial=g.view(0,false);
    int repeats=0;
    for(int attempts=0;attempts<200;attempts++) {
      if(!g.phase.equals("REDEAL_DECISION")) {
        // Reach another fresh deal using normal all-pass flow.
        for(int seat=0;seat<4;seat++)g.choose(seat,false);
        for(int seat=0;seat<4;seat++)g.call(seat,"PASS",null,null);
      }
      if(!g.phase.equals("REDEAL_DECISION"))continue;
      var old=g.dealId;int history=g.view(null,false).auctionRounds().size();
      g.chooseRedeal(eligible(g),true);repeats++;
      assertNotEquals(old,g.dealId);
      assertEquals(history,g.view(null,false).auctionRounds().size());
      assertEquals("HAND_REDEAL",g.view(null,false).lastRedealReason());
      assertEquals(initial.dealIndex(),g.view(0,false).dealIndex());
      assertEquals(initial.dealerSeat(),g.view(0,false).dealerSeat());
      assertEquals(initial.teamTotals(),g.view(0,false).teamTotals());
      assertTrue(g.calls.isEmpty());assertTrue(g.votes.isEmpty());
      assertEquals(52,g.hands.values().stream().flatMap(List::stream).distinct().count());
      for(var hand:g.hands.values())assertEquals(13,hand.size());
    }
    assertTrue(repeats>20);
  }
  @Test void commonWindowEarlyKeepAndTimeoutAreSeparateFromPrivilege() throws Exception {
    var time=new AtomicLong();var g=gameWithEligible(time);time.set(5000);
    assertEquals(15000,g.view(0,false).operationTimer().remainingMs());
    for(int seat=0;seat<4;seat++)if(g.canChooseRedeal(seat,false))g.chooseRedeal(seat,false);
    assertEquals("PRIVILEGE_DECISION",g.phase);assertEquals(20000,g.view(0,false).operationTimer().remainingMs());
    assertTrue(g.votes.isEmpty());
    g=gameWithEligible(time);time.addAndGet(20000);assertTrue(g.expireOperation());
    assertEquals("PRIVILEGE_DECISION",g.phase);assertTrue(g.votes.isEmpty());
  }
  @Test void pausedWindowPreservesTimeAndEndedGameCannotRedraw() throws Exception {
    var time=new AtomicLong();var g=gameWithEligible(time);time.set(3000);g.pauseTimer();time.addAndGet(10000);
    assertFalse(g.expireOperation());assertEquals(17000,g.view(0,true).operationTimer().remainingMs());
    g.resumeTimer();time.addAndGet(16999);assertFalse(g.expireOperation());time.incrementAndGet();assertTrue(g.expireOperation());
    g=gameWithEligible(time);int seat=eligible(g);String id=g.dealId;g.finishWithoutHumans("NO_HUMANS_CONNECTED");
    var ended=g;assertEquals("WRONG_PHASE",assertThrows(RoomService.Failure.class,()->ended.chooseRedeal(seat,true)).code);
    assertEquals(id,g.dealId);
  }
  @Test void ineligibleChoiceAndOtherPhasesRejectAndBotLateDeadlineStillRedraws() throws Exception {
    var time=new AtomicLong();var g=gameWithEligible(time);int entitled=eligible(g);
    for(int seat=0;seat<4;seat++) if(!g.view(seat,false).redeal().eligible()) {
      int actor=seat;assertEquals("REDEAL_NOT_ELIGIBLE",assertThrows(RoomService.Failure.class,()->g.chooseRedeal(actor,true)).code);
    }
    String old=g.dealId;time.set(20001);assertTrue(g.expireOperation(Map.of(entitled,"BOT_TAKEOVER")));
    assertNotEquals(old,g.dealId);assertEquals("HAND_REDEAL",g.lastRedealReason);
  }
}
