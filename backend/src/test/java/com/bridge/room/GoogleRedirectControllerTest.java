package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class GoogleRedirectControllerTest {
  RoomService rooms;RoomService.Identity guest;MockMvc mvc;AtomicInteger writes;String binding,nonce;boolean broken, cancelledAfterWrite, deceptiveStorageError;GoogleRedirectController controller;GoogleAuthService auth;long time;
  final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time);}};
  @BeforeEach void setup() {
    broken=false;cancelledAfterWrite=false;deceptiveStorageError=false;time=System.currentTimeMillis();rooms=new RoomService();guest=rooms.createSession("Guest");writes=new AtomicInteger();
    var store=new IdentityStore(){public boolean enabled(){return true;}
      public String findOrCreateAccount(String i,String s){throw new AssertionError();}
      public Profile findOrCreateForEntry(String i,String s){writes.incrementAndGet();if(cancelledAfterWrite)auth.cancel(guest,"https://bridge.example",nonce);if(deceptiveStorageError)throw RoomService.fail(401,"INVALID_GOOGLE_CREDENTIAL","storage error");if(broken)throw new RuntimeException();return new Profile(UUID.randomUUID().toString(),"Saved");}};
    GoogleCredentialVerifier verifier=(credential,audience,n)->{assertEquals(nonce,n);if(!"valid".equals(credential))throw RoomService.fail(401,"INVALID_GOOGLE_CREDENTIAL","invalid");return new GoogleCredentialVerifier.Principal("https://accounts.google.com","subject");};
    var policy=CollectionPolicy.managed("contact","retention").requireVerifiedErasureWorkflow(true).requireVerifiedQuarterPolicy(true);
    auth=new GoogleAuthService(rooms,store,verifier,"client",Set.of("https://bridge.example"),clock,policy);
    controller=new GoogleRedirectController(rooms,auth,true,"https://bridge.example",clock);
    mvc=MockMvcBuilders.standaloneSetup(controller).build();
  }
  String bearer(){return "Bearer "+guest.token();}
  void start() throws Exception {
    var result=mvc.perform(post("/api/auth/google/redirect/start").header("Authorization",bearer()).header("Origin","https://bridge.example")
      .contentType(MediaType.APPLICATION_JSON).content("{\"consent\":true,\"profileConsentVersion\":\"account-profile-v1\"}"))
      .andExpect(status().isOk()).andReturn().getResponse();
    String cookie=result.getHeader("Set-Cookie");assertTrue(cookie.contains("Secure"));assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=None"));
    binding=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));nonce=new ObjectMapper().readTree(result.getContentAsString()).path("nonce").asText();
  }
  void callback() throws Exception {
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .cookie(new Cookie("__Host-bridge_google_redirect",binding),new Cookie("g_csrf_token","csrf"))
      .content("credential=valid&g_csrf_token=csrf")).andExpect(status().isSeeOther())
      .andExpect(header().string("Location","https://bridge.example/?google_return=1"));
  }
  @Test void callbackStagesOnlyOriginalSessionClaimsExactlyOnce() throws Exception {
    start();callback();assertEquals(0,writes.get());var other=rooms.createSession("Other");
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization","Bearer "+other.token()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("FAILED"));assertEquals(0,writes.get());
    for(int i=0;i<2;i++)mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETE")).andExpect(jsonPath("$.status.signedIn").value(true));
    assertEquals(1,writes.get());
  }
  @Test void missingCsrfWrongBindingAndDuplicateFormsFailClosed() throws Exception {
    start();
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .cookie(new Cookie("__Host-bridge_google_redirect",binding)).content("credential=valid&g_csrf_token=csrf")).andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .cookie(new Cookie("__Host-bridge_google_redirect","wrong"),new Cookie("g_csrf_token","csrf")).content("credential=valid&g_csrf_token=csrf"))
      .andExpect(status().isConflict());
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("credential=a&credential=b"))
      .andExpect(status().isBadRequest());assertEquals(0,writes.get());
  }
  @Test void unknownCannotCancelRestartOrReplayWrite() throws Exception {
    start();callback();broken=true;
    for(int i=0;i<2;i++)mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("UNKNOWN"));
    mvc.perform(post("/api/auth/google/redirect/cancel").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("UNKNOWN"));
    mvc.perform(post("/api/auth/google/redirect/start").header("Authorization",bearer()).header("Origin","https://bridge.example")
      .contentType(MediaType.APPLICATION_JSON).content("{\"consent\":true,\"profileConsentVersion\":\"account-profile-v1\"}"))
      .andExpect(status().isConflict());assertEquals(1,writes.get());
  }
  @Test void cancelPreventsLateCallbackAndMissingBearerOrOriginReject() throws Exception {
    start();
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer())).andExpect(status().isForbidden());
    mvc.perform(get("/api/auth/google/redirect/status")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/google/redirect/cancel").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("FAILED"));
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .cookie(new Cookie("__Host-bridge_google_redirect",binding),new Cookie("g_csrf_token","csrf"))
      .content("credential=valid&g_csrf_token=csrf")).andExpect(status().isConflict());assertEquals(0,writes.get());
  }
  @Test void duplicateCallbackDoesNotReplaceFirstCredential() throws Exception {
    start();callback();
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .cookie(new Cookie("__Host-bridge_google_redirect",binding),new Cookie("g_csrf_token","csrf"))
      .content("credential=another&g_csrf_token=csrf")).andExpect(status().isConflict());
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("COMPLETE"));assertEquals(1,writes.get());
  }
  @Test void expiredReceivedCredentialCannotWriteAndCanStartNew() throws Exception {
    start();callback();time+=300001;controller.expireCredentials();
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("FAILED"));assertEquals(0,writes.get());start();
  }
  @Test void malformedCredentialFailsNonceVerifierBeforeStorage() throws Exception {
    start();
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .cookie(new Cookie("__Host-bridge_google_redirect",binding),new Cookie("g_csrf_token","csrf"))
      .content("credential=not-valid&g_csrf_token=csrf")).andExpect(status().isSeeOther());
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("FAILED"));assertEquals(0,writes.get());start();
  }
  @Test void revokedNonceFailsBeforeStorageAndAllowsNewStart() throws Exception {
    start();callback();auth.cancel(guest,"https://bridge.example",nonce);
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("FAILED"));assertEquals(0,writes.get());start();
  }
  @Test void disabledByDefaultAndUnsafeOriginsRejected() throws Exception {
    var disabled=new GoogleRedirectController(rooms,auth,false,"",clock);
    assertEquals(false,disabled.config().get("enabled"));
    assertThrows(RoomService.Failure.class,()->disabled.status(bearer()));
    for(String unsafe:List.of("http://bridge.example","https://bridge.example/", "https://bridge.example?x=1","https://user@bridge.example"))
      assertThrows(RuntimeException.class,()->new GoogleRedirectController(rooms,auth,true,unsafe,clock));
  }
  @Test void oversizedFormAndDuplicateCookiesRejected() throws Exception {
    start();
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .content("x="+"a".repeat(16384))).andExpect(status().isPayloadTooLarge());
    mvc.perform(post("/api/auth/google/redirect/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .cookie(new Cookie("__Host-bridge_google_redirect",binding),new Cookie("g_csrf_token","csrf"),new Cookie("g_csrf_token","csrf"))
      .content("credential=valid&g_csrf_token=csrf")).andExpect(status().isForbidden());assertEquals(0,writes.get());
  }

  @Test void cancellationAfterStorageAndStorageWrappedCodesRemainUnknown() throws Exception {
    start();callback();cancelledAfterWrite=true;
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("UNKNOWN"));assertEquals(1,writes.get());
    setup();start();callback();deceptiveStorageError=true;
    mvc.perform(post("/api/auth/google/redirect/finish").header("Authorization",bearer()).header("Origin","https://bridge.example"))
      .andExpect(jsonPath("$.state").value("UNKNOWN"));assertEquals(1,writes.get());
  }

}
