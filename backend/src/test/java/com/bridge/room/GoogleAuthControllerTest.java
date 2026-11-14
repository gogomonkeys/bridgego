package com.bridge.room;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GoogleAuthControllerTest {
  RoomService rooms; RoomService.Identity guest; MockMvc mvc;
  @BeforeEach void setup() {
    rooms=new RoomService();guest=rooms.createSession("Guest");
    var store=new IdentityStore(){public boolean enabled(){return true;}public String findOrCreateAccount(String i,String s){return UUID.randomUUID().toString();}};
    GoogleCredentialVerifier verifier=(credential,audience,nonce)->new GoogleCredentialVerifier.Principal("https://accounts.google.com","subject");
    var auth=new GoogleAuthService(rooms,store,verifier,"public-client",Set.of("https://bridge.example"),Clock.systemUTC(),new CollectionPolicy(Set.of(new GoogleCredentialVerifier.Principal("https://accounts.google.com","subject")),"test contact","test retention"));
    mvc=MockMvcBuilders.standaloneSetup(new GoogleAuthController(rooms,auth)).build();
  }
  @Test void publicConfigContainsOnlyPublicClient() throws Exception {
    mvc.perform(get("/api/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.clientId").value("public-client")).andExpect(jsonPath("$.accountId").doesNotExist());
  }
  @Test void sessionAndOriginAreRequired() throws Exception {
    mvc.perform(post("/api/auth/nonce").header("Origin","https://bridge.example")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/nonce").header("Authorization","Bearer "+guest.token())).andExpect(status().isForbidden());
    mvc.perform(get("/api/auth/status").header("Authorization","Bearer "+guest.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.signedIn").value(false));
  }
  @Test void loginAndLogoutExposeNoInternalAccountOrCredential() throws Exception {
    String bearer="Bearer "+guest.token();
    mvc.perform(post("/api/auth/nonce").header("Authorization",bearer).header("Origin","https://bridge.example"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.expiresInSeconds").value(300));
    mvc.perform(post("/api/auth/google").header("Authorization",bearer).header("Origin","https://bridge.example")
        .contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"signed-token\",\"consent\":true}"))
        .andExpect(status().isOk()).andExpect(content().json("{\"signedIn\":true,\"nicknameRequired\":false,\"nickname\":null}",true));
    mvc.perform(post("/api/auth/logout").header("Authorization",bearer).header("Origin","https://bridge.example"))
        .andExpect(status().isOk()).andExpect(content().json("{\"signedIn\":false,\"nicknameRequired\":false,\"nickname\":null}",true));
    Assertions.assertEquals(guest,rooms.authenticate(guest.token()));
  }
  @Test void explicitVerificationReturnsOnlyVerifiedPairAndDoesNotLink() throws Exception {
    String bearer="Bearer "+guest.token();
    mvc.perform(post("/api/auth/nonce").header("Authorization",bearer).header("Origin","https://bridge.example")
        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"verify\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/auth/google/verify").header("Authorization",bearer).header("Origin","https://bridge.example")
        .contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"signed-token\",\"consent\":true}"))
        .andExpect(status().isOk()).andExpect(content().json(
            "{\"issuer\":\"https://accounts.google.com\",\"subject\":\"subject\",\"admittedTester\":true}",true));
    Assertions.assertNull(rooms.accountId(guest));
  }

  @Test void acceptedLinkChallengeUnlinksBeforeFailureAndStatusStaysGuest() throws Exception {
    String bearer="Bearer "+guest.token();
    rooms.linkAccountOutsideRoom(guest,UUID.randomUUID().toString(),true);
    mvc.perform(post("/api/auth/nonce").header("Authorization",bearer).header("Origin","https://bridge.example"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/status").header("Authorization",bearer))
        .andExpect(status().isOk()).andExpect(content().json("{\"signedIn\":false,\"nicknameRequired\":false,\"nickname\":null}",true));
    mvc.perform(post("/api/auth/google").header("Authorization",bearer).header("Origin","https://bridge.example")
        .contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"signed-token\",\"consent\":false}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    mvc.perform(get("/api/auth/status").header("Authorization",bearer))
        .andExpect(status().isOk()).andExpect(content().json("{\"signedIn\":false,\"nicknameRequired\":false,\"nickname\":null}",true));
    Assertions.assertEquals(guest,rooms.authenticate(guest.token()));
  }

  @Test void seatedCancellationIsAllowedWithoutChangingCurrentLinkedAccount() throws Exception {
    String bearer="Bearer "+guest.token(), account=UUID.randomUUID().toString();
    rooms.linkAccountOutsideRoom(guest,account,true);
    var nonceResponse=mvc.perform(post("/api/auth/nonce").header("Authorization",bearer)
        .header("Origin","https://bridge.example").contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"verify\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String nonce=new ObjectMapper().readTree(nonceResponse).path("nonce").asText();
    String room=rooms.create(guest,1).roomId();
    mvc.perform(post("/api/auth/cancel").header("Authorization",bearer).header("Origin","https://bridge.example")
        .contentType(MediaType.APPLICATION_JSON).content("{\"nonce\":\""+nonce+"\"}"))
        .andExpect(status().isOk()).andExpect(content().json("{\"cancelled\":true}",true));
    Assertions.assertEquals(account,rooms.accountId(guest));
    rooms.leave(room,guest);
    mvc.perform(post("/api/auth/google/verify").header("Authorization",bearer).header("Origin","https://bridge.example")
        .contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"signed-token\",\"consent\":true}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_NONCE_EXPIRED"));
    Assertions.assertEquals(account,rooms.accountId(guest));
  }

}
