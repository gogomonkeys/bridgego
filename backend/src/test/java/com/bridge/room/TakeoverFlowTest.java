package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TakeoverFlowTest {
  RoomServiceTest.MutableClock clock;
  RoomService service;
  RoomService.Identity[] players;
  String room;
  @BeforeEach void setup() { clock = new RoomServiceTest.MutableClock(); service = new RoomService(clock); startRoom(); }
  void startRoom() {
    players = new RoomService.Identity[4];
    for (int i=0;i<4;i++) players[i]=service.createSession("P"+i);
    room=service.create(players[0]).roomId();
    for(int i=0;i<4;i++){if(i>0)service.join(room,players[i]);service.connect(room,players[i],"socket-"+i);service.ready(room,players[i],true);}
    service.start(room,players[0],UUID.randomUUID().toString(),view(0).revision());
    KeepDealFixture.keep(service, room, players);
  }
  RoomService.Snapshot view(int seat){return service.get(room,players[seat]);}
  RoomService.PlayerView player(int seat){return view(3).players().stream().filter(p->p.seat()==seat).findFirst().orElseThrow();}
  void offline(int seat){service.disconnect(room,players[seat].playerId(),"socket-"+seat);}
  void reconnect(int seat){service.connect(room,players[seat],"resumed-"+seat);}
  void advance(long ms){clock.now.addAndGet(ms);service.expireInitialSubscriptions();}
  @Test void exactGraceTakesOverAndOriginalIdentityReclaimsUnchangedHand(){
    var hand=view(0).you().ownHand(); offline(0);advance(9999);
    assertEquals("RECONNECTING",player(0).controlMode());assertTrue(view(1).game().paused());
    advance(1);assertEquals("BOT_TAKEOVER",player(0).controlMode());assertFalse(player(0).connected());
    assertFalse(view(1).game().paused());assertEquals("HUMAN",player(0).playerType());
    assertEquals(hand,view(0).you().ownHand());reconnect(0);assertEquals("HUMAN",player(0).controlMode());assertEquals(hand,view(0).you().ownHand());
  }
  @Test void voluntaryExitRevokesMembershipAndReplacesSameSeat(){
    service.leave(room,players[0]);var replacement=player(0);
    assertEquals("BOT",replacement.playerType());assertNotEquals(players[0].playerId(),replacement.playerId());
    assertEquals("PRIVILEGE_DECISION",view(1).phase());assertNull(service.me(players[0]).roomId());
    assertThrows(RoomService.Failure.class,()->view(0));assertThrows(RoomService.Failure.class,()->reconnect(0));
    assertFalse(service.hasConnection(room,players[0].playerId(),"socket-0"));
    assertEquals(players[1].playerId(),view(1).hostPlayerId());
    assertEquals("P0",view(1).game().roster().get(0).displayName());
    service.disconnect(room,players[0].playerId(),"socket-0");assertFalse(view(1).game().paused());
  }
  @Test void allOfflineGetsTenSecondsFromLastDisconnectAndNoBotActions(){
    offline(0);advance(8000);offline(1);offline(2);offline(3);
    long deadline=view(0).allHumansReconnectDeadline();advance(9999);
    assertEquals("PRIVILEGE_DECISION",view(0).phase());assertTrue(view(0).game().paused());assertEquals(deadline,view(0).allHumansReconnectDeadline());
    advance(1);var s=view(0);assertEquals("MATCH_RESULT",s.phase());assertEquals("NO_CONTEST",s.game().matchResult().outcome());
    assertEquals("NO_HUMANS_CONNECTED",s.game().matchResult().reason());assertNull(s.game().matchResult().winnerTeamId());assertEquals(0,s.game().completedDeals());assertFalse(s.canRematch());
    assertTrue(service.list().isEmpty());reconnect(0);assertEquals("MATCH_RESULT",view(0).phase());assertFalse(view(0).canRematch());
  }
  @Test void timelyLastHumanReturnCancelsGlobalDeadlineAndExpiredSeatsBecomeBots(){
    for(int i=0;i<4;i++)offline(i);advance(9999);reconnect(3);advance(1);
    assertNull(view(3).allHumansReconnectDeadline());assertFalse(view(3).game().paused());
    for(int i=0;i<3;i++)assertEquals("BOT_TAKEOVER",player(i).controlMode());assertEquals("HUMAN",player(3).controlMode());
  }
  @Test void lastOnlineHumanVoluntaryExitTerminatesWithoutGrace(){
    offline(0);offline(1);offline(2);service.leave(room,players[3]);
    assertEquals("NO_HUMANS_REMAIN",view(0).game().matchResult().reason());assertNull(view(0).allHumansReconnectDeadline());assertTrue(service.list().isEmpty());
  }
  @Test void lastHumanOwnerVoluntaryExitDeletesBotOnlyRoom(){
    for(int i=0;i<4;i++)service.leave(room,players[i]);assertTrue(service.list().isEmpty());
    for(var p:players)assertNull(service.me(p).roomId());
  }
  @Test void anotherLiveTabAndOldCloseDoNotTakeOver(){
    service.connect(room,players[0],"second-tab");offline(0);advance(10000);
    assertEquals("HUMAN",player(0).controlMode());assertFalse(view(1).game().paused());
    service.disconnect(room,players[0].playerId(),"socket-0");assertEquals("HUMAN",player(0).controlMode());
  }
  @Test void takeoverRejectsNewHumanCommandAndHidesControls(){
    offline(0);advance(10000);var s=view(0);
    assertTrue(s.you().legalCalls().isEmpty());assertTrue(s.you().legalCards().isEmpty());
    var ex=assertThrows(RoomService.Failure.class,()->service.privilege(room,players[0],s.game().gameId(),s.game().dealId(),UUID.randomUUID().toString(),s.revision(),true));
    assertEquals("CONTROL_TAKEN_OVER",ex.code);
  }
  @Test void graceUsesMonotonicTimeNotWallClock(){
    var tick=new java.util.concurrent.atomic.AtomicLong(50000);service=new RoomService(clock,tick::get);startRoom();offline(0);
    clock.now.addAndGet(1000000);tick.addAndGet(9999);assertEquals("RECONNECTING",player(0).controlMode());
    clock.now.addAndGet(-2000000);tick.incrementAndGet();assertEquals("BOT_TAKEOVER",player(0).controlMode());
  }
  @Test void closedArchiveHasFixedTtlAndDoesNotReserveRoomCapacity(){
    for(int i=0;i<4;i++)offline(i);advance(10000);reconnect(0);
    var newcomer=service.createSession("new");assertNotNull(service.create(newcomer));
    advance(299999);assertEquals("MATCH_RESULT",view(0).phase());advance(1);
    assertNull(service.me(players[0]).roomId());assertThrows(RoomService.Failure.class,()->view(0));
  }
  @Test void archivedMemberCanCreateNewRoomWithoutOldMembership(){
    for(int i=0;i<4;i++)offline(i);advance(10000);var newRoom=service.create(players[0]);
    assertNotEquals(room,newRoom.roomId());assertEquals(newRoom.roomId(),service.me(players[0]).roomId());assertThrows(RoomService.Failure.class,()->view(0));
  }

  @Test void takeoverActionsUseDistinctSourceAndAcceptedRetryIsStillReplay() {
    var initial=view(1);String acceptedId=UUID.randomUUID().toString();
    service.privilege(room,players[1],initial.game().gameId(),initial.game().dealId(),acceptedId,initial.revision(),false);
    offline(1);advance(10000);
    assertEquals("REPLAYED",service.privilege(room,players[1],initial.game().gameId(),initial.game().dealId(),acceptedId,initial.revision(),false).outcome());
    reconnect(1);offline(0);advance(10000);advance(900);
    assertEquals("BOT_TAKEOVER",view(0).game().privilege().yourChoiceSource());
    assertFalse(view(0).game().privilege().canChoose());assertFalse(view(0).game().paused());
  }
  @Test void reclaimCancelsQueuedComputerTurnWithoutDuplicateAction() {
    offline(0);advance(10000);advance(899);reconnect(0);advance(1);
    assertNull(view(0).game().privilege().yourChoiceSource());
    assertTrue(view(0).game().privilege().canChoose());
    var s=view(0);service.privilege(room,players[0],s.game().gameId(),s.game().dealId(),UUID.randomUUID().toString(),s.revision(),true);
    advance(900);assertEquals("MANUAL",view(0).game().privilege().yourChoiceSource());
  }
  @Test void globalExpiryCannotBeRescuedAtExactBoundary() {
    for(int i=0;i<4;i++)offline(i);clock.now.addAndGet(10000);reconnect(0);
    assertEquals("NO_HUMANS_CONNECTED",view(0).game().matchResult().reason());assertFalse(view(0).canRematch());
  }

  @Test void threeTakeoversFinishMatchAndRematchReleasesOfflineHumanSeats() {
    for(int i=0;i<3;i++)offline(i);advance(10000);
    for(int step=0;step<3000 && !view(3).phase().equals("MATCH_RESULT");step++) {
      var s=view(3);var g=s.game();String action=UUID.randomUUID().toString();
      if(g.redeal().canChoose())service.redeal(room,players[3],g.gameId(),g.dealId(),action,s.revision(),false);
      else if(g.privilege().canChoose())service.privilege(room,players[3],g.gameId(),g.dealId(),action,s.revision(),false);
      else if(!s.you().legalCalls().isEmpty())service.call(room,players[3],g.gameId(),g.dealId(),action,s.revision(),"PASS",null,null);
      else if(!s.you().legalCards().isEmpty())service.play(room,players[3],g.gameId(),g.dealId(),action,s.revision(),s.you().legalCards().get(0));
      else if(s.you().canAcknowledgeResult())service.acknowledgeResult(room,players[3],g.gameId(),g.dealId(),action,s.revision());
      advance(1000);
    }
    var end=view(3);assertEquals("MATCH_RESULT",end.phase());assertEquals(3,end.game().completedDeals());
    var waiting=service.rematch(room,players[3],end.game().gameId(),UUID.randomUUID().toString(),end.revision()).room();
    assertEquals(1,waiting.players().size());assertEquals(3,waiting.players().get(0).seat());
    for(int i=0;i<3;i++)assertNull(service.me(players[i]).roomId());
  }
}
