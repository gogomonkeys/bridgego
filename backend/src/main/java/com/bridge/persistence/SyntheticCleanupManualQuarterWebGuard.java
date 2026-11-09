package com.bridge.persistence;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Narrow same-origin bounded API. No CORS, session cookies or arbitrary URLs. */
final class SyntheticCleanupManualQuarterWebGuard extends OncePerRequestFilter {
  private static final Set<String> GET = Set.of("/", "/quarter.js", "/health/ready");
  private static final Set<String> POST = Set.of("/quarter/challenge", "/quarter/preview", "/quarter/confirm");
  private final SyntheticCleanupManualQuarterHost.Settings settings;
  private long window; private int count;
  SyntheticCleanupManualQuarterWebGuard(SyntheticCleanupManualQuarterHost.Settings settings) { this.settings = settings; }
  private synchronized boolean allowed() { long now = System.nanoTime(); if (now - window > 60_000_000_000L) { window = now; count = 0; } return ++count <= 60; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff"); response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("X-Frame-Options", "DENY");
    String path = request.getRequestURI();
    if (request.getQueryString() != null || !(GET.contains(path) || POST.contains(path))) { fail(response, 404, "NOT_FOUND"); return; }
    if (GET.contains(path)) {
      if (!Set.of("GET", "HEAD").contains(request.getMethod())) { fail(response, 405, "METHOD_NOT_ALLOWED"); return; }
      chain.doFilter(request, response); return;
    }
    if (!request.getMethod().equals("POST")) { fail(response, 405, "METHOD_NOT_ALLOWED"); return; }
    if (!allowed()) { fail(response, 429, "RATE_LIMITED"); return; }
    if (!settings.enabled() || !settings.origin().equals(request.getHeader("Origin"))
        || request.getHeaders("Origin").asIterator().hasNext() && Collections.list(request.getHeaders("Origin")).size() != 1) {
      fail(response, 403, "ORIGIN_NOT_ALLOWED"); return;
    }
    String type = request.getContentType();
    if (type == null || !type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
        || request.getHeader("Content-Encoding") != null) { fail(response, 415, "JSON_REQUIRED"); return; }
    if (request.getContentLengthLong() > 16384) { fail(response, 413, "REQUEST_TOO_LARGE"); return; }
    byte[] body = request.getInputStream().readNBytes(16385);
    if (body.length > 16384) { fail(response, 413, "REQUEST_TOO_LARGE"); return; }
    if (path.equals("/quarter/challenge") && !new String(body, StandardCharsets.UTF_8).equals("{}")) {
      fail(response, 400, "EMPTY_CHALLENGE_REQUIRED"); return;
    }
    HttpServletRequest bounded = new HttpServletRequestWrapper(request) {
      @Override public ServletInputStream getInputStream() {
        var bytes = new ByteArrayInputStream(body);
        return new ServletInputStream() {
          public int read() { return bytes.read(); } public boolean isFinished() { return bytes.available() == 0; }
          public boolean isReady() { return true; } public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
        };
      }
      @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    };
    try { chain.doFilter(bounded, response); } finally { Arrays.fill(body, (byte)0); }
  }
  private static void fail(HttpServletResponse response, int status, String code) throws IOException {
    response.setStatus(status); response.setContentType("application/json"); response.getWriter().write("{\"code\":\"" + code + "\"}");
  }
}
