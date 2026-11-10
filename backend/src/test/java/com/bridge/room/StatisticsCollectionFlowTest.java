package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class StatisticsCollectionFlowTest {
  static PlayFlowTest fixture(int linked, int consented, MatchPersistenceTest.RecordingStore store) {
    return fixture(linked, consented, store, "FORMAL");
  }
  static PlayFlowTest fixture(int linked, int consented, MatchPersistenceTest.RecordingStore store, String roomType) {
    var f = new PlayFlowTest(); f.clock = new RoomServiceTest.MutableClock();
    f.clock.now.set(Instant.parse("2026-11-09T12:00:00Z").toEpochMilli());
    f.service = new RoomService(f.clock); f.service.statsStore(store);
    f.service.experimentPolicy(ManagedCollectionTest.policy()); f.service.statisticsPolicy(true, true);
    f.players = new RoomService.Identity[4];
    for (int i = 0; i < 4; i++) {
      f.players[i] = f.service.createSession("玩家" + i);
      if (i < consented) f.service.statisticsConsent(f.players[i], true, StatisticsService.CONSENT_VERSION);
      if (i < linked) f.service.linkAccountOutsideRoom(f.players[i], UUID.randomUUID().toString(), true);
    }
    f.room = f.service.create(f.players[0], 1, roomType).roomId();
    for (int i = 0; i < 4; i++) {
      if (i > 0) f.service.join(f.room, f.players[i]);
      f.service.connect(f.room, f.players[i], "socket-" + i); f.service.ready(f.room, f.players[i], true);
    }
    f.start(); f.auctionToPlay(); return f;
  }

  @Test void allGuestsMixedAndFourLoginSaveOnlyMinimalConsentedEligibleFacts() {
    for (int linked : List.of(0, 1, 2, 3, 4)) {
      var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(linked, 4, store);
      f.playRemainingDeal(); assertEquals(1, store.writes.size());
      var envelope = store.writes.get(0); var payload = envelope.payload();
      assertEquals(FinalMatchEnvelope.STATISTICS_RULE, payload.path("match").path("statistics_rule").asText());
      for (int seat = 0; seat < 4; seat++) {
        var person = payload.path("participants").get(seat);
        assertTrue(person.path("guest_id").isNull());
        if (seat < linked) assertEquals(f.service.accountId(f.players[seat]), person.path("account_id").asText());
        else assertTrue(person.path("account_id").isNull());
        assertFalse(envelope.canonicalJson().contains(f.players[seat].playerId()));
        assertFalse(envelope.canonicalJson().contains(f.players[seat].token()));
        assertFalse(envelope.canonicalJson().contains(f.players[seat].displayName()));
      }
      f.clock.now.addAndGet(5000); f.view(0); assertEquals(1, store.writes.size());
    }
  }

  @Test void olderClientsAndMissingConsentNeverEnterNewCollectionEvenIfAllLoggedIn() {
    for (int consented : List.of(0, 1, 3)) {
      var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(4, consented, store);
      assertEquals("AUTH_IN_ROOM", assertThrows(RoomService.Failure.class,
          () -> f.service.statisticsConsent(f.players[3], true, StatisticsService.CONSENT_VERSION)).code);
      f.playRemainingDeal(); assertTrue(store.writes.isEmpty());
      assertEquals("DISABLED", f.service.persistenceStatus(f.room, f.view(0).game().gameId(), f.players[0]));
    }
  }

  @Test void actualTakeoverRestoredBeforeSettlementStillSavesWithMixedHistoricalProvenance() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(1, 4, store, "CASUAL");
    int seat = f.view(0).game().play().nextSeat();
    f.service.disconnect(f.room, f.players[seat].playerId(), "socket-" + seat);
    f.clock.now.addAndGet(10_000); f.service.expireInitialSubscriptions();
    f.clock.now.addAndGet(900); f.service.expireInitialSubscriptions();
    assertEquals(1, f.view(0).game().play().currentTrick().size());
    f.service.connect(f.room, f.players[seat], "returned"); f.playRemainingDeal();
    assertEquals(1, store.writes.size());
    assertEquals("MIXED", store.writes.get(0).payload().path("participants").get(seat).path("control_type").asText());
  }

  @Test void takeoverAtSettlementSavesCasualAndLateReturnCannotChangeIt() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(4, 4, store, "CASUAL");
    var flow = new SettlementStatisticsFlowTest(); flow.beforeFinalCard(f);
    int actor = f.view(0).game().play().nextSeat();
    int offline = java.util.stream.IntStream.range(1, 4).filter(i -> i != actor).findFirst().orElseThrow();
    f.service.disconnect(f.room, f.players[offline].playerId(), "socket-" + offline);
    f.clock.now.addAndGet(10_000); f.service.expireInitialSubscriptions(); flow.playNext(f);
    assertEquals(1, store.writes.size());
    var frozen = store.writes.get(0);
    assertEquals("CASUAL", frozen.statisticsCategory());
    assertEquals("BOT", frozen.payload().path("participants").get(offline).path("settlement_control").asText());
    f.service.connect(f.room, f.players[offline], "too-late");
    f.clock.now.addAndGet(2000); f.service.expireInitialSubscriptions();
    assertEquals(List.of(frozen), store.writes);
  }

  @Test void featureRequiresExplicitManagedStorageConfigurationAndConsentVersion() {
    var rooms = new RoomService(); var who = rooms.createSession("Guest");
    assertFalse(rooms.statisticsEnabled()); rooms.statisticsPolicy(true, true); assertFalse(rooms.statisticsEnabled());
    rooms.experimentPolicy(ManagedCollectionTest.policy()); rooms.statisticsPolicy(true, false); assertFalse(rooms.statisticsEnabled());
    rooms.statisticsPolicy(false, true); assertFalse(rooms.statisticsEnabled());
    rooms.statisticsPolicy(true, true); assertTrue(rooms.statisticsEnabled());
    for (String version : Arrays.asList(null, "", "statistics-v0", "statistics-v1", "statistics-v2", "statistics-v3", "statistics-v5"))
      assertEquals("STATS_CONSENT_INVALID", assertThrows(RoomService.Failure.class,
          () -> rooms.statisticsConsent(who, true, version)).code);
    assertThrows(RoomService.Failure.class, () -> rooms.statisticsConsent(who, null, StatisticsService.CONSENT_VERSION));
    rooms.statisticsConsent(who, true, StatisticsService.CONSENT_VERSION);
    rooms.linkAccountOutsideRoom(who, UUID.randomUUID().toString(), true); rooms.unlinkAccountOutsideRoom(who);
    assertDoesNotThrow(() -> rooms.statisticsConsent(who, false, StatisticsService.CONSENT_VERSION));
  }

  @Test void casualNoContestSavesOnlyCompletedDealsAndNoWinner() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = fixture(1, 4, store, "CASUAL");
    for (int i = 1; i < 4; i++) f.service.leave(f.room, f.players[i]);
    f.service.disconnect(f.room, f.players[0].playerId(), "socket-0");
    f.clock.now.addAndGet(10_001); f.service.expireInitialSubscriptions();
    assertEquals(1, store.writes.size());
    assertEquals("CASUAL", store.writes.get(0).statisticsCategory());
    assertEquals(0, store.writes.get(0).payload().path("completed_deals").size());
    assertTrue(store.writes.get(0).payload().path("match").path("winner_team_id").isNull());
    assertEquals("NO_CONTEST", f.view(0).game().matchResult().outcome());
  }
}
