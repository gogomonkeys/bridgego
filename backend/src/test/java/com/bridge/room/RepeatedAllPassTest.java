package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Regression for user reports: every call times out, all four Pass, repeatedly redeal. */
class RepeatedAllPassTest {
  @Test void hundredAutomaticAllPassRedealsPreserveTimersHistorySeatsAndPrivacy() throws Exception {
    var f=new OperationTimerTest();f.setup();var initial=f.start();
    var ids=new HashSet<String>();ids.add(initial.game().dealId());
    String gameId=initial.game().gameId();int dealIndex=initial.game().dealIndex();
    var events=new AtomicInteger();f.service.publisher(ignored->events.incrementAndGet());
    List<GameState.Card> previous=f.view(0).you().ownHand();
    GameState.AuctionRound first=null;
    var json=new ObjectMapper();
    for(int cycle=1;cycle<=100;cycle++) {
      // Every fresh deal gets the full privilege decision budget.
      var before=f.view(0);
      assertEquals("PRIVILEGE_DECISION",before.phase());
      assertEquals("PRIVILEGE",before.game().operationTimer().kind());
      assertEquals(20_000,before.game().operationTimer().remainingMs());
      f.clock.now.addAndGet(19_999);f.service.expireInitialSubscriptions();
      assertEquals("PRIVILEGE_DECISION",f.view(0).phase());
      assertEquals(1,f.view(0).game().operationTimer().remainingMs());
      f.clock.now.incrementAndGet();f.service.expireInitialSubscriptions();
      assertEquals("BIDDING",f.view(0).phase());
      for(int call=0;call<4;call++) {
        var auction=f.view(0);
        assertEquals(call,auction.game().auction().calls().size());
        assertEquals(20_000,auction.game().operationTimer().remainingMs());
        f.clock.now.addAndGet(19_999);f.service.expireInitialSubscriptions();
        assertEquals(call,f.view(0).game().auction().calls().size());
        f.clock.now.incrementAndGet();f.service.expireInitialSubscriptions();
      }
      var fresh=f.view(0);
      assertTrue(ids.add(fresh.game().dealId()));
      assertEquals(gameId,fresh.game().gameId());
      assertEquals(dealIndex,fresh.game().dealIndex());
      assertEquals(cycle,fresh.game().redealCount());
      assertEquals(0,fresh.game().completedDeals());
      assertEquals("PRIVILEGE_DECISION",fresh.phase());
      assertEquals(20_000,fresh.game().operationTimer().remainingMs());
      assertFalse(fresh.game().paused());assertNull(fresh.game().matchResult());
      var rounds=fresh.game().auctionRounds();
      assertEquals(cycle+1,rounds.size());
      if(first==null)first=rounds.get(0);
      assertEquals(first,rounds.get(0));
      for(var round:rounds.subList(0,cycle)) {
        assertEquals("ALL_PASS",round.status());assertEquals(4,round.calls().size());
        assertTrue(round.calls().stream().allMatch(c->c.type().equals("PASS")&&c.source().equals("TIMEOUT")));
      }
      assertEquals("IN_PROGRESS",rounds.get(cycle).status());
      var allCards=new HashSet<GameState.Card>();
      for(int seat=0;seat<4;seat++) {
        var privateView=f.view(seat);
        assertEquals(f.room,f.service.me(f.players[seat]).roomId());
        assertEquals(f.players[seat],f.service.authenticate(f.players[seat].token()));
        assertEquals(4,privateView.players().size());
        assertTrue(privateView.players().stream().allMatch(p->p.connected()&&p.reconnectDeadline()==null));
        assertEquals(13,privateView.you().ownHand().size());
        allCards.addAll(privateView.you().ownHand());
        var tree=json.valueToTree(privateView);
        assertEquals(1,tree.findValues("ownHand").size());
        assertFalse(tree.has("hands"));assertFalse(tree.path("game").has("hands"));
        assertFalse(json.writeValueAsString(privateView).contains(f.players[seat].token()));
      }
      assertEquals(52,allCards.size());
      assertNotEquals(previous,fresh.you().ownHand());previous=fresh.you().ownHand();
      // Five authoritative mutations only: privilege timeout plus four Pass timeouts.
      assertEquals(cycle*5,events.get());
    }
  }

  @Test void repeatedManualAllPassUsesSameRedealPathWithoutConnectionMutation() {
    var f=new OperationTimerTest();f.setup();var initial=f.start();
    String gameId=initial.game().gameId();String previous=initial.game().dealId();
    for(int cycle=1;cycle<=25;cycle++) {
      for(int seat=0;seat<4;seat++)f.vote(seat,false);
      for(int seat=0;seat<4;seat++)f.call(seat,"PASS",null,null);
      var s=f.view(0);
      assertNotEquals(previous,s.game().dealId());previous=s.game().dealId();
      assertEquals(gameId,s.game().gameId());assertEquals(initial.game().dealIndex(),s.game().dealIndex());
      assertEquals(cycle,s.game().redealCount());assertEquals(20_000,s.game().operationTimer().remainingMs());
      assertEquals(cycle+1,s.game().auctionRounds().size());
      assertTrue(s.players().stream().allMatch(p->p.connected()&&p.reconnectDeadline()==null));
      assertNull(s.game().matchResult());
    }
  }
}
