package com.bridge.persistence;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;

/**
 * Dedicated manual-maintenance transport. The caller supplies already-authorized, in-memory inputs.
 * No file, environment, ADC, metadata-server, delegation, persistent token, refresh, or retry path exists.
 * The fixed OAuth scope is broad; exact target binding here is not an OAuth grant restriction.
 */
final class LiveQuarterHttp implements LiveQuarterStorage.Transport, AutoCloseable {
  static final int MAX_CREDENTIAL_BYTES = 64 * 1024, MAX_TOKEN_BYTES = 16 * 1024,
      MAX_BATCH_RESPONSE_BYTES = 64 * 1024, MAX_REQUEST_BYTES = 4 * 1024 * 1024;
  static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
  private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
  private static final Duration DEADLINE = Duration.ofSeconds(20);
  private static final String MESSAGE = "Manual-quarter HTTP operation rejected or unavailable";
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_CREDENTIAL_BYTES)
          .maxNestingDepth(4).maxStringLength(20000).maxNameLength(128).maxNumberLength(20)
          .maxTokenCount(128).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> CREDENTIAL_FIELDS = Set.of("type", "project_id", "private_key_id", "private_key",
      "client_email", "client_id", "auth_uri", "token_uri", "auth_provider_x509_cert_url",
      "client_x509_cert_url", "universe_domain");
  private final URI readUri, writeUri;
  private final Credentials credentials;
  private final CloseableHttpClient http;
  private final Duration deadline;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<Operation> running = new AtomicReference<>();
  private final AtomicBoolean tokenRequested = new AtomicBoolean();
  private volatile Token token;

  /** Validates the binding and credentials before allocating the fixed-route client; does not send HTTP. */
  static LiveQuarterHttp open(ManualQuarterMaintenance.Binding binding, byte[] serviceAccountJson) {
    validateBinding(binding);
    Credentials credentials = parseCredentials(serviceAccountJson);
    try { return new LiveQuarterHttp(binding, credentials, newClient(), DEADLINE); }
    catch (Exception | LinkageError rejected) { credentials.close(); throw failure(); }
  }

  /** Package-private fake-client seams. They cannot change endpoints, scopes, headers, or HTTP methods. */
  LiveQuarterHttp(ManualQuarterMaintenance.Binding binding, byte[] serviceAccountJson, CloseableHttpClient http) {
    this(binding, serviceAccountJson, http, DEADLINE);
  }
  LiveQuarterHttp(ManualQuarterMaintenance.Binding binding, byte[] serviceAccountJson, CloseableHttpClient http,
      Duration deadline) {
    this(binding, checkedCredentials(binding, serviceAccountJson, http, deadline), http, deadline);
  }
  private LiveQuarterHttp(ManualQuarterMaintenance.Binding binding, Credentials credentials,
      CloseableHttpClient http, Duration deadline) {
    this.credentials = credentials; this.http = http; this.deadline = deadline;
    String base = "https://sheets.googleapis.com/v4/spreadsheets/" + binding.spreadsheetId();
    readUri = URI.create(base + "?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED");
    writeUri = URI.create(base + ":batchUpdate");
  }
  private static Credentials checkedCredentials(ManualQuarterMaintenance.Binding binding, byte[] bytes,
      CloseableHttpClient http, Duration deadline) {
    validateBinding(binding);
    if (http == null || deadline == null || deadline.isNegative() || deadline.isZero()
        || deadline.compareTo(DEADLINE) > 0) throw failure();
    return parseCredentials(bytes);
  }
  private static void validateBinding(ManualQuarterMaintenance.Binding binding) {
    try {
      if (binding == null || binding.spreadsheetId() == null
          || !binding.spreadsheetId().matches("[A-Za-z0-9_-]{10,160}") || binding.datasetId() == null
          || !UUID.fromString(binding.datasetId()).toString().equals(binding.datasetId())) throw failure();
    } catch (Exception rejected) { throw failure(); }
  }

  private static CloseableHttpClient newClient() {
    requireSilentLogging(); // Refuse active Apache header/wire logging before any client is allocated.
    try {
      return configure(HttpClients.custom()).setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
          .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
              .setMaxHeaderCount(32).setMaxLineLength(8192).setMaxEmptyLineCount(1).build()).build())
          .setMaxConnTotal(1).setMaxConnPerRoute(1).setDefaultConnectionConfig(ConnectionConfig.custom()
              .setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(5)).build()).build()).build();
    } catch (Exception | LinkageError rejected) { throw failure(); }
  }
  static void requireSilentLogging() {
    try { if (!(LoggerFactory.getILoggerFactory() instanceof NOPLoggerFactory)) throw failure(); }
    catch (Exception | LinkageError rejected) { throw failure(); }
  }
  static HttpClientBuilder configure(HttpClientBuilder builder) {
    // Default trusted TLS only. No system proxy/properties, credential provider, custom trust, or logger.
    return builder.disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
        .disableAuthCaching().disableContentCompression().disableConnectionState().disableDefaultUserAgent()
        .setRoutePlanner((host, context) -> {
          if (!"https".equals(host.getSchemeName())
              || !Set.of("oauth2.googleapis.com", "sheets.googleapis.com").contains(host.getHostName())
              || host.getPort() != -1 && host.getPort() != 443)
            throw new HttpException("Manual-quarter route rejected");
          return new HttpRoute(new HttpHost("https", host.getHostName(), 443), null, true);
        });
  }

  @Override public byte[] readWorkbook() { return invoke(null); }
  @Override public void replaceWorkbook(byte[] json) {
    if (json == null || json.length == 0 || json.length > MAX_REQUEST_BYTES) throw failure();
    // Only Storage constructs this body. It has no transport-level URL, method, header, or query controls.
    byte[] copy = json.clone();
    try { invoke(copy); }
    finally {
      // If a timed-out worker still owns this buffer, it clears it only after the actual IO finishes.
      if (running.get() == null) Arrays.fill(copy, (byte) 0);
    }
  }
  private byte[] invoke(byte[] body) {
    if (closed.get() || Thread.currentThread().isInterrupted()) { clear(body); throw failure(); }
    Operation operation = new Operation(body, System.nanoTime() + deadline.toNanos());
    if (!running.compareAndSet(null, operation)) { clear(body); throw failure(); }
    FutureTask<byte[]> work = new FutureTask<>(() -> {
      try { return perform(operation); }
      finally { operation.finish(); }
    });
    operation.work = work;
    Thread worker = new Thread(() -> {
      try { work.run(); }
      finally { operation.finish(); } // Also release a task cancelled before its callable started.
    }, "manual-quarter-http");
    worker.setDaemon(true);
    try {
      if (closed.get()) throw failure();
      worker.start();
      byte[] result = work.get(remaining(operation).toNanos(), TimeUnit.NANOSECONDS);
      remaining(operation);
      return result;
    } catch (InterruptedException interrupted) {
      operation.stop(); Thread.currentThread().interrupt(); throw failure();
    } catch (Exception | LinkageError rejected) {
      operation.stop();
      if (worker.getState() == Thread.State.NEW) operation.finish();
      throw failure();
    }
  }
  private byte[] perform(Operation operation) {
    try {
      remaining(operation);
      Token currentToken = accessToken(operation);
      remaining(operation);
      byte[] result = exchange(operation, operation.body == null ? new HttpGet(readUri) : new HttpPost(writeUri),
          operation.body, operation.body == null ? null : ContentType.APPLICATION_JSON,
          currentToken.bearer(), operation.body == null ? LiveQuarterWorkbook.MAX_RESPONSE_BYTES : MAX_BATCH_RESPONSE_BYTES);
      if (operation.body != null) { clear(result); return new byte[0]; }
      return result;
    } catch (Exception | LinkageError rejected) { throw failure(); }
  }
  private Token accessToken(Operation operation) {
    Token existing = token;
    if (existing != null) { existing.requireValid(); return existing; }
    // Latch before signing or issuing the first POST. An uncertain token issuance is never retried.
    if (!tokenRequested.compareAndSet(false, true)) throw failure();
    Token acquired = acquireToken(operation);
    synchronized (this) {
      if (closed.get() || operation.stopped.get()) { acquired.close(); throw failure(); }
      token = acquired;
      return acquired;
    }
  }
  private Token acquireToken(Operation operation) {
    byte[] assertion = null, response = null;
    long issuedAtNanos = System.nanoTime(); // Conservative lifetime includes signing and token response latency.
    try {
      remaining(operation);
      assertion = credentials.assertionBody();
      remaining(operation);
      response = exchange(operation, new HttpPost(TOKEN_URI), assertion,
          ContentType.APPLICATION_FORM_URLENCODED, null, MAX_TOKEN_BYTES);
      JsonNode result = JSON.readTree(response);
      fields(result, Set.of("access_token", "token_type", "expires_in", "scope"));
      if (!"Bearer".equals(text(result, "token_type")) || !result.path("expires_in").isIntegralNumber()
          || !result.path("expires_in").canConvertToInt() || result.path("expires_in").intValue() < 1
          || result.path("expires_in").intValue() > 3600
          || result.has("scope") && !SCOPE.equals(text(result, "scope"))) throw failure();
      remaining(operation);
      return new Token(text(result, "access_token"), result.path("expires_in").intValue(), issuedAtNanos);
    } catch (Exception | LinkageError rejected) { throw failure(); }
    finally { clear(assertion); clear(response); credentials.close(); }
  }

  @SuppressWarnings("deprecation") // Close immediately instead of consuming/draining error or oversized bodies.
  private byte[] exchange(Operation operation, HttpUriRequestBase request, byte[] payload, ContentType contentType,
      String bearer, int limit) {
    CloseableHttpResponse response = null;
    try {
      remaining(operation);
      operation.current.set(request);
      Timeout left = Timeout.ofMilliseconds(Math.max(1, remaining(operation).toMillis()));
      request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).setAuthenticationEnabled(false)
          .setContentCompressionEnabled(false).setHardCancellationEnabled(true).setProtocolUpgradeEnabled(false)
          .setConnectionRequestTimeout(left).setResponseTimeout(left).build());
      request.setHeader("Accept", "application/json"); request.setHeader("Accept-Encoding", "identity");
      if (bearer != null) request.setHeader("Authorization", bearer);
      if (payload != null) request.setEntity(new ByteArrayEntity(payload, contentType));
      remaining(operation);
      if (bearer != null) { Token current = token; if (current == null) throw failure(); current.requireValid(); }
      response = http.execute(request);
      remaining(operation);
      if (response != null && response.getCode() == 401 && bearer != null) {
        Token invalid = token; if (invalid != null) invalid.close();
      }
      if (response == null || response.getCode() != 200) throw failure();
      return boundedBody(response, operation, limit);
    } catch (Exception | LinkageError rejected) { throw failure(); }
    finally {
      // Removal does not promise to erase immutable strings or copies held by the HTTP/TLS/JCA libraries.
      request.cancel(); request.removeHeaders("Authorization"); request.setEntity(null);
      try { if (response != null) response.close(CloseMode.IMMEDIATE); }
      catch (Exception | LinkageError rejected) { throw failure(); }
      finally { operation.current.compareAndSet(request, null); }
    }
  }
  private byte[] boundedBody(ClassicHttpResponse response, Operation operation, int limit) throws IOException {
    Header[] headers = response.getHeaders();
    if (headers.length > 32) throw failure();
    int headerBytes = 0;
    for (Header header : headers) {
      String name = header.getName(), value = header.getValue();
      if (name == null || value == null || name.length() + value.length() > 8192
          || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw failure();
      headerBytes += name.length() + value.length();
      if (headerBytes > 32 * 8192) throw failure();
    }
    String type = oneHeader(response, "Content-Type", true), encoding = oneHeader(response, "Content-Encoding", false);
    String lengthText = oneHeader(response, "Content-Length", false), transfer = oneHeader(response, "Transfer-Encoding", false);
    if (!type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
        || !Set.of("", "identity").contains(encoding)
        || !Set.of("", "chunked").contains(transfer) || !transfer.isEmpty() && !lengthText.isEmpty()) throw failure();
    long length = -1;
    if (!lengthText.isEmpty()) {
      if (!lengthText.matches("[0-9]{1,7}")) throw failure();
      length = Long.parseLong(lengthText);
    }
    HttpEntity entity = response.getEntity();
    if (entity == null || length > limit || entity.getContentLength() > limit || entity.getContentLength() < -1
        || length >= 0 && entity.getContentLength() >= 0 && length != entity.getContentLength()) throw failure();
    InputStream body = entity.getContent();
    if (body == null) throw failure();
    WipingOutput output = new WipingOutput(); byte[] buffer = new byte[4096];
    try {
      while (output.size() <= limit) {
        remaining(operation);
        int count = body.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size()));
        remaining(operation);
        if (count == -1) break;
        if (count <= 0) throw failure();
        output.write(buffer, 0, count);
      }
      if (output.size() > limit || length >= 0 && output.size() != length
          || entity.getContentLength() >= 0 && output.size() != entity.getContentLength()) throw failure();
      return output.toByteArray();
    } finally { clear(buffer); output.wipe(); }
  }
  private static String oneHeader(ClassicHttpResponse response, String key, boolean required) {
    Header[] headers = response.getHeaders(key);
    if (headers.length > 1 || required && headers.length != 1) throw failure();
    return headers.length == 0 ? "" : headers[0].getValue();
  }
  private Duration remaining(Operation operation) {
    long left = operation.deadlineNanos - System.nanoTime();
    if (left <= 0 || closed.get() || operation.stopped.get() || Thread.currentThread().isInterrupted()) throw failure();
    return Duration.ofNanos(left);
  }
  private final class Operation {
    final byte[] body;
    final long deadlineNanos;
    final AtomicBoolean stopped = new AtomicBoolean();
    final AtomicReference<HttpUriRequestBase> current = new AtomicReference<>();
    volatile FutureTask<byte[]> work;
    Operation(byte[] body, long deadlineNanos) { this.body = body; this.deadlineNanos = deadlineNanos; }
    void stop() {
      stopped.set(true);
      HttpUriRequestBase request = current.get(); if (request != null) request.cancel();
      FutureTask<byte[]> task = work; if (task != null) task.cancel(true);
    }
    void finish() { clear(body); running.compareAndSet(this, null); }
    @Override public String toString() { return "Operation[REDACTED]"; }
  }
  private static final class WipingOutput extends ByteArrayOutputStream {
    void wipe() { clear(buf); reset(); }
    @Override public String toString() { return "ResponseBuffer[REDACTED]"; }
  }
  private static final class Token {
    private char[] value;
    private final long expiresAtNanos;
    Token(String raw, int expiresInSeconds, long issuedAtNanos) {
      if (raw == null || raw.isEmpty() || raw.length() > 4096
          || !raw.matches("[A-Za-z0-9._~+/=-]+")) throw failure();
      value = raw.toCharArray();
      // A short invocation only: never retain a usable token for more than ten minutes, never refresh.
      expiresAtNanos = issuedAtNanos + TimeUnit.SECONDS.toNanos(Math.max(0, Math.min(600, expiresInSeconds) - 5));
    }
    synchronized void requireValid() {
      if (value == null || expiresAtNanos - System.nanoTime() <= 0) { close(); throw failure(); }
    }
    synchronized String bearer() { requireValid(); return "Bearer " + new String(value); }
    synchronized void close() { if (value != null) Arrays.fill(value, '\0'); value = null; }
    @Override public String toString() { return "Token[REDACTED]"; }
  }
  private static final class Credentials {
    private String email;
    private PrivateKey key;
    Credentials(String email, PrivateKey key) { this.email = email; this.key = key; }
    synchronized byte[] assertionBody() {
      byte[] signingBytes = null, signature = null;
      try {
        if (key == null || email == null) throw failure();
        long now = Instant.now().getEpochSecond();
        String signing = b64(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT"))) + "."
            + b64(JSON.writeValueAsBytes(Map.of("iss", email, "scope", SCOPE, "aud", TOKEN_URI.toString(),
                "iat", now, "exp", now + 300)));
        signingBytes = signing.getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key); signer.update(signingBytes);
        signature = signer.sign();
        return ("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="
            + signing + "." + b64(signature)).getBytes(StandardCharsets.US_ASCII);
      } catch (Exception | LinkageError rejected) { throw failure(); }
      finally { clear(signingBytes); clear(signature); }
    }
    synchronized void close() {
      PrivateKey old = key; key = null; email = null;
      if (old != null) try { old.destroy(); } catch (Exception ignored) { /* JCA may not support destruction. */ }
    }
    @Override public String toString() { return "Credentials[REDACTED]"; }
  }
  private static Credentials parseCredentials(byte[] source) {
    byte[] copy = null, encoded = null;
    try {
      if (source == null || source.length == 0 || source.length > MAX_CREDENTIAL_BYTES) throw failure();
      copy = source.clone(); JsonNode root = JSON.readTree(copy); fields(root, CREDENTIAL_FIELDS);
      if (!"service_account".equals(text(root, "type")) || !TOKEN_URI.toString().equals(text(root, "token_uri"))
          || root.has("universe_domain") && !"googleapis.com".equals(text(root, "universe_domain"))) throw failure();
      String email = text(root, "client_email");
      if (email.length() > 254 || !email.matches("[a-z0-9][a-z0-9._-]*@[a-z0-9][a-z0-9.-]*\\.iam\\.gserviceaccount\\.com")) throw failure();
      for (String field : List.of("project_id", "private_key_id", "client_id")) if (root.has(field)) {
        String value = text(root, field);
        if (value.isEmpty() || value.length() > 256 || !value.matches("[A-Za-z0-9_-]+")) throw failure();
      }
      optionalExact(root, "auth_uri", "https://accounts.google.com/o/oauth2/auth");
      optionalExact(root, "auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs");
      if (root.has("client_x509_cert_url")) {
        String cert = text(root, "client_x509_cert_url"), prefix = "https://www.googleapis.com/robot/v1/metadata/x509/";
        if (!cert.equals(prefix + email) && !cert.equals(prefix + email.replace("@", "%40"))) throw failure();
      }
      String pem = text(root, "private_key");
      if (pem.length() > 16384 || !pem.matches("-----BEGIN PRIVATE KEY-----\\r?\\n[A-Za-z0-9+/=\\r\\n]+-----END PRIVATE KEY-----(?:\\r?\\n)?")) throw failure();
      String base64 = pem.substring("-----BEGIN PRIVATE KEY-----".length(), pem.indexOf("-----END PRIVATE KEY-----"))
          .replace("\r", "").replace("\n", "");
      encoded = Base64.getDecoder().decode(base64);
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
      if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048 || rsa.getModulus().bitLength() > 8192)
        throw failure();
      return new Credentials(email, key);
    } catch (Exception | LinkageError rejected) { throw failure(); }
    finally { clear(copy); clear(encoded); }
  }
  private static void fields(JsonNode root, Set<String> allowed) {
    if (root == null || !root.isObject()) throw failure();
    root.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw failure(); });
  }
  private static String text(JsonNode root, String name) {
    if (root.get(name) == null || !root.get(name).isTextual()) throw failure();
    return root.get(name).textValue();
  }
  private static void optionalExact(JsonNode root, String name, String value) {
    if (root.has(name) && !value.equals(text(root, name))) throw failure();
  }
  private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private static void clear(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
  private static IllegalStateException failure() { return new IllegalStateException(MESSAGE); }
  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    Operation operation = running.get(); if (operation != null) operation.stop();
    credentials.close();
    synchronized (this) { Token old = token; token = null; if (old != null) old.close(); }
    try { http.close(CloseMode.IMMEDIATE); }
    catch (Exception | LinkageError rejected) { throw failure(); }
  }
  @Override public String toString() { return "LiveQuarterHttp[target=REDACTED, credentials=REDACTED]"; }
}
