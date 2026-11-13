package com.bridge.room;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RoomSocket extends TextWebSocketHandler {
  static final long PROBE_INTERVAL_NANOS = 2_000_000_000L;
  static final long PONG_TIMEOUT_NANOS = 6_000_000_000L;
  static final long HEARTBEAT_CADENCE_MS = 250;
  static final long HEARTBEAT_CADENCE_NANOS = HEARTBEAT_CADENCE_MS * 1_000_000;
  static final long LOCAL_LATENESS_THRESHOLD_NANOS = PROBE_INTERVAL_NANOS;
  static final long WRITE_TIMEOUT_NANOS = 15_000_000_000L;
  private static final Logger log = LoggerFactory.getLogger(RoomSocket.class);

  private static class Client {
    final WebSocketSession socket;
    final long openedAt;
    final Queue<Runnable> writes = new ArrayDeque<>();
    final CompletableFuture<Void> transportClosed = new CompletableFuture<>();
    boolean writerRunning;
    long textWindowStarted;
    int textWindowCount;
    long lastSnapshotRevision = -1;
    volatile Long writeProgressSince;
    long lastProbe;
    Long probeSentAt;
    long probeObservedAt, probeScheduleLateness, probeMaxScheduleGap;
    byte[] pendingProbe;
    long pongCount, mismatchCount, lastRttNanos, lastSendNanos;
    boolean pauseReprobeUsed;
    volatile long schedulerGapNanos;
    volatile String roomId;
    volatile RoomService.Identity identity;
    volatile boolean closed;

    Client(WebSocketSession socket, long now) {
      this.socket = socket;
      this.openedAt = now;
      this.lastProbe = now;
      this.textWindowStarted = now;
    }
  }

  private final Map<String, Client> clients = new ConcurrentHashMap<>();
  private final RoomService service;
  private final ObjectMapper json;
  private final LongSupplier nanoTime;
  private final Executor io;
  private final ExecutorService ownedIo;
  private final AtomicLong probeSequence = new AtomicLong();
  private long lastHeartbeat;
  private final AtomicBoolean shutdownStarted = new AtomicBoolean();

  @Autowired
  public RoomSocket(RoomService service, ObjectMapper json) {
    this(
        service,
        json,
        System::nanoTime,
        new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            task -> {
              Thread thread = new Thread(task, "room-socket-io");
              thread.setDaemon(true);
              return thread;
            }, new ThreadPoolExecutor.AbortPolicy()),
        true);
  }

  RoomSocket(RoomService service, ObjectMapper json, LongSupplier nanoTime) {
    this(service, json, nanoTime, Runnable::run, false);
  }

  RoomSocket(
      RoomService service, ObjectMapper json, LongSupplier nanoTime, Executor io, boolean owned) {
    this.nanoTime = nanoTime;
    this.io = io;
    this.ownedIo = owned ? (ExecutorService) io : null;
    this.service = service;
    this.json = json;
    this.lastHeartbeat = nanoTime.getAsLong();
    service.publisher(this::publish);
  }

  @EventListener(ContextClosedEvent.class)
  public void contextClosing() { shutdown(); }

  @PreDestroy
  public void shutdown() {
    if (!shutdownStarted.compareAndSet(false, true)) return;
    // Domain freeze MUST precede transport close callbacks and scheduler sweeps.
    service.beginMaintenance();
    List<Client> closing = new ArrayList<>(clients.values());
    for (Client c : closing) {
      write(c, () -> {
        try {
          c.socket.sendMessage(new TextMessage(json.writeValueAsString(Map.of(
              "type", "maintenance", "instanceEpoch", service.instanceEpoch(),
              "reason", "SERVER_STOPPING"))));
        } catch (Exception ignored) { /* Best effort; close code is a second signal. */ }
        close(c, new CloseStatus(1012, "SERVER_STOPPING"));
      });
    }
    // ContextClosedEvent precedes Tomcat stop. Keep the executor accepting the close tasks
    // until transport close attempts complete; never wait while holding the domain lock.
    if (ownedIo != null) {
      try {
        CompletableFuture.allOf(closing.stream().map(c -> c.transportClosed)
            .toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException | TimeoutException ignored) {
        // Bounded best effort: a blocked peer cannot prevent process shutdown.
      } finally { ownedIo.shutdown(); }
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    session.setTextMessageSizeLimit(4096);
    session.setBinaryMessageSizeLimit(4096);
    synchronized (clients) {
      if (service.stopping() || clients.size() >= service.limits().maxSockets()) {
        try { session.close(new CloseStatus(service.stopping() ? 1012 : 1008,
            service.stopping() ? "SERVER_STOPPING" : "CONNECTION_LIMIT")); }
        catch (java.io.IOException ignored) { /* Admission already refused. */ }
        return;
      }
      clients.put(session.getId(), new Client(session, nanoTime.getAsLong()));
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    Client c = clients.get(socket.getId());
    if (c == null) return;
    boolean rateLimited;
    synchronized (c) {
      long now = nanoTime.getAsLong();
      if (now - c.textWindowStarted >= 10_000_000_000L) {
        c.textWindowStarted = now;
        c.textWindowCount = 0;
      }
      rateLimited = ++c.textWindowCount > service.limits().socketMessagesPerWindow();
    }
    if (rateLimited) {
      reject(c, "RATE_LIMITED", "Too many socket messages");
      return;
    }
    try {
      JsonNode node = json.readTree(message.getPayload());
      if (node == null || !node.isObject())
        throw RoomService.fail(400, "INVALID_MESSAGE", "Expected a JSON object");
      String type = node.path("type").asText();
      if (c.identity == null) {
        if (!type.equals("subscribe"))
          throw RoomService.fail(401, "UNAUTHORIZED", "Subscribe with your guest session first");
        synchronized (service) {
          if (c.closed) return;
          RoomService.Identity id = service.authenticate(node.path("token").asText());
          String roomId = node.path("roomId").asText();
          service.get(roomId, id);
          c.roomId = roomId;
          c.identity = id;
          sendSnapshot(c, service.connect(roomId, id, socket.getId()));
        }
      } else if (type.equals("ping")) {
        if (!service.hasConnection(c.roomId, c.identity.playerId(), socket.getId()))
          throw RoomService.fail(403, "NOT_MEMBER", "Room membership ended");
        send(c, Map.of("type", "pong"));
      } else
        throw RoomService.fail(
            400, "INVALID_MESSAGE", "Only subscribe once, then ping; use HTTP for commands");
    } catch (RoomService.Failure e) {
      log.info("room_socket_rejected connection={} failureCode={}", socket.getId(), e.code);
      reject(c, e.code, e.getMessage());
    } catch (JsonProcessingException e) {
      log.info("room_socket_rejected connection={} failureCode=INVALID_JSON", socket.getId());
      reject(c, "INVALID_MESSAGE", "Malformed socket message");
    } catch (Exception e) {
      failure(c, "MESSAGE_INTERNAL_ERROR", e);
    }
  }

  private void reject(Client c, String code, String message) {
    // Queue the bounded protocol error before closing; never log token or request content.
    write(
        c,
        () -> {
          try {
            c.socket.sendMessage(
                new TextMessage(
                    json.writeValueAsString(
                        Map.of("type", "error", "code", code, "message", message))));
          } catch (Exception e) {
            log.info(
                "room_socket_write_error connection={} exceptionClass={}",
                c.socket.getId(),
                e.getClass().getSimpleName());
          }
          close(c, new CloseStatus(1008, "INVALID_SUBSCRIPTION_OR_MESSAGE"));
        });
  }

  // Domain transitions enqueue immutable snapshots, never perform network I/O under the room lock.
  private record Delivery(Client client, RoomService.Snapshot room) {}

  private void publish(RoomService.Snapshot snapshot) {
    List<Delivery> deliveries = new ArrayList<>();
    List<Client> revoked = new ArrayList<>();
    // Capture every immutable viewer projection at this transition before any enqueue can
    // synchronously fail and trigger a nested disconnect transition.
    for (Client c : clients.values()) {
      if (!snapshot.roomId().equals(c.roomId) || c.identity == null || c.closed) continue;
      if (snapshot.players().stream().noneMatch(p -> p.playerId().equals(c.identity.playerId())))
        revoked.add(c);
      else {
        try {
          deliveries.add(new Delivery(c, service.viewFor(snapshot.roomId(), c.identity)));
        } catch (RoomService.Failure removed) {
          revoked.add(c);
        }
      }
    }
    for (Client c : revoked) close(c, new CloseStatus(4003, "MEMBERSHIP_ENDED"));
    for (Delivery delivery : deliveries) sendSnapshot(delivery.client(), delivery.room());
  }

  private void sendSnapshot(Client c, RoomService.Snapshot snapshot) {
    write(
        c,
        () -> {
          try {
            c.socket.sendMessage(
                new TextMessage(
                    json.writeValueAsString(Map.of("type", "snapshot", "room", snapshot))));
          } catch (Exception e) {
            failure(c, "SEND_FAILED", e);
          }
        },
        snapshot.revision());
  }

  private void send(Client c, Object value) {
    write(
        c,
        () -> {
          try {
            c.socket.sendMessage(new TextMessage(json.writeValueAsString(value)));
          } catch (Exception e) {
            failure(c, "SEND_FAILED", e);
          }
        });
  }

  // Each connection has ordered bounded output; a slow socket cannot stall other rooms or Pongs.
  private void write(Client c, Runnable task) {
    write(c, task, null);
  }

  private void write(Client c, Runnable task, Long snapshotRevision) {
    boolean start = false, overflow = false;
    synchronized (c.writes) {
      if (c.closed) return;
      if (snapshotRevision != null && snapshotRevision < c.lastSnapshotRevision) return;
      if (c.writes.size() >= 64) overflow = true;
      else {
        c.writes.add(task);
        if (snapshotRevision != null) c.lastSnapshotRevision = snapshotRevision;
        if (c.writeProgressSince == null) c.writeProgressSince = nanoTime.getAsLong();
        if (!c.writerRunning) {
          c.writerRunning = true;
          c.writeProgressSince = nanoTime.getAsLong();
          start = true;
        }
      }
    }
    if (overflow) {
      close(c, new CloseStatus(1011, "OUTPUT_BACKPRESSURE"));
      return;
    }
    if (start) {
      try {
        io.execute(() -> drain(c));
      } catch (RuntimeException error) {
        clearWriter(c);
        failure(c, "OUTPUT_EXECUTOR_REJECTED", error);
      }
    }
  }

  private void clearWriter(Client c) {
    synchronized (c.writes) {
      c.writes.clear();
      c.writerRunning = false;
      c.writeProgressSince = null;
    }
  }

  private void drain(Client c) {
    while (true) {
      Runnable next;
      synchronized (c.writes) {
        next = c.writes.poll();
        if (next == null || c.closed) {
          clearWriter(c);
          return;
        }
      }
      try {
        next.run();
      } catch (RuntimeException error) {
        clearWriter(c);
        failure(c, "OUTPUT_TASK_FAILED", error);
        return;
      }
      synchronized (c.writes) {
        // Completion proves writer progress; the next queued task gets its own bounded budget.
        c.writeProgressSince = c.writes.isEmpty() ? null : nanoTime.getAsLong();
      }
    }
  }

  private void failure(Client c, String cause, Exception error) {
    log.warn(
        "room_socket_failure connection={} cause={} exceptionClass={}",
        c.socket.getId(),
        cause,
        error.getClass().getSimpleName());
    close(c, new CloseStatus(1011, cause));
  }

  private void diagnostics(Client c, CloseStatus reason) {
    synchronized (c) {
      // The writer clears this volatile field under a different monitor: read it only once.
      Long writeSince = c.writeProgressSince;
      long age = c.probeSentAt == null ? -1 : (nanoTime.getAsLong() - c.probeSentAt) / 1_000_000;
      log.info(
          "room_socket_closed connection={} code={} cause={} probeAgeMs={} schedulerGapMs={}"
              + " pongCount={} mismatchCount={} lastRttMs={} lastSendMs={} writeAgeMs={}"
              + " probeLocalLateMs={} probeMaxGapMs={}",
          c.socket.getId(),
          reason.getCode(),
          reason.getReason(),
          age,
          c.schedulerGapNanos / 1_000_000,
          c.pongCount,
          c.mismatchCount,
          c.lastRttNanos / 1_000_000,
          c.lastSendNanos / 1_000_000,
          writeSince == null ? -1 : (nanoTime.getAsLong() - writeSince) / 1_000_000,
          c.probeScheduleLateness / 1_000_000,
          c.probeMaxScheduleGap / 1_000_000);
    }
  }

  private void close(Client c, CloseStatus reason) {
    synchronized (c) {
      // A Pong may have arrived after the scheduler observed an expired probe.
      if (reason.getCode() == 4001
          && (c.pendingProbe == null
              || c.probeSentAt == null
              || nanoTime.getAsLong() - c.probeSentAt < PONG_TIMEOUT_NANOS)) return;
      if (reason.getCode() == 4002 && c.identity != null) return;
      synchronized (c.writes) {
        if (reason.getReason().equals("OUTPUT_WRITE_TIMEOUT")
            && (c.writeProgressSince == null
                || nanoTime.getAsLong() - c.writeProgressSince < WRITE_TIMEOUT_NANOS)) return;
        if (!clients.remove(c.socket.getId(), c)) return;
        c.closed = true;
      }
    }
    diagnostics(c, reason);
    synchronized (service) {
      if (c.identity != null) service.disconnect(c.roomId, c.identity.playerId(), c.socket.getId());
    }
    Runnable closeSocket =
        () -> {
          try {
            c.socket.close(reason);
          } catch (Exception ignored) {
          } finally { c.transportClosed.complete(null); }
        };
    try {
      io.execute(closeSocket);
    } catch (RuntimeException rejected) {
      // Shutdown/rejection must not strand membership or block the domain/scheduler thread.
      log.warn(
          "room_socket_close_fallback connection={} exceptionClass={}",
          c.socket.getId(),
          rejected.getClass().getSimpleName());
      Thread fallback = new Thread(closeSocket, "room-socket-close");
      fallback.setDaemon(true);
      fallback.start();
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    Client c = clients.remove(socket.getId());
    if (c == null) return;
    c.closed = true;
    c.transportClosed.complete(null);
    diagnostics(c, new CloseStatus(status.getCode(), "PEER_CLOSED"));
    synchronized (service) {
      if (c.identity != null) service.disconnect(c.roomId, c.identity.playerId(), socket.getId());
    }
  }

  @Override
  public void handleTransportError(WebSocketSession socket, Throwable error) {
    Client c = clients.get(socket.getId());
    if (c != null) {
      log.warn(
          "room_socket_failure connection={} cause=TRANSPORT_ERROR exceptionClass={}",
          socket.getId(),
          error.getClass().getSimpleName());
      close(c, new CloseStatus(1011, "TRANSPORT_ERROR"));
    }
  }

  // Pong handling intentionally never acquires the domain lock or the outbound socket monitor.
  @Override
  protected void handlePongMessage(WebSocketSession socket, PongMessage message) {
    Client c = clients.get(socket.getId());
    if (c == null) return;
    ByteBuffer payload = message.getPayload().duplicate();
    byte[] received = new byte[payload.remaining()];
    payload.get(received);
    synchronized (c) {
      if (c.closed) return;
      if (c.pendingProbe != null && Arrays.equals(received, c.pendingProbe)) {
        if (c.probeSentAt != null) c.lastRttNanos = nanoTime.getAsLong() - c.probeSentAt;
        c.pongCount++;
        c.pendingProbe = null;
        c.probeSentAt = null;
        c.pauseReprobeUsed = false;
      } else c.mismatchCount++;
    }
  }

  @Scheduled(fixedDelay = HEARTBEAT_CADENCE_MS)
  public synchronized void heartbeat() {
    if (service.stopping()) return;
    long tick = nanoTime.getAsLong();
    long gap = tick - lastHeartbeat;
    lastHeartbeat = tick;
    for (Client c : clients.values()) {
      byte[] probe = null;
      CloseStatus timeout = null;
      Long progress = c.writeProgressSince;
      if (progress != null && nanoTime.getAsLong() - progress >= WRITE_TIMEOUT_NANOS) {
        close(c, new CloseStatus(1011, "OUTPUT_WRITE_TIMEOUT"));
        continue;
      }
      synchronized (c) {
        long now = nanoTime.getAsLong();
        c.schedulerGapNanos = gap;
        if (c.closed) continue;
        if (c.identity == null) {
          if (now - c.openedAt >= 5_000_000_000L)
            timeout = new CloseStatus(4002, "SUBSCRIBE_TIMEOUT");
        } else if (c.pendingProbe != null) {
          if (c.probeSentAt != null) {
            // Record only local scheduling time after this Ping actually finished writing.
            // An earlier or cumulative sub-timeout stall must not disappear at an on-time tick.
            long observedGap = Math.max(0, now - c.probeObservedAt);
            c.probeObservedAt = now;
            c.probeMaxScheduleGap = Math.max(c.probeMaxScheduleGap, observedGap);
            c.probeScheduleLateness += Math.max(0, observedGap - HEARTBEAT_CADENCE_NANOS);
          }
          if (c.probeSentAt != null && now - c.probeSentAt >= PONG_TIMEOUT_NANOS) {
            // After an evidenced local execution pause, one fresh probe distinguishes stale
            // queued callbacks from a dead peer. It cannot reset forever without a valid Pong.
            if (c.probeScheduleLateness >= LOCAL_LATENESS_THRESHOLD_NANOS && !c.pauseReprobeUsed) {
              c.pauseReprobeUsed = true;
              c.pendingProbe = null;
              c.probeSentAt = null;
              log.info(
                  "room_socket_reprobe connection={} cause=LOCAL_SCHEDULER_LATENESS"
                      + " schedulerGapMs={} probeLocalLateMs={} probeMaxGapMs={}",
                  c.socket.getId(),
                  gap / 1_000_000,
                  c.probeScheduleLateness / 1_000_000,
                  c.probeMaxScheduleGap / 1_000_000);
            } else timeout = new CloseStatus(4001, "HEARTBEAT_TIMEOUT");
          }
        }
        if (c.identity != null
            && c.pendingProbe == null
            && timeout == null
            && now - c.lastProbe >= PROBE_INTERVAL_NANOS) {
          probe = ByteBuffer.allocate(Long.BYTES).putLong(probeSequence.incrementAndGet()).array();
          c.pendingProbe = probe;
          c.probeSentAt = null; // No response deadline until the actual send completes.
          c.lastProbe = now;
        }
      }
      if (timeout != null) close(c, timeout);
      if (probe != null) sendProbe(c, probe);
    }
  }

  private void sendProbe(Client c, byte[] probe) {
    write(
        c,
        () -> {
          synchronized (c) {
            if (c.pendingProbe != probe) return;
          }
          long started = nanoTime.getAsLong();
          try {
            c.socket.sendMessage(new PingMessage(ByteBuffer.wrap(probe)));
            synchronized (c) {
              c.lastSendNanos = nanoTime.getAsLong() - started;
              // A fast Pong may already have cleared this probe while sendMessage was finishing.
              if (c.pendingProbe == probe) {
                c.probeSentAt = nanoTime.getAsLong();
                c.probeObservedAt = c.probeSentAt;
                c.probeScheduleLateness = 0;
                c.probeMaxScheduleGap = 0;
              }
            }
          } catch (Exception e) {
            failure(c, "PING_SEND_FAILED", e);
          }
        });
  }
}
