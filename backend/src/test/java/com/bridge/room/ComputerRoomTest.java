package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerRoomTest {
  RoomServiceTest.MutableClock clock;
  RoomService service;
  RoomService.Identity host;
  String room;
  String action() { return UUID.randomUUID().toString(); }
  RoomService.Snapshot state() { return service.get(room,host); }
  void tick(long millis) { clock.now.addAndGet(millis); service.expireInitialSubscriptions(); }
  RoomService.Snapshot bot(int seat) { return service.addComputer(room,host,action(),state().revision(),seat).room(); }
  @BeforeEach void setup() {
    clock = new RoomServiceTest.MutableClock(); service = new RoomService(clock);
    host = service.createSession("人類"); room = service.create(host).roomId();
    service.connect(room,host,"host");
  }
  void start() {
    for (int seat=1;seat<4;seat++) bot(seat);
    service.ready(room,host,true);
    service.start(room,host,action(),state().revision());
  }
  @Test void managementIdempotentNoBotAuthAndSeatCapacity() {
    String id = action(); long rev=state().revision();
    var first=service.addComputer(room,host,id,rev,1);
    assertEquals("REPLAYED",service.addComputer(room,host,id,rev,1).outcome());
    var computer=first.room().players().get(1);
    assertEquals("BOT", computer.playerType()); assertTrue(computer.connected()); assertTrue(computer.ready());
    assertNull(computer.initialSubscriptionDeadline()); assertNull(computer.disconnectedAt());
    assertThrows(RoomService.Failure.class,()->service.authenticate(computer.playerId()));
    assertThrows(RoomService.Failure.class,()->service.get(room,new RoomService.Identity(null,computer.playerId(),computer.displayName())));
    assertEquals("SEAT_OCCUPIED",assertThrows(RoomService.Failure.class,()->bot(1)).code);
    bot(2);bot(3);assertEquals(4,state().players().size());
    assertEquals("ROOM_FULL",assertThrows(RoomService.Failure.class,()->service.join(room,service.createSession("路人"))).code);
    id=action(); rev=state().revision();
    assertEquals("APPLIED",service.removeComputer(room,host,computer.playerId(),id,rev).outcome());
    assertEquals("REPLAYED",service.removeComputer(room,host,computer.playerId(),id,rev).outcome());
    assertEquals(3,state().players().size());
    assertEquals("NOT_COMPUTER_PLAYER",assertThrows(RoomService.Failure.class,()->service.removeComputer(room,host,host.playerId(),action(),state().revision())).code);
  }
  @Test void hostOnlyAndWaitingOnly() {
    var guest=service.createSession("來賓");service.join(room,guest);service.connect(room,guest,"guest");
    assertEquals("HOST_ONLY",assertThrows(RoomService.Failure.class,()->service.addComputer(room,guest,action(),state().revision(),2)).code);
    bot(2);bot(3);service.ready(room,host,true);service.ready(room,guest,true);
    service.start(room,host,action(),state().revision());
    String botId=state().players().get(2).playerId();
    assertEquals("WRONG_PHASE",assertThrows(RoomService.Failure.class,()->service.removeComputer(room,host,botId,action(),state().revision())).code);
  }
  @Test void botsNeverBecomeHostAndLastHumanDepartureDeletesRoom() {
    bot(1); var guest=service.createSession("來賓");service.join(room,guest);service.connect(room,guest,"guest");bot(3);
    service.leave(room,host);
    assertEquals(guest.playerId(),service.get(room,guest).hostPlayerId());
    service.leave(room,guest);assertTrue(service.list().isEmpty());
  }
  @Test void botActionsPacedAndPauseForDisconnectedHuman() {
    start(); var s=state();service.privilege(room,host,s.game().gameId(),s.game().dealId(),action(),s.revision(),false);
    long rev=state().revision();tick(100);assertEquals(rev,state().revision());
    tick(900);assertTrue(state().revision()>rev);
    service.disconnect(room,host.playerId(),"host");
    long pausedRev=state().revision();tick(3000);assertEquals(pausedRev,state().revision());assertTrue(state().game().paused());
    service.connect(room,host,"new");tick(100);tick(900);assertFalse(state().game().paused());
    service.beginMaintenance();long stopped=state().revision();tick(100000);assertEquals(stopped,state().revision());
  }
  @Test void threeDealsMixedHumanAndBotsPreservePrivateViewsAndRematch() {
    start(); int moves=0;
    while(!state().phase().equals("MATCH_RESULT") && moves++<2000) {
      var s=state();var g=s.game();
      assertNotNull(s.you());assertEquals(host.playerId(),s.you().playerId());
      assertNull(service.viewFor(room,host).players().get(1).reconnectDeadline());
      if(g.privilege().canChoose()) service.privilege(room,host,g.gameId(),g.dealId(),action(),s.revision(),false);
      else if(!s.you().legalCalls().isEmpty()) service.call(room,host,g.gameId(),g.dealId(),action(),s.revision(),"PASS",null,null);
      else if(!s.you().legalCards().isEmpty()) service.play(room,host,g.gameId(),g.dealId(),action(),s.revision(),s.you().legalCards().get(0));
      else if(s.you().canAcknowledgeResult()) service.acknowledgeResult(room,host,g.gameId(),g.dealId(),action(),s.revision());
      tick(1000);
    }
    var finalState=state();assertEquals("MATCH_RESULT",finalState.phase());assertEquals(3,finalState.game().completedDeals());
    assertEquals(3,finalState.game().completedDealResults().size());
    for(var deal:finalState.game().completedDealResults()) {
      assertEquals(13,deal.completedTricks().size());
      for(var trick:deal.completedTricks()) for(var p:trick.plays()) assertEquals(p.seat()==0?"MANUAL":"BOT",p.source());
      for(var round:deal.auctionRounds()) for(var c:round.calls()) assertEquals(c.seat()==0?"MANUAL":"BOT",c.source());
    }
    assertEquals(3,finalState.game().roster().stream().filter(p->p.playerType().equals("BOT")).count());
    service.rematch(room,host,finalState.game().gameId(),action(),finalState.revision());
    var waiting=state();assertEquals("WAITING",waiting.phase());assertEquals(4,waiting.players().size());
    assertFalse(waiting.players().get(0).ready());assertTrue(waiting.players().stream().skip(1).allMatch(RoomService.PlayerView::ready));
  }
  @Test void humanTimeoutRemainsTimeoutNotBotStrategy() {
    start();for(int i=0;i<10;i++)tick(1000);tick(20000);
    var g=state().game();assertEquals("TIMEOUT",g.privilege().yourChoiceSource());
    tick(20000);assertTrue(state().game().auction().calls().stream().anyMatch(c->c.seat()==0&&c.source().equals("TIMEOUT")));
  }
  @Test void lastHumanDisconnectedForfeitAndIdleCleanupStillWork() {
    start();service.disconnect(room,host.playerId(),"host");tick(10001);
    assertEquals("MATCH_RESULT",state().phase());assertEquals("FORFEIT",state().game().matchResult().outcome());
    tick(BetaLimits.defaults().finishedRoomIdleMs()+1);
    assertTrue(service.list().isEmpty());
  }
  @Test void humanPrivilegeWinsTeamChoiceWithoutBotOverride() {
    start();var s=state();service.privilege(room,host,s.game().gameId(),s.game().dealId(),action(),s.revision(),true);
    for(int i=0;i<8;i++)tick(1000);
    assertTrue(state().game().privilege().usedByTeam().get("NS"));
    assertFalse(state().game().privilege().usedByTeam().get("EW"));
  }
  @Test void initialSubscriptionExpiryOfLastHumanDeletesComputersToo() {
    bot(1);service.disconnect(room,host.playerId(),"host");assertTrue(service.list().isEmpty());
  }
  @Test void staleOrInvalidManagementDoesNotMutateAndOfflineHostCannotManage() {
    long old=state().revision();bot(1);
    assertEquals("STALE_REVISION",assertThrows(RoomService.Failure.class,()->service.addComputer(room,host,action(),old,2)).code);
    assertEquals("INVALID_SEAT",assertThrows(RoomService.Failure.class,()->bot(4)).code);
    assertEquals(2,state().players().size());
    var provisional=service.createSession("未連線");String room2=service.create(provisional).roomId();
    assertEquals("HOST_OFFLINE",assertThrows(RoomService.Failure.class,()->service.addComputer(room2,provisional,action(),service.get(room2,provisional).revision(),1)).code);
  }
  @Test void twoRoomsRemainLimitWithComputers() {
    bot(1);var second=service.createSession("另一人");String r2=service.create(second).roomId();service.connect(r2,second,"two");
    service.addComputer(r2,second,action(),service.get(r2,second).revision(),1);
    assertEquals("ROOM_LIMIT_REACHED",assertThrows(RoomService.Failure.class,()->service.create(service.createSession("第三"))).code);
  }
  @Test void stalledSweepNeverLabelsComputerTurnTimeout() {
    start();var s=state();service.privilege(room,host,s.game().gameId(),s.game().dealId(),action(),s.revision(),false);
    for(int i=0;i<3;i++) tick(25000);
    s=state();if(!s.you().legalCalls().isEmpty())service.call(room,host,s.game().gameId(),s.game().dealId(),action(),s.revision(),"PASS",null,null);
    for(int i=0;i<4;i++)tick(25000);
    for(var round:state().game().auctionRounds())for(var call:round.calls())
      if(call.seat()!=0)assertEquals("BOT",call.source());
  }

  @Test void nearExpiredFirstDetectionClampsDelayAndStillUsesBotPolicy() {
    start();var s=state();service.privilege(room,host,s.game().gameId(),s.game().dealId(),action(),s.revision(),false);
    for(int i=0;i<8;i++)tick(1000);
    s=state();assertEquals(0,s.game().auction().nextSeat());
    service.call(room,host,s.game().gameId(),s.game().dealId(),action(),s.revision(),"PASS",null,null);
    clock.now.addAndGet(19500);service.expireInitialSubscriptions();
    clock.now.addAndGet(600);service.expireInitialSubscriptions();
    var call=state().game().auction().calls().get(1);
    assertEquals(1,call.seat());assertEquals("BOT",call.source());assertEquals("BID",call.type());
  }

}
