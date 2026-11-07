package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import com.bridge.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

/** Real configuration factories, isolated Spring test context, fake identities only. No server/network. */
class GameCollectionGateWiringTest {
  static final String ORIGIN = "https://bridge.example";
  static final String ALLOW = "[{\"issuer\":\"https://accounts.google.com\",\"subject\":\"synthetic-subject\"}]";

  ApplicationContextRunner context(AtomicInteger verifications) {
    return new ApplicationContextRunner()
        .withInitializer(c -> {
          c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
          c.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
          c.getEnvironment().setActiveProfiles("prod");
        })
        .withUserConfiguration(GoogleAuthConfiguration.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(RoomService.class, RoomService::new)
        .withBean(GoogleCredentialVerifier.class, () -> (credential, audience, nonce) -> {
          verifications.incrementAndGet();
          if (!credential.equals("synthetic:" + nonce)) throw new IllegalArgumentException();
          return new GoogleCredentialVerifier.Principal("https://accounts.google.com", "synthetic-subject");
        })
        .withPropertyValues("PUBLIC_ORIGIN=" + ORIGIN, "GOOGLE_CLIENT_ID=synthetic-public-client",
            "BRIDGE_COLLECTION_MODE=restricted", "BRIDGE_TEST_GOOGLE_IDENTITIES=" + ALLOW,
            "BRIDGE_PRIVACY_CONTACT=synthetic contact", "BRIDGE_PRIVACY_RETENTION=synthetic policy");
  }

  StatsStore noStats() {
    return new StatsStore() {
      public SaveStatus submit(FinalMatchEnvelope e) { throw new AssertionError("No writes expected"); }
      public SaveStatus status(String id) { return SaveStatus.DISABLED; }
      public List<FinalMatchEnvelope> exportCommitted() { return List.of(); }
    };
  }

  @Test void absentOrEitherFalseReadinessFlagBlocksLinkBeforeVerifierOrIdentityWrites() {
    for (String[] flags : List.of(new String[0],
        new String[]{"BRIDGE_ERASURE_WORKFLOW_VERIFIED=false", "BRIDGE_QUARTER_POLICY_VERIFIED=true"},
        new String[]{"BRIDGE_ERASURE_WORKFLOW_VERIFIED=true", "BRIDGE_QUARTER_POLICY_VERIFIED=false"})) {
      var calls = new AtomicInteger();
      var writes = new AtomicInteger();
      context(calls).withPropertyValues(flags).withBean(StatsStore.class, this::noStats)
          .withBean(IdentityStore.class, () -> new IdentityStore() {
            public boolean enabled() { return true; }
            public String findOrCreateAccount(String issuer, String subject) {
              writes.incrementAndGet(); throw new AssertionError("Readiness must fail before storage");
            }
          }).run(c -> {
            assertNull(c.getStartupFailure());
            var auth = c.getBean(GoogleAuthService.class);
            var rooms = c.getBean(RoomService.class);
            var guest = rooms.createSession("Test");
            assertFalse(c.getBean(CollectionPolicy.class).ready());
            assertFalse(auth.config().enabled());
            assertEquals("GOOGLE_AUTH_DISABLED", assertThrows(RoomService.Failure.class,
                () -> auth.nonce(guest, ORIGIN, "link")).code);
            assertEquals("GOOGLE_AUTH_DISABLED", assertThrows(RoomService.Failure.class,
                () -> auth.login(guest, ORIGIN, "synthetic", true)).code);
            assertEquals(0, calls.get()); assertEquals(0, writes.get());
            var nonce = auth.nonce(guest, ORIGIN, "verify");
            assertEquals("synthetic-subject", auth.verifyOnly(guest, ORIGIN, "synthetic:" + nonce.nonce(), true).subject());
            assertEquals(1, calls.get()); assertEquals(0, writes.get());
            assertFalse(auth.status(guest).signedIn());
          });
    }
  }

  @Test void actualPersistenceDisabledConfigurationIgnoresMalformedSyntheticCredentialAndCannotLink() {
    var calls = new AtomicInteger();
    context(calls).withUserConfiguration(PersistenceConfiguration.class)
        .withPropertyValues("BRIDGE_SHEETS_ENABLED=false", "BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON=invalid-synthetic-not-a-key",
            "BRIDGE_ERASURE_WORKFLOW_VERIFIED=true", "BRIDGE_QUARTER_POLICY_VERIFIED=true")
        .run(c -> {
          assertNull(c.getStartupFailure());
          assertTrue(c.getBean(CollectionPolicy.class).ready()); // purely fake test wiring, not operational evidence
          assertFalse(c.getBean(IdentityStore.class).enabled());
          var auth = c.getBean(GoogleAuthService.class);
          var guest = c.getBean(RoomService.class).createSession("Test");
          assertFalse(auth.config().enabled());
          assertEquals("GOOGLE_AUTH_DISABLED", assertThrows(RoomService.Failure.class,
              () -> auth.nonce(guest, ORIGIN)).code);
          assertEquals(StatsStore.SaveStatus.DISABLED, c.getBean(StatsStore.class).status("synthetic"));
          assertTrue(c.getBeansOfType(GoogleSheetsGateway.class).isEmpty());
          assertEquals(0, calls.get());
        });
  }

  @Test void productionHttpOriginCannotBeUsedAsLocalShortcut() {
    context(new AtomicInteger()).withBean(StatsStore.class, this::noStats)
        .withPropertyValues("PUBLIC_ORIGIN=http://localhost:8080")
        .run(c -> assertNotNull(c.getStartupFailure()));
  }
}
