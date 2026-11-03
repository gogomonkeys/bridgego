package com.bridge.room;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

class ComputerControllerTest {
  RoomService service; RoomService.Identity host; String room; MockMvc mvc;
  @BeforeEach void setup() {
    service=new RoomService(new RoomServiceTest.MutableClock());host=service.createSession("玩家");room=service.create(host).roomId();service.connect(room,host,"s");
    mvc=MockMvcBuilders.standaloneSetup(new RoomController(service)).addFilters(new BetaRequestFilter(service)).build();
  }
  String request(int seat) { return "{\"actionId\":\""+UUID.randomUUID()+"\",\"expectedRevision\":"+service.get(room,host).revision()+",\"seat\":"+seat+"}"; }
  @Test void addRemoveAreAuthenticatedTypedIdempotentNoStoreCommands() throws Exception {
    String body=request(1);
    mvc.perform(post("/api/rooms/"+room+"/bots").contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/rooms/"+room+"/bots").header("Authorization","Bearer "+host.token()).contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
      .andExpect(jsonPath("$.outcome").value("APPLIED")).andExpect(jsonPath("$.room.players[1].playerType").value("BOT"))
      .andExpect(jsonPath("$.room.players[1].token").doesNotExist()).andExpect(jsonPath("$.room.players[1].ownHand").doesNotExist());
    mvc.perform(post("/api/rooms/"+room+"/bots").header("Authorization","Bearer "+host.token()).contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("REPLAYED"));
    String id=service.get(room,host).players().get(1).playerId();
    mvc.perform(delete("/api/rooms/"+room+"/bots/"+id).header("Authorization","Bearer "+host.token()).contentType(MediaType.APPLICATION_JSON).content(request(1)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.room.players",hasSize(1)));
  }
  @Test void malformedAndCrossRoomRequestsCannotChangeSeats() throws Exception {
    mvc.perform(post("/api/rooms/"+room+"/bots").header("Authorization","Bearer "+host.token()).contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isBadRequest());
    var outsider=service.createSession("外人");
    mvc.perform(post("/api/rooms/"+room+"/bots").header("Authorization","Bearer "+outsider.token()).contentType(MediaType.APPLICATION_JSON).content(request(1)))
      .andExpect(status().isForbidden());
  }
}
