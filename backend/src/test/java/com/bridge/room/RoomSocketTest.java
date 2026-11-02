package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;

class RoomSocketTest {
  static class Socket implements WebSocketSession {
    final String id = UUID.randomUUID().toString();
    final List<WebSocketMessage<?>> sent = new ArrayList<>();
    volatile CloseStatus closed;

    public String getId() {
      return id;
    }

    public URI getUri() {
      return URI.create("ws://127.0.0.1/ws");
    }

    public HttpHeaders getHandshakeHeaders() {
      return new HttpHeaders();
    }

    public Map<String, Object> getAttributes() {
      return Map.of();
    }

    public Principal getPrincipal() {
      return null;
    }

    public InetSocketAddress getLocalAddress() {
      return null;
    }

    public InetSocketAddress getRemoteAddress() {
      return null;
    }

    public String getAcceptedProtocol() {
      return null;
    }

    public void setTextMessageSizeLimit(int n) {}

    public int getTextMessageSizeLimit() {
      return 4096;
    }

    public void setBinaryMessageSizeLimit(int n) {}

    public int getBinaryMessageSizeLimit() {
      return 4096;
    }

    public List<WebSocketExtension> getExtensions() {
      return List.of();
    }

    public void sendMessage(WebSocketMessage<?> m) {
      sent.add(m);
    }

    public boolean isOpen() {
      return closed == null;
    }

    public void close() {
      close(CloseStatus.NORMAL);
    }

    public void close(CloseStatus status) {
      closed = status;
    }

    PingMessage lastPing() {
      return (PingMessage)
          sent.stream().filter(m -> m instanceof PingMessage).reduce((a, b) -> b).orElseThrow();
    }
  }

  final RoomService service = new RoomService();
  final ObjectMapper json = new ObjectMapper();
  final AtomicLong nanos = new AtomicLong();
  final RoomSocket handler = new RoomSocket(service, json, nanos::get);
  final RoomService.Identity player = service.createSession("Sock");
  final String room = service.create(player).roomId();

  Socket subscribe() throws Exception {
    Socket socket = new Socket();
    handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(
        socket,
        new TextMessage(
            json.writeValueAsString(
                Map.of("type", "subscribe", "token", player.token(), "roomId", room))));
    return socket;
  }

  void advance(long milliseconds) {
    nanos.addAndGet(milliseconds * 1_000_000);
    handler.heartbeat();
  }

  void regular(long milliseconds) {
    while (milliseconds > 0) {
      long step = Math.min(250, milliseconds);
      advance(step);
      milliseconds -= step;
    }
  }

  @Test
  void controlPongsKeepIdleApplicationAliveForThreeMinutes() throws Exception {
    Socket socket = subscribe();
    for (int i = 0; i < 90; i++) {
      advance(2000);
      handler.handlePongMessage(
          socket, new PongMessage(socket.lastPing().getPayload().duplicate()));
      assertNull(socket.closed);
    }
    assertEquals(room, service.me(player).roomId());
  }

  @Test
  void missingControlPongReleasesImmediatelyAtDetection() throws Exception {
    Socket socket = subscribe();
    advance(2000);
    regular(5999);
    assertNull(socket.closed);
    assertEquals(room, service.me(player).roomId());
    advance(1);
    assertEquals(4001, socket.closed.getCode());
    assertEquals("HEARTBEAT_TIMEOUT", socket.closed.getReason());
    assertNull(service.me(player).roomId());
  }

  @Test
  void wrongPongAndLegacyApplicationPingCannotMaskDeadControlTransport() throws Exception {
    Socket socket = subscribe();
    advance(2000);
    handler.handlePongMessage(socket, new PongMessage(ByteBuffer.wrap(new byte[] {1, 2, 3})));
    handler.handleTextMessage(socket, new TextMessage("{\"type\":\"ping\"}"));
    regular(6000);
    assertEquals(4001, socket.closed.getCode());
  }

  @Test
  void oneUnresponsiveTabDoesNotRemoveAnotherLiveTab() throws Exception {
    Socket dead = subscribe(), live = subscribe();
    advance(2000);
    handler.handlePongMessage(live, new PongMessage(live.lastPing().getPayload().duplicate()));
    regular(6000);
    assertEquals(4001, dead.closed.getCode());
    assertNull(live.closed);
    assertEquals(room, service.me(player).roomId());
  }

  @Test
  void realSocketCloseReleasesWithoutAnyGrace() throws Exception {
    Socket socket = subscribe();
    handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
    assertNull(service.me(player).roomId());
  }

  @Test
  void unauthenticatedSocketHasDistinctTimeoutReason() {
    Socket socket = new Socket();
    handler.afterConnectionEstablished(socket);
    advance(5000);
    assertEquals(4002, socket.closed.getCode());
    assertEquals("SUBSCRIBE_TIMEOUT", socket.closed.getReason());
  }

  @Test
  void delayedPingSendMustGetFullResponseBudgetAfterSendCompletes() throws Exception {
    Socket socket =
        new Socket() {
          @Override
          public void sendMessage(WebSocketMessage<?> message) {
            if (message instanceof PingMessage) nanos.addAndGet(7_000_000_000L);
            super.sendMessage(message);
          }
        };
    handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(
        socket,
        new TextMessage(
            json.writeValueAsString(
                Map.of("type", "subscribe", "token", player.token(), "roomId", room))));
    advance(2000);
    handler.heartbeat();
    assertNull(socket.closed, "Healthy peer was allowed zero response time after delayed send");
    handler.handlePongMessage(socket, new PongMessage(socket.lastPing().getPayload().duplicate()));
    assertEquals(room, service.me(player).roomId());
  }

  @Test
  void pongProcessingDoesNotWaitForGlobalRoomLock() throws Exception {
    Socket socket = subscribe();
    advance(2000);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      synchronized (service) {
        pool.submit(
                () ->
                    handler.handlePongMessage(
                        socket, new PongMessage(socket.lastPing().getPayload().duplicate())))
            .get(1, TimeUnit.SECONDS);
      }
      advance(6000);
      assertNull(socket.closed);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void pongReceivedDuringSendIsNotOverwrittenWhenSendReturns() throws Exception {
    Socket socket =
        new Socket() {
          @Override
          public void sendMessage(WebSocketMessage<?> message) {
            super.sendMessage(message);
            if (message instanceof PingMessage ping) {
              handler.handlePongMessage(this, new PongMessage(ping.getPayload().duplicate()));
              nanos.addAndGet(7_000_000_000L);
            }
          }
        };
    handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(
        socket,
        new TextMessage(
            json.writeValueAsString(
                Map.of("type", "subscribe", "token", player.token(), "roomId", room))));
    advance(2000);
    handler.heartbeat();
    assertNull(socket.closed);
    assertEquals(room, service.me(player).roomId());
  }

  @Test
  void schedulerPauseAllowsOnlyOneFreshVerificationWithoutPong() throws Exception {
    Socket socket = subscribe();
    advance(2000);
    byte[] first = socket.lastPing().getPayload().array().clone();
    advance(7000);
    assertNull(socket.closed);
    assertFalse(Arrays.equals(first, socket.lastPing().getPayload().array()));
    // A second long local pause cannot perpetually preserve an actually unresponsive peer.
    advance(7000);
    assertEquals(4001, socket.closed.getCode());
    assertNull(service.me(player).roomId());
  }

  @Test
  void freshVerificationPongSurvivesSchedulerPause() throws Exception {
    Socket socket = subscribe();
    advance(2000);
    advance(7000);
    handler.handlePongMessage(socket, new PongMessage(socket.lastPing().getPayload().duplicate()));
    advance(6000);
    assertNull(socket.closed);
    assertEquals(room, service.me(player).roomId());
  }

  @Test
  void queuedOldSnapshotsAreDiscardedOnLeaveAndFreshJoin() throws Exception {
    Queue<Runnable> tasks = new ArrayDeque<>();
    RoomSocket asynchronous = new RoomSocket(service, json, nanos::get, tasks::add, false);
    var other = service.createSession("Other");
    service.join(room, other);
    service.connect(room, other, "other-connection");
    Socket old = new Socket();
    asynchronous.afterConnectionEstablished(old);
    String subscribe =
        json.writeValueAsString(
            Map.of("type", "subscribe", "token", player.token(), "roomId", room));
    asynchronous.handleTextMessage(old, new TextMessage(subscribe));
    service.ready(room, player, true);
    service.leave(room, player);
    service.join(room, player);
    Socket fresh = new Socket();
    asynchronous.afterConnectionEstablished(fresh);
    asynchronous.handleTextMessage(fresh, new TextMessage(subscribe));
    while (!tasks.isEmpty()) tasks.remove().run();
    assertTrue(old.sent.isEmpty(), "Revoked connection must not receive queued snapshots");
    assertEquals(4003, old.closed.getCode());
    long previous = -1;
    for (var message : fresh.sent) {
      if (message instanceof TextMessage text) {
        var snapshot = json.readTree(text.getPayload()).path("room");
        long revision = snapshot.path("revision").asLong();
        assertTrue(revision >= previous);
        previous = revision;
        var me = snapshot.path("players").findValuesAsText("playerId");
        assertTrue(me.contains(player.playerId()));
      }
    }
    asynchronous.afterConnectionClosed(old, CloseStatus.NORMAL);
    assertTrue(
        service.get(room, player).players().stream()
            .anyMatch(p -> p.playerId().equals(player.playerId()) && p.connected() && !p.ready()));
  }

  @Test
  void writerThatNeverRunsCannotReserveMembershipForever() throws Exception {
    Queue<Runnable> tasks = new ArrayDeque<>();
    RoomSocket stalled = new RoomSocket(service, json, nanos::get, tasks::add, false);
    Socket socket = new Socket();
    stalled.afterConnectionEstablished(socket);
    stalled.handleTextMessage(
        socket,
        new TextMessage(
            json.writeValueAsString(
                Map.of("type", "subscribe", "token", player.token(), "roomId", room))));
    nanos.set(RoomSocket.WRITE_TIMEOUT_NANOS - 1);
    stalled.heartbeat();
    assertEquals(room, service.me(player).roomId());
    nanos.incrementAndGet();
    stalled.heartbeat();
    assertNull(service.me(player).roomId());
    while (!tasks.isEmpty()) tasks.remove().run();
    assertEquals("OUTPUT_WRITE_TIMEOUT", socket.closed.getReason());
    assertTrue(socket.sent.isEmpty());
  }

  @Test
  void rejectedExecutorDoesNotStrandSeatOrLeakRequestContent() throws Exception {
    RoomSocket rejected =
        new RoomSocket(
            service,
            json,
            nanos::get,
            task -> {
              throw new RejectedExecutionException("synthetic private exception text");
            },
            false);
    Socket socket = new Socket();
    rejected.afterConnectionEstablished(socket);
    rejected.handleTextMessage(
        socket,
        new TextMessage(
            json.writeValueAsString(
                Map.of("type", "subscribe", "token", player.token(), "roomId", room))));
    assertNull(service.me(player).roomId());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (socket.closed == null && System.nanoTime() < deadline) Thread.yield();
    assertNotNull(socket.closed);
    assertEquals("OUTPUT_EXECUTOR_REJECTED", socket.closed.getReason());
  }

  @Test
  void actuallyBlockedPingWriterIsBoundedWithoutBlockingDomainOrScheduler() throws Exception {
    CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService io = Executors.newCachedThreadPool();
    RoomSocket asynchronous = new RoomSocket(service, json, nanos::get, io, false);
    Socket socket =
        new Socket() {
          @Override
          public void sendMessage(WebSocketMessage<?> message) {
            super.sendMessage(message);
            if (message instanceof PingMessage) {
              sending.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          }
        };
    try {
      asynchronous.afterConnectionEstablished(socket);
      asynchronous.handleTextMessage(
          socket,
          new TextMessage(
              json.writeValueAsString(
                  Map.of("type", "subscribe", "token", player.token(), "roomId", room))));
      nanos.set(2_000_000_000L);
      asynchronous.heartbeat();
      assertTrue(sending.await(1, TimeUnit.SECONDS));
      assertEquals(room, service.me(player).roomId());
      nanos.set(2_000_000_000L + RoomSocket.WRITE_TIMEOUT_NANOS);
      asynchronous.heartbeat();
      assertNull(service.me(player).roomId());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (socket.closed == null && System.nanoTime() < deadline) Thread.yield();
      assertEquals("OUTPUT_WRITE_TIMEOUT", socket.closed.getReason());
    } finally {
      release.countDown();
      io.shutdownNow();
    }
  }
}
