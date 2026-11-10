package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SettlementStatisticsFlowTest {
  PlayFlowTest fixture(int linkedPlayers, MatchPersistenceTest.RecordingStore store) {
    return fixture(linkedPlayers, store, "FORMAL");
  }
  PlayFlowTest fixture(int linkedPlayers, MatchPersistenceTest.RecordingStore store, String roomType) {
    var f = new PlayFlowTest(); f.clock = new RoomServiceTest.MutableClock();
    f.service = new RoomService(f.clock); f.service.statsStore(store); f.players = new RoomService.Identity[4];
    for (int i = 0; i < 4; i++) {
      f.players[i] = f.service.createSession("玩家" + i);
      if (i < linkedPlayers) f.service.linkAccountOutsideRoom(f.players[i], UUID.randomUUID().toString(), true);
    }
    f.room = f.service.create(f.players[0], 1, roomType).roomId();
    for (int i = 0; i < 4; i++) {
      if (i > 0) f.service.join(f.room, f.players[i]);
      f.service.connect(f.room, f.players[i], "socket-" + i); f.service.ready(f.room, f.players[i], true);
    }
    f.start(); f.auctionToPlay(); return f;
  }

  void beforeFinalCard(PlayFlowTest f) {
    for (int step = 0; step < 100; step++) {
      var snapshot = f.view(0);
      if (snapshot.phase().equals("TRICK_RESULT")) { f.finishTrickDisplay(); continue; }
      if (snapshot.game().play().trickNumber() == 13 && snapshot.game().play().currentTrick().size() == 3) return;
      playNext(f);
    }
    fail("Final card not reached");
  }

  void playNext(PlayFlowTest f) {
    int seat = f.view(0).game().play().nextSeat(); var s = f.view(seat);
    f.play(seat, s.you().legalCards().get(0), PlayFlowTest.action(), s.revision(), s.game().dealId());
  }

  @Test void guestsQualifyInMemoryWithoutChangingCollectionOrSavingTheirIdentity() {
    for (int linked : List.of(0, 1, 3)) {
      var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(linked, store);
      assertEquals("NO_LAST_MATCH", assertThrows(RoomService.Failure.class,
          () -> f.service.settlementStatistics(f.room, f.players[0])).code);
      f.playRemainingDeal(); var facts = f.service.settlementStatistics(f.room, f.players[0]);
      assertEquals(SettlementEligibility.Result.ELIGIBLE, facts.eligibility());
      assertEquals(1, facts.completedDeals()); assertEquals("SCORED", facts.outcome());
      assertTrue(store.writes.isEmpty());
      assertEquals("DISABLED", f.service.persistenceStatus(f.room, facts.matchId(), f.players[0]));
      String projected = facts.toString();
      for (var player : f.players) {
        assertFalse(projected.contains(player.playerId())); assertFalse(projected.contains(player.token()));
        assertFalse(projected.contains(player.displayName()));
      }
    }
  }

  @Test void restoredHumanCountsEvenAfterAnActualComputerPlay() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(4, store, "CASUAL");
    int seat = f.view(0).game().play().nextSeat();
    f.service.disconnect(f.room, f.players[seat].playerId(), "socket-" + seat);
    f.clock.now.addAndGet(10_000); f.service.expireInitialSubscriptions();
    f.clock.now.addAndGet(900); f.service.expireInitialSubscriptions();
    assertEquals(1, f.view(0).game().play().currentTrick().size());
    f.service.connect(f.room, f.players[seat], "returned"); f.playRemainingDeal();
    var facts = f.service.settlementStatistics(f.room, f.players[0]);
    assertEquals(SettlementEligibility.Result.FIXED_CASUAL, facts.eligibility());
    assertEquals(1, store.writes.size());
    assertEquals("MIXED", store.writes.get(0).payload().path("participants").get(seat).path("control_type").asText());
    assertFalse(store.writes.get(0).canonicalJson().contains("eligibility")); // persistent schema is unchanged
  }

  @Test void casualRoomRemainsCasualWhenHumanReturnsDuringTwoSecondDisplay() {
    var f = fixture(0, new MatchPersistenceTest.RecordingStore(), "CASUAL"); beforeFinalCard(f);
    int finalSeat = f.view(0).game().play().nextSeat();
    int offline = java.util.stream.IntStream.range(1, 4).filter(seat -> seat != finalSeat).findFirst().orElseThrow();
    f.service.disconnect(f.room, f.players[offline].playerId(), "socket-" + offline);
    f.clock.now.addAndGet(10_000); f.service.expireInitialSubscriptions();
    playNext(f);
    assertEquals("TRICK_RESULT", f.view(0).phase()); assertNotNull(f.view(0).game().matchResult());
    var frozen = f.service.settlementStatistics(f.room, f.players[0]);
    assertEquals(SettlementEligibility.Result.FIXED_CASUAL, frozen.eligibility());
    f.service.connect(f.room, f.players[offline], "late-return");
    f.clock.now.addAndGet(2_000); f.service.expireInitialSubscriptions();
    assertEquals("MATCH_RESULT", f.view(0).phase());
    assertSame(frozen, f.service.settlementStatistics(f.room, f.players[0]));
  }

  @Test void humanAtFinalCardRemainsEligibleAfterTerminalDisconnectWithoutSavingGuest() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(0, store); beforeFinalCard(f); playNext(f);
    var frozen = f.service.settlementStatistics(f.room, f.players[0]);
    assertEquals(SettlementEligibility.Result.ELIGIBLE, frozen.eligibility());
    f.service.disconnect(f.room, f.players[1].playerId(), "socket-1");
    f.clock.now.addAndGet(2_000); f.service.expireInitialSubscriptions();
    assertSame(frozen, f.service.settlementStatistics(f.room, f.players[0]));
    assertTrue(store.writes.isEmpty());
  }

  @Test void duplicateLinkedAccountIsExcludedWithoutChangingExistingStoragePolicy() {
    var f = new PlayFlowTest(); f.clock = new RoomServiceTest.MutableClock(); f.service = new RoomService(f.clock);
    var store = new MatchPersistenceTest.RecordingStore(); f.service.statsStore(store);
    f.players = new RoomService.Identity[4]; String account = UUID.randomUUID().toString();
    for (int i = 0; i < 4; i++) {
      f.players[i] = f.service.createSession("P" + i);
      f.service.linkAccountOutsideRoom(f.players[i], i < 2 ? account : UUID.randomUUID().toString(), true);
    }
    f.room = f.service.create(f.players[0], 1, "CASUAL").roomId();
    for (int i = 0; i < 4; i++) {
      if (i > 0) f.service.join(f.room, f.players[i]);
      f.service.connect(f.room, f.players[i], "socket-" + i); f.service.ready(f.room, f.players[i], true);
    }
    f.start(); f.auctionToPlay(); f.playRemainingDeal();
    assertEquals(SettlementEligibility.Result.DUPLICATE_ACCOUNT, f.service.settlementStatistics(f.room, f.players[0]).eligibility());
    assertEquals(1, store.writes.size());
  }

  @Test void internalProjectionRequiresOriginalAuthenticatedParticipant() {
    var f = fixture(0, new MatchPersistenceTest.RecordingStore()); f.playRemainingDeal();
    var outsider = f.service.createSession("外人");
    assertEquals("NOT_PARTICIPANT", assertThrows(RoomService.Failure.class,
        () -> f.service.settlementStatistics(f.room, outsider)).code);
    var spoof = new RoomService.Identity(outsider.token(), f.players[0].playerId(), "Spoof");
    assertThrows(RoomService.Failure.class, () -> f.service.settlementStatistics(f.room, spoof));
  }

  @Test void internalProjectionDoesNotBypassTaipeiQuarterAccessGuard() {
    var f = fixture(0, new MatchPersistenceTest.RecordingStore());
    f.clock.now.set(java.time.Instant.parse("2026-03-31T15:55:00Z").toEpochMilli());
    f.service.quarterRetentionEnabled(true); f.playRemainingDeal();
    assertNotNull(f.service.settlementStatistics(f.room, f.players[0]));
    f.clock.now.set(java.time.Instant.parse("2026-03-31T16:00:00Z").toEpochMilli());
    assertThrows(RoomService.Failure.class, () -> f.service.settlementStatistics(f.room, f.players[0]));
  }
}
