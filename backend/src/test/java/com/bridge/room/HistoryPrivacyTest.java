package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

class HistoryPrivacyTest {
  PlayFlowTest f;
  @BeforeEach void setup() { f = new PlayFlowTest(); f.setup(); }
  void playOne() {
    int seat = f.view(0).game().play().nextSeat();
    var s = f.view(seat);
    f.play(seat, s.you().legalCards().get(0), PlayFlowTest.action(), s.revision(), s.game().dealId());
  }
  void trick() { for(int i=0;i<4;i++) playOne(); }
  void hidden(List<GameState.TrickView> tricks) {
    assertFalse(tricks.isEmpty());
    assertTrue(tricks.stream().noneMatch(GameState.TrickView::cardsVisible));
    assertTrue(tricks.stream().allMatch(t -> t.plays().isEmpty()));
  }
  void visible(List<GameState.TrickView> tricks) {
    assertEquals(13, tricks.size());
    assertTrue(tricks.stream().allMatch(t -> t.cardsVisible() && t.plays().size()==4));
  }
  @Test void everyViewerAndReconnectReceiveMetadataOnlyAfterCapture() {
    f.playing(); trick();
    for(int seat=0;seat<4;seat++) {
      var s=f.view(seat); hidden(s.game().play().completedTricks());
      assertEquals(4,s.game().play().currentTrick().size());
    }
    f.finishTrickDisplay();
    for(int seat=0;seat<4;seat++) {
      var s=f.view(seat); hidden(s.game().play().completedTricks());
      assertTrue(s.game().play().currentTrick().isEmpty());
      assertEquals(1,s.game().play().tricksByTeam().values().stream().mapToInt(Integer::intValue).sum());
    }
    f.service.disconnect(f.room,f.players[0].playerId(),"socket-0");
    hidden(f.service.connect(f.room,f.players[0],"again").game().play().completedTricks());
  }
  @Test void finalCardReplayUsesCurrentRedactedProjectionThenSettledReview() {
    f.playing(); for(int i=0;i<3;i++)playOne();
    int seat=f.view(0).game().play().nextSeat();var s=f.view(seat);
    var card=s.you().legalCards().get(0);String action=PlayFlowTest.action();
    f.play(seat,card,action,s.revision(),s.game().dealId());f.finishTrickDisplay();
    var replay=f.play(seat,card,action,s.revision(),s.game().dealId());
    assertEquals("REPLAYED",replay.outcome());hidden(replay.room().game().play().completedTricks());
    f.playRemainingDeal();
    visible(f.view(0).game().play().completedTricks());
    var old=f.view(0).game().completedDealResults().get(0);
    visible(old.completedTricks());f.advanceResult();
    assertEquals(old,f.view(0).game().completedDealResults().get(0));
  }
  @Test void incompleteForfeitNeverUnlocksCollectedFacesOrIncludesUnplayedHandsInArchive() {
    f.playing(); trick();f.finishTrickDisplay();f.service.leave(f.room,f.players[0]);
    var terminal=f.view(1);hidden(terminal.game().play().completedTricks());
    assertTrue(terminal.lastMatch().completedDealResults().isEmpty());
    assertEquals(1,terminal.lastMatch().auctionRounds().size());
  }
  @Test void everyDealAuctionAndAllPassRedealRetainedImmutably() {
    f.bidding();String original=f.view(0).game().dealId();
    for(int seat=0;seat<4;seat++)f.call(seat,"PASS",null,null);
    var rounds=f.view(0).game().auctionRounds();
    assertEquals(2,rounds.size());assertEquals("ALL_PASS",rounds.get(0).status());
    assertEquals(original,rounds.get(0).dealId());assertEquals(4,rounds.get(0).calls().size());
    assertEquals("IN_PROGRESS",rounds.get(1).status());assertEquals(1,rounds.get(1).redealCount());
    f.auctionToPlay();f.playRemainingDeal();
    var result=f.view(0).game().completedDealResults().get(0);
    assertEquals(2,result.auctionRounds().size());
    assertEquals("CONTRACT",result.auctionRounds().get(1).status());
    assertEquals(4,result.auctionRounds().get(1).calls().size());
    assertThrows(UnsupportedOperationException.class,()->result.auctionRounds().get(0).calls().clear());
    f.advanceResult(); f.auctionToPlay();
    assertEquals(result,f.view(0).game().completedDealResults().get(0));
    assertEquals(3,f.view(0).game().auctionRounds().size());
    assertEquals(2,f.view(0).game().auctionRounds().get(2).dealIndex());
  }
  @Test void timeoutAllPassSourcesRetainedWithoutPrivateChoices() {
    f.bidding();for(int i=0;i<4;i++){f.clock.now.addAndGet(20_000);f.view(0);}
    var round=f.view(0).game().auctionRounds().get(0);
    assertEquals("ALL_PASS",round.status());
    assertTrue(round.calls().stream().allMatch(call->call.source().equals("TIMEOUT")));
    assertNull(round.result());
    assertNull(f.view(0).game().privilege().usedByTeam());
  }
}
