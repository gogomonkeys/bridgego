package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;

class StatisticsHttpTest {
  static final String ORIGIN = "https://bridge.example";
  MockMvc mvc(RoomService rooms, MatchPersistenceTest.RecordingStore store) {
    return MockMvcBuilders.standaloneSetup(new StatisticsController(rooms, new StatisticsService(rooms, store),
        ManagedCollectionTest.policy(), ORIGIN, new MockEnvironment())).addFilters(new BetaRequestFilter(rooms)).build();
  }
  RoomService rooms() {
    var rooms = new RoomService(); rooms.experimentPolicy(ManagedCollectionTest.policy()); rooms.statisticsPolicy(true, true);
    return rooms;
  }

  @Test void configIsPublicButAllStatisticsIncludingOverallAreDeniedToGuests() throws Exception {
    var rooms = rooms(); var guest = rooms.createSession("Guest"); var mvc = mvc(rooms, new MatchPersistenceTest.RecordingStore());
    mvc.perform(get("/api/statistics/config")).andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.consentVersion").value(StatisticsService.CONSENT_VERSION))
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/api/statistics")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/statistics").header("Authorization", "Bearer " + guest.token()))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STATS_LOGIN_REQUIRED"))
        .andExpect(jsonPath("$.overall").doesNotExist()).andExpect(jsonPath("$.personal").doesNotExist());
  }

  @Test void authenticatedRouteUsesOnlyServerAccountAndNeverAcceptsRequestedOtherAccount() throws Exception {
    var rooms = rooms(); var who = rooms.createSession("User"); String account = UUID.randomUUID().toString();
    rooms.linkAccountOutsideRoom(who, account, true); var mvc = mvc(rooms, new MatchPersistenceTest.RecordingStore());
    var result = mvc.perform(get("/api/statistics").queryParam("account_id", "another-account")
        .header("Authorization", "Bearer " + who.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.personal.matches").value(0))
        .andExpect(jsonPath("$.overall.matches").value(0)).andExpect(jsonPath("$.asOf").isString())
        .andExpect(header().string("Cache-Control", "no-store")).andReturn();
    String json = result.getResponse().getContentAsString();
    assertFalse(json.contains(account)); assertFalse(json.contains(who.playerId())); assertFalse(json.contains(who.token()));
    rooms.unlinkAccountOutsideRoom(who);
    mvc.perform(get("/api/statistics").header("Authorization", "Bearer " + who.token()))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.overall").doesNotExist());
  }

  @Test void consentRequiresValidOriginSessionCurrentVersionAndOutsideRoom() throws Exception {
    var rooms = rooms(); var who = rooms.createSession("Guest"); var mvc = mvc(rooms, new MatchPersistenceTest.RecordingStore());
    String body = "{\"consent\":true,\"version\":\"statistics-v4\"}";
    mvc.perform(put("/api/statistics/consent").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/statistics/consent").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    for (String origin : List.of("https://evil.test", "null", "https://bridge.example.evil.test"))
      mvc.perform(put("/api/statistics/consent").header("Origin", origin).header("Authorization", "Bearer " + who.token())
          .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    mvc.perform(put("/api/statistics/consent").header("Origin", ORIGIN).header("Authorization", "Bearer " + who.token())
        .contentType(MediaType.APPLICATION_JSON).content("{\"consent\":true,\"version\":\"old\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(put("/api/statistics/consent").header("Origin", ORIGIN).header("Authorization", "Bearer " + who.token())
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
    rooms.create(who, 1);
    mvc.perform(put("/api/statistics/consent").header("Origin", ORIGIN).header("Authorization", "Bearer " + who.token())
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUTH_IN_ROOM"));
  }

  @Test void disabledConfigAndReadNeverClaimNumbersOrMutateConsent() throws Exception {
    var rooms = rooms(); var who = rooms.createSession("User"); rooms.linkAccountOutsideRoom(who, UUID.randomUUID().toString(), true);
    rooms.statisticsPolicy(false, true); var mvc = mvc(rooms, new MatchPersistenceTest.RecordingStore());
    mvc.perform(get("/api/statistics/config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    mvc.perform(get("/api/statistics").header("Authorization", "Bearer " + who.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.personal").isEmpty()).andExpect(jsonPath("$.overall").isEmpty());
  }
}
