package com.bridge.persistence;

import com.bridge.persistence.SyntheticSheetsReadOnlyAdapter.*;
import com.bridge.persistence.SyntheticSheetsReadOnlyTransport.*;
import com.bridge.persistence.SyntheticSheetsReadOnlyTransportSmoke.*;
import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticSheetsReadOnlyTransportTest {
  private final SyntheticSheetsErasurePreparation.Manifest manifest = SyntheticSheetsReadOnlyAdapterSmoke.manifest();
  private final URI target = URI.create("https://sheets.googleapis.com/v4/spreadsheets/" + manifest.sheetId()
      + "?includeGridData=true&commentsViewMode=" + SyntheticSheetsReadOnlyAdapter.COMMENTS);
  private ReadRequest spec(Duration timeout) { return new ReadRequest("GET", target, Map.of("Accept", "application/json", "Accept-Encoding", "identity"), false, timeout, 262144); }
  private ReadRequest spec() { return spec(Duration.ofSeconds(5)); }
  private Token token() { return new Token("fake.synthetic.bearer".toCharArray()); }
  private SyntheticSheetsReadOnlyTransport transport(FakeClient client) { return new SyntheticSheetsReadOnlyTransport(manifest, (scopes, remaining) -> token(), client); }
  private static void sanitized(Throwable error) {
    assertEquals("Whole-workbook read rejected or unavailable", error.getMessage()); assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
  }
  @Test void requestAndScopeAreExactAndTokenHeadersClearedAfterward() throws Exception {
    AtomicReference<HttpGet> captured = new AtomicReference<>(); AtomicReference<String> bearer = new AtomicReference<>(); AtomicInteger provider = new AtomicInteger();
    FakeClient client = new FakeClient(request -> {
      var get = (HttpGet) request; captured.set(get);
      bearer.set(get.getFirstHeader("Authorization").getValue());
      return SyntheticSheetsReadOnlyTransportSmoke.response(200, new ByteArrayInputStream("{}".getBytes()), 2);
    });
    try (var transport = new SyntheticSheetsReadOnlyTransport(manifest, (scopes, remaining) -> {
      provider.incrementAndGet(); assertEquals(Set.of(SyntheticSheetsReadOnlyTransport.READ_ONLY_SCOPE), scopes); assertTrue(remaining.toMillis() <= 5000); return token();
    }, client); var response = transport.execute(spec())) {
      assertArrayEquals("{}".getBytes(), response.body().readAllBytes()); assertEquals(target, response.finalUri());
      assertFalse(transport.toString().contains(manifest.sheetId()));
    }
    assertEquals(1, provider.get()); assertEquals(1, client.calls.get());
    var get = captured.get(); assertEquals("GET", get.getMethod()); assertEquals(target, get.getUri()); assertNull(get.getEntity());
    assertEquals("Bearer fake.synthetic.bearer", bearer.get()); assertEquals("identity", get.getFirstHeader("Accept-Encoding").getValue());
    assertFalse(get.getConfig().isRedirectsEnabled()); assertFalse(get.getConfig().isAuthenticationEnabled());
    assertFalse(get.getConfig().isContentCompressionEnabled()); assertTrue(get.getConfig().isHardCancellationEnabled());
    assertTrue(get.getConfig().getResponseTimeout().toMilliseconds() <= 5000);
    assertTrue(captured.get().isCancelled()); assertFalse(captured.get().containsHeader("Authorization"));
  }
  @Test void everyChangedRequestContractFailsBeforeProviderOrHttp() {
    AtomicInteger provider = new AtomicInteger(); FakeClient client = new FakeClient(request -> { throw new AssertionError(); });
    var transport = new SyntheticSheetsReadOnlyTransport(manifest, (scopes, remaining) -> { provider.incrementAndGet(); return token(); }, client);
    List<ReadRequest> invalid = new ArrayList<>();
    for (String method : List.of("POST", "PATCH", "DELETE", "HEAD")) invalid.add(new ReadRequest(method, target, spec().headers(), false, Duration.ofSeconds(5), 262144));
    for (String uri : List.of(target.toString().replace("https:", "http:"), target.toString().replace("sheets.googleapis.com", "attacker.invalid"),
        target + "&ranges=A1", target + "#x", target.toString().replace("/v4/", ":444/v4/"), target.toString().replace(manifest.sheetId(), "different_target")))
      invalid.add(new ReadRequest("GET", URI.create(uri), spec().headers(), false, Duration.ofSeconds(5), 262144));
    invalid.add(new ReadRequest("GET", target, Map.of("Authorization", "fake"), false, Duration.ofSeconds(5), 262144));
    invalid.add(new ReadRequest("GET", target, spec().headers(), true, Duration.ofSeconds(5), 262144));
    invalid.add(new ReadRequest("GET", target, spec().headers(), false, Duration.ofSeconds(6), 262144));
    invalid.add(new ReadRequest("GET", target, spec().headers(), false, Duration.ZERO, 262144));
    invalid.add(new ReadRequest("GET", target, spec().headers(), false, Duration.ofSeconds(5), 262145));
    for (ReadRequest request : invalid) sanitized(assertThrows(ReadFailure.class, () -> transport.execute(request)));
    assertEquals(0, provider.get()); assertEquals(0, client.calls.get());
  }
  @Test void matureClientConfigurationDisablesRetryRedirectAuthAndProxyFallback() throws Exception {
    HttpClientBuilder builder = HttpClients.custom();
    assertSame(builder, SyntheticSheetsReadOnlyTransport.configure(builder));
    // Inspect the pinned library builder without JVM instrumentation, a socket, or a network server.
    for (String name : List.of("automaticRetriesDisabled", "redirectHandlingDisabled", "cookieManagementDisabled",
        "authCachingDisabled", "contentCompressionDisabled", "connectionStateDisabled")) assertEquals(true, field(builder, name));
    assertEquals(false, field(builder, "systemProperties"));
    HttpRoutePlanner planner = (HttpRoutePlanner) field(builder, "routePlanner");
    var route = planner.determineRoute(new HttpHost("https", "sheets.googleapis.com", 443), new BasicHttpContext());
    assertTrue(route.isSecure()); assertNull(route.getProxyHost());
    assertThrows(HttpException.class, () -> planner.determineRoute(new HttpHost("http", "sheets.googleapis.com", 80), new BasicHttpContext()));
    assertThrows(HttpException.class, () -> planner.determineRoute(new HttpHost("https", "wrong.invalid", 443), new BasicHttpContext()));
  }
  private static Object field(HttpClientBuilder builder, String name) throws Exception {
    var field = HttpClientBuilder.class.getDeclaredField(name); field.setAccessible(true); return field.get(builder);
  }
  @Test void errorRedirectAndRateLimitNeverReadBodyRefreshTokenOrRetry() {
    for (int status : List.of(206, 301, 302, 307, 401, 403, 429, 500, 503)) {
      AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger(), provider = new AtomicInteger();
      var client = new FakeClient(request -> SyntheticSheetsReadOnlyTransportSmoke.response(status, new InputStream() {
        public int read() { reads.incrementAndGet(); return 1; } public void close() { closes.incrementAndGet(); }
      }, -1));
      var transport = new SyntheticSheetsReadOnlyTransport(manifest, (scopes, left) -> { provider.incrementAndGet(); return token(); }, client);
      sanitized(assertThrows(ReadFailure.class, () -> transport.execute(spec())));
      assertEquals(0, reads.get()); assertEquals(1, client.calls.get()); assertEquals(1, provider.get()); assertEquals(1, closes.get());
    }
  }
  @Test void byteLimitStopsAtLimitPlusOneWithoutDrainingEntity() {
    AtomicInteger count = new AtomicInteger(), closed = new AtomicInteger();
    var client = new FakeClient(request -> SyntheticSheetsReadOnlyTransportSmoke.response(200, new InputStream() {
      public int read() { count.incrementAndGet(); return 'x'; } public void close() { closed.incrementAndGet(); }
    }, -1));
    sanitized(assertThrows(ReadFailure.class, () -> transport(client).execute(spec())));
    assertEquals(262145, count.get()); assertEquals(1, closed.get());
  }
  @Test void headerTypeEncodingLengthAndAmbiguityRejectWithoutBodyRead() {
    for (String[] bad : List.of(new String[]{"Content-Type", "text/html"}, new String[]{"Content-Encoding", "gzip"},
        new String[]{"Content-Length", "262145"}, new String[]{"Content-Length", "-1"}, new String[]{"Transfer-Encoding", "gzip"})) {
      AtomicInteger reads = new AtomicInteger();
      var client = new FakeClient(request -> {
        var response = SyntheticSheetsReadOnlyTransportSmoke.response(200, new InputStream() { public int read() { reads.incrementAndGet(); return -1; } }, -1);
        response.setHeader(bad[0], bad[1]); return response;
      });
      assertThrows(ReadFailure.class, () -> transport(client).execute(spec())); assertEquals(0, reads.get());
    }
    for (boolean duplicate : List.of(true, false)) {
      var client = new FakeClient(request -> {
        var response = SyntheticSheetsReadOnlyTransportSmoke.response(200, new ByteArrayInputStream(new byte[0]), 0);
        response.setHeader("Content-Length", "0");
        response.addHeader(duplicate ? "Content-Length" : "Transfer-Encoding", duplicate ? "0" : "chunked"); return response;
      });
      assertThrows(ReadFailure.class, () -> transport(client).execute(spec()));
    }
  }
  @Test void mismatchedActualLengthAndIoFailureAreSanitized() {
    for (long length : List.of(1L, 3L)) {
      var client = new FakeClient(request -> SyntheticSheetsReadOnlyTransportSmoke.response(200, new ByteArrayInputStream(new byte[2]), length));
      sanitized(assertThrows(ReadFailure.class, () -> transport(client).execute(spec())));
    }
    var client = new FakeClient(request -> { throw new IOException("fake.token https://private.invalid raw-body"); });
    sanitized(assertThrows(ReadFailure.class, () -> transport(client).execute(spec()))); assertEquals(1, client.calls.get());
  }
  @Test void tokensAreRedactedClosedAndRejectHeaderInjection() {
    try (Token token = token()) { assertEquals("Token[REDACTED]", token.toString()); }
    for (String invalid : List.of("", "fake\r\nX: leak", "fake bearer", "a".repeat(4097)))
      sanitized(assertThrows(ReadFailure.class, () -> new Token(invalid.toCharArray())));
    var client = new FakeClient(request -> { throw new AssertionError(); });
    Token closed = token(); closed.close();
    var transport = new SyntheticSheetsReadOnlyTransport(manifest, (scopes, left) -> closed, client);
    sanitized(assertThrows(ReadFailure.class, () -> transport.execute(spec()))); assertEquals(0, client.calls.get());
  }
  @Test void providerFailureIsSanitizedWithoutHttp() {
    var client = new FakeClient(request -> { throw new AssertionError(); });
    var transport = new SyntheticSheetsReadOnlyTransport(manifest, (scopes, left) -> { throw new IOException("fake-secret"); }, client);
    sanitized(assertThrows(ReadFailure.class, () -> transport.execute(spec()))); assertEquals(0, client.calls.get());
  }
  @Test void realFactoryRefusesLoggingBindingBeforeAnyTokenAcquisition() {
    AtomicInteger provider = new AtomicInteger();
    if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof org.slf4j.helpers.NOPLoggerFactory) {
      assertDoesNotThrow(SyntheticSheetsReadOnlyTransport::requireSilentLogging);
    } else {
      sanitized(assertThrows(ReadFailure.class, () -> new SyntheticSheetsReadOnlyTransport(manifest,
          (scopes, left) -> { provider.incrementAndGet(); return token(); })));
    }
    assertEquals(0, provider.get());
  }
  static void ignoreInterruptsUntil(CountDownLatch release) {
    boolean done = false; while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) {}
  }
  @Test void timeoutDuringProviderPreventsLateHttpAndOverlappingCalls() throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ended = new CountDownLatch(1);
    var client = new FakeClient(request -> { throw new AssertionError(); });
    var transport = new SyntheticSheetsReadOnlyTransport(manifest, (scopes, left) -> {
      started.countDown(); ignoreInterruptsUntil(release); ended.countDown(); return token();
    }, client);
    long begin = System.nanoTime();
    try {
      assertThrows(ReadFailure.class, () -> transport.execute(spec(Duration.ofMillis(100))));
      assertTrue(started.await(1, TimeUnit.SECONDS)); assertTrue(Duration.ofNanos(System.nanoTime() - begin).toMillis() < 1500);
      assertThrows(ReadFailure.class, () -> transport.execute(spec())); assertEquals(0, client.calls.get());
    } finally { release.countDown(); }
    assertTrue(ended.await(1, TimeUnit.SECONDS)); assertEquals(0, client.calls.get());
  }
  @Test void lateResponseAfterDeadlineIsClosedWithoutReadingAndNotRetried() throws Exception {
    CountDownLatch release = new CountDownLatch(1), closed = new CountDownLatch(1); AtomicInteger reads = new AtomicInteger(); AtomicReference<HttpGet> request = new AtomicReference<>();
    var client = new FakeClient(get -> {
      request.set((HttpGet)get); ignoreInterruptsUntil(release);
      return SyntheticSheetsReadOnlyTransportSmoke.response(200, new InputStream() {
        public int read() { reads.incrementAndGet(); return -1; } public void close() { closed.countDown(); }
      }, -1);
    });
    var transport = transport(client);
    try {
      assertThrows(ReadFailure.class, () -> transport.execute(spec(Duration.ofMillis(100))));
      assertTrue(request.get().isCancelled()); assertThrows(ReadFailure.class, () -> transport.execute(spec()));
    } finally { release.countDown(); }
    assertTrue(closed.await(1, TimeUnit.SECONDS)); assertEquals(0, reads.get()); assertEquals(1, client.calls.get());
  }
  @Test void deadlineIncludesBlockedBodyAndClosure() throws Exception {
    for (boolean blockClose : List.of(false, true)) {
      CountDownLatch release = new CountDownLatch(1), closed = new CountDownLatch(1); AtomicReference<HttpGet> request = new AtomicReference<>();
      var client = new FakeClient(get -> {
        request.set((HttpGet)get);
        return SyntheticSheetsReadOnlyTransportSmoke.response(200, new InputStream() {
          public int read() { if (!blockClose) ignoreInterruptsUntil(release); return -1; }
          public void close() { if (blockClose) ignoreInterruptsUntil(release); closed.countDown(); }
        }, -1);
      });
      var transport = transport(client);
      try {
        assertThrows(ReadFailure.class, () -> transport.execute(spec(Duration.ofMillis(100))));
        assertTrue(request.get().isCancelled()); assertThrows(ReadFailure.class, () -> transport.execute(spec()));
      } finally { release.countDown(); }
      assertTrue(closed.await(1, TimeUnit.SECONDS)); assertEquals(1, client.calls.get());
    }
  }
  @Test void interruptPreservedAndClosedTransportCannotAcquireToken() {
    var client = new FakeClient(request -> { throw new AssertionError(); }); var transport = transport(client);
    Thread.currentThread().interrupt();
    try { assertThrows(ReadFailure.class, () -> transport.execute(spec())); assertTrue(Thread.currentThread().isInterrupted()); }
    finally { Thread.interrupted(); }
    transport.close(); assertThrows(ReadFailure.class, () -> transport.execute(spec())); assertEquals(0, client.calls.get());
  }
  @Test void actualAdapterIntegratesWithFakeHttpOnly() throws Exception { SyntheticSheetsReadOnlyTransportSmoke.runFake(); }
}
