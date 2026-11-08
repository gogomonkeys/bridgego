package com.bridge.syntheticcleanup;

import com.bridge.persistence.SyntheticCleanupWorkbook;
import com.bridge.room.GoogleCredentialVerifier;
import java.net.URI;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Operator authentication is separate from the reserved synthetic workbook identity. No mutation API. */
final class SyntheticCleanupService {
  record Settings(boolean enabled, String origin, String clientId, String sheetId, String marker, String operatorHash) {
    Settings {
      if (enabled) {
        SyntheticCleanupWorkbook.requireApprovedTarget(sheetId);
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || !uri.getRawPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null
            || uri.getPort() == 0 || uri.getPort() > 65535 || origin.contains(",") || origin.contains("*")) throw rejected();
        if (clientId == null || !clientId.matches("[0-9]{6,30}-[A-Za-z0-9_-]{8,128}\\.apps\\.googleusercontent\\.com")
            || operatorHash == null || !operatorHash.matches("[0-9a-f]{64}")
            || !"SYNTHETIC-ONLY-FORMAT-TEST".equals(marker)) throw rejected();
      }
    }
    static Settings read(Function<String,String> env) {
      String enabled = env.apply("BRIDGE_SYNTHETIC_PREFLIGHT_ENABLED");
      if (enabled == null || enabled.equals("false")) return new Settings(false, "", "", "", "", "");
      if (!enabled.equals("true")) throw rejected();
      return new Settings(true, env.apply("PUBLIC_ORIGIN"), env.apply("GOOGLE_CLIENT_ID"),
          env.apply("BRIDGE_SYNTHETIC_SHEET_ID"), env.apply("BRIDGE_SYNTHETIC_MARKER"),
          env.apply("BRIDGE_SYNTHETIC_OPERATOR_SHA256"));
    }
    @Override public String toString() { return "Synthetic preflight settings (redacted)"; }
  }
  interface Reader { byte[] read(); }
  static final class Failure extends RuntimeException {
    final int status; final String code;
    int retryAfterSeconds;
    String authentication = "NOT_ATTEMPTED", read = "NOT_ATTEMPTED", schema = "NOT_ATTEMPTED";
    Map<String,Object> body() { return Map.of("code", code, "retryAfterSeconds", retryAfterSeconds,
        "authentication", authentication, "read", read, "schema", schema, "cleanupReady", false, "writesEnabled", false); }
    Failure(int status, String code) { super(code); this.status = status; this.code = code; }
  }
  enum Diagnostic { AUTHENTICATION_STARTED, AUTHENTICATION_SUCCESS, AUTHENTICATION_UNVERIFIED,
    READ_STARTED, READ_SUCCESS, READ_UNVERIFIED, SCHEMA_SUCCESS, SCHEMA_UNVERIFIED }
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SyntheticCleanupService.class);
  private static void diagnostic(Diagnostic code) { LOG.info("SYNTHETIC_PREFLIGHT {}", code.name()); }
  synchronized int retryAfterSeconds() {
    Instant now = clock.instant();
    if (!nextRead.isAfter(now)) return 0;
    Duration remaining = Duration.between(now, nextRead);
    return (int)Math.min(60, remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
  }
  private Failure cooldown() { Failure f = new Failure(429, "READ_COOLDOWN"); f.retryAfterSeconds = retryAfterSeconds(); return f; }
  record Challenge(String nonce, String clientId, Instant expiresAt) {}
  private final Settings settings;
  private final GoogleCredentialVerifier verifier;
  private final Reader reader;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String,Instant> challenges = new HashMap<>();
  private boolean busy;
  private final AtomicBoolean authActive = new AtomicBoolean();
  private final long authTimeoutMillis;
  private Instant nextRead = Instant.MIN;
  SyntheticCleanupService(Settings settings, GoogleCredentialVerifier verifier, Reader reader, Clock clock) {
    this(settings, verifier, reader, clock, 10000);
  }
  SyntheticCleanupService(Settings settings, GoogleCredentialVerifier verifier, Reader reader, Clock clock, long authTimeoutMillis) {
    if (authTimeoutMillis < 1 || authTimeoutMillis > 10000) throw rejected();
    this.authTimeoutMillis = authTimeoutMillis;
    this.settings = Objects.requireNonNull(settings); this.verifier = Objects.requireNonNull(verifier);
    this.reader = Objects.requireNonNull(reader); this.clock = Objects.requireNonNull(clock);
  }
  Map<String,Object> health() { return Map.of("mode", "synthetic-readonly-preflight", "configured", settings.enabled(),
      "cleanupReady", false, "writesEnabled", false, "retryAfterSeconds", retryAfterSeconds()); }
  synchronized Challenge challenge() {
    requireEnabled(); Instant now = clock.instant();
    if (retryAfterSeconds() > 0) throw cooldown();
    challenges.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    if (challenges.size() >= 16) throw new Failure(429, "CHALLENGE_LIMIT");
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = now.plusSeconds(120); challenges.put(nonce, expires);
    return new Challenge(nonce, settings.clientId(), expires);
  }
  SyntheticCleanupWorkbook.Summary preflight(String nonce, String credential, Boolean consent) {
    final Instant proofExpires;
    synchronized (this) {
      requireEnabled();
      Instant expiry = nonce == null ? null : challenges.remove(nonce); // consume on EVERY proof attempt
      if (expiry == null || !expiry.isAfter(clock.instant())) throw new Failure(401, "FRESH_AUTHENTICATION_REQUIRED");
      proofExpires = expiry;
      if (!Boolean.TRUE.equals(consent) || credential == null || credential.isBlank() || credential.length() > 12000)
        throw new Failure(400, "EXPLICIT_READ_CONSENT_REQUIRED");
      if (busy || authActive.get()) throw new Failure(409, "PREFLIGHT_BUSY");
      busy = true;
    }
    String authentication = "UNVERIFIED", read = "NOT_ATTEMPTED", schema = "NOT_ATTEMPTED";
    diagnostic(Diagnostic.AUTHENTICATION_STARTED);
    try {
      GoogleCredentialVerifier.Principal principal;
      try { principal = verifyBounded(credential, nonce); }
      catch (RuntimeException invalid) { throw new Failure(401, "OPERATOR_AUTHENTICATION_FAILED"); }
      if (principal == null || !"https://accounts.google.com".equals(principal.issuer())
          || principal.subject() == null || principal.subject().isBlank() || principal.subject().startsWith("SYNTHETIC-ONLY"))
        throw new Failure(403, "OPERATOR_NOT_ALLOWED");
      String digest = SyntheticCleanupWorkbook.sha256(principal.issuer() + "\n" + principal.subject());
      if (!MessageDigest.isEqual(digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          settings.operatorHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new Failure(403, "OPERATOR_NOT_ALLOWED");
      authentication = "SUCCESS";
      diagnostic(Diagnostic.AUTHENTICATION_SUCCESS);
      synchronized (this) {
        if (!proofExpires.isAfter(clock.instant())) throw new Failure(401, "FRESH_AUTHENTICATION_REQUIRED");
        if (clock.instant().isBefore(nextRead)) throw cooldown();
        nextRead = clock.instant().plusSeconds(60);
      }
      read = "UNVERIFIED";
      diagnostic(Diagnostic.READ_STARTED);
      byte[] raw = reader.read();
      read = "SUCCESS";
      diagnostic(Diagnostic.READ_SUCCESS);
      schema = "UNVERIFIED";
      try {
        var result = SyntheticCleanupWorkbook.inspect(raw, settings.sheetId(), settings.marker());
        schema = "SUCCESS";
        diagnostic(Diagnostic.SCHEMA_SUCCESS);
        return result;
      }
      finally { if (raw != null) Arrays.fill(raw, (byte)0); }
    } catch (Failure expected) {
      expected.authentication = authentication; expected.read = read; expected.schema = schema;
      expected.retryAfterSeconds = retryAfterSeconds();
      if (authentication.equals("UNVERIFIED")) diagnostic(Diagnostic.AUTHENTICATION_UNVERIFIED);
      throw expected;
    }
    catch (Exception | LinkageError unavailable) {
      Failure failure = new Failure(503, "COMPLETE_PREFLIGHT_UNVERIFIED");
      failure.authentication = authentication; failure.read = read; failure.schema = schema;
      failure.retryAfterSeconds = retryAfterSeconds();
      diagnostic(schema.equals("UNVERIFIED") ? Diagnostic.SCHEMA_UNVERIFIED : Diagnostic.READ_UNVERIFIED);
      throw failure;
    }
    finally { synchronized (this) { busy = false; } }
  }
  private GoogleCredentialVerifier.Principal verifyBounded(String credential, String nonce) {
    if (!authActive.compareAndSet(false, true)) throw new Failure(409, "PREFLIGHT_BUSY");
    FutureTask<GoogleCredentialVerifier.Principal> task = new FutureTask<>(() -> {
      try { return verifier.verify(credential, settings.clientId(), nonce); }
      finally { authActive.set(false); }
    });
    Thread worker = new Thread(task, "synthetic-operator-verification"); worker.setDaemon(true); worker.start();
    try { return task.get(authTimeoutMillis, TimeUnit.MILLISECONDS); }
    catch (InterruptedException stopped) { task.cancel(true); Thread.currentThread().interrupt(); throw new Failure(401, "OPERATOR_AUTHENTICATION_FAILED"); }
    catch (Exception unavailable) { task.cancel(true); throw new Failure(401, "OPERATOR_AUTHENTICATION_FAILED"); }
    // This worker can only return a principal, never access the Sheet. If still running, authActive blocks new proofs.
  }
  private void requireEnabled() { if (!settings.enabled()) throw new Failure(503, "PREFLIGHT_NOT_CONFIGURED"); }
  private static IllegalArgumentException rejected() { return new IllegalArgumentException("Synthetic preflight configuration rejected"); }
}
