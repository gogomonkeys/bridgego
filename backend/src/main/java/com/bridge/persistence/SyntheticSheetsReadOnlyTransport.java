package com.bridge.persistence;

import com.bridge.persistence.SyntheticSheetsErasurePreparation.Manifest;
import com.bridge.persistence.SyntheticSheetsReadOnlyAdapter.*;
import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;

/** Isolated, target-bound GET transport. No token acquisition implementation, credentials, or CLI. */
final class SyntheticSheetsReadOnlyTransport implements Transport, AutoCloseable {
  static final String READ_ONLY_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";
  interface TokenProvider {
    // Required scope is a request to the provider, not proof of the token's actual OAuth grant.
    Token acquire(Set<String> requiredScopes, Duration remaining) throws Exception;
  }
  static final class Token implements AutoCloseable {
    private char[] value;
    Token(char[] value) {
      if (value == null || value.length < 1 || value.length > 4096) throw failure();
      this.value = value.clone();
      for (char c : this.value) if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
          || "._~+/-=".indexOf(c) >= 0)) { close(); throw failure(); }
    }
    private synchronized String bearer() { if (value == null) throw failure(); return "Bearer " + new String(value); }
    @Override public synchronized void close() { if (value != null) Arrays.fill(value, '\0'); value = null; }
    @Override public String toString() { return "Token[REDACTED]"; }
  }
  private final URI endpoint;
  private final TokenProvider tokens;
  private final CloseableHttpClient http;
  private final AtomicBoolean active = new AtomicBoolean(), closed = new AtomicBoolean();

  SyntheticSheetsReadOnlyTransport(Manifest manifest, TokenProvider tokens) { this(manifest, tokens, newClient(manifest, tokens)); }
  /** Package-private client injection is for tests, not a production runtime switch. */
  SyntheticSheetsReadOnlyTransport(Manifest manifest, TokenProvider tokens, CloseableHttpClient http) {
    if (manifest == null || tokens == null || http == null || !manifest.sheetId().matches("[A-Za-z0-9_-]{10,160}")) throw failure();
    endpoint = URI.create("https://sheets.googleapis.com/v4/spreadsheets/" + manifest.sheetId()
        + "?includeGridData=true&commentsViewMode=" + SyntheticSheetsReadOnlyAdapter.COMMENTS);
    this.tokens = tokens; this.http = http;
  }
  private static CloseableHttpClient newClient(Manifest manifest, TokenProvider tokens) {
    if (manifest == null || tokens == null || !manifest.sheetId().matches("[A-Za-z0-9_-]{10,160}")) throw failure();
    requireSilentLogging(); // Apache header/wire loggers must be inert before any bearer token exists.
    return configure(HttpClients.custom()).setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
        .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
            .setMaxHeaderCount(32).setMaxLineLength(8192).setMaxEmptyLineCount(1).build()).build())
        .setMaxConnTotal(1).setMaxConnPerRoute(1).setDefaultConnectionConfig(ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(5)).build()).build()).build();
  }
  static void requireSilentLogging() {
    if (!(LoggerFactory.getILoggerFactory() instanceof NOPLoggerFactory)) throw failure();
  }
  static HttpClientBuilder configure(HttpClientBuilder builder) {
    // No system proxy, cookie, credential provider, custom TLS trust, or application logger is installed.
    return builder.disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
        .disableAuthCaching().disableContentCompression().disableConnectionState().disableDefaultUserAgent()
        .setRoutePlanner((host, context) -> {
          if (!"https".equals(host.getSchemeName()) || !"sheets.googleapis.com".equals(host.getHostName())
              || host.getPort() != -1 && host.getPort() != 443) throw new HttpException("Read-only route rejected");
          return new HttpRoute(new HttpHost("https", "sheets.googleapis.com", 443), null, true);
        });
  }
  @Override public ReadResponse execute(ReadRequest request) {
    validate(request); // destination and method validation precedes token provider invocation
    if (closed.get() || Thread.currentThread().isInterrupted() || !active.compareAndSet(false, true)) throw failure();
    long deadline = System.nanoTime() + request.timeout().toNanos();
    AtomicBoolean stopped = new AtomicBoolean(); AtomicReference<HttpGet> current = new AtomicReference<>();
    FutureTask<ReadResponse> work = new FutureTask<>(() -> {
      try { return read(request, deadline, stopped, current); }
      finally { active.set(false); }
    });
    Thread worker = new Thread(work, "synthetic-read-only-http"); worker.setDaemon(true); worker.start();
    try {
      ReadResponse result = work.get(remaining(deadline, stopped).toNanos(), TimeUnit.NANOSECONDS);
      remaining(deadline, stopped); return result;
    }
    catch (InterruptedException interrupted) { stop(stopped, current, work); Thread.currentThread().interrupt(); throw failure(); }
    catch (Exception rejected) { stop(stopped, current, work); throw failure(); }
  }
  private void validate(ReadRequest request) {
    if (request == null || !"GET".equals(request.method()) || !endpoint.equals(request.uri()) || request.followRedirects()
        || !Map.of("Accept", "application/json", "Accept-Encoding", "identity").equals(request.headers())
        || request.maxResponseBytes() != SyntheticSheetsReadOnlyAdapter.MAX_RESPONSE_BYTES || request.timeout() == null
        || request.timeout().isZero() || request.timeout().isNegative() || request.timeout().compareTo(Duration.ofSeconds(5)) > 0) throw failure();
  }
  @SuppressWarnings("deprecation") // Raw response allows immediate close without automatic entity draining.
  private ReadResponse read(ReadRequest spec, long deadline, AtomicBoolean stopped, AtomicReference<HttpGet> current) {
    HttpGet request = null; CloseableHttpResponse response = null;
    try (Token token = tokens.acquire(Set.of(READ_ONLY_SCOPE), remaining(deadline, stopped))) {
      remaining(deadline, stopped);
      if (token == null || closed.get()) throw failure();
      request = new HttpGet(endpoint); current.set(request);
      Timeout left = Timeout.ofMilliseconds(Math.max(1, remaining(deadline, stopped).toMillis()));
      request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).setAuthenticationEnabled(false)
          .setContentCompressionEnabled(false).setHardCancellationEnabled(true).setProtocolUpgradeEnabled(false)
          .setConnectionRequestTimeout(left).setResponseTimeout(left).build());
      request.setHeader("Accept", "application/json"); request.setHeader("Accept-Encoding", "identity");
      request.setHeader("Authorization", token.bearer());
      remaining(deadline, stopped);
      if (closed.get()) throw failure();
      response = http.execute(request);
      remaining(deadline, stopped);
      if (response == null || response.getCode() != 200) throw failure();
      String type = oneHeader(response, "Content-Type", true), encoding = oneHeader(response, "Content-Encoding", false);
      String lengthText = oneHeader(response, "Content-Length", false);
      String transfer = oneHeader(response, "Transfer-Encoding", false);
      if (!type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?") || !Set.of("", "identity").contains(encoding)) throw failure();
      if (!Set.of("", "chunked").contains(transfer) || !transfer.isEmpty() && !lengthText.isEmpty()) throw failure();
      long length = -1;
      if (!lengthText.isEmpty()) { if (!lengthText.matches("[0-9]{1,7}")) throw failure(); length = Long.parseLong(lengthText); }
      HttpEntity entity = response.getEntity();
      if (entity == null || length > spec.maxResponseBytes() || entity.getContentLength() > spec.maxResponseBytes()
          || length >= 0 && entity.getContentLength() >= 0 && length != entity.getContentLength()) throw failure();
      InputStream body = entity.getContent();
      if (body == null) throw failure();
      ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
      while (output.size() <= spec.maxResponseBytes()) {
        remaining(deadline, stopped);
        int count = body.read(buffer, 0, Math.min(buffer.length, spec.maxResponseBytes() + 1 - output.size()));
        remaining(deadline, stopped);
        if (count == -1) break;
        if (count == 0) throw failure();
        output.write(buffer, 0, count);
      }
      byte[] bytes = output.toByteArray();
      if (bytes.length > spec.maxResponseBytes() || length >= 0 && bytes.length != length
          || entity.getContentLength() >= 0 && bytes.length != entity.getContentLength()) throw failure();
      return new ReadResponse(200, endpoint, type, encoding, bytes.length, new ByteArrayInputStream(bytes));
    } catch (Exception | LinkageError rejected) { throw failure(); }
    finally {
      if (request != null) { request.cancel(); request.removeHeaders("Authorization"); }
      if (response != null) response.close(CloseMode.IMMEDIATE);
    }
  }
  private static String oneHeader(ClassicHttpResponse response, String key, boolean required) {
    Header[] headers = response.getHeaders(key);
    if (headers.length > 1 || required && headers.length != 1) throw failure();
    return headers.length == 0 ? "" : headers[0].getValue();
  }
  private static Duration remaining(long deadline, AtomicBoolean stopped) {
    long left = deadline - System.nanoTime();
    if (left <= 0 || stopped.get() || Thread.currentThread().isInterrupted()) throw failure();
    return Duration.ofNanos(left);
  }
  private static void stop(AtomicBoolean stopped, AtomicReference<HttpGet> current, FutureTask<?> work) {
    stopped.set(true); HttpGet request = current.get(); if (request != null) request.cancel(); work.cancel(true);
  }
  private static ReadFailure failure() { return new ReadFailure(); }
  @Override public void close() {
    try { if (closed.compareAndSet(false, true)) http.close(CloseMode.IMMEDIATE); }
    catch (Exception | LinkageError rejected) { throw failure(); }
  }
  @Override public String toString() { return "SyntheticSheetsReadOnlyTransport[target=REDACTED]"; }
}
