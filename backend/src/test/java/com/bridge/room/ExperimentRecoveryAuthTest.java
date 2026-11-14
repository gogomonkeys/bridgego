package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
class ExperimentRecoveryAuthTest {
 static final String ORIGIN="https://bridge.example";
 RoomService rooms; RoomService.Identity guest; GoogleAuthService auth;
 GoogleAuthServiceTest.MutableClock clock; GoogleAuthServiceTest.FakeStore store;
 GoogleAuthServiceTest.FakeVerifier verifier; int calls; Runnable during=()->{};
 @BeforeEach void setup(){
  clock=new GoogleAuthServiceTest.MutableClock(); clock.now=Instant.parse("2026-11-08T00:00:00Z").toEpochMilli();
  rooms=new RoomService();guest=rooms.createSession("Guest");store=new GoogleAuthServiceTest.FakeStore();verifier=new GoogleAuthServiceTest.FakeVerifier();
  auth=new GoogleAuthService(rooms,store,verifier,"client",Set.of(ORIGIN),clock,policy(),(i,s)->{calls++;during.run();return store.account;});
 }
 CollectionPolicy policy(){return CollectionPolicy.experiment(new ObjectMapper(),"[{\"issuer\":\"https://accounts.google.com\",\"subject\":\"subject\"}]","contact","retention","00000000-0000-0000-0000-000000000001","2026-11-08T13:00:00Z",clock);}
 @Test void recoveryIsExplicitAndProductionStaysClosed(){
  assertTrue(auth.config().experimentRecoveryEnabled());assertFalse(auth.config().collectionReady());
  assertThrows(RoomService.Failure.class,()->auth.nonce(guest,ORIGIN,"link"));
  auth.nonce(guest,ORIGIN,"recover");assertTrue(auth.recover(guest,ORIGIN,"synthetic",true).signedIn());
  assertEquals(1,calls);assertEquals(0,store.calls);assertEquals(store.account,rooms.accountId(guest));
  assertThrows(RoomService.Failure.class,()->auth.nonce(guest,ORIGIN,"recover"));
 }
 @Test void recoveryDisabledByDefault(){
  var off=new GoogleAuthService(rooms,store,verifier,"client",Set.of(ORIGIN),clock,policy());
  assertFalse(off.config().experimentRecoveryEnabled());assertThrows(RoomService.Failure.class,()->off.nonce(guest,ORIGIN,"recover"));
  assertThrows(RoomService.Failure.class,()->off.recover(guest,ORIGIN,"synthetic",true));assertEquals(0,calls);
 }
 @Test void wrongPurposeAndReplayCannotClaim(){
  auth.nonce(guest,ORIGIN,"verify");assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));
  assertEquals(0,calls);auth.nonce(guest,ORIGIN,"recover");auth.recover(guest,ORIGIN,"synthetic",true);
  assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(1,calls);
 }
 @Test void exactPrincipalConsentAndOriginBeforeStorage(){
  for(var p:List.of(new GoogleCredentialVerifier.Principal("https://accounts.google.com","other"),new GoogleCredentialVerifier.Principal("https://evil.example","subject"))){
   verifier.principal=p;auth.nonce(guest,ORIGIN,"recover");assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));
  }
  auth.nonce(guest,ORIGIN,"recover");assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",false));
  assertThrows(RoomService.Failure.class,()->auth.nonce(guest,"https://evil.example","recover"));assertEquals(0,calls);
 }
 @Test void cancellationBeforeStoragePreventsClaim(){
  var n=auth.nonce(guest,ORIGIN,"recover");verifier.during=()->auth.cancel(guest,ORIGIN,n.nonce());
  assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(0,calls);
 }
 @Test void cancellationAfterClaimCannotLink(){
  var n=auth.nonce(guest,ORIGIN,"recover");during=()->auth.cancel(guest,ORIGIN,n.nonce());
  assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(1,calls);assertFalse(auth.status(guest).signedIn());
 }
 @Test void expiryBeforeAndDuringVerificationStopsClaim(){
  auth.nonce(guest,ORIGIN,"recover");verifier.during=()->clock.now+=Duration.ofHours(14).toMillis();
  assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(0,calls);
  assertFalse(auth.config().experimentRecoveryEnabled());
 }
 @Test void roomEntryOrExpiryDuringClaimCannotLink(){
  auth.nonce(guest,ORIGIN,"recover");during=()->rooms.create(guest,1);
  assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(1,calls);assertFalse(auth.status(guest).signedIn());
 }
 @Test void storageFailureIsSafeAndNoFallback(){
  auth=new GoogleAuthService(rooms,store,verifier,"client",Set.of(ORIGIN),clock,policy(),(i,s)->{calls++;throw new IllegalStateException("private details");});
  auth.nonce(guest,ORIGIN,"recover");var e=assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));
  assertEquals("EXPERIMENT_UNAVAILABLE",e.code);assertFalse(e.getMessage().contains("private"));assertEquals(0,store.calls);assertEquals(1,calls);assertFalse(auth.status(guest).signedIn());
 }

 @Test void recoveryHttpRequiresOriginBearerAndConsent() throws Exception {
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new GoogleAuthController(rooms,auth)).build();
  String token="Bearer "+guest.token();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/google/recover").header("Origin",ORIGIN)
    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"credential\":\"synthetic\",\"consent\":true}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/google/recover").header("Authorization",token)
    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"credential\":\"synthetic\",\"consent\":true}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
  auth.nonce(guest,ORIGIN,"recover");
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/google/recover").header("Authorization",token).header("Origin",ORIGIN)
    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"credential\":\"synthetic\",\"consent\":true}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("{\"signedIn\":true,\"nicknameRequired\":false,\"nickname\":null}",true));
  assertEquals(1,calls);
 }
 @Test void recoveryNonceCannotBeUsedForVerifyOrLogin(){
  auth.nonce(guest,ORIGIN,"recover");assertThrows(RoomService.Failure.class,()->auth.verifyOnly(guest,ORIGIN,"synthetic",true));
  auth.nonce(guest,ORIGIN,"recover");assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"synthetic",true));assertEquals(0,calls);
 }
 @Test void supersededRecoveryDoesNotClaim(){
  auth.nonce(guest,ORIGIN,"recover");verifier.during=()->auth.nonce(guest,ORIGIN,"verify");
  assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(0,calls);
 }
 @Test void expiryDuringClaimCannotLink(){
  auth.nonce(guest,ORIGIN,"recover");during=()->clock.now+=Duration.ofHours(14).toMillis();
  assertThrows(RoomService.Failure.class,()->auth.recover(guest,ORIGIN,"synthetic",true));assertEquals(1,calls);assertFalse(auth.status(guest).signedIn());
 }
}
