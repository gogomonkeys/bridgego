package com.bridge.syntheticcleanup;

import com.bridge.persistence.SyntheticCleanupWorkbook;
import com.bridge.room.GoogleCredentialVerifier;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversarial tests. All proofs and readers are fake; no network or real keys are used. */
class SyntheticCleanupIndependentReviewTest {
  private static final String SHEET = System.getProperty("bridge.synthetic.test.sheet", "");
  private static String approvedSheet() {
    org.junit.jupiter.api.Assumptions.assumeTrue(!SHEET.isBlank(), "Private isolated test-sheet binding was not supplied");
    SyntheticCleanupWorkbook.requireApprovedTarget(SHEET);
    return SHEET;
  }
  private static final String MARKER = "SYNTHETIC-ONLY-FORMAT-TEST";
  private static final String ISSUER = "https://accounts.google.com";
  private static final String SUBJECT = "independent-fake-operator";
  private static final String CLIENT = "123456789012-independent_fake_client.apps.googleusercontent.com";
  private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
  private static final class MovingClock extends Clock {
    Instant now = NOW;
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
  private static SyntheticCleanupService.Settings settings(String subject) {
    return new SyntheticCleanupService.Settings(true, "https://isolated.example", CLIENT, approvedSheet(), MARKER,
        SyntheticCleanupWorkbook.sha256(ISSUER + "\n" + subject));
  }
  private static GoogleCredentialVerifier.Principal principal(String subject) {
    return new GoogleCredentialVerifier.Principal(ISSUER, subject);
  }
  private static void failure(int status, String code, Runnable action) {
    var error = assertThrows(SyntheticCleanupService.Failure.class, action::run);
    assertEquals(status, error.status); assertEquals(code, error.code);
    assertEquals(code, error.getMessage()); assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
  }
  @Test void constructionHealthAndChallengeNeverReadCredentialsOrWorkbook() {
    AtomicInteger proofs = new AtomicInteger(), reads = new AtomicInteger();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> { proofs.incrementAndGet(); throw new AssertionError(); },
        () -> { reads.incrementAndGet(); throw new AssertionError(); }, Clock.fixed(NOW, ZoneOffset.UTC));
    assertEquals(false, service.health().get("cleanupReady"));
    assertEquals(false, service.health().get("writesEnabled"));
    assertEquals(true, service.health().get("configured"));
    assertNotNull(service.challenge());
    assertEquals(0, proofs.get()); assertEquals(0, reads.get());
  }
  @Test void disabledSettingsDoNotReadOtherEnvironmentValues() {
    List<String> accessed = new ArrayList<>();
    var disabled = SyntheticCleanupService.Settings.read(key -> { accessed.add(key); return null; });
    assertFalse(disabled.enabled());
    assertEquals(List.of("BRIDGE_SYNTHETIC_PREFLIGHT_ENABLED"), accessed);
  }
  @Test void wrongTargetRejectedEvenWithOtherwiseValidSettings() {
    assertThrows(IllegalArgumentException.class, () -> new SyntheticCleanupService.Settings(true,
        "https://isolated.example", CLIENT, "another_spreadsheet_123456789", MARKER,
        SyntheticCleanupWorkbook.sha256(ISSUER + "\n" + SUBJECT)));
  }
  @Test void wrongOriginAndClientIdCannotEnablePreflight() {
    String sheet = approvedSheet();
    for (String origin : List.of("http://isolated.example", "https://isolated.example/path", "https://u@isolated.example",
        "https://isolated.example?query=x", "https://isolated.example#fragment", "https://*.example"))
      assertThrows(IllegalArgumentException.class, () -> new SyntheticCleanupService.Settings(true,
          origin, CLIENT, sheet, MARKER, SyntheticCleanupWorkbook.sha256(ISSUER + "\n" + SUBJECT)));
    assertThrows(IllegalArgumentException.class, () -> new SyntheticCleanupService.Settings(true,
        "https://isolated.example", "untrusted-client", sheet, MARKER,
        SyntheticCleanupWorkbook.sha256(ISSUER + "\n" + SUBJECT)));
  }
  @Test void failedProofConsumesNonceWithoutOpeningReaderAndSanitizesException() {
    AtomicInteger proofs = new AtomicInteger(), reads = new AtomicInteger();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> {
      proofs.incrementAndGet(); throw new IllegalArgumentException("fake-id-token private claims secret");
    }, () -> { reads.incrementAndGet(); return new byte[0]; }, Clock.fixed(NOW, ZoneOffset.UTC));
    var nonce = service.challenge().nonce();
    failure(401, "OPERATOR_AUTHENTICATION_FAILED", () -> service.preflight(nonce, "fake-proof", true));
    failure(401, "FRESH_AUTHENTICATION_REQUIRED", () -> service.preflight(nonce, "fake-proof", true));
    assertEquals(1, proofs.get()); assertEquals(0, reads.get());
  }
  @Test void wrongOperatorAndSyntheticPrincipalNeverOpenReader() {
    for (String subject : List.of("another-fake-operator", "SYNTHETIC-ONLY:target")) {
      AtomicInteger reads = new AtomicInteger();
      String allowlisted = subject.startsWith("SYNTHETIC-ONLY:") ? subject : SUBJECT;
      var service = new SyntheticCleanupService(settings(allowlisted), (c,a,n) -> principal(subject),
          () -> { reads.incrementAndGet(); return new byte[0]; }, Clock.fixed(NOW, ZoneOffset.UTC));
      var challenge = service.challenge();
      failure(403, "OPERATOR_NOT_ALLOWED", () -> service.preflight(challenge.nonce(), "fake-proof", true));
      assertEquals(0, reads.get());
    }
  }
  @Test void missingConsentConsumesNonceBeforeAnyProofOrRead() {
    AtomicInteger proofs = new AtomicInteger(), reads = new AtomicInteger();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> { proofs.incrementAndGet(); return principal(SUBJECT); },
        () -> { reads.incrementAndGet(); return new byte[0]; }, Clock.fixed(NOW, ZoneOffset.UTC));
    var nonce = service.challenge().nonce();
    failure(400, "EXPLICIT_READ_CONSENT_REQUIRED", () -> service.preflight(nonce, "fake-proof", false));
    failure(401, "FRESH_AUTHENTICATION_REQUIRED", () -> service.preflight(nonce, "fake-proof", true));
    assertEquals(0, proofs.get()); assertEquals(0, reads.get());
  }
  @Test void expiredNonceRejectedBeforeVerifier() {
    MovingClock clock = new MovingClock(); AtomicInteger proofs = new AtomicInteger();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> { proofs.incrementAndGet(); return principal(SUBJECT); },
        () -> { throw new AssertionError(); }, clock);
    var nonce = service.challenge().nonce(); clock.now = NOW.plusSeconds(120);
    failure(401, "FRESH_AUTHENTICATION_REQUIRED", () -> service.preflight(nonce, "fake-proof", true));
    assertEquals(0, proofs.get());
  }
  @Test void expiryDuringVerificationCannotStartLateCredentialRead() {
    MovingClock clock = new MovingClock(); AtomicInteger reads = new AtomicInteger();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> {
      clock.now = NOW.plusSeconds(121); return principal(SUBJECT);
    }, () -> { reads.incrementAndGet(); return new byte[0]; }, clock);
    var nonce = service.challenge().nonce();
    assertThrows(SyntheticCleanupService.Failure.class, () -> service.preflight(nonce, "fake-proof", true));
    assertEquals(0, reads.get(), "Proof freshness must still hold when credential access/read starts");
  }
  @Test void unavailableReaderIsSanitizedAndCannotBeRetriedWithSameOrFreshNonceInsideCooldown() {
    AtomicInteger reads = new AtomicInteger();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> principal(SUBJECT),
        () -> { reads.incrementAndGet(); throw new IllegalStateException("fake-key fake-bearer private-sheet-body"); },
        Clock.fixed(NOW, ZoneOffset.UTC));
    var first = service.challenge().nonce();
    var second = service.challenge().nonce();
    failure(503, "COMPLETE_PREFLIGHT_UNVERIFIED", () -> service.preflight(first, "fake-proof", true));
    failure(401, "FRESH_AUTHENTICATION_REQUIRED", () -> service.preflight(first, "fake-proof", true));
    failure(429, "READ_COOLDOWN", service::challenge);
    failure(429, "READ_COOLDOWN", () -> service.preflight(second, "fake-proof", true));
    assertEquals(1, reads.get());
  }
  @Test void rawResponseBytesAreZeroedOnParserRejection() {
    byte[] body = "{\"private\":\"fake-sensitive-data\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> principal(SUBJECT), () -> body,
        Clock.fixed(NOW, ZoneOffset.UTC));
    var nonce = service.challenge().nonce();
    failure(503, "COMPLETE_PREFLIGHT_UNVERIFIED", () -> service.preflight(nonce, "fake-proof", true));
    assertArrayEquals(new byte[body.length], body);
  }
  @Test void diagnosticsDistinguishAuthenticationTransportAndSchemaWithoutPrivateData() {
    for (int stage = 0; stage < 3; stage++) {
      final int failureStage = stage;
      var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> {
        if (failureStage == 0) throw new IllegalArgumentException("PRIVATE-EXCEPTION");
        return principal(SUBJECT);
      }, () -> {
        if (failureStage == 1) throw new IllegalStateException("PRIVATE-BODY");
        return new byte[0];
      }, Clock.fixed(NOW, ZoneOffset.UTC));
      var nonce = service.challenge().nonce();
      var failure = assertThrows(SyntheticCleanupService.Failure.class, () -> service.preflight(nonce, "PRIVATE-PROOF", true));
      assertEquals(stage == 0 ? "UNVERIFIED" : "SUCCESS", failure.authentication);
      assertEquals(stage == 0 ? "NOT_ATTEMPTED" : stage == 1 ? "UNVERIFIED" : "SUCCESS", failure.read);
      assertEquals(stage == 2 ? "UNVERIFIED" : "NOT_ATTEMPTED", failure.schema);
      assertEquals(stage == 0 ? 0 : 60, failure.retryAfterSeconds);
      String body = failure.body().toString();
      for (String secret : List.of("PRIVATE", SUBJECT, nonce, approvedSheet())) assertFalse(body.contains(secret));
      assertNull(failure.getCause()); assertEquals(false, failure.body().get("cleanupReady"));
    }
  }
  @Test void cooldownIsRoundedUpAndFreshChallengeCannotBypassIt() {
    MovingClock clock = new MovingClock();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> principal(SUBJECT),
        () -> { throw new IllegalStateException(); }, clock);
    assertEquals(0, service.health().get("retryAfterSeconds"));
    var nonce = service.challenge().nonce();
    assertThrows(SyntheticCleanupService.Failure.class, () -> service.preflight(nonce, "fake", true));
    clock.now = Instant.MIN; assertEquals(60, service.retryAfterSeconds());
    clock.now = Instant.MAX; assertEquals(0, service.retryAfterSeconds());
    clock.now = NOW;
    assertEquals(60, service.health().get("retryAfterSeconds"));
    clock.now = NOW.plusMillis(59001);
    var blocked = assertThrows(SyntheticCleanupService.Failure.class, service::challenge);
    assertEquals(1, blocked.retryAfterSeconds);
    clock.now = NOW.plusSeconds(60); assertNotNull(service.challenge());
    assertEquals(0, service.retryAfterSeconds());
  }
  @Test void challengeCapacityIsBoundedAndExpiredSlotsAreRecovered() {
    MovingClock clock = new MovingClock();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> { throw new AssertionError(); },
        () -> { throw new AssertionError(); }, clock);
    Set<String> nonces = new HashSet<>();
    for (int i = 0; i < 16; i++) assertTrue(nonces.add(service.challenge().nonce()));
    failure(429, "CHALLENGE_LIMIT", service::challenge);
    clock.now = NOW.plusSeconds(120); assertNotNull(service.challenge());
  }
  @Test void concurrentProofCannotCreateSecondReadAndBusyAttemptNonceIsConsumed() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    var service = new SyntheticCleanupService(settings(SUBJECT), (c,a,n) -> {
      entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
      return principal(SUBJECT);
    }, () -> { reads.incrementAndGet(); throw new IllegalStateException(); }, Clock.fixed(NOW, ZoneOffset.UTC));
    var first = service.challenge().nonce(); var second = service.challenge().nonce();
    var thread = new Thread(() -> {
      try { service.preflight(first, "fake-proof", true); }
      catch (SyntheticCleanupService.Failure expected) { /* unavailable fake reader */ }
    });
    thread.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      failure(409, "PREFLIGHT_BUSY", () -> service.preflight(second, "fake-proof", true));
      failure(401, "FRESH_AUTHENTICATION_REQUIRED", () -> service.preflight(second, "fake-proof", true));
    } finally { release.countDown(); thread.join(6000); }
    assertFalse(thread.isAlive()); assertEquals(1, reads.get());
  }
  @Test void guardRejectsCrossOriginDuplicateOriginAndQueryBeforeController() throws Exception {
    var config = settings(SUBJECT);
    for (String origin : List.of("", "https://attacker.invalid")) {
      var request = request("POST", "/preflight/read", "{}");
      if (!origin.isEmpty()) request.addHeader("Origin", origin);
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      AtomicInteger downstream = new AtomicInteger();
      new SyntheticCleanupGuard(config).doFilter(request, response, (a,b) -> downstream.incrementAndGet());
      assertEquals(403, response.getStatus()); assertEquals(0, downstream.get());
    }
    var duplicate = request("POST", "/preflight/read", "{}");
    duplicate.addHeader("Origin", "https://isolated.example"); duplicate.addHeader("Origin", "https://isolated.example");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    new SyntheticCleanupGuard(config).doFilter(duplicate, response, (a,b) -> fail("Duplicate Origin reached controller"));
    assertEquals(403, response.getStatus());
    var query = request("POST", "/preflight/read", "{}");
    query.setQueryString("sheet=another_target"); query.addHeader("Origin", "https://isolated.example");
    response = new org.springframework.mock.web.MockHttpServletResponse();
    new SyntheticCleanupGuard(config).doFilter(query, response, (a,b) -> fail("Query override reached controller"));
    assertEquals(404, response.getStatus());
  }
  @Test void guardExposesNoMutationOrGameRoutesAndNeverSetsSessionCookies() throws Exception {
    var guard = new SyntheticCleanupGuard(settings(SUBJECT));
    for (String path : List.of("/preflight/execute", "/preflight/seed", "/maintenance/execute", "/quarter/execute", "/api/rooms", "/ws")) {
      var request = request("POST", path, "{}"); request.addHeader("Origin", "https://isolated.example");
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      guard.doFilter(request, response, (a,b) -> fail("Forbidden route reached controller"));
      assertEquals(404, response.getStatus()); assertNull(response.getHeader("Set-Cookie"));
      assertEquals("no-store", response.getHeader("Cache-Control"));
    }
  }
  @Test void guardBoundsActualBodyEvenWithoutDeclaredLength() throws Exception {
    var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/preflight/read") {
      @Override public long getContentLengthLong() { return -1; }
    };
    request.setContentType("application/json"); request.addHeader("Origin", "https://isolated.example");
    request.setContent(new byte[16385]);
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    new SyntheticCleanupGuard(settings(SUBJECT)).doFilter(request, response, (a,b) -> fail("Oversized stream reached controller"));
    assertEquals(413, response.getStatus());
  }
  @Test void guardRequiresJsonNoEncodingAndExactEmptyChallenge() throws Exception {
    var config = settings(SUBJECT);
    for (int i = 0; i < 3; i++) {
      var request = request("POST", i == 2 ? "/preflight/challenge" : "/preflight/read", i == 2 ? "{\"sheet\":\"other\"}" : "{}");
      request.addHeader("Origin", "https://isolated.example");
      if (i == 0) request.setContentType("text/plain");
      if (i == 1) request.addHeader("Content-Encoding", "gzip");
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      new SyntheticCleanupGuard(config).doFilter(request, response, (a,b) -> fail("Malformed request reached controller"));
      assertEquals(i == 2 ? 400 : 415, response.getStatus());
    }
  }
  private static org.springframework.mock.web.MockHttpServletRequest request(String method, String path, String body) {
    var request = new org.springframework.mock.web.MockHttpServletRequest(method, path);
    request.setContentType("application/json"); request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return request;
  }

}
