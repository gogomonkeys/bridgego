package com.bridge.syntheticcleanup;

import com.bridge.persistence.SyntheticCleanupWorkbook;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.interfaces.RSAKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Read-only OAuth scope and exactly one pinned workbook GET. No batch, values, retry or redirect methods. */
final class SyntheticCleanupSheetsReader implements SyntheticCleanupService.Reader {
  static final int MAX_BYTES = 4 * 1024 * 1024;
  static final String READONLY_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";
  static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(20000)
          .maxDocumentLength(65536).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  record Request(String method, URI uri, Map<String,String> headers, byte[] body) {
    @Override public String toString() { return "Readonly transport request (redacted)"; }
  }
  record Response(int status, URI uri, String type, String encoding, long length, InputStream body) {}
  interface Wire { Response send(Request request) throws Exception; }
  private final URI workbookUri;
  private final Supplier<String> credentials;
  private final Wire wire;
  private final Clock clock;
  private final AtomicBoolean active = new AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicReference<InputStream> openBody = new java.util.concurrent.atomic.AtomicReference<>();
  SyntheticCleanupSheetsReader(String sheetId, Supplier<String> credentials) {
    this(sheetId, credentials, new HttpWire(), Clock.systemUTC());
  }
  SyntheticCleanupSheetsReader(String sheetId, Supplier<String> credentials, Wire wire, Clock clock) {
    SyntheticCleanupWorkbook.requireApprovedTarget(sheetId);
    this.workbookUri = URI.create("https://sheets.googleapis.com/v4/spreadsheets/" + sheetId
        + "?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED");
    this.credentials = Objects.requireNonNull(credentials); this.wire = Objects.requireNonNull(wire); this.clock = Objects.requireNonNull(clock);
  }
  @Override public byte[] read() { return read(20000); }
  byte[] read(long timeoutMillis) {
    if (timeoutMillis < 1 || timeoutMillis > 20000 || Thread.currentThread().isInterrupted()
        || !active.compareAndSet(false, true)) throw unavailable();
    AtomicBoolean cancelled = new AtomicBoolean();
    FutureTask<byte[]> work = new FutureTask<>(() -> { try { return readOnce(cancelled); } finally { active.set(false); } });
    Thread thread = new Thread(work, "synthetic-readonly-preflight"); thread.setDaemon(true); thread.start();
    try { return work.get(timeoutMillis, TimeUnit.MILLISECONDS); }
    catch (InterruptedException stopped) { cancelled.set(true); work.cancel(true); abortBody(); Thread.currentThread().interrupt(); throw unavailable(); }
    catch (Exception invalid) { cancelled.set(true); work.cancel(true); abortBody(); throw unavailable(); }
  }
  private byte[] readOnce(AtomicBoolean cancelled) {
    byte[] signing = null, grant = null, keyBytes = null, tokenBytes = null;
    try {
      String supplied = credentials.get(); // Deliberately AFTER authenticated explicit trigger, never at startup.
      if (cancelled.get() || supplied == null || supplied.length() > 20000) throw unavailable();
      JsonNode key = JSON.readTree(supplied);
      String email = key.path("client_email").asText();
      if (!"service_account".equals(key.path("type").asText())
          || !email.matches("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\\.gserviceaccount\\.com")) throw unavailable();
      String pem = key.path("private_key").asText();
      if (!pem.startsWith("-----BEGIN PRIVATE KEY-----") || !pem.trim().endsWith("-----END PRIVATE KEY-----")) throw unavailable();
      keyBytes = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
      PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
      if (!(privateKey instanceof RSAKey rsa) || rsa.getModulus().bitLength() < 2048) throw unavailable();
      long now = clock.instant().getEpochSecond();
      String unsigned = b64(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT"))) + "."
          + b64(JSON.writeValueAsBytes(Map.of("iss", email, "scope", READONLY_SCOPE, "aud", TOKEN_URI.toString(), "iat", now, "exp", now + 300)));
      signing = unsigned.getBytes(StandardCharsets.US_ASCII);
      Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(privateKey); signer.update(signing);
      String assertion = unsigned + "." + b64(signer.sign());
      grant = ("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + assertion).getBytes(StandardCharsets.US_ASCII);
      tokenBytes = receive(new Request("POST", TOKEN_URI, Map.of("Content-Type", "application/x-www-form-urlencoded",
          "Accept", "application/json", "Accept-Encoding", "identity"), grant), 65536, cancelled);
      JsonNode token = JSON.readTree(tokenBytes);
      String bearer = token.path("access_token").asText();
      if (!"Bearer".equalsIgnoreCase(token.path("token_type").asText()) || !bearer.matches("[A-Za-z0-9._~-]{1,4096}")) throw unavailable();
      if (cancelled.get() || Thread.currentThread().isInterrupted()) throw unavailable();
      return receive(new Request("GET", workbookUri, Map.of("Authorization", "Bearer " + bearer,
          "Accept", "application/json", "Accept-Encoding", "identity"), new byte[0]), MAX_BYTES, cancelled);
    } catch (Exception | LinkageError invalid) { throw unavailable(); }
    finally { for (byte[] bytes : new byte[][] {signing, grant, keyBytes, tokenBytes}) if (bytes != null) Arrays.fill(bytes, (byte)0); }
  }
  private byte[] receive(Request request, int limit, AtomicBoolean cancelled) throws Exception {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw unavailable();
    Response response = wire.send(request);
    if (response == null) throw unavailable();
    openBody.set(response.body());
    try (InputStream body = response.body()) {
      if (cancelled.get() || Thread.currentThread().isInterrupted() || body == null || response.status() != 200 || !request.uri().equals(response.uri())
          || response.type() == null || !response.type().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
          || response.encoding() == null || !Set.of("", "identity").contains(response.encoding())
          || response.length() < -1 || response.length() > limit) throw unavailable();
      byte[] bytes = body.readNBytes(limit + 1);
      if (bytes.length > limit || response.length() >= 0 && response.length() != bytes.length
          || cancelled.get() || Thread.currentThread().isInterrupted()) { Arrays.fill(bytes, (byte)0); throw unavailable(); }
      return bytes;
    } finally { openBody.compareAndSet(response.body(), null); }
  }
  private void abortBody() {
    InputStream body = openBody.get();
    if (body != null) {
      Thread closer = new Thread(() -> { try { body.close(); } catch (Exception ignored) {} }, "synthetic-response-close");
      closer.setDaemon(true); closer.start();
    }
  }
  private static final class HttpWire implements Wire {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
          public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
          public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
        }).build();
    @Override public Response send(Request request) throws Exception {
      if (!(request.method().equals("POST") && request.uri().equals(TOKEN_URI)
          || request.method().equals("GET") && request.uri().getHost().equals("sheets.googleapis.com"))) throw unavailable();
      var builder = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(10));
      request.headers().forEach(builder::header);
      if (request.method().equals("GET")) builder.GET(); else builder.POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
      var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      try {
        var headers = result.headers();
        for (String key : List.of("Content-Type", "Content-Encoding", "Content-Length", "Transfer-Encoding"))
          if (headers.allValues(key).size() > 1) throw unavailable();
        if (headers.firstValue("Content-Length").isPresent() && headers.firstValue("Transfer-Encoding").isPresent()) throw unavailable();
        return new Response(result.statusCode(), result.uri(), headers.firstValue("Content-Type").orElse(""),
            headers.firstValue("Content-Encoding").orElse(""), headers.firstValueAsLong("Content-Length").orElse(-1), result.body());
      } catch (RuntimeException invalid) { result.body().close(); throw unavailable(); }
    }
  }
  private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private static IllegalStateException unavailable() { return new IllegalStateException("Full read unavailable"); }
}
