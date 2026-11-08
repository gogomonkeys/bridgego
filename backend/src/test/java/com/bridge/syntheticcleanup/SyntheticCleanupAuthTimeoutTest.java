package com.bridge.syntheticcleanup;

import com.bridge.persistence.SyntheticCleanupWorkbook;
import com.bridge.room.GoogleCredentialVerifier;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupAuthTimeoutTest {
  @Test void stalledVerifierCannotReadSheetOrAccumulateMoreProofWorkers() throws Exception {
    String target = System.getProperty("bridge.synthetic.test.sheet", "");
    Assumptions.assumeFalse(target.isBlank(), "Private isolated target not supplied");
    String issuer = "https://accounts.google.com", subject = "fake-test-operator";
    var settings = new SyntheticCleanupService.Settings(true, "https://isolated.example",
        "123456789012-independent_fake_client.apps.googleusercontent.com", target, "SYNTHETIC-ONLY-FORMAT-TEST",
        SyntheticCleanupWorkbook.sha256(issuer + "\n" + subject));
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
    AtomicInteger proofs = new AtomicInteger(), reads = new AtomicInteger();
    var service = new SyntheticCleanupService(settings, (credential,audience,nonce) -> {
      proofs.incrementAndGet(); entered.countDown();
      while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) {} }
      finished.countDown(); return new GoogleCredentialVerifier.Principal(issuer, subject);
    }, () -> { reads.incrementAndGet(); throw new AssertionError(); }, Clock.systemUTC(), 300);
    try {
      var first = service.challenge();
      var failure = assertThrows(SyntheticCleanupService.Failure.class, () -> service.preflight(first.nonce(), "fake", true));
      assertEquals("OPERATOR_AUTHENTICATION_FAILED", failure.code); assertTrue(entered.await(1, TimeUnit.SECONDS));
      var second = service.challenge();
      assertEquals("PREFLIGHT_BUSY", assertThrows(SyntheticCleanupService.Failure.class,
          () -> service.preflight(second.nonce(), "fake", true)).code);
      assertEquals(1, proofs.get()); assertEquals(0, reads.get());
      release.countDown(); assertTrue(finished.await(1, TimeUnit.SECONDS));
      assertEquals(0, reads.get()); // cancelled worker only returns a principal; it never owns the Sheet continuation
    } finally { release.countDown(); }
  }
}
