package com.bridge.room;
import com.bridge.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


class FriendsControllerTest {
 @Test void rejectsUnauthenticatedAndCrossOriginBeforeStorage()throws Exception{
  RoomService rooms=new RoomService();SocialStore store=new FriendsServiceTest.Store(){ public boolean enabled(){throw new AssertionError("Unexpected storage access");} };
  var controller=new FriendsController(rooms,new FriendsService(rooms,store),CollectionPolicy.restrictedEmpty(),"",new MockEnvironment());
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  mvc.perform(get("/api/friends")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/friends/initialize").header("Origin","https://evil.invalid").contentType("application/json").content("{\"actionId\":\"x\"}"))
    .andExpect(status().isForbidden());

 }
 @Test void knownInvalidCodeIsNotAnUncertainWrite()throws Exception{
  RoomService rooms=new RoomService();FriendsService service=new FriendsService(rooms,new FriendsServiceTest.Store()) {
    @Override public View request(RoomService.Identity who,String c,String r,String p,String a){throw new IllegalArgumentException("Friend unavailable");}
  };
  var who=rooms.createSession("Alice");

  var mvc=MockMvcBuilders.standaloneSetup(new FriendsController(rooms,service,CollectionPolicy.restrictedEmpty(),"",new MockEnvironment())).build();
  mvc.perform(post("/api/friends/requests").header("Origin","http://localhost:5173").header("Authorization","Bearer "+who.token())
      .contentType("application/json").content("{\"actionId\":\"abc\",\"friendCode\":\"bad\"}"))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FRIENDS_INVALID_REQUEST"));
 }
}
