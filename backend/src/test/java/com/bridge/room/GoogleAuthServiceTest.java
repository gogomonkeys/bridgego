package com.bridge.room;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
class GoogleAuthServiceTest {
  static final String ORIGIN = "https://bridge.example";
  RoomService rooms; FakeStore store; FakeVerifier verifier; GoogleAuthService auth;
  RoomService.Identity guest; MutableClock clock;
  static class MutableClock extends Clock {
    volatile long now=1000; public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId z){return this;} public Instant instant(){return Instant.ofEpochMilli(now);}
  }
  static class FakeStore implements IdentityStore {
    String account=UUID.randomUUID().toString(); int calls; boolean fail; Runnable during=()->{};
    public boolean enabled(){return true;}
    public String findOrCreateAccount(String issuer,String subject){calls++;during.run(); if(fail)throw new IllegalStateException("private");return account;}
  }
  static class FakeVerifier implements GoogleCredentialVerifier {
    int calls; String nonce; Principal principal=new Principal("https://accounts.google.com","subject"); Runnable during=()->{};
    public Principal verify(String credential,String audience,String nonce){calls++;this.nonce=nonce;during.run();return principal;}
  }
  @BeforeEach void setup() {
    rooms=new RoomService();guest=rooms.createSession("Guest");store=new FakeStore();verifier=new FakeVerifier();clock=new MutableClock();
    auth=new GoogleAuthService(rooms,store,verifier,"client",Set.of(ORIGIN),clock,new CollectionPolicy(Set.of(new GoogleCredentialVerifier.Principal("https://accounts.google.com","subject")),"test contact","test retention"));
  }
  @Test void upgradePreservesGuestIdentityAndConsumesNonce() {
    var nonce=auth.nonce(guest,ORIGIN);assertTrue(auth.login(guest,ORIGIN,"credential",true).signedIn());
    assertEquals(nonce.nonce(),verifier.nonce);assertEquals(store.account,rooms.accountId(guest));
    assertEquals(guest,rooms.authenticate(guest.token()));
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));assertEquals(1,verifier.calls);
  }
  @Test void consentRequiredAndAttemptConsumesChallenge() {
    auth.nonce(guest,ORIGIN);assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",false));
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));assertEquals(0,verifier.calls);
  }
  @Test void nonceIsSessionBoundAndExpires() {
    auth.nonce(guest,ORIGIN);var other=rooms.createSession("Other");
    assertThrows(RoomService.Failure.class,()->auth.login(other,ORIGIN,"credential",true));
    clock.now=301000;assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));assertEquals(0,verifier.calls);
  }
  @Test void missingOrForeignOriginRejected() {
    for(String origin:Arrays.asList(null,"null","https://evil.test",ORIGIN+"/")) {
      assertThrows(RoomService.Failure.class,()->auth.nonce(guest,origin));
      assertThrows(RoomService.Failure.class,()->auth.login(guest,origin,"credential",true));
      assertThrows(RoomService.Failure.class,()->auth.logout(guest,origin));
    }
    assertEquals(0,verifier.calls);assertNull(rooms.accountId(guest));
  }
  @Test void storageFailureDoesNotUpgrade() {
    auth.nonce(guest,ORIGIN);store.fail=true;
    var failure=assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));
    assertEquals(503,failure.status);assertFalse(failure.getMessage().contains("private"));assertNull(rooms.accountId(guest));
  }
  @Test void disabledByDefaultAndRoomGuardApplied() {
    var disabled=new GoogleAuthService(rooms,null,verifier,"",Set.of(ORIGIN),clock,new CollectionPolicy(Set.of(new GoogleCredentialVerifier.Principal("https://accounts.google.com","subject")),"test contact","test retention"));
    assertFalse(disabled.config().enabled());assertEquals("",disabled.config().clientId());
    assertThrows(RoomService.Failure.class,()->disabled.nonce(guest,ORIGIN));rooms.create(guest,1);
    assertThrows(RoomService.Failure.class,()->auth.nonce(guest,ORIGIN));
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));assertEquals(0,verifier.calls);
  }
  @Test void logoutOnlyUnlinksAndCancelsNonce() {
    auth.nonce(guest,ORIGIN);auth.login(guest,ORIGIN,"credential",true);auth.nonce(guest,ORIGIN);
    assertFalse(auth.logout(guest,ORIGIN).signedIn());assertNull(rooms.accountId(guest));
    assertEquals(guest,rooms.authenticate(guest.token()));
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));
  }
  @Test void cancelledInFlightAttemptCannotAttach() {
    var nonce=auth.nonce(guest,ORIGIN);verifier.during=()->auth.cancel(guest,ORIGIN,nonce.nonce());
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));assertNull(rooms.accountId(guest));
  }
  @Test void expiryDuringVerificationPreventsStorage() {
    auth.nonce(guest,ORIGIN);verifier.during=()->clock.now=301000;
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));
    assertEquals(0,store.calls);assertNull(rooms.accountId(guest));
  }
  @Test void cancellationDuringStoragePreventsAttachmentButDoesNotUndoMapping() {
    var nonce=auth.nonce(guest,ORIGIN);store.during=()->auth.cancel(guest,ORIGIN,nonce.nonce());
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));
    assertEquals(1,store.calls);assertNull(rooms.accountId(guest));
  }
  @Test void cancelDuringBlockedVerificationPreventsStorage() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var nonce=auth.nonce(guest,ORIGIN);verifier.during=()->{
      entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timed out");}
      catch(InterruptedException e){throw new AssertionError(e);}
    };
    var executor=Executors.newSingleThreadExecutor();
    try {
      var login=executor.submit(()->auth.login(guest,ORIGIN,"credential",true));
      assertTrue(entered.await(5,TimeUnit.SECONDS));auth.cancel(guest,ORIGIN,nonce.nonce());release.countDown();
      assertInstanceOf(RoomService.Failure.class,assertThrows(ExecutionException.class,()->login.get(5,TimeUnit.SECONDS)).getCause());
      assertEquals(0,store.calls);assertNull(rooms.accountId(guest));
    } finally {release.countDown();executor.shutdownNow();}
  }
  @Test void expiryDuringStorageCannotAttach() {
    auth.nonce(guest,ORIGIN);store.during=()->clock.now=301000;
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));assertNull(rooms.accountId(guest));
  }
  @Test void roomEntryDuringStorageCannotAttach() {
    auth.nonce(guest,ORIGIN);store.during=()->rooms.create(guest,1);
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));assertNull(rooms.accountId(guest));
  }
  @Test void staleCancellationDoesNotCancelNewAttempt() {
    var first=auth.nonce(guest,ORIGIN);var second=auth.nonce(guest,ORIGIN);auth.cancel(guest,ORIGIN,first.nonce());
    assertTrue(auth.login(guest,ORIGIN,"credential",true).signedIn());assertEquals(second.nonce(),verifier.nonce);
  }
  @Test void logoutDuringBlockedVerificationPreventsLateAttachment() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    auth.nonce(guest,ORIGIN);verifier.during=()->{
      entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timed out");}
      catch(InterruptedException e){throw new AssertionError(e);}
    };
    var executor=Executors.newSingleThreadExecutor();
    try {
      var login=executor.submit(()->auth.login(guest,ORIGIN,"credential",true));
      assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(auth.logout(guest,ORIGIN).signedIn());release.countDown();
      assertInstanceOf(RoomService.Failure.class,assertThrows(ExecutionException.class,()->login.get(5,TimeUnit.SECONDS)).getCause());
      assertFalse(auth.status(guest).signedIn());assertEquals(0,store.calls);
    } finally {release.countDown();executor.shutdownNow();}
  }
  @Test void unapprovedOrForgedIssuerNeverReachesStorage() {
    for (var principal : List.of(new GoogleCredentialVerifier.Principal("https://accounts.google.com","unapproved"),
        new GoogleCredentialVerifier.Principal("https://evil.test","subject"),
        new GoogleCredentialVerifier.Principal("https://accounts.google.com","subject@example.com"))) {
      verifier.principal=principal;auth.nonce(guest,ORIGIN);
      var failure=assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));
      assertEquals("TESTER_NOT_ADMITTED",failure.code);assertEquals(0,store.calls);assertNull(rooms.accountId(guest));
    }
  }
  @Test void defaultPolicyFailsClosedButAllowsExplicitVerifyOnly() {
    auth=new GoogleAuthService(rooms,store,verifier,"client",Set.of(ORIGIN),clock);
    assertFalse(auth.config().enabled());assertFalse(auth.config().collectionReady());assertTrue(auth.config().verificationEnabled());
    assertThrows(RoomService.Failure.class,()->auth.nonce(guest,ORIGIN));
    auth.nonce(guest,ORIGIN,"verify");var verified=auth.verifyOnly(guest,ORIGIN,"credential",true);
    assertEquals("subject",verified.subject());assertFalse(verified.admittedTester());
    assertEquals(0,store.calls);assertNull(rooms.accountId(guest));
    assertThrows(RoomService.Failure.class,()->auth.verifyOnly(guest,ORIGIN,"credential",true));
  }
  @Test void verifyOnlyWorksWithoutStorageAndNeverPersistsAdmittedIdentity() {
    auth=new GoogleAuthService(rooms,null,verifier,"client",Set.of(ORIGIN),clock,
        new CollectionPolicy(Set.of(verifier.principal),"contact","retention"));
    auth.nonce(guest,ORIGIN,"verify");var verified=auth.verifyOnly(guest,ORIGIN,"credential",true);
    assertTrue(verified.admittedTester());assertNull(rooms.accountId(guest));assertEquals(0,store.calls);
  }
  @Test void noncePurposeCannotBeSwappedForPersistentLink() {
    auth.nonce(guest,ORIGIN,"verify");
    assertEquals("AUTH_PURPOSE_MISMATCH",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    auth.nonce(guest,ORIGIN,"link");
    assertEquals("AUTH_PURPOSE_MISMATCH",assertThrows(RoomService.Failure.class,
        ()->auth.verifyOnly(guest,ORIGIN,"credential",true)).code);
    assertEquals(0,store.calls);assertEquals(0,verifier.calls);
  }
  @Test void verifyOnlyRequiresConsentAndHonorsCancel() {
    auth.nonce(guest,ORIGIN,"verify");
    assertThrows(RoomService.Failure.class,()->auth.verifyOnly(guest,ORIGIN,"credential",false));
    assertEquals(0,verifier.calls);
    var challenge=auth.nonce(guest,ORIGIN,"verify");verifier.during=()->auth.cancel(guest,ORIGIN,challenge.nonce());
    assertThrows(RoomService.Failure.class,()->auth.verifyOnly(guest,ORIGIN,"credential",true));
    assertEquals(0,store.calls);assertNull(rooms.accountId(guest));
  }

  @Test void invalidCredentialNeverReachesStoreAndConsumesChallenge() {
    auth.nonce(guest,ORIGIN);verifier.during=()->{throw RoomService.fail(401,"INVALID_GOOGLE_CREDENTIAL","Invalid");};
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"malicious",true));
    assertEquals(0,store.calls);assertNull(rooms.accountId(guest));
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"malicious",true));assertEquals(1,verifier.calls);
  }

}
