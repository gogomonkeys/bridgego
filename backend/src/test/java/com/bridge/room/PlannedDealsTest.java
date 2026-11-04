package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlannedDealsTest {
  @ParameterizedTest @ValueSource(ints = {1, 3, 5})
  void fullMatchAndRematch(int count) {
    var f = new PlayFlowTest();
    f.clock = new RoomServiceTest.MutableClock(); f.service = new RoomService(f.clock);
    f.players = new RoomService.Identity[4];
    for (int i = 0; i < 4; i++) f.players[i] = f.service.createSession("P" + i);
    f.room = f.service.create(f.players[0], count).roomId();
    assertEquals(count, f.service.list().get(0).plannedDeals());
    for (int i = 0; i < 4; i++) {
      if (i > 0) f.service.join(f.room, f.players[i]);
      f.service.connect(f.room, f.players[i], "socket-" + i);
      f.service.ready(f.room, f.players[i], true);
      assertEquals(count, f.view(i).plannedDeals());
    }
    f.start();
    for (int deal = 1; deal <= count; deal++) {
      assertEquals(count, f.view(0).game().plannedDeals());
      assertEquals(deal, f.view(0).game().dealIndex());
      assertEquals((deal - 1) % 4, f.view(0).game().dealerSeat());
      f.auctionToPlay(); f.playRemainingDeal();
      var s = f.view(0);
      assertEquals(deal, s.game().completedDeals());
      assertEquals(13, s.game().completedDealResults().get(deal - 1).completedTricks().size());
      for (String team : new String[]{"NS", "EW"}) {
        int sum = s.game().completedDealResults().stream().mapToInt(r -> r.scores().get(team).total()).sum();
        assertEquals(sum, s.game().teamTotals().get(team));
      }
      if (deal < count) {
        assertEquals("DEAL_RESULT", s.phase()); assertNull(s.game().matchResult());
        assertEquals(90_000, s.game().operationTimer().remainingMs());
        if (deal % 2 == 0) {
          f.clock.now.addAndGet(90_000);
          assertEquals(deal + 1, f.view(0).game().dealIndex());
        } else f.advanceResult();
      } else {
        assertEquals("MATCH_RESULT", s.phase()); assertNull(s.game().operationTimer());
        assertEquals(count == 3 ? "THREE_DEALS_COMPLETE" : "PLANNED_DEALS_COMPLETE", s.game().matchResult().reason());
        f.clock.now.addAndGet(180_000); assertEquals(deal, f.view(0).game().completedDeals());
      }
    }
    var last = f.view(0);
    var waiting = f.service.rematch(f.room, f.players[0], last.game().gameId(), PlayFlowTest.action(), last.revision()).room();
    assertEquals(count, waiting.plannedDeals());
    assertEquals(count, waiting.lastMatch().completedDealResults().size());
    for (int i = 0; i < 4; i++) f.service.ready(f.room, f.players[i], true);
    f.start();
    assertEquals(count, f.view(0).game().plannedDeals()); assertEquals(1, f.view(0).game().dealIndex());
    assertEquals(0, f.view(0).game().completedDeals());
  }
  @ParameterizedTest @ValueSource(strings = {"", "{}", "{\"plannedDeals\":null}", "{\"plannedDeals\":1}", "{\"plannedDeals\":3}", "{\"plannedDeals\":5}"})
  void createAcceptsChoicesAndLegacyRequests(String body) throws Exception {
    var service = new RoomService(new RoomServiceTest.MutableClock()); var who = service.createSession("Host");
    var mvc = MockMvcBuilders.standaloneSetup(new RoomController(service)).build();
    int count = body.contains(":1") ? 1 : body.contains(":5") ? 5 : 3;
    mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + who.token()).contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isOk()).andExpect(jsonPath("$.plannedDeals").value(count));
  }
  @ParameterizedTest @ValueSource(strings = {"0", "2", "4", "6", "-1", "1.5", "3.0", "\"3\"", "true", "[]", "{}", "999999999999999999999"})
  void invalidInputNeverCreatesRoom(String value) throws Exception {
    var service = new RoomService(new RoomServiceTest.MutableClock()); var who = service.createSession("Host");
    var mvc = MockMvcBuilders.standaloneSetup(new RoomController(service)).build();
    mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + who.token()).contentType(MediaType.APPLICATION_JSON).content("{\"plannedDeals\":" + value + "}"))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PLANNED_DEALS"));
    assertTrue(service.list().isEmpty()); assertNull(service.me(who).roomId());
  }
}
