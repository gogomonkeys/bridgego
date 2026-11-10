package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Fixed room semantics with local clocks and identities; no remote services. */
class FixedRoomModeTest {
  static PlayFlowTest fixture(String type, int planned) {
    var f = new PlayFlowTest(); f.clock = new RoomServiceTest.MutableClock();
    f.clock.now.set(Instant.parse("2026-11-10T00:00:00Z").toEpochMilli());
    f.service = new RoomService(f.clock); f.players = new RoomService.Identity[4];
    for (int seat=0;seat<4;seat++) f.players[seat]=f.service.createSession("P"+seat);
    f.room=f.service.create(f.players[0], planned, type).roomId();
    for (int seat=0;seat<4;seat++) {
      if (seat>0) f.service.join(f.room,f.players[seat]);
      f.service.connect(f.room,f.players[seat],"socket-"+seat); f.service.ready(f.room,f.players[seat],true);
    }
    f.start(); f.auctionToPlay(); return f;
  }
  static void tick(PlayFlowTest f,long ms) { f.clock.now.addAndGet(ms); f.service.expireInitialSubscriptions(); }

  @Test void roomTypeIsFixedAndOldCreateDefaultsToCasual() {
    var service = new RoomService(); var host=service.createSession("Host");
    assertEquals("CASUAL",service.create(host,1).roomType());
    service.leave(service.me(host).roomId(),host);
    for (String invalid : List.of("", "formal", "OTHER"))
      assertEquals("INVALID_ROOM_TYPE",assertThrows(RoomService.Failure.class,()->service.create(host,1,invalid)).code);
    var formal=service.create(host,3,"FORMAL"); service.connect(formal.roomId(),host,"host");
    assertEquals("FORMAL",service.list().get(0).roomType());
    assertEquals("FORMAL_COMPUTER_FORBIDDEN",assertThrows(RoomService.Failure.class,()->service.addComputer(
        formal.roomId(),host,PlayFlowTest.action(),service.get(formal.roomId(),host).revision(),1)).code);
    assertEquals(1,service.get(formal.roomId(),host).players().size());
  }

  @Test void formalStartRejectsDuplicateKnownAccountsEvenAcrossDifferentSessions() {
    var rooms=new RoomService();var players=new RoomService.Identity[4];var account=UUID.randomUUID().toString();
    for(int seat=0;seat<4;seat++) {
      players[seat]=rooms.createSession("P"+seat);
      if(seat<2) rooms.linkAccountOutsideRoom(players[seat],account,true);
    }
    String room=rooms.create(players[0],1,"FORMAL").roomId();
    for(int seat=0;seat<4;seat++) {
      if(seat>0) rooms.join(room,players[seat]);
      rooms.connect(room,players[seat],"s"+seat);rooms.ready(room,players[seat],true);
    }
    assertEquals("START_NOT_READY",assertThrows(RoomService.Failure.class,()->rooms.start(room,players[0],
        PlayFlowTest.action(),rooms.get(room,players[0]).revision())).code);
    assertEquals("WAITING",rooms.get(room,players[0]).phase());
  }

  @Test void manualDepartureLosesAfterBoundedWindowWithoutAComputerOrRejoining() {
    var f=fixture("FORMAL",3);f.service.leave(f.room,f.players[0]);
    assertNull(f.service.me(f.players[0]).roomId());
    assertEquals("FORFEIT_PENDING",f.view(1).phase());
    assertTrue(f.view(1).players().stream().allMatch(p->p.playerType().equals("HUMAN")));
    assertEquals("NOT_MEMBER",assertThrows(RoomService.Failure.class,()->f.service.connect(f.room,f.players[0],"late")).code);
    assertEquals("NOT_MEMBER",assertThrows(RoomService.Failure.class,()->f.service.join(f.room,f.players[0])).code);
    tick(f,999);assertEquals("FORFEIT_PENDING",f.view(1).phase());
    tick(f,1);var s=f.view(1);assertEquals("MATCH_RESULT",s.phase());
    assertEquals("FORFEIT",s.game().matchResult().outcome());assertEquals("EW",s.game().matchResult().winnerTeamId());
    assertEquals("MANUAL_EXIT",s.game().matchResult().reason());
    assertEquals(List.of("NS"),s.game().matchResult().failedTeamIds());
    assertEquals(0,s.game().completedDeals());assertEquals("FORMAL",s.game().roomType());
    assertEquals("FORMAL",s.lastMatch().roomType());
    var result=s.game().matchResult();f.service.leave(f.room,f.players[1]);
    assertEquals(result,f.view(2).game().matchResult());
  }

  @Test void oppositeDeparturesWithinWindowAreNoContestAndSameTeamIsOneLoss() {
    for (int second:List.of(1,2)) {
      var f=fixture("FORMAL",1);f.service.leave(f.room,f.players[0]);tick(f,500);
      f.service.leave(f.room,f.players[second]);tick(f,500);var result=f.view(3).game().matchResult();
      assertEquals(second==1?"NO_CONTEST":"FORFEIT",result.outcome());
      assertEquals(second==1?null:"EW",result.winnerTeamId());
      assertEquals(second==1?"BOTH_TEAMS_FAILED":"MANUAL_EXIT",result.reason());
    }
  }

  @Test void temporaryDisconnectAndSecondSocketDoNotForfeitOrCreateBots() {
    var f=fixture("FORMAL",1);
    f.service.connect(f.room,f.players[1],"second"); f.service.disconnect(f.room,f.players[1].playerId(),"socket-1");
    tick(f,11_000);assertNull(f.view(0).game().matchResult());
    f.service.disconnect(f.room,f.players[1].playerId(),"second");tick(f,9_999);
    assertTrue(f.view(0).game().paused());f.service.connect(f.room,f.players[1],"return");
    assertFalse(f.view(0).game().paused());assertEquals("PLAYING",f.view(0).phase());
    assertTrue(f.view(0).players().stream().noneMatch(p->!p.controlMode().equals("HUMAN")));
    f.service.disconnect(f.room,f.players[1].playerId(),"second"); // stale close cannot evict the return
    tick(f,5_000);assertNull(f.view(0).game().matchResult());
  }

  @Test void timeoutForfeitsAndFailedPlayerReturnCannotUndoIt() {
    var f=fixture("FORMAL",1);f.service.disconnect(f.room,f.players[1].playerId(),"socket-1");
    tick(f,10_000);assertEquals("FORFEIT_PENDING",f.view(0).phase());
    f.service.connect(f.room,f.players[1],"too-late");tick(f,1_000);
    assertEquals("NS",f.view(0).game().matchResult().winnerTeamId());
    assertEquals("RECONNECT_TIMEOUT",f.view(0).game().matchResult().reason());
    assertEquals("HUMAN",f.view(0).players().get(1).controlMode());
  }

  @Test void alreadyDisconnectedOpponentRetainsOnlyOriginalGrace() {
    for(boolean returns:List.of(false,true)) {
      var f=fixture("FORMAL",1);f.service.disconnect(f.room,f.players[1].playerId(),"socket-1");
      tick(f,2_000);f.service.leave(f.room,f.players[0]);tick(f,1_000);
      assertEquals("FORFEIT_PENDING",f.view(2).phase());
      if(returns){tick(f,6_000);f.service.connect(f.room,f.players[1],"returned");}
      else tick(f,7_000);
      var result=f.view(2).game().matchResult();assertNotNull(result);
      assertEquals(returns?"FORFEIT":"NO_CONTEST",result.outcome());
      assertEquals(returns?"EW":null,result.winnerTeamId());
    }
  }

  @Test void simultaneousAllOfflineIsNoContestAndLateReturnOnlyReadsResult() {
    var f=fixture("FORMAL",1);
    for(int seat=0;seat<4;seat++)f.service.disconnect(f.room,f.players[seat].playerId(),"socket-"+seat);
    tick(f,10_000);var result=f.view(0).game().matchResult();
    assertEquals("NO_CONTEST",result.outcome());assertNull(result.winnerTeamId());
    f.service.connect(f.room,f.players[0],"read-only");assertEquals(result,f.view(0).game().matchResult());
    assertTrue(f.view(0).canRematch());
  }

  @Test void finalCardSettlementWinsRaceAndTerminalDepartureDoesNotRewriteIt() {
    var f=fixture("FORMAL",1);var flow=new SettlementStatisticsFlowTest();flow.beforeFinalCard(f);flow.playNext(f);
    var frozen=f.view(0).game().matchResult();assertEquals("SCORED",frozen.outcome());
    f.service.leave(f.room,f.players[1]);tick(f,2_000);
    assertEquals(frozen,f.view(0).game().matchResult());assertEquals(1,f.view(0).game().completedDeals());
  }

  @Test void partialDealsStayScoredAndRematchKeepsModeButReleasesDepartedSeat() {
    var f=fixture("FORMAL",5);f.playRemainingDeal();
    var first=f.view(1).game().completedDealResults().get(0);f.service.leave(f.room,f.players[0]);tick(f,1_000);
    var s=f.view(1);assertEquals("FORFEIT",s.game().matchResult().outcome());
    assertEquals(List.of(first),s.game().completedDealResults());assertEquals(1,s.game().completedDeals());
    f.service.rematch(f.room,f.players[1],s.game().gameId(),PlayFlowTest.action(),s.revision());
    var waiting=f.view(1);assertEquals("FORMAL",waiting.roomType());assertEquals(5,waiting.plannedDeals());
    assertEquals("WAITING",waiting.phase());assertEquals(3,waiting.players().size());
    assertFalse(waiting.canStart());assertTrue(waiting.players().stream().noneMatch(RoomService.PlayerView::ready));
  }

  @Test void departingPlayerNewMembershipSurvivesOriginalRoomRematchAndCleanup() {
    var f=fixture("FORMAL",1);f.service.leave(f.room,f.players[0]);
    var next=f.service.create(f.players[0],1,"CASUAL");
    f.service.connect(next.roomId(),f.players[0],"new-room");tick(f,1_000);
    var terminal=f.view(1);f.service.rematch(f.room,f.players[1],terminal.game().gameId(),
        PlayFlowTest.action(),terminal.revision());
    assertEquals(next.roomId(),f.service.me(f.players[0]).roomId());
    assertEquals("ALREADY_IN_ROOM",assertThrows(RoomService.Failure.class,
        ()->f.service.create(f.players[0],1,"FORMAL")).code);
  }

  @Test void newlyDisconnectedOpponentDoesNotExtendOriginalAdjudicationWindow() {
    var f=fixture("FORMAL",1);f.service.leave(f.room,f.players[0]);tick(f,500);
    f.service.disconnect(f.room,f.players[1].playerId(),"socket-1");tick(f,500);
    var result=f.view(2).game().matchResult();
    assertEquals("FORFEIT",result.outcome());assertEquals("EW",result.winnerTeamId());
    tick(f,10_000);assertEquals(result,f.view(2).game().matchResult());
  }

  @Test void casualHumanOnlyRoomStillUsesTakeoverAndNeverBecomesFormal() {
    var f=fixture("CASUAL",1);f.service.leave(f.room,f.players[1]);
    assertNotEquals("FORFEIT_PENDING",f.view(0).phase());
    assertEquals("BOT",f.view(0).players().get(1).playerType());assertEquals("CASUAL",f.view(0).game().roomType());
  }
}
