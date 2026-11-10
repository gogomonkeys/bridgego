package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FixedRoomTypeHttpTest {
  @ParameterizedTest @ValueSource(strings={"FORMAL","CASUAL"})
  void explicitTypeIsReturnedByCreateAndListing(String type) throws Exception {
    var rooms=new RoomService();var who=rooms.createSession("Host");
    var mvc=MockMvcBuilders.standaloneSetup(new RoomController(rooms)).build();
    mvc.perform(post("/api/rooms").header("Authorization","Bearer "+who.token())
        .contentType(MediaType.APPLICATION_JSON).content("{\"plannedDeals\":5,\"roomType\":\""+type+"\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.roomType").value(type)).andExpect(jsonPath("$.plannedDeals").value(5));
    mvc.perform(get("/api/rooms").header("Authorization","Bearer "+who.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].roomType").value(type));
  }
  @ParameterizedTest @ValueSource(strings={"", "{}", "{\"plannedDeals\":1}"})
  void omittedTypeRetainsCasualCompatibility(String body) throws Exception {
    var rooms=new RoomService();var who=rooms.createSession("Host");
    var mvc=MockMvcBuilders.standaloneSetup(new RoomController(rooms)).build();
    mvc.perform(post("/api/rooms").header("Authorization","Bearer "+who.token())
        .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.roomType").value("CASUAL"));
  }
  @ParameterizedTest @ValueSource(strings={"null", "0", "true", "[]", "{}", "\"\"", "\"formal\"", "\"PRACTICE\""})
  void invalidTypeCreatesNothing(String type) throws Exception {
    var rooms=new RoomService();var who=rooms.createSession("Host");
    var mvc=MockMvcBuilders.standaloneSetup(new RoomController(rooms)).build();
    mvc.perform(post("/api/rooms").header("Authorization","Bearer "+who.token())
        .contentType(MediaType.APPLICATION_JSON).content("{\"roomType\":"+type+"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ROOM_TYPE"));
    assertTrue(rooms.list().isEmpty());assertNull(rooms.me(who).roomId());
  }
}
