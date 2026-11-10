package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real room transitions with synthetic local users, clocks, bots, and store. */
class StatisticsCategoryFlowTest {
  static String action() { return UUID.randomUUID().toString(); }

  @Test void oneTwoAndThreeStartingBotsSaveCasualWithoutInventingPersonalPlayers() {
    for (int bots = 1; bots <= 3; bots++) for (int planned : List.of(1, 3, 5)) {
      var clock = new RoomServiceTest.MutableClock();
      clock.now.set(Instant.parse("2026-11-10T00:00:00Z").toEpochMilli());
      var service = new RoomService(clock); var store = new MatchPersistenceTest.RecordingStore();
      service.statsStore(store); service.experimentPolicy(ManagedCollectionTest.policy()); service.statisticsPolicy(true, true);
      List<RoomService.Identity> humans = new ArrayList<>();
      for (int seat = 0; seat < 4 - bots; seat++) {
        var id = service.createSession("玩家" + seat); humans.add(id);
        service.statisticsConsent(id, true, StatisticsService.CONSENT_VERSION);
        if (seat == 0) service.linkAccountOutsideRoom(id, UUID.randomUUID().toString(), true);
      }
      var host = humans.get(0); String room = service.create(host, planned).roomId();
      for (var id : humans) {
        if (id != host) service.join(room, id);
        service.connect(room, id, "socket-" + id.playerId()); service.ready(room, id, true);
      }
      for (int seat = humans.size(); seat < 4; seat++)
        service.addComputer(room, host, action(), service.get(room, host).revision(), seat);
      service.start(room, host, action(), service.get(room, host).revision());
      complete(service, room, humans, clock);
      assertEquals(1, store.writes.size(), "bots=" + bots + " planned=" + planned);
      var saved = store.writes.get(0); assertEquals("CASUAL", saved.statisticsCategory());
      assertEquals(planned, saved.payload().path("match").path("completed_deals").asInt());
      assertEquals("elapsed-including-pauses-v1", saved.payload().path("match").path("deal_timing_rule").asText());
      for (int index = 0; index < planned; index++) {
        var elapsed = saved.payload().path("completed_deals").get(index).path("elapsed_ms");
        assertTrue(elapsed.isIntegralNumber()); assertTrue(elapsed.longValue() >= 0);
        assertEquals(service.get(room, host).game().completedDealResults().get(index).elapsedMs(), elapsed.longValue());
      }
      int savedBots = 0;
      for (var person : saved.payload().path("participants")) {
        assertTrue(person.path("guest_id").isNull());
        if ("BOT".equals(person.path("player_type").asText())) {
          savedBots++; assertTrue(person.path("account_id").isNull());
          assertEquals("BOT", person.path("settlement_control").asText());
        }
      }
      assertEquals(bots, savedBots);
      var report = new StatisticsService(service, store, Clock.fixed(clock.instant(), ZoneOffset.UTC)).read(host);
      assertEquals(0, report.personal().matches()); assertEquals(0, report.overall().matches());
      assertEquals(1, report.casualPersonal().matches()); assertEquals(1, report.casualOverall().matches());
      assertEquals(planned, report.casualPersonal().completedDeals());
      for (var id : humans) {
        assertFalse(saved.canonicalJson().contains(id.playerId()));
        assertFalse(saved.canonicalJson().contains(id.displayName()));
      }
      if (humans.size() > 1) assertEquals("STATS_LOGIN_REQUIRED", assertThrows(RoomService.Failure.class,
          () -> new StatisticsService(service, store).read(humans.get(1))).code);
    }
  }

  @Test void permanentReplacementCountsOriginalParticipantsOnlyInCasual() {
    var store = new MatchPersistenceTest.RecordingStore(); var f = StatisticsCollectionFlowTest.fixture(4, 4, store, "CASUAL");
    String account = f.service.accountId(f.players[1]);
    f.service.leave(f.room, f.players[1]);
    complete(f.service, f.room, List.of(f.players[0], f.players[2], f.players[3]), f.clock);
    assertEquals(1, store.writes.size()); var saved = store.writes.get(0);
    assertEquals("CASUAL", saved.statisticsCategory());
    var replaced = saved.payload().path("participants").get(1);
    assertEquals(account, replaced.path("account_id").asText());
    assertEquals("HUMAN", replaced.path("player_type").asText());
    assertEquals("MIXED", replaced.path("control_type").asText());
    assertEquals("BOT", replaced.path("settlement_control").asText());
    var report = new StatisticsService(f.service, store, Clock.fixed(f.clock.instant(), ZoneOffset.UTC)).read(f.players[1]);
    assertEquals(0, report.personal().matches()); assertEquals(1, report.casualPersonal().matches());
  }

  static void complete(RoomService service, String room, List<RoomService.Identity> humans,
      RoomServiceTest.MutableClock clock) {
    var host = humans.get(0);
    for (int step = 0; step < 12000; step++) {
      if ("MATCH_RESULT".equals(service.get(room, host).phase())) return;
      for (var id : humans) {
        var s = service.get(room, id); var g = s.game();
        if ("MATCH_RESULT".equals(s.phase())) return;
        if (g.redeal().canChoose()) service.redeal(room, id, g.gameId(), g.dealId(), action(), s.revision(), false);
        else if (g.privilege().canChoose()) service.privilege(room, id, g.gameId(), g.dealId(), action(), s.revision(), false);
        else if (!s.you().legalCalls().isEmpty()) service.call(room, id, g.gameId(), g.dealId(), action(), s.revision(), "PASS", null, null);
        else if (!s.you().legalCards().isEmpty()) service.play(room, id, g.gameId(), g.dealId(), action(), s.revision(), s.you().legalCards().get(0));
        else if (s.you().canAcknowledgeResult()) service.acknowledgeResult(room, id, g.gameId(), g.dealId(), action(), s.revision());
      }
      clock.now.addAndGet(1000); service.expireInitialSubscriptions();
    }
    fail("Synthetic mixed match did not complete within bounded moves");
  }
}
