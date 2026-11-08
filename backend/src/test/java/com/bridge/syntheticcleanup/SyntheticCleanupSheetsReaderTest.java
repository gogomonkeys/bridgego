package com.bridge.syntheticcleanup;

import com.bridge.persistence.SyntheticCleanupWorkbook;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupSheetsReaderTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static String testKey;
  @BeforeAll static void generatedEphemeralTestKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
    testKey = JSON.writeValueAsString(Map.of("type", "service_account", "client_email", "fake@test-project.iam.gserviceaccount.com",
        "private_key", "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n"));
  }
  private static String target() {
    String target = System.getProperty("bridge.synthetic.test.sheet", "");
    Assumptions.assumeFalse(target.isBlank(), "Private isolated target not supplied"); SyntheticCleanupWorkbook.requireApprovedTarget(target); return target;
  }
  private static SyntheticCleanupSheetsReader.Response response(SyntheticCleanupSheetsReader.Request request, byte[] bytes) {
    return new SyntheticCleanupSheetsReader.Response(200, request.uri(), "application/json", "", bytes.length, new ByteArrayInputStream(bytes));
  }
  private static byte[] token() { return "{\"access_token\":\"fake-test-token\",\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8); }
  @Test void exactReadonlyScopeAndFullPinnedGetWithoutStartupSecretRead() throws Exception {
    AtomicInteger secrets = new AtomicInteger(), calls = new AtomicInteger(); String sheet = target();
    var source = new SyntheticCleanupSheetsReader(sheet, () -> { secrets.incrementAndGet(); return testKey; }, request -> {
      assertFalse(request.toString().contains("Bearer")); assertFalse(request.toString().contains(sheet));
      if (calls.incrementAndGet() == 1) {
        assertEquals("POST", request.method()); assertEquals(SyntheticCleanupSheetsReader.TOKEN_URI, request.uri());
        String assertion = new String(request.body(), StandardCharsets.US_ASCII).split("assertion=")[1];
        var claims = JSON.readTree(Base64.getUrlDecoder().decode(assertion.split("\\.")[1]));
        assertEquals(SyntheticCleanupSheetsReader.READONLY_SCOPE, claims.path("scope").asText());
        assertEquals("https://oauth2.googleapis.com/token", claims.path("aud").asText());
        return response(request, token());
      }
      assertEquals("GET", request.method()); assertEquals("sheets.googleapis.com", request.uri().getHost());
      assertEquals("/v4/spreadsheets/" + sheet, request.uri().getPath());
      assertEquals("includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED", request.uri().getRawQuery());
      assertEquals(0, request.body().length); assertEquals("identity", request.headers().get("Accept-Encoding"));
      return response(request, "{}".getBytes(StandardCharsets.UTF_8));
    }, Clock.systemUTC());
    assertEquals(0, secrets.get()); assertEquals(0, calls.get());
    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), source.read()); assertEquals(1, secrets.get()); assertEquals(2, calls.get());
  }
  @Test void wrongTargetIsRejectedBeforeSecretOrHttp() {
    AtomicInteger effects = new AtomicInteger();
    assertThrows(IllegalArgumentException.class, () -> new SyntheticCleanupSheetsReader("arbitrary-sheet-123456", () -> {
      effects.incrementAndGet(); return "";
    }, request -> { effects.incrementAndGet(); throw new AssertionError(); }, Clock.systemUTC()));
    assertEquals(0, effects.get());
  }
  @Test void redirectFailureNeverAdvancesToWorkbookOrRetries() {
    AtomicInteger calls = new AtomicInteger();
    var source = new SyntheticCleanupSheetsReader(target(), () -> testKey, request -> {
      calls.incrementAndGet(); return new SyntheticCleanupSheetsReader.Response(302, request.uri(), "application/json", "", 0, new ByteArrayInputStream(new byte[0]));
    }, Clock.systemUTC());
    assertThrows(IllegalStateException.class, source::read); assertEquals(1, calls.get());
  }
  @Test void oversizedBodyWrongTypeAndDifferentFinalTargetFailClosed() {
    for (String bad : List.of("size", "type", "target", "encoding", "length")) {
      AtomicInteger calls = new AtomicInteger();
      var source = new SyntheticCleanupSheetsReader(target(), () -> testKey, request -> {
        if (calls.incrementAndGet() == 1) return response(request, token());
        return new SyntheticCleanupSheetsReader.Response(200,
            bad.equals("target") ? java.net.URI.create("https://sheets.googleapis.com/v4/spreadsheets/wrong") : request.uri(),
            bad.equals("type") ? "text/html" : "application/json", bad.equals("encoding") ? "gzip" : "",
            bad.equals("size") ? 4194305 : bad.equals("length") ? 4 : 2, new ByteArrayInputStream(new byte[] {'{','}'}));
      }, Clock.systemUTC());
      assertThrows(IllegalStateException.class, source::read); assertEquals(2, calls.get());
    }
  }
  @Test void deadlineClosesBodyAndBlocksOverlapUntilWorkerActuallyStops() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    InputStream stuck = new InputStream() {
      @Override public int read() { entered.countDown(); while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) {} } return -1; }
      @Override public void close() { closed.countDown(); }
    };
    var source = new SyntheticCleanupSheetsReader(target(), () -> testKey, request -> {
      calls.incrementAndGet(); return new SyntheticCleanupSheetsReader.Response(200, request.uri(), "application/json", "", -1, stuck);
    }, Clock.systemUTC());
    try {
      assertThrows(IllegalStateException.class, () -> source.read(500)); assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTrue(closed.await(1, TimeUnit.SECONDS)); assertThrows(IllegalStateException.class, source::read); assertEquals(1, calls.get());
    } finally { release.countDown(); }
  }
  @Test void lateHeadersAfterCancellationCannotReadBodyOrAdvanceToSheet() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger(), bodyReads = new AtomicInteger();
    var source = new SyntheticCleanupSheetsReader(target(), () -> testKey, request -> {
      calls.incrementAndGet(); entered.countDown();
      while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) {} }
      return new SyntheticCleanupSheetsReader.Response(200, request.uri(), "application/json", "", -1, new InputStream() {
        public int read() { bodyReads.incrementAndGet(); return -1; }
        public void close() { closed.countDown(); }
      });
    }, Clock.systemUTC());
    try {
      assertThrows(IllegalStateException.class, () -> source.read(500)); assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, source::read); release.countDown(); assertTrue(closed.await(1, TimeUnit.SECONDS));
      assertEquals(0, bodyReads.get()); assertEquals(1, calls.get());
    } finally { release.countDown(); }
  }

}
