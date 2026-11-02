package com.bridge.room;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Small bounded resource guard. Peer address is the container's, never untrusted X-Forwarded-For. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BetaRequestFilter extends OncePerRequestFilter {
  private static final int MAX_PEERS = 256;
  private final RoomService service;
  private final BetaLimits limits;
  private final LongSupplier ticker;
  private final Map<String, Window> peers = new HashMap<>();
  private Window requests;
  private Window sessions;

  private static class Window {
    final long start;
    int count;
    Window(long start) { this.start = start; }
  }

  @org.springframework.beans.factory.annotation.Autowired
  public BetaRequestFilter(RoomService service) {
    this(service, () -> System.nanoTime() / 1_000_000);
  }

  BetaRequestFilter(RoomService service, LongSupplier ticker) {
    this.service = service;
    this.limits = service.limits();
    this.ticker = ticker;
  }

  private synchronized boolean allowed(boolean createSession, String peer) {
    long now = ticker.getAsLong();
    if (requests == null || now - requests.start >= 10_000) requests = new Window(now);
    if (++requests.count > limits.httpRequestsPerWindow()) return false;
    if (!createSession) return true;
    if (sessions == null || now - sessions.start >= 60_000) sessions = new Window(now);
    if (++sessions.count > limits.sessionsPerMinute()) return false;
    peers.entrySet().removeIf(entry -> now - entry.getValue().start >= 60_000);
    Window window = peers.get(peer);
    if (window == null) {
      if (peers.size() >= MAX_PEERS) return false;
      window = new Window(now);
      peers.put(peer, window);
    }
    return ++window.count <= limits.sessionsPerPeerMinute();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!(path.startsWith("/api/") || path.startsWith("/health/") || path.equals("/ws"))) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Bridge-Instance-Epoch", service.instanceEpoch());
    boolean mutation = !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
    if (service.stopping() && (mutation || path.equals("/ws"))) {
      failure(response, 503, "SERVER_MAINTENANCE", "Server is stopping; reconnect later");
      return;
    }
    // Health remains available under API load and contains no state or credentials.
    if (!path.startsWith("/health/")
        && !allowed(path.equals("/api/sessions") && request.getMethod().equals("POST"),
            request.getRemoteAddr())) {
      response.setHeader("Retry-After", "60");
      failure(response, 429, "RATE_LIMITED", "Too many requests; try again later");
      return;
    }
    if (request.getContentLengthLong() > 16_384) {
      failure(response, 413, "REQUEST_TOO_LARGE", "Request body is too large");
      return;
    }
    chain.doFilter(new BoundedBodyRequest(request, 16_384), response);
  }

  private void failure(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }
}
