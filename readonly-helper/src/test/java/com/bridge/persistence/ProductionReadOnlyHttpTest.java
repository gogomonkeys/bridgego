package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.*;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generated ephemeral RSA keys and fake clients only: no credential files, sockets, or real HTTP. */
class ProductionReadOnlyHttpTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SHEET = ProductionReadOnlyWorkbookTest.ID;
  static final String EMAIL = "synthetic-test@synthetic-project.iam.gserviceaccount.com";
  private static final String TOKEN = "synthetic.fake.token";
  private static final String OAUTH = "https://oauth2.googleapis.com/token";
  private static final String BASE = "https://sheets.googleapis.com/v4/spreadsheets/" + SHEET;
  static final ProductionReadOnlyTarget BINDING = ProductionReadOnlyWorkbookTest.TARGET;
  private static KeyPair pair;
  static byte[] credentialBytes;
  @BeforeAll static void generateEphemeralKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
    pair = generator.generateKeyPair(); credentialBytes = json(credentials(pair));
  }
  @AfterAll static void discardEphemeralKey() throws Exception {
    Arrays.fill(credentialBytes, (byte) 0); credentialBytes = null;
    try { pair.getPrivate().destroy(); } catch (Exception ignored) {}
    pair = null;
  }
  /** Independent test helper; no dependency on JUnit lifecycle or any stored key. Caller clears returned bytes. */
  static byte[] generatedCredentials() {
    KeyPair temporary = null;
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
      temporary = generator.generateKeyPair(); return json(credentials(temporary));
    } catch (GeneralSecurityException failure) { throw new AssertionError("Ephemeral test key generation failed"); }
    finally { if (temporary != null) try { temporary.getPrivate().destroy(); } catch (Exception ignored) {} }
  }
  private static ObjectNode credentials(KeyPair key) {
    ObjectNode result = JSON.createObjectNode();
    result.put("type", "service_account"); result.put("client_email", EMAIL); result.put("token_uri", OAUTH);
    byte[] encoded = key.getPrivate().getEncoded();
    try {
      result.put("private_key", "-----BEGIN PRIVATE KEY-----\n"
          + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded) + "\n-----END PRIVATE KEY-----\n");
    } finally { Arrays.fill(encoded, (byte) 0); }
    return result;
  }
  private static byte[] json(Object value) {
    try { return JSON.writeValueAsBytes(value); } catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
  static byte[] validToken() {
    return json(Map.of("access_token", TOKEN, "token_type", "Bearer", "expires_in", 3600));
  }
  private static void sanitized(Throwable error) {
    assertEquals("Production read-only HTTP operation rejected or unavailable", error.getMessage());
    assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length);
    assertFalse(error.toString().contains(TOKEN)); assertFalse(error.toString().contains(SHEET));
  }
  private static void rejects(Runnable action) { sanitized(assertThrows(IllegalStateException.class, action::run)); }
  interface Exchange { CloseableHttpResponse execute(ClassicHttpRequest request) throws IOException; }
  static final class FakeClient extends CloseableHttpClient {
    final AtomicInteger calls = new AtomicInteger(), closes = new AtomicInteger();
    final Exchange exchange;
    FakeClient(Exchange exchange) { this.exchange = exchange; }
    @Override protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request, HttpContext context)
        throws IOException { calls.incrementAndGet(); return exchange.execute(request); }
    @Override public void close() { closes.incrementAndGet(); }
    @Override public void close(CloseMode mode) { closes.incrementAndGet(); }
  }
  static CloseableHttpResponse response(int status, byte[] body) {
    return response(status, new ByteArrayInputStream(body), body.length);
  }
  static CloseableHttpResponse response(int status, InputStream stream, long length) {
    var response = new BasicClassicHttpResponse(status);
    response.setHeader("Content-Type", "application/json");
    response.setEntity(new InputStreamEntity(stream, length, ContentType.APPLICATION_JSON));
    return CloseableHttpResponse.adapt(response);
  }
  private static boolean tokenRequest(ClassicHttpRequest request) {
    try { return OAUTH.equals(request.getUri().toString()); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static ProductionReadOnlyHttp transport(FakeClient client) { return new ProductionReadOnlyHttp(BINDING, credentialBytes, EMAIL, client); }
  private static Object field(Object object, String name) throws Exception {
    var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
  }

  @Test void exactRequestsReuseOneTokenAndClearSensitiveRequestFields() throws Exception {
    List<HttpUriRequestBase> requests = new ArrayList<>(); List<String> methods = new ArrayList<>(), uris = new ArrayList<>();
    List<String> authorizations = new ArrayList<>(); List<byte[]> bodies = new ArrayList<>();
    var client = new FakeClient(raw -> {
      var request = (HttpUriRequestBase)raw; requests.add(request); methods.add(request.getMethod());
      try { uris.add(request.getUri().toString()); } catch (Exception failure) { throw new IOException(failure); }
      authorizations.add(request.getFirstHeader("Authorization") == null ? null : request.getFirstHeader("Authorization").getValue());
      bodies.add(request.getEntity() == null ? null : request.getEntity().getContent().readAllBytes());
      assertEquals("application/json", request.getFirstHeader("Accept").getValue());
      assertEquals("identity", request.getFirstHeader("Accept-Encoding").getValue());
      assertFalse(request.getConfig().isRedirectsEnabled()); assertFalse(request.getConfig().isAuthenticationEnabled());
      assertFalse(request.getConfig().isContentCompressionEnabled()); assertTrue(request.getConfig().isHardCancellationEnabled());
      assertTrue(request.getConfig().getResponseTimeout().toMilliseconds() <= 20000);
      return response(200, tokenRequest(raw) ? validToken() : bytes("{}"));
    });
    byte[] batch = bytes("{\"requests\":[]}");
    try (var transport = transport(client)) {
      assertArrayEquals(bytes("{}"), transport.readFullResource()); transport.readFullResource();
      assertArrayEquals(bytes("{}"), transport.readFullResource());
      assertFalse(transport.toString().contains(SHEET)); assertFalse(transport.toString().contains(EMAIL));
      assertTrue(transport.toString().contains("REDACTED"));
      Object token = field(transport, "token"); assertEquals("Token[REDACTED]", token.toString());
      assertEquals("Credentials[REDACTED]", field(transport, "credentials").toString());
      assertNull(field(field(transport, "credentials"), "key"));
    }
    assertEquals(List.of("POST", "GET", "GET", "GET"), methods);
    String read = BASE + "?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED";
    assertEquals(List.of(OAUTH, read, read, read), uris);
    assertNull(authorizations.get(0));
    assertEquals(List.of("Bearer " + TOKEN, "Bearer " + TOKEN, "Bearer " + TOKEN), authorizations.subList(1, 4));
    assertNull(bodies.get(1)); assertNull(bodies.get(2)); assertNull(bodies.get(3));
    assertArrayEquals(bytes("{\"requests\":[]}"), batch); // Caller-owned input was not erased.
    for (var request : requests) { assertTrue(request.isCancelled()); assertFalse(request.containsHeader("Authorization")); assertNull(request.getEntity()); }
    assertEquals(4, client.calls.get()); assertEquals(1, client.closes.get());
  }

  @Test void jwtHasOnlyFixedScopeAudienceAndValidGeneratedKeySignature() throws Exception {
    AtomicReference<byte[]> assertionBody = new AtomicReference<>(); AtomicReference<String> type = new AtomicReference<>();
    var client = new FakeClient(request -> {
      if (tokenRequest(request)) { assertionBody.set(request.getEntity().getContent().readAllBytes()); type.set(request.getEntity().getContentType()); }
      return response(200, tokenRequest(request) ? validToken() : bytes("{}"));
    });
    try (var transport = transport(client)) { transport.readFullResource(); }
    assertTrue(type.get().startsWith("application/x-www-form-urlencoded"));
    String form = new String(assertionBody.get(), StandardCharsets.US_ASCII);
    String prefix = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=";
    assertTrue(form.startsWith(prefix)); String[] jwt = form.substring(prefix.length()).split("\\."); assertEquals(3, jwt.length);
    JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(jwt[0]));
    JsonNode claim = JSON.readTree(Base64.getUrlDecoder().decode(jwt[1]));
    assertEquals(Set.of("alg", "typ"), names(header)); assertEquals("RS256", header.get("alg").textValue());
    assertEquals("JWT", header.get("typ").textValue());
    assertEquals(Set.of("iss", "scope", "aud", "iat", "exp"), names(claim));
    assertEquals(EMAIL, claim.get("iss").textValue()); assertEquals("https://www.googleapis.com/auth/spreadsheets.readonly", claim.get("scope").textValue());
    assertEquals(OAUTH, claim.get("aud").textValue()); assertEquals(300, claim.get("exp").longValue() - claim.get("iat").longValue());
    Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(pair.getPublic());
    verifier.update(bytes(jwt[0] + "." + jwt[1])); assertTrue(verifier.verify(Base64.getUrlDecoder().decode(jwt[2])));
    Arrays.fill(assertionBody.get(), (byte)0);
  }
  private static Set<String> names(JsonNode node) { Set<String> names = new HashSet<>(); node.fieldNames().forEachRemaining(names::add); return names; }

  @Test void invalidBindingAndRequestBoundsFailBeforeAnyHttp() {
    var client = new FakeClient(request -> { throw new AssertionError("No HTTP expected"); });
    rejects(() -> new ProductionReadOnlyHttp(null, credentialBytes, EMAIL, client));
    rejects(() -> ProductionReadOnlyHttp.open(null, credentialBytes, EMAIL, true));
    rejects(() -> new ProductionReadOnlyHttp(BINDING, credentialBytes, EMAIL, client, Duration.ZERO));
    rejects(() -> new ProductionReadOnlyHttp(BINDING, credentialBytes, EMAIL, client, Duration.ofSeconds(21)));
    assertEquals(0, client.calls.get());
  }

  @Test void credentialsRejectOverridesUnknownsAndAlternativeAuthenticationBeforeClient() {
    var client = new FakeClient(request -> { throw new AssertionError("No HTTP expected"); });
    Map<String,String> invalid = Map.ofEntries(Map.entry("token_uri", "https://attacker.invalid/token"),
        Map.entry("universe_domain", "example.invalid"), Map.entry("auth_uri", "https://attacker.invalid/auth"),
        Map.entry("auth_provider_x509_cert_url", "https://attacker.invalid/certs"),
        Map.entry("client_x509_cert_url", "https://attacker.invalid/x509"), Map.entry("type", "external_account"),
        Map.entry("subject", "delegated@example.invalid"), Map.entry("delegates", "delegated"),
        Map.entry("service_account_impersonation_url", "https://iamcredentials.googleapis.com/"),
        Map.entry("credential_source", "/fake/not/read"), Map.entry("quota_project_id", "extra"),
        Map.entry("private_key_file", "/fake/not/read"), Map.entry("client_email", "header\r\nInjection"));
    for (var entry : invalid.entrySet()) {
      ObjectNode changed = credentials(pair); changed.put(entry.getKey(), entry.getValue());
      rejects(() -> new ProductionReadOnlyHttp(BINDING, json(changed), EMAIL, client));
      rejects(() -> ProductionReadOnlyHttp.open(BINDING, json(changed), EMAIL, true)); // Validation precedes real client factory.
    }
    for (String required : List.of("type", "client_email", "private_key", "token_uri")) {
      ObjectNode missing = credentials(pair); missing.remove(required);
      rejects(() -> new ProductionReadOnlyHttp(BINDING, json(missing), EMAIL, client));
    }
    assertEquals(0, client.calls.get());
  }

  @Test void strictCredentialJsonRejectsDuplicateTrailingNestedMalformedAndOversizedData() {
    var client = new FakeClient(request -> { throw new AssertionError(); });
    String valid = new String(credentialBytes, StandardCharsets.UTF_8);
    List<byte[]> bad = new ArrayList<>(List.of(bytes(valid.substring(0, valid.length()-1) + ",\"type\":\"service_account\"}"),
        bytes(valid + " {}"), bytes("[]"), bytes("null"), bytes("{"), new byte[0], new byte[ProductionReadOnlyHttp.MAX_CREDENTIAL_BYTES + 1]));
    for (String name : List.of("type", "client_email", "private_key", "token_uri", "universe_domain")) {
      ObjectNode changed = credentials(pair); changed.put(name, 123); bad.add(json(changed));
      changed = credentials(pair); changed.putNull(name); bad.add(json(changed));
    }
    ObjectNode nested = credentials(pair); nested.set("project_id", JSON.createArrayNode().add(JSON.createObjectNode())); bad.add(json(nested));
    for (byte[] bytes : bad) rejects(() -> new ProductionReadOnlyHttp(BINDING, bytes, EMAIL, client));
    rejects(() -> new ProductionReadOnlyHttp(BINDING, null, EMAIL, client)); assertEquals(0, client.calls.get());
  }

  @Test void onlyExplicitRsaPkcs8AtLeast2048BitsAccepted() throws Exception {
    var client = new FakeClient(request -> { throw new AssertionError(); });
    KeyPairGenerator weak = KeyPairGenerator.getInstance("RSA"); weak.initialize(1024);
    rejects(() -> new ProductionReadOnlyHttp(BINDING, json(credentials(weak.generateKeyPair())), EMAIL, client));
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC"); ec.initialize(256);
    rejects(() -> new ProductionReadOnlyHttp(BINDING, json(credentials(ec.generateKeyPair())), EMAIL, client));
    for (String key : List.of("not-a-key", "-----BEGIN RSA PRIVATE KEY-----\nAA==\n-----END RSA PRIVATE KEY-----\n",
        "-----BEGIN PRIVATE KEY-----\n@@@\n-----END PRIVATE KEY-----\n")) {
      ObjectNode changed = credentials(pair); changed.put("private_key", key);
      rejects(() -> new ProductionReadOnlyHttp(BINDING, json(changed), EMAIL, client));
    }
    assertEquals(0, client.calls.get());
  }

  @Test void optionalStandardMetadataHasNoEffectOnEndpoints() {
    var fields = credentials(pair);
    fields.put("project_id", "synthetic-project"); fields.put("private_key_id", "1234abcd"); fields.put("client_id", "123456789");
    fields.put("universe_domain", "googleapis.com"); fields.put("auth_uri", "https://accounts.google.com/o/oauth2/auth");
    fields.put("auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs");
    fields.put("client_x509_cert_url", "https://www.googleapis.com/robot/v1/metadata/x509/" + EMAIL.replace("@", "%40"));
    var client = new FakeClient(request -> response(200, tokenRequest(request) ? validToken() : bytes("{}")));
    try (var transport = new ProductionReadOnlyHttp(BINDING, json(fields), EMAIL, client)) { assertArrayEquals(bytes("{}"), transport.readFullResource()); }
    assertEquals(2, client.calls.get());
  }

  @Test void matureClientIsDirectTrustedDefaultTlsWithoutRetryRedirectAuthOrProxy() throws Exception {
    HttpClientBuilder builder = HttpClients.custom(); assertSame(builder, ProductionReadOnlyHttp.configure(builder));
    for (String name : List.of("automaticRetriesDisabled", "redirectHandlingDisabled", "cookieManagementDisabled",
        "authCachingDisabled", "contentCompressionDisabled", "connectionStateDisabled")) assertEquals(true, field(builder, name));
    assertEquals(false, field(builder, "systemProperties"));
    HttpRoutePlanner planner = (HttpRoutePlanner)field(builder, "routePlanner");
    for (String host : List.of("oauth2.googleapis.com", "sheets.googleapis.com")) {
      var route = planner.determineRoute(new HttpHost("https", host, 443), new BasicHttpContext());
      assertTrue(route.isSecure()); assertNull(route.getProxyHost());
      assertThrows(HttpException.class, () -> planner.determineRoute(new HttpHost("http", host, 80), new BasicHttpContext()));
      assertThrows(HttpException.class, () -> planner.determineRoute(new HttpHost("https", host, 444), new BasicHttpContext()));
    }
    for (String host : List.of("metadata.google.internal", "attacker.invalid", "localhost", "www.googleapis.com"))
      assertThrows(HttpException.class, () -> planner.determineRoute(new HttpHost("https", host, 443), new BasicHttpContext()));
  }

  @Test void realFactoryRequiresNopLoggingBeforeAllocationAndNeverExecutesHttp() {
    if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof org.slf4j.helpers.NOPLoggerFactory) {
      assertDoesNotThrow(ProductionReadOnlyHttp::requireSilentLogging); // Do not create a real client in this suite.
    } else {
      rejects(ProductionReadOnlyHttp::requireSilentLogging);
      rejects(() -> ProductionReadOnlyHttp.open(BINDING, credentialBytes, EMAIL, true));
    }
  }

  @Test void tokenErrorsNeverReadErrorBodyOrIssueAnotherToken() {
    for (int status : List.of(206, 301, 302, 307, 401, 403, 429, 500, 503)) {
      AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
      var client = new FakeClient(request -> response(status, countingBody(reads, closes), -1));
      try (var transport = transport(client)) {
        rejects(transport::readFullResource); rejects(transport::readFullResource); rejects(() -> transport.readFullResource());
      }
      assertEquals(1, client.calls.get()); assertEquals(0, reads.get()); assertEquals(1, closes.get());
    }
  }
  private static InputStream countingBody(AtomicInteger reads, AtomicInteger closes) {
    return new InputStream() { public int read() { reads.incrementAndGet(); return 'x'; } public void close() { closes.incrementAndGet(); } };
  }

  @Test void sheetsErrorsDoNotRefreshRetryOrWriteAnythingAnd401InvalidatesToken() {
    for (int status : List.of(206, 301, 302, 307, 401, 403, 429, 500, 503)) {
      AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
      var client = new FakeClient(request -> tokenRequest(request) ? response(200, validToken()) : response(status, countingBody(reads, closes), -1));
      try (var transport = transport(client)) {
        rejects(() -> { transport.readFullResource(); });
        if (status == 401) rejects(transport::readFullResource);
      }
      assertEquals(2, client.calls.get()); assertEquals(0, reads.get()); assertEquals(1, closes.get());
    }
  }

  @Test void malformedAndExpiredTokenResponsesAreSanitizedWithoutSheetsOrReissuance() {
    List<byte[]> malformed = new ArrayList<>();
    for (Object value : List.of("", "injected\r\nheader", "a".repeat(4097), 123))
      malformed.add(json(Map.of("access_token", value, "token_type", "Bearer", "expires_in", 3600)));
    for (Object expiry : List.of(0, -1, 3601, "3600", 1.5, 1, 5))
      malformed.add(json(Map.of("access_token", TOKEN, "token_type", "Bearer", "expires_in", expiry)));
    for (String type : List.of("Basic", "", "bearer")) malformed.add(json(Map.of("access_token", TOKEN, "token_type", type, "expires_in", 3600)));
    malformed.add(bytes("{\"access_token\":\"synthetic.fake.token\",\"access_token\":\"another\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
    malformed.add(json(Map.of("access_token", TOKEN, "token_type", "Bearer", "expires_in", 3600, "refresh_token", "reject")));
    malformed.add(json(Map.of("access_token", TOKEN, "token_type", "Bearer", "expires_in", 3600, "scope", "wrong")));
    malformed.add(json(Map.of("access_token", TOKEN, "token_type", "Bearer", "expires_in", 3600, "scope", "https://www.googleapis.com/auth/spreadsheets")));
    malformed.add(bytes("{}")); malformed.add(bytes("not-json with synthetic.fake.token"));
    for (byte[] token : malformed) {
      var client = new FakeClient(request -> response(200, token));
      try (var transport = transport(client)) { rejects(transport::readFullResource); rejects(transport::readFullResource); }
      assertEquals(1, client.calls.get());
    }
  }

  @Test void expiredCachedTokenFailsBeforeAnotherHttpCallOrRefresh() throws Exception {
    var client = new FakeClient(request -> response(200, tokenRequest(request) ? validToken() : bytes("{}")));
    try (var transport = transport(client)) {
      transport.readFullResource(); Object token = field(transport, "token");
      var expiry = token.getClass().getDeclaredField("expiresAtNanos"); expiry.setAccessible(true);
      expiry.setLong(token, System.nanoTime() - 1);
      rejects(transport::readFullResource); rejects(() -> transport.readFullResource());
      assertNull(field(token, "value"));
    }
    assertEquals(2, client.calls.get());
  }

  @Test void bothResponseKindsEnforceStreamingByteLimitsWithoutDraining() {
    for (int phase = 0; phase < 2; phase++) {
      int selected = phase;
      int limit = phase == 0 ? ProductionReadOnlyHttp.MAX_TOKEN_BYTES : ProductionReadOnlyWorkbook.MAX_RESPONSE_BYTES;
      AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
      var client = new FakeClient(request -> selected != 0 && tokenRequest(request) ? response(200, validToken())
          : response(200, countingBody(reads, closes), -1));
      try (var transport = transport(client)) {
        rejects(() -> { transport.readFullResource(); });
      }
      assertEquals(limit + 1, reads.get()); assertEquals(1, closes.get()); assertEquals(phase == 0 ? 1 : 2, client.calls.get());
    }
  }

  @Test void headersAndDeclaredBoundsRejectBeforeReadingBodyForEveryResponseKind() {
    for (int phase = 0; phase < 2; phase++) {
      int selected = phase;
      int limit = phase == 0 ? ProductionReadOnlyHttp.MAX_TOKEN_BYTES : ProductionReadOnlyWorkbook.MAX_RESPONSE_BYTES;
      for (String[] bad : List.of(new String[]{"Content-Type", "text/html"}, new String[]{"Content-Encoding", "gzip"},
          new String[]{"Content-Length", "-1"}, new String[]{"Content-Length", Integer.toString(limit + 1)},
          new String[]{"Transfer-Encoding", "gzip"}, new String[]{"Content-Type", "application/json; charset=utf-16"},
          new String[]{"X-Extra", "x".repeat(8193)}, new String[]{"X-Extra", "line\r\nbreak"})) {
        AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
        var client = new FakeClient(request -> {
          if (selected != 0 && tokenRequest(request)) return response(200, validToken());
          var response = response(200, countingBody(reads, closes), -1); response.setHeader(bad[0], bad[1]); return response;
        });
        try (var transport = transport(client)) { rejects(() -> { transport.readFullResource(); }); }
        assertEquals(0, reads.get()); assertEquals(1, closes.get());
      }
    }
  }

  @Test void duplicateConflictingHeadersHeaderCountAndLengthMismatchAreRejected() {
    for (int variant = 0; variant < 7; variant++) {
      int selected = variant; AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
      var client = new FakeClient(request -> {
        if (tokenRequest(request)) return response(200, validToken());
        var response = response(200, countingBody(reads, closes), -1);
        if (selected == 0) response.addHeader("Content-Type", "application/json");
        if (selected == 1) { response.setHeader("Content-Length", "0"); response.addHeader("Content-Length", "0"); }
        if (selected == 2) { response.setHeader("Content-Length", "0"); response.setHeader("Transfer-Encoding", "chunked"); }
        if (selected == 3) { for (int n = 0; n < 33; n++) response.addHeader("X-Header-" + n, "x"); }
        if (selected == 4) response.removeHeaders("Content-Type");
        if (selected == 5) { response.setHeader("Content-Encoding", "identity"); response.addHeader("Content-Encoding", "identity"); }
        if (selected == 6) { response.setHeader("Transfer-Encoding", "chunked"); response.addHeader("Transfer-Encoding", "chunked"); }
        return response;
      });
      try (var transport = transport(client)) { rejects(transport::readFullResource); }
      assertEquals(0, reads.get()); assertEquals(1, closes.get());
    }
    for (long declared : List.of(1L, 3L)) {
      var client = new FakeClient(request -> tokenRequest(request) ? response(200, validToken()) : response(200, new ByteArrayInputStream(bytes("{}")), declared));
      try (var transport = transport(client)) { rejects(transport::readFullResource); }
    }
    var conflict = new FakeClient(request -> {
      if (tokenRequest(request)) return response(200, validToken());
      var response = response(200, bytes("{}")); response.setHeader("Content-Length", "1"); return response;
    });
    try (var transport = transport(conflict)) { rejects(transport::readFullResource); }
  }

  @Test void validChunkedIdentityUtf8IsAcceptedWithoutRequiringContentLength() {
    var client = new FakeClient(request -> {
      if (tokenRequest(request)) return response(200, validToken());
      var response = response(200, new ByteArrayInputStream(bytes("{}")), -1);
      response.setHeader("Content-Type", "application/json; charset=utf-8"); response.setHeader("Content-Encoding", "identity");
      response.setHeader("Transfer-Encoding", "chunked"); return response;
    });
    try (var transport = transport(client)) { assertArrayEquals(bytes("{}"), transport.readFullResource()); }
  }

  @Test void transportExceptionsNeverExposeCauseUrlRawBodyTokenOrRetry() {
    for (boolean tokenStage : List.of(false, true)) {
      var client = new FakeClient(request -> {
        if (!tokenStage && tokenRequest(request)) return response(200, validToken());
        throw new IOException(TOKEN + " https://private.invalid/raw /private/path");
      });
      try (var transport = transport(client)) { rejects(() -> transport.readFullResource()); if (tokenStage) rejects(transport::readFullResource); }
      assertEquals(tokenStage ? 1 : 2, client.calls.get());
    }
  }

  private static void ignoreInterruptsUntil(CountDownLatch release) {
    boolean done = false; while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) {}
  }
  @Test void lateTokenResponseIsClosedAndCannotCauseSheetsOrSecondTokenRequest() throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger(); AtomicReference<HttpUriRequestBase> captured = new AtomicReference<>();
    var client = new FakeClient(request -> {
      captured.set((HttpUriRequestBase)request); started.countDown(); ignoreInterruptsUntil(release);
      return response(200, new InputStream() { public int read() { reads.incrementAndGet(); return -1; } public void close() { closed.countDown(); } }, -1);
    });
    try (var transport = new ProductionReadOnlyHttp(BINDING, credentialBytes, EMAIL, client, Duration.ofMillis(200))) {
      try {
        rejects(transport::readFullResource); assertTrue(started.await(1, TimeUnit.SECONDS)); assertTrue(captured.get().isCancelled());
        rejects(transport::readFullResource); assertEquals(1, client.calls.get());
      } finally { release.countDown(); }
      assertTrue(closed.await(2, TimeUnit.SECONDS)); awaitInactive(transport); rejects(transport::readFullResource);
    }
    assertEquals(1, client.calls.get()); assertEquals(0, reads.get());
  }

  @Test void deadlineCoversTokenPlusSheetsAndLateGetIsCancelled() throws Exception {
    CountDownLatch release = new CountDownLatch(1), closed = new CountDownLatch(1);
    AtomicReference<HttpUriRequestBase> captured = new AtomicReference<>(); AtomicInteger reads = new AtomicInteger();
    var client = new FakeClient(request -> {
      if (tokenRequest(request)) return response(200, validToken());
      captured.set((HttpUriRequestBase)request); ignoreInterruptsUntil(release);
      return response(200, new InputStream() { public int read() { reads.incrementAndGet(); return -1; } public void close() { closed.countDown(); } }, -1);
    });
    try (var transport = new ProductionReadOnlyHttp(BINDING, credentialBytes, EMAIL, client, Duration.ofMillis(200))) {
      long begin = System.nanoTime();
      try {
        rejects(() -> transport.readFullResource());
        assertTrue(Duration.ofNanos(System.nanoTime() - begin).toMillis() < 2000);
        assertNotNull(captured.get()); assertTrue(captured.get().isCancelled());
        rejects(transport::readFullResource); rejects(() -> transport.readFullResource());
      } finally { release.countDown(); }
      assertTrue(closed.await(2, TimeUnit.SECONDS)); awaitInactive(transport);
    }
    assertEquals(2, client.calls.get()); assertEquals(0, reads.get());
  }

  @Test void deadlineAndActiveGuardIncludeBodyReaderAndResponseClosure() throws Exception {
    for (boolean blockClose : List.of(false, true)) {
      CountDownLatch release = new CountDownLatch(1), ended = new CountDownLatch(1);
      var client = new FakeClient(request -> {
        if (tokenRequest(request)) return response(200, validToken());
        return response(200, new InputStream() {
          public int read() { if (!blockClose) ignoreInterruptsUntil(release); return -1; }
          public void close() { if (blockClose) ignoreInterruptsUntil(release); ended.countDown(); }
        }, -1);
      });
      try (var transport = new ProductionReadOnlyHttp(BINDING, credentialBytes, EMAIL, client, Duration.ofMillis(200))) {
        try { rejects(transport::readFullResource); rejects(transport::readFullResource); }
        finally { release.countDown(); }
        assertTrue(ended.await(2, TimeUnit.SECONDS)); awaitInactive(transport);
      }
      assertEquals(2, client.calls.get());
    }
  }
  private static void awaitInactive(ProductionReadOnlyHttp transport) throws Exception {
    AtomicReference<?> running = (AtomicReference<?>)field(transport, "running");
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (running.get() != null && System.nanoTime() < end) Thread.sleep(5);
    assertNull(running.get());
  }

  @Test void tokenIssuanceAndWorkbookUseOneSharedDeadline() throws Exception {
    CountDownLatch release = new CountDownLatch(1), closed = new CountDownLatch(1);
    AtomicLong sheetsTimeout = new AtomicLong();
    var client = new FakeClient(request -> {
      if (tokenRequest(request)) {
        try { Thread.sleep(200); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        return response(200, validToken());
      }
      sheetsTimeout.set(((HttpUriRequestBase)request).getConfig().getResponseTimeout().toMilliseconds());
      ignoreInterruptsUntil(release);
      return response(200, new InputStream() {
        public int read() { return -1; } public void close() { closed.countDown(); }
      }, -1);
    });
    try (var transport = new ProductionReadOnlyHttp(BINDING, credentialBytes, EMAIL, client, Duration.ofMillis(500))) {
      try { rejects(transport::readFullResource); assertTrue(sheetsTimeout.get() > 0 && sheetsTimeout.get() < 350); }
      finally { release.countDown(); }
      assertTrue(closed.await(2, TimeUnit.SECONDS)); awaitInactive(transport);
    }
    assertEquals(2, client.calls.get());
  }

  @Test void absentAndNonProgressingBodiesFailClosedWithoutRetries() {
    for (int variant = 0; variant < 3; variant++) {
      int selected = variant;
      var client = new FakeClient(request -> {
        if (tokenRequest(request)) return response(200, validToken());
        if (selected == 0) return null;
        if (selected == 1) { var response = response(200, bytes("{}")); response.setEntity(null); return response; }
        return response(200, new InputStream() {
          public int read() { return 0; }
          public int read(byte[] buffer, int offset, int length) { return 0; }
        }, -1);
      });
      try (var transport = transport(client)) { rejects(transport::readFullResource); }
      assertEquals(2, client.calls.get());
    }
  }

  @Test void interruptDuringHttpCancelsRequestPreservesFlagAndBlocksOverlap() throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
    AtomicReference<HttpUriRequestBase> captured = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean(); AtomicReference<Throwable> error = new AtomicReference<>();
    var client = new FakeClient(request -> {
      captured.set((HttpUriRequestBase)request); started.countDown(); ignoreInterruptsUntil(release);
      return response(200, new InputStream() {
        public int read() { return -1; } public void close() { closed.countDown(); }
      }, -1);
    });
    try (var transport = transport(client)) {
      Thread caller = new Thread(() -> {
        try { transport.readFullResource(); } catch (Throwable failure) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
      });
      caller.start();
      try {
        assertTrue(started.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000);
        assertFalse(caller.isAlive()); assertNotNull(error.get()); sanitized(error.get()); assertTrue(interrupted.get());
        assertTrue(captured.get().isCancelled()); rejects(transport::readFullResource);
      } finally { release.countDown(); caller.interrupt(); }
      assertTrue(closed.await(2, TimeUnit.SECONDS)); awaitInactive(transport);
    }
    assertEquals(1, client.calls.get());
  }

  @Test void closedTransportAndInterruptedCallerCannotIssueTokenAndCloseWipesCachedToken() throws Exception {
    var unused = new FakeClient(request -> { throw new AssertionError(); });
    try (var transport = transport(unused)) {
      Thread.currentThread().interrupt();
      try { rejects(transport::readFullResource); assertTrue(Thread.currentThread().isInterrupted()); }
      finally { Thread.interrupted(); }
    }
    assertEquals(0, unused.calls.get());
    var client = new FakeClient(request -> response(200, tokenRequest(request) ? validToken() : bytes("{}")));
    var transport = transport(client); transport.readFullResource(); Object token = field(transport, "token");
    char[] mutableToken = (char[])field(token, "value"); transport.close(); transport.close();
    assertNull(field(token, "value")); assertArrayEquals(new char[mutableToken.length], mutableToken);
    assertNull(field(transport, "token")); rejects(transport::readFullResource);
    assertEquals(2, client.calls.get()); assertEquals(1, client.closes.get());
  }

  @Test void closeFailureWipesCompleteOAuthAndWorkbookResponseBeforeTransfer() throws Exception {
    for(boolean failToken:List.of(true,false)) {
      AtomicReference<ProductionReadOnlyHttp> transportRef=new AtomicReference<>();AtomicReference<byte[]> owned=new AtomicReference<>();
      var client=new FakeClient(request->{
        boolean token=tokenRequest(request);if(token && !failToken)return response(200,validToken());
        byte[] input=token?validToken():bytes("{\"synthetic_private_payload\":\"must-not-escape\"}");
        return response(200,new ByteArrayInputStream(input){@Override public void close() throws IOException {
          try {var operation=((AtomicReference<?>)field(transportRef.get(),"running")).get();
            owned.set((byte[])((AtomicReference<?>)field(operation,"response")).get());
          }catch(Exception e){throw new IOException("test lookup failed");}
          throw new IllegalStateException("synthetic token and private payload close failure");
        }},input.length);
      });
      try(var transport=transport(client)){transportRef.set(transport);rejects(transport::readFullResource);}
      assertNotNull(owned.get());assertTrue(owned.get().length>0);for(byte b:owned.get())assertEquals(0,b);
      assertEquals(failToken?1:2,client.calls.get());
    }
  }
  @Test void disabledFactoryAndIndependentIdentityPinPrecedeAnyClientAllocation(){
    rejects(()->ProductionReadOnlyHttp.open(null,null,null,false));
    rejects(()->ProductionReadOnlyHttp.open(BINDING,credentialBytes,"other@synthetic-project.iam.gserviceaccount.com",true));
  }

  @Test void eightDigitContentLengthWithinFullResourceLimitIsAccepted(){
    byte[] payload=new byte[10_000_000];var client=new FakeClient(request->{
      if(tokenRequest(request))return response(200,validToken());var response=response(200,payload);response.setHeader("Content-Length","10000000");return response;
    });
    try(var transport=transport(client)){byte[] result=transport.readFullResource();assertEquals(payload.length,result.length);Arrays.fill(result,(byte)0);}
    assertEquals(2,client.calls.get());
  }

  @Test void timeoutWipesCompleteResponseWhileCleanupIsStillBlocked() throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
    AtomicReference<ProductionReadOnlyHttp> ref=new AtomicReference<>();AtomicReference<byte[]> owned=new AtomicReference<>();
    var client=new FakeClient(request->{
      if(tokenRequest(request))return response(200,validToken());byte[] input=bytes("{\"private\":\"synthetic\"}");
      return response(200,new ByteArrayInputStream(input){@Override public void close(){
        try{var op=((AtomicReference<?>)field(ref.get(),"running")).get();owned.set((byte[])((AtomicReference<?>)field(op,"response")).get());}
        catch(Exception e){throw new IllegalStateException("test lookup");}
        entered.countDown();ignoreInterruptsUntil(release);finished.countDown();
      }},input.length);
    });
    try(var transport=new ProductionReadOnlyHttp(BINDING,credentialBytes,EMAIL,client,Duration.ofMillis(200))){
      ref.set(transport);try{rejects(transport::readFullResource);assertTrue(entered.await(1,TimeUnit.SECONDS));assertNotNull(owned.get());for(byte b:owned.get())assertEquals(0,b);}
      finally{release.countDown();}assertTrue(finished.await(2,TimeUnit.SECONDS));awaitInactive(transport);
    }
  }
}
