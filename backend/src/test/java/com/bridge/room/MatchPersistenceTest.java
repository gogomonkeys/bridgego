package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatchPersistenceTest {
  static class RecordingStore implements StatsStore {
    final List<FinalMatchEnvelope> writes = new ArrayList<>();
    SaveStatus state = SaveStatus.PENDING;
    boolean fail;
    public SaveStatus submit(FinalMatchEnvelope e) {
      if (fail) throw new IllegalStateException("fake failure");
      writes.add(e); return state;
    }
    public SaveStatus status(String id) { return state; }
    public List<FinalMatchEnvelope> exportCommitted() { return List.copyOf(writes); }
  }
  PlayFlowTest fixture(RecordingStore store, String account) { return fixture(store, account, 100_000); }
  PlayFlowTest fixture(RecordingStore store, String account, long startTime) {
    var f = new PlayFlowTest();
    f.clock = new RoomServiceTest.MutableClock(); f.clock.now.set(startTime); f.service = new RoomService(f.clock);
    if (store != null) f.service.statsStore(store);
    f.players = new RoomService.Identity[4];
    for (int i=0;i<4;i++) f.players[i] = f.service.createSession("P"+i);
    for (int i=0;i<4;i++) f.service.linkAccountOutsideRoom(f.players[i],
        i==0 && account!=null ? account : UUID.randomUUID().toString(), true);
    f.room = f.service.create(f.players[0],1).roomId();
    for (int i=0;i<4;i++) {
      if(i>0) f.service.join(f.room,f.players[i]);
      f.service.connect(f.room,f.players[i],"socket-"+i);
      f.service.ready(f.room,f.players[i],true);
    }
    f.start(); return f;
  }
  @Test void finalOnlyOncePrivateProjectionVersionAndPendingConfirmed() {
    var store = new RecordingStore(); var account=UUID.randomUUID().toString(); var f=fixture(store,account);
    assertTrue(store.writes.isEmpty());
    f.auctionToPlay(); f.playRemainingDeal();
    assertEquals(1,store.writes.size());
    var e=store.writes.get(0); var p=e.payload();
    assertEquals(f.view(0).game().gameId(),e.matchId());
    assertEquals(1,p.path("completed_deals").size());
    assertEquals(4,p.path("participants").size());
    assertEquals(account,p.path("participants").get(0).path("account_id").asText());
    assertTrue(p.path("participants").get(0).path("guest_id").isNull());
    assertTrue(p.path("participants").get(1).path("guest_id").isNull());
    assertFalse(p.path("participants").get(1).path("account_id").asText().isBlank());
    assertEquals(MatchPersistence.SCORE_VERSION,p.path("match").path("score_version").asText());
    for(String secret : List.of("displayName","token","completedTricks","hands","auctionRounds",f.players[0].token(),"P0"))
      assertFalse(e.canonicalJson().contains(secret));
    assertEquals("PENDING",f.service.persistenceStatus(f.room,e.matchId(),f.players[0]));
    store.state=StatsStore.SaveStatus.CONFIRMED;
    assertEquals("CONFIRMED",f.service.persistenceStatus(f.room,e.matchId(),f.players[0]));
    f.service.disconnect(f.room,f.players[1].playerId(),"socket-1");
    f.clock.now.addAndGet(5000); f.view(0);
    assertEquals(1,store.writes.size());
    assertEquals(e,store.writes.get(0));
    var stranger=f.service.createSession("Other");
    assertEquals("NOT_PARTICIPANT",assertThrows(RoomService.Failure.class,
        ()->f.service.persistenceStatus(f.room,e.matchId(),stranger)).code);
    assertEquals("NO_LAST_MATCH",assertThrows(RoomService.Failure.class,
        ()->f.service.persistenceStatus(f.room,UUID.randomUUID().toString(),f.players[0])).code);
  }
  @Test void disabledAndFailedSavesNeverUndoGame() {
    for(boolean fail : List.of(false,true)) {
      var store=new RecordingStore(); store.fail=fail;
      var f=fixture(fail?store:null,null); f.auctionToPlay(); f.playRemainingDeal();
      assertEquals("MATCH_RESULT",f.view(0).phase());
      assertEquals(fail?"FAILED":"DISABLED",f.service.persistenceStatus(f.room,f.view(0).game().gameId(),f.players[0]));
    }
  }
  @Test void frozenAccountNoRetroactiveLinkAndRematchNewId() {
    var store=new RecordingStore(); var f=fixture(store,null); f.auctionToPlay(); f.playRemainingDeal();
    var e=store.writes.get(0); var s=f.view(0);
    assertEquals("AUTH_IN_ROOM",assertThrows(RoomService.Failure.class,
        ()->f.service.linkAccountOutsideRoom(f.players[0],UUID.randomUUID().toString())).code);
    f.service.rematch(f.room,f.players[0],s.game().gameId(),PlayFlowTest.action(),s.revision());
    for(int i=0;i<4;i++) f.service.ready(f.room,f.players[i],true);
    f.start();
    assertNotEquals(e.matchId(),f.view(0).game().gameId());
    assertEquals("PENDING",f.service.persistenceStatus(f.room,e.matchId(),f.players[0]));
    f.auctionToPlay(); f.playRemainingDeal();
    assertEquals(2,store.writes.size());
    assertNotEquals(e.commitId(),store.writes.get(1).commitId());
    assertFalse(e.payload().path("participants").get(0).path("account_id").asText().isBlank());
  }
  @Test void noContestCapturesMixedOriginalAttributionNoIncompleteDeal() {
    var store=new RecordingStore(); var f=fixture(store,null);
    for(int i=1;i<4;i++) f.service.leave(f.room,f.players[i]);
    f.service.disconnect(f.room,f.players[0].playerId(),"socket-0");
    f.clock.now.addAndGet(10_001); f.service.expireInitialSubscriptions();
    assertEquals(1,store.writes.size()); var p=store.writes.get(0).payload();
    assertEquals("NO_CONTEST",p.path("match").path("outcome").asText());
    assertTrue(p.path("match").path("winner_team_id").isNull());
    assertEquals(0,p.path("completed_deals").size());
    for(int i=1;i<4;i++) {
      assertEquals("MIXED",p.path("participants").get(i).path("control_type").asText());
      assertEquals("HUMAN",p.path("participants").get(i).path("player_type").asText());
      assertTrue(p.path("participants").get(i).path("guest_id").isNull());
      assertFalse(p.path("participants").get(i).path("account_id").asText().isBlank());
    }
  }
  @Test void identityHooksPreserveSessionAndRejectSpoofingAndInRoomUnlink() {
    var service=new RoomService(new RoomServiceTest.MutableClock());
    var id=service.createSession("Host"); var account=UUID.randomUUID().toString();
    service.linkAccountOutsideRoom(id,account);
    assertEquals(id,service.authenticate(id.token())); assertEquals(account,service.accountId(id));
    assertThrows(RoomService.Failure.class,()->service.linkAccountOutsideRoom(
        new RoomService.Identity(id.token(),UUID.randomUUID().toString(),"Host"),account));
    var room=service.create(id).roomId();
    assertEquals("AUTH_IN_ROOM",assertThrows(RoomService.Failure.class,()->service.unlinkAccountOutsideRoom(id)).code);
    service.leave(room,id); service.unlinkAccountOutsideRoom(id);
    assertNull(service.accountId(id)); assertEquals(id,service.authenticate(id.token()));
  }
  @Test void anyVisitorOrUnapprovedAccountExcludesWholeMatch() {
    for (boolean linkedButUnapproved : List.of(false, true)) {
      var store = new RecordingStore(); var f = new PlayFlowTest();
      f.clock = new RoomServiceTest.MutableClock(); f.service = new RoomService(f.clock); f.service.statsStore(store);
      f.players = new RoomService.Identity[4];
      for (int i=0;i<4;i++) {
        f.players[i]=f.service.createSession("P"+i);
        if (i<3) f.service.linkAccountOutsideRoom(f.players[i],UUID.randomUUID().toString(),true);
        else if(linkedButUnapproved) f.service.linkAccountOutsideRoom(f.players[i],UUID.randomUUID().toString());
      }
      f.room=f.service.create(f.players[0],1).roomId();
      for(int i=0;i<4;i++) {
        if(i>0) f.service.join(f.room,f.players[i]);
        f.service.connect(f.room,f.players[i],"socket-"+i); f.service.ready(f.room,f.players[i],true);
      }
      f.start(); f.auctionToPlay(); f.playRemainingDeal();
      assertTrue(store.writes.isEmpty());
      assertEquals("DISABLED",f.service.persistenceStatus(f.room,f.view(0).game().gameId(),f.players[0]));
    }
  }
  @Test void approvedTaiwanQuarterBoundaryExpiresFinishedRoomButNotActiveMatchOrAccounts() {
    var store=new RecordingStore(); var f=fixture(store,null,
        java.time.Instant.parse("2026-03-31T15:55:00Z").toEpochMilli());
    f.service.quarterRetentionEnabled(true);
    f.auctionToPlay();f.playRemainingDeal();
    String account=f.service.accountId(f.players[0]);
    f.clock.now.set(java.time.Instant.parse("2026-03-31T16:00:00Z").toEpochMilli());
    assertEquals("ROOM_NOT_FOUND",assertThrows(RoomService.Failure.class,()->f.view(0)).code);
    assertEquals(account,f.service.accountId(f.players[0]));
    var active=fixture(new RecordingStore(),null);active.service.quarterRetentionEnabled(true);
    active.clock.now.set(java.time.Instant.parse("2026-03-31T16:00:00Z").toEpochMilli());
    assertNotNull(active.view(0).game()); // retention never interrupts an unfinished game
  }
}
