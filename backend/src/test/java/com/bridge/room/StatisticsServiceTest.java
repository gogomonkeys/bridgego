package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StatisticsServiceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static FinalMatchEnvelope changed(FinalMatchEnvelope original, java.util.function.Consumer<ObjectNode> change) {
    var payload = (ObjectNode) original.payload(); change.accept(payload);
    return FinalMatchEnvelope.create(original.matchId(), original.revision(), JSON.convertValue(payload, Map.class));
  }
  static Clock clock(PlayFlowTest f) { return Clock.fixed(f.clock.instant(), ZoneOffset.UTC); }

  @Test void personalIsAuthenticatedAccountOnlyAndOverallIncludesGuestsWithoutIdentityFields() throws Exception {
    var store = new MatchPersistenceTest.RecordingStore(); var f = StatisticsCollectionFlowTest.fixture(2, 4, store);
    f.playRemainingDeal(); var service = new StatisticsService(f.service, store, clock(f));
    for (int seat = 0; seat < 2; seat++) {
      var report = service.read(f.players[seat]); var end = f.view(seat).game();
      assertEquals("AVAILABLE", report.status()); assertEquals(1, report.personal().matches());
      assertEquals(1, report.overall().matches()); assertEquals(1, report.personal().completedDeals());
      String team = seat % 2 == 0 ? "NS" : "EW"; String winner = end.matchResult().winnerTeamId();
      assertEquals(winner == null ? 1 : 0, report.personal().draws());
      assertEquals(team.equals(winner) ? 1 : 0, report.personal().wins());
      assertEquals(winner != null && !team.equals(winner) ? 1 : 0, report.personal().losses());
      assertEquals((long) end.teamTotals().get(team), report.personal().totalScore());
      String json = JSON.writeValueAsString(report);
      assertFalse(json.contains(store.writes.get(0).matchId()));
      for (var player : f.players) {
        assertFalse(json.contains(player.playerId())); assertFalse(json.contains(player.token()));
        assertFalse(json.contains(player.displayName()));
      }
      assertFalse(json.contains(f.service.accountId(f.players[seat])));
    }
    assertEquals("STATS_LOGIN_REQUIRED", assertThrows(RoomService.Failure.class, () -> service.read(f.players[2])).code);
  }

  @Test void legacyDataNeverBackfillsAndRepeatedExactCommitCountsOnce() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = StatisticsCollectionFlowTest.fixture(1, 4, store); f.playRemainingDeal();
    var original = store.writes.get(0); String account = f.service.accountId(f.players[0]);
    var legacy = changed(original, p -> {
      ((ObjectNode)p.path("match")).remove(List.of("statistics_rule", "match_category", "room_type", "lifetime_rule"));
      p.path("participants").forEach(person -> ((ObjectNode) person).remove("settlement_control"));
    });
    assertEquals(0, StatisticsService.project(List.of(legacy), account, f.clock.instant()).overall().matches());
    assertEquals(1, StatisticsService.project(List.of(original, original), account, f.clock.instant()).overall().matches());
    assertThrows(IllegalArgumentException.class, () -> StatisticsService.project(List.of(original, legacy), account, f.clock.instant()));
  }

  @Test void quarterBoundaryAndScoringVersionRestrictBothPersonalAndOverall() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = StatisticsCollectionFlowTest.fixture(1, 4, store); f.playRemainingDeal();
    var original = store.writes.get(0); String account = f.service.accountId(f.players[0]);
    var boundary = Instant.parse("2026-12-31T16:00:00Z");
    assertEquals(0, StatisticsService.project(List.of(original), account, boundary).overall().matches());
    var current = changed(original, p -> ((ObjectNode)p.path("match")).put("finished_at", boundary.toString()));
    var report = StatisticsService.project(List.of(current), account, boundary);
    assertEquals("2027-Q1", report.quarter()); assertEquals(1, report.personal().matches());
    var otherScore = changed(current, p -> ((ObjectNode)p.path("match")).put("score_version", "later-scoring-v2"));
    assertEquals(0, StatisticsService.project(List.of(otherScore), account, boundary).overall().matches());
    assertThrows(IllegalArgumentException.class, () -> StatisticsService.project(List.of(current), account, boundary.minusNanos(1)));
  }

  @Test void erasureRemovesPersonalContributionButNotNonidentifyingOverallCounts() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = StatisticsCollectionFlowTest.fixture(1, 4, store); f.playRemainingDeal();
    String account = f.service.accountId(f.players[0]);
    var erased = changed(store.writes.get(0), p -> ((ObjectNode)p.path("participants").get(0))
        .putNull("account_id").put("erased_marker", UUID.randomUUID().toString()));
    var report = StatisticsService.project(List.of(erased), account, f.clock.instant());
    assertEquals(0, report.personal().matches()); assertEquals(1, report.overall().matches());
  }

  @Test void unavailableAndDisabledNeverPretendToBeEmptyStatistics() {
    var store = new MatchPersistenceTest.RecordingStore() {
      @Override public List<FinalMatchEnvelope> exportCommitted() { throw new IllegalStateException("private data do not expose"); }
    };
    var f = StatisticsCollectionFlowTest.fixture(1, 4, store); var service = new StatisticsService(f.service, store, clock(f));
    var failure = service.read(f.players[0]); assertEquals("UNAVAILABLE", failure.status());
    assertNull(failure.personal()); assertNull(failure.overall());
    f.service.statisticsPolicy(false, true); var disabled = service.read(f.players[0]);
    assertEquals("DISABLED", disabled.status()); assertNull(disabled.personal());
    assertThrows(RoomService.Failure.class, () -> service.read(f.players[1]));
  }

  @Test void accountChangeDuringRemoteReadCannotReceivePriorAccountResult() {
    var rooms = new RoomService(); rooms.experimentPolicy(ManagedCollectionTest.policy()); rooms.statisticsPolicy(true, true);
    var who = rooms.createSession("User"); rooms.linkAccountOutsideRoom(who, UUID.randomUUID().toString(), true);
    var store = new MatchPersistenceTest.RecordingStore() {
      @Override public List<FinalMatchEnvelope> exportCommitted() { rooms.unlinkAccountOutsideRoom(who); return List.of(); }
    };
    var service = new StatisticsService(rooms, store);
    assertEquals("STATS_LOGIN_REQUIRED", assertThrows(RoomService.Failure.class, () -> service.read(who)).code);
  }

  @Test void remoteReadNeverHoldsGameplayLockAndOnlyOneReadCanBeInFlight() throws Exception {
    var rooms = new RoomService(); rooms.experimentPolicy(ManagedCollectionTest.policy()); rooms.statisticsPolicy(true, true);
    var who = rooms.createSession("User"); rooms.linkAccountOutsideRoom(who, UUID.randomUUID().toString(), true);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
    var store = new MatchPersistenceTest.RecordingStore() {
      @Override public List<FinalMatchEnvelope> exportCommitted() {
        calls.incrementAndGet(); entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        return List.of();
      }
    };
    var service = new StatisticsService(rooms, store); var pool = Executors.newSingleThreadExecutor();
    try {
      var first = pool.submit(() -> service.read(who)); assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertNotNull(rooms.createSession("Other")));
      assertEquals("UNAVAILABLE", service.read(who).status()); assertEquals(1, calls.get());
      release.countDown(); assertEquals("AVAILABLE", first.get(2, TimeUnit.SECONDS).status());
    } finally { release.countDown(); pool.shutdownNow(); }
  }

  @Test void guestHistoryNeverBecomesPersonalAfterLaterLogin() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = StatisticsCollectionFlowTest.fixture(0, 4, store);
    f.playRemainingDeal(); f.service.leave(f.room, f.players[0]);
    f.service.linkAccountOutsideRoom(f.players[0], UUID.randomUUID().toString(), true);
    var report = new StatisticsService(f.service, store, clock(f)).read(f.players[0]);
    assertEquals(0, report.personal().matches()); assertEquals(1, report.overall().matches());
  }

  @Test void onlyCommittedExportCountsAndUnavailableReadDoesNotReuseOldTotals() {
    var backing = new MatchPersistenceTest.RecordingStore(); var f = StatisticsCollectionFlowTest.fixture(1, 4, backing);
    f.playRemainingDeal(); var exportPhase = new AtomicInteger();
    var store = new MatchPersistenceTest.RecordingStore() {
      @Override public List<FinalMatchEnvelope> exportCommitted() {
        if (exportPhase.get() == 0) return List.of();
        if (exportPhase.get() == 1) return backing.writes;
        throw new IllegalStateException("Unavailable after prior successful read");
      }
    };
    var service = new StatisticsService(f.service, store, clock(f));
    assertEquals(0, service.read(f.players[0]).overall().matches());
    exportPhase.set(1); assertEquals(1, service.read(f.players[0]).personal().matches());
    exportPhase.set(2); var unavailable = service.read(f.players[0]);
    assertEquals("UNAVAILABLE", unavailable.status()); assertNull(unavailable.personal()); assertNull(unavailable.overall());
  }
}
