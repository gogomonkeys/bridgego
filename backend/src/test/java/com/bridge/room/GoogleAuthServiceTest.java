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
  @Test void managedLoginUsesSessionNicknameAndVerifiedEmailOnly() {
    List<String> captured=new ArrayList<>();
    IdentityStore profiles=new IdentityStore(){
      public boolean enabled(){return true;}
      public String findOrCreateAccount(String issuer,String subject){throw new AssertionError("legacy write");}
      public String findOrCreateAccount(String issuer,String subject,String nickname,String email){captured.addAll(List.of(issuer,subject,nickname,email));return store.account;}
    };
    verifier.principal=new GoogleCredentialVerifier.Principal("https://accounts.google.com","subject","verified@example.test");
    var managed=new GoogleAuthService(rooms,profiles,verifier,"client",Set.of(ORIGIN),clock,CollectionPolicy.managed("contact","retention"));
    managed.nonce(guest,ORIGIN);assertTrue(managed.login(guest,ORIGIN,"credential",true,"account-profile-v1").signedIn());
    assertEquals(List.of("https://accounts.google.com","subject","Guest","verified@example.test"),captured);
    assertFalse(managed.status(guest).toString().contains("verified@example.test"));
  }
  @Test void managedLegacyConsentDoesNotCollectProfile() {
    IdentityStore legacy=new FakeStore(){
      @Override public String findOrCreateAccount(String issuer,String subject,String nickname,String email){throw new AssertionError("no new profile consent");}
    };
    var managed=new GoogleAuthService(rooms,legacy,verifier,"client",Set.of(ORIGIN),clock,CollectionPolicy.managed("contact","retention"));
    managed.nonce(guest,ORIGIN);assertTrue(managed.login(guest,ORIGIN,"credential",true,null).signedIn());
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
  @Test void cancellationWhileSeatedSurvivesLeaveBeforeStorageReturns() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var nonce=auth.nonce(guest,ORIGIN);
    store.during=()->{
      entered.countDown();
      try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timed out");}
      catch(InterruptedException e){throw new AssertionError(e);}
    };
    var executor=Executors.newSingleThreadExecutor();
    try {
      var login=executor.submit(()->auth.login(guest,ORIGIN,"credential",true));
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      String room=rooms.create(guest,1).roomId();
      auth.cancel(guest,ORIGIN,nonce.nonce());
      rooms.leave(room,guest);
      release.countDown();
      var failure=assertInstanceOf(RoomService.Failure.class,
          assertThrows(ExecutionException.class,()->login.get(5,TimeUnit.SECONDS)).getCause());
      assertEquals("AUTH_CANCELLED",failure.code);
      assertEquals(1,store.calls);assertFalse(auth.status(guest).signedIn());
      assertEquals(guest,rooms.authenticate(guest.token()));
    } finally {release.countDown();executor.shutdownNow();}
  }
  @Test void cancellationWhileSeatedRequiresExactSessionAndDoesNotUnlink() {
    String old=UUID.randomUUID().toString();rooms.linkAccountOutsideRoom(guest,old,true);
    var nonce=auth.nonce(guest,ORIGIN,"verify");rooms.create(guest,1);
    var forged=new RoomService.Identity(guest.token(),guest.playerId(),"Changed");
    assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,
        ()->auth.cancel(forged,ORIGIN,nonce.nonce())).code);
    auth.cancel(guest,ORIGIN,nonce.nonce());
    assertEquals(old,rooms.accountId(guest));
    assertEquals("AUTH_IN_ROOM",assertThrows(RoomService.Failure.class,()->auth.logout(guest,ORIGIN)).code);
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

  @Test void roomEntryDuringVerificationPreventsAnyStorage() {
    auth.nonce(guest,ORIGIN);verifier.during=()->rooms.create(guest,1);
    assertEquals("AUTH_IN_ROOM",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    assertEquals(0,store.calls);assertNull(rooms.accountId(guest));
  }

  @Test void sessionExpiryDuringVerificationPreventsAnyStorage() {
    MutableClock sessionClock=shortLivedSession();
    auth.nonce(guest,ORIGIN);verifier.during=()->sessionClock.now+=1000;
    assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    assertEquals(0,store.calls);
  }

  @Test void sessionExpiryDuringStoragePreventsAttachment() {
    MutableClock sessionClock=shortLivedSession();
    auth.nonce(guest,ORIGIN);store.during=()->sessionClock.now+=1000;
    assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    assertEquals(1,store.calls);
    assertThrows(RoomService.Failure.class,()->rooms.authenticate(guest.token()));
    var fresh=rooms.createSession("Fresh");assertFalse(auth.status(fresh).signedIn());
  }

  private MutableClock shortLivedSession() {
    var sessionClock=new MutableClock();
    rooms=new RoomService(sessionClock,sessionClock::millis,
        new BetaLimits(2,128,1000,300000,64,4,600,60,20,30));
    guest=rooms.createSession("Guest");
    auth=new GoogleAuthService(rooms,store,verifier,"client",Set.of(ORIGIN),clock,
        new CollectionPolicy(Set.of(verifier.principal),"contact","retention"));
    return sessionClock;
  }

  @Test void forgedExactSessionCannotConsumeOrCancelLegitimateChallenge() {
    var challenge=auth.nonce(guest,ORIGIN);
    for(var forged:List.of(new RoomService.Identity(guest.token(),guest.playerId(),"Other name"),
        new RoomService.Identity(guest.token(),UUID.randomUUID().toString(),guest.displayName()),
        new RoomService.Identity("bad-token",guest.playerId(),guest.displayName()))) {
      assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,()->auth.nonce(forged,ORIGIN)).code);
      assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,()->auth.login(forged,ORIGIN,"credential",true)).code);
      assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,()->auth.cancel(forged,ORIGIN,challenge.nonce())).code);
      assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,()->auth.logout(forged,ORIGIN)).code);
      assertEquals("UNAUTHORIZED",assertThrows(RoomService.Failure.class,()->auth.status(forged)).code);
    }
    assertEquals(0,verifier.calls);assertTrue(auth.login(guest,ORIGIN,"credential",true).signedIn());
    assertEquals(challenge.nonce(),verifier.nonce);
  }

  @Test void linkChallengeUnlinksBeforeReplacementAndFailedSwitchStaysGuest() {
    var old=UUID.randomUUID().toString();rooms.linkAccountOutsideRoom(guest,old,true);
    auth.nonce(guest,ORIGIN);assertFalse(auth.status(guest).signedIn());
    store.fail=true;
    assertEquals("IDENTITY_STORAGE_UNAVAILABLE",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    assertNull(rooms.accountId(guest));assertEquals(guest,rooms.authenticate(guest.token()));
  }

  @Test void cancelledAndDeniedSwitchNeverRestorePreviousAccount() {
    var old=UUID.randomUUID().toString();rooms.linkAccountOutsideRoom(guest,old,true);
    var challenge=auth.nonce(guest,ORIGIN);auth.cancel(guest,ORIGIN,challenge.nonce());
    assertFalse(auth.status(guest).signedIn());assertEquals(0,store.calls);
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"credential",true));
    rooms.linkAccountOutsideRoom(guest,old,true);auth.nonce(guest,ORIGIN);
    assertEquals("CONSENT_REQUIRED",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",false)).code);
    assertFalse(auth.status(guest).signedIn());assertEquals(0,store.calls);
    rooms.linkAccountOutsideRoom(guest,old,true);auth.nonce(guest,ORIGIN);
    verifier.principal=new GoogleCredentialVerifier.Principal("https://accounts.google.com","not-admitted");
    assertEquals("TESTER_NOT_ADMITTED",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    assertFalse(auth.status(guest).signedIn());assertEquals(0,store.calls);
  }

  @Test void successfulSwitchUsesNewAccountAndKeepsGuestSession() {
    var old=UUID.randomUUID().toString();rooms.linkAccountOutsideRoom(guest,old,true);
    auth.nonce(guest,ORIGIN);assertTrue(auth.login(guest,ORIGIN,"credential",true).signedIn());
    assertEquals(store.account,rooms.accountId(guest));assertNotEquals(old,rooms.accountId(guest));
    assertEquals(guest,rooms.authenticate(guest.token()));
  }

  @Test void verificationAndInvalidLinkRequestsDoNotUnlinkExistingAccount() {
    var old=UUID.randomUUID().toString();rooms.linkAccountOutsideRoom(guest,old,true);
    assertThrows(RoomService.Failure.class,()->auth.nonce(guest,"https://evil.test"));
    assertThrows(RoomService.Failure.class,()->auth.nonce(guest,ORIGIN,"unknown"));
    assertThrows(RoomService.Failure.class,()->auth.login(guest,ORIGIN,"no-challenge",true));
    assertEquals(old,rooms.accountId(guest));
    auth.nonce(guest,ORIGIN,"verify");auth.verifyOnly(guest,ORIGIN,"credential",true);
    assertEquals(old,rooms.accountId(guest));assertEquals(0,store.calls);
    var challenge=auth.nonce(guest,ORIGIN,"verify");auth.cancel(guest,ORIGIN,challenge.nonce());
    assertEquals(old,rooms.accountId(guest));
    rooms.create(guest,1);assertThrows(RoomService.Failure.class,()->auth.nonce(guest,ORIGIN));
    assertEquals(old,rooms.accountId(guest));
  }

  @Test void replacementChallengeDuringVerificationPreventsOlderStorageAndKeepsNewChallenge() {
    auth.nonce(guest,ORIGIN);verifier.during=()->auth.nonce(guest,ORIGIN);
    assertEquals("AUTH_CANCELLED",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    assertEquals(0,store.calls);verifier.during=()->{};
    assertTrue(auth.login(guest,ORIGIN,"credential",true).signedIn());assertEquals(1,store.calls);
  }

  @Test void invalidStoreAccountFailsClosed() {
    auth.nonce(guest,ORIGIN);store.account="not-an-account-id";
    assertEquals("IDENTITY_STORAGE_UNAVAILABLE",assertThrows(RoomService.Failure.class,
        ()->auth.login(guest,ORIGIN,"credential",true)).code);
    assertFalse(auth.status(guest).signedIn());
  }

}
