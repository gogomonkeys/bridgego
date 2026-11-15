package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TutorialControllerTest {
  @Test void requiresBearerOutsideRoomAndMutationOrigin() {
    var rooms = new RoomService();
    var policy = CollectionPolicy.restrictedEmpty();
    var tutorials = new TutorialService();
    var controller = new TutorialController(rooms, tutorials, policy, "https://bridge.example", new MockEnvironment().withProperty("unused", "1"));
    var who = rooms.createSession("P");
    String bearer = "Bearer " + who.token();
    assertThrows(RoomService.Failure.class, () -> controller.view(null));
    assertThrows(RoomService.Failure.class, () -> controller.start(bearer, "https://evil.example"));
    var a = controller.start(bearer, "http://localhost:5173");
    assertEquals(a, controller.view(bearer));
    assertNull(controller.state("Bearer " + rooms.createSession("Other").token()).game());
    rooms.create(who);
    assertThrows(RoomService.Failure.class, () -> controller.view(bearer));
  }

  @Test void googleChoiceIsAccountBoundAndPersistedAcrossSessions() {
    var rooms = new RoomService();
    var store = new PreferenceStore();
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("identity", store);
    var controller = new TutorialController(rooms, new TutorialService(), CollectionPolicy.restrictedEmpty(),
        "", new MockEnvironment(), beans.getBeanProvider(IdentityStore.class));
    var who = rooms.createSession("A");
    String bearer = "Bearer " + who.token();
    String account = java.util.UUID.randomUUID().toString();
    rooms.linkAccountOutsideRoom(who, account);
    assertFalse(controller.status(bearer).offered());
    var choice = controller.choice(bearer, "http://localhost:5173", new TutorialController.Choice(false));
    assertTrue(choice.signedIn());
    assertTrue(choice.offered());
    assertNull(choice.game());
    assertEquals(java.util.Set.of(account), store.saved);
    var other = rooms.createSession("B");
    rooms.linkAccountOutsideRoom(other, account);
    assertTrue(controller.status("Bearer " + other.token()).offered());
    assertNotNull(controller.choice(bearer, "http://localhost:5173", new TutorialController.Choice(true)).game());
  }

  @Test void uncertainPreferenceNeverCreatesGameOrClaimsUnseen() {
    var rooms = new RoomService();
    var store = new PreferenceStore();
    store.broken = true;
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("identity", store);
    var controller = new TutorialController(rooms, new TutorialService(), CollectionPolicy.restrictedEmpty(),
        "", new MockEnvironment(), beans.getBeanProvider(IdentityStore.class));
    var who = rooms.createSession("A");
    String bearer = "Bearer " + who.token();
    rooms.linkAccountOutsideRoom(who, java.util.UUID.randomUUID().toString());
    assertEquals(503, assertThrows(RoomService.Failure.class, () -> controller.status(bearer)).status);
    assertEquals(503, assertThrows(RoomService.Failure.class, () -> controller.choice(bearer,
        "http://localhost:5173", new TutorialController.Choice(true))).status);
    assertNull(controller.state(bearer).game());
  }

  @Test void guestChoiceDoesNotRequireOrWriteIdentityStore() {
    var rooms = new RoomService();
    var controller = new TutorialController(rooms, new TutorialService(), CollectionPolicy.restrictedEmpty(),
        "", new MockEnvironment());
    String bearer = "Bearer " + rooms.createSession("A").token();
    var status = controller.choice(bearer, "http://localhost:5173", new TutorialController.Choice(true));
    assertFalse(status.signedIn());
    assertFalse(status.offered());
    assertNotNull(status.game());
  }

  @Test void completeExitRestartAndExpiryNeverTouchMatchStorage() {
    var rooms = new RoomService();
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    rooms.statsStore(new com.bridge.persistence.StatsStore() {
      public SaveStatus submit(com.bridge.persistence.FinalMatchEnvelope value) { writes.incrementAndGet(); return SaveStatus.CONFIRMED; }
      public SaveStatus status(String id) { throw new AssertionError("No match storage access"); }
      public java.util.List<com.bridge.persistence.FinalMatchEnvelope> exportCommitted() { throw new AssertionError("No match storage access"); }
    });
    var clock = new java.util.concurrent.atomic.AtomicLong(9_000_000);
    var controller = new TutorialController(rooms, new TutorialService(clock::get), CollectionPolicy.restrictedEmpty(),
        "", new MockEnvironment());
    String bearer = "Bearer " + rooms.createSession("A").token();
    String origin = "http://localhost:5173";
    var view = controller.start(bearer, origin);
    for (int i = 0; i < 13; i++) {
      view = controller.play(bearer, origin, new TutorialController.PlayRequest(
          view.sessionId(), view.revision(), view.legalCards().get(0)));
    }
    assertEquals(TutorialEngine.Lesson.COMPLETE, view.lesson());
    controller.exit(bearer, origin, new TutorialController.ExitRequest(view.sessionId()));
    var fresh = controller.start(bearer, origin);
    assertNotEquals(view.sessionId(), fresh.sessionId());
    clock.addAndGet(3_600_001);
    assertNull(controller.state(bearer).game());
    assertTrue(rooms.list().isEmpty());
    assertEquals(0, writes.get());
  }

  @Test void accountChangeDuringPreferenceWriteCannotStartForNewIdentity() {
    var rooms = new RoomService();
    var who = rooms.createSession("A");
    rooms.linkAccountOutsideRoom(who, java.util.UUID.randomUUID().toString());
    var store = new PreferenceStore();
    store.afterWrite = () -> rooms.unlinkAccountOutsideRoom(who);
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("identity", store);
    var controller = new TutorialController(rooms, new TutorialService(), CollectionPolicy.restrictedEmpty(),
        "", new MockEnvironment(), beans.getBeanProvider(IdentityStore.class));
    String bearer = "Bearer " + who.token();
    var failure = assertThrows(RoomService.Failure.class, () -> controller.choice(bearer,
        "http://localhost:5173", new TutorialController.Choice(true)));
    assertEquals("TUTORIAL_IDENTITY_CHANGED", failure.code);
    assertNull(controller.state(bearer).game());
  }

  @Test void disabledFlagBlocksNewGuestAndGoogleTutorialsWithoutPreferenceAccess() {
    for (boolean google : java.util.List.of(false, true)) {
      var rooms = new RoomService();
      var who = rooms.createSession("A");
      if (google) rooms.linkAccountOutsideRoom(who, java.util.UUID.randomUUID().toString());
      var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
      beans.registerSingleton("identity", new IdentityStore() {
        public boolean enabled() { throw new AssertionError("No preference access"); }
        public String findOrCreateAccount(String issuer, String subject) { throw new AssertionError(); }
        public boolean tutorialOffered(String account) { throw new AssertionError("No reads"); }
        public void markTutorialOffered(String account) { throw new AssertionError("No writes"); }
      });
      var controller = new TutorialController(rooms, new TutorialService(), CollectionPolicy.restrictedEmpty(),
          "", new MockEnvironment().withProperty("BRIDGE_TUTORIAL_ENABLED", "false"), beans.getBeanProvider(IdentityStore.class));
      String bearer = "Bearer " + who.token();
      var status = controller.status(bearer);
      assertFalse(status.enabled());
      assertEquals(google, status.signedIn());
      assertFalse(status.offered());
      assertNull(status.game());
      assertEquals(401, assertThrows(RoomService.Failure.class, () -> controller.status(null)).status);
      assertEquals("TUTORIAL_DISABLED", assertThrows(RoomService.Failure.class,
          () -> controller.start(bearer, "http://localhost:5173")).code);
      for (boolean start : java.util.List.of(false, true))
        assertEquals("TUTORIAL_DISABLED", assertThrows(RoomService.Failure.class,
            () -> controller.choice(bearer, "http://localhost:5173", new TutorialController.Choice(start))).code);
      assertNull(controller.state(bearer).game());
      assertNotNull(rooms.create(who));
      assertEquals(1, rooms.list().size());
    }
  }

  @Test void disabledFlagAllowsExistingGameReadPlayAndExit() {
    var rooms = new RoomService();
    var service = new TutorialService();
    var who = rooms.createSession("A");
    String bearer = "Bearer " + who.token(), origin = "http://localhost:5173";
    var enabled = new TutorialController(rooms, service, CollectionPolicy.restrictedEmpty(), "", new MockEnvironment());
    assertTrue(enabled.status(bearer).enabled());
    var game = enabled.start(bearer, origin);
    var disabled = new TutorialController(rooms, service, CollectionPolicy.restrictedEmpty(), "",
        new MockEnvironment().withProperty("BRIDGE_TUTORIAL_ENABLED", "false"));
    assertEquals(game, disabled.status(bearer).game());
    assertEquals(game, disabled.view(bearer));
    assertEquals(game, disabled.state(bearer).game());
    for (int i=0; i<13; i++) game = disabled.play(bearer, origin,
        new TutorialController.PlayRequest(game.sessionId(), game.revision(), game.legalCards().get(0)));
    assertEquals(TutorialEngine.Lesson.COMPLETE, game.lesson());
    assertTrue(disabled.exit(bearer, origin, new TutorialController.ExitRequest(game.sessionId())).get("exited"));
    assertNull(disabled.state(bearer).game());
  }

  @Test void malformedFlagFailsConfigurationRatherThanSilentlyEnabling() {
    assertThrows(IllegalArgumentException.class, () -> new TutorialController(new RoomService(),
        new TutorialService(), CollectionPolicy.restrictedEmpty(), "",
        new MockEnvironment().withProperty("BRIDGE_TUTORIAL_ENABLED", "invalid")));
  }

  private static class PreferenceStore implements IdentityStore {
    final java.util.Set<String> saved = new java.util.HashSet<>();
    boolean broken;
    Runnable afterWrite = () -> {};
    public boolean enabled() { return true; }
    public String findOrCreateAccount(String issuer, String subject) { throw new AssertionError(); }
    public boolean tutorialOffered(String accountId) {
      if (broken) throw new IllegalStateException();
      return saved.contains(accountId);
    }
    public void markTutorialOffered(String accountId) {
      if (broken) throw new IllegalStateException();
      saved.add(accountId);
      afterWrite.run();
    }
  }
}
