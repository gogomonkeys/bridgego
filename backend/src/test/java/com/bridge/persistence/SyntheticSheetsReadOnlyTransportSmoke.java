package com.bridge.persistence;

import com.bridge.persistence.SyntheticSheetsReadOnlyTransport.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;

/** Test-only fake HTTP client. Never opens a socket or reads a credential. */
public final class SyntheticSheetsReadOnlyTransportSmoke {
  interface Exchange { CloseableHttpResponse execute(ClassicHttpRequest request) throws IOException; }
  static class FakeClient extends CloseableHttpClient {
    final AtomicInteger calls = new AtomicInteger(); final Exchange exchange;
    FakeClient(Exchange exchange) { this.exchange = exchange; }
    @Override protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request, HttpContext context) throws IOException {
      calls.incrementAndGet(); return exchange.execute(request);
    }
    @Override public void close() {}
    @Override public void close(CloseMode mode) {}
  }
  static CloseableHttpResponse response(int status, InputStream stream, long length) {
    var response = new BasicClassicHttpResponse(status);
    response.setHeader("Content-Type", "application/json");
    response.setEntity(new InputStreamEntity(stream, length, ContentType.APPLICATION_JSON));
    return CloseableHttpResponse.adapt(response);
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException();
    SyntheticSheetsReadOnlyTransport.requireSilentLogging();
    runFake();
  }
  static void runFake() throws Exception {
    var manifest = SyntheticSheetsReadOnlyAdapterSmoke.manifest();
    byte[] fixture = SyntheticSheetsReadOnlyAdapterSmoke.JSON.writeValueAsBytes(SyntheticSheetsReadOnlyAdapterSmoke.response(manifest, true));
    var client = new FakeClient(request -> {
      if (!request.getMethod().equals("GET") || request.getEntity() != null || request.countHeaders("Authorization") != 1)
        throw new AssertionError("Read-only request contract failed");
      return response(200, new ByteArrayInputStream(fixture), fixture.length);
    });
    try (var transport = new SyntheticSheetsReadOnlyTransport(manifest, (scopes, remaining) -> new Token("synthetic.fake.token".toCharArray()), client)) {
      var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, transport);
      if (!adapter.readComplete().equals(manifest.baseline()) || client.calls.get() != 1) throw new AssertionError("Transport smoke mismatch");
    }
    System.out.println("PACKAGED_READ_ONLY_TRANSPORT_OK: fake HTTP client and fake token only; no network.");
  }
}
