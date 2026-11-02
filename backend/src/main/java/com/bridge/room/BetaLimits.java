package com.bridge.room;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, configurable single-instance beta limits; not a scalability guarantee. */
@Component
public record BetaLimits(
    @Value("${bridge.beta.max-rooms:2}") int maxRooms,
    @Value("${bridge.beta.max-sessions:128}") int maxSessions,
    @Value("${bridge.beta.session-idle-ms:3600000}") long sessionIdleMs,
    @Value("${bridge.beta.finished-room-idle-ms:300000}") long finishedRoomIdleMs,
    @Value("${bridge.beta.max-sockets:64}") int maxSockets,
    @Value("${bridge.beta.max-sockets-per-session:4}") int maxSocketsPerSession,
    @Value("${bridge.beta.http-requests-per-10s:600}") int httpRequestsPerWindow,
    @Value("${bridge.beta.sessions-per-minute:60}") int sessionsPerMinute,
    @Value("${bridge.beta.sessions-per-peer-minute:20}") int sessionsPerPeerMinute,
    @Value("${bridge.beta.socket-messages-per-10s:30}") int socketMessagesPerWindow) {
  public BetaLimits {
    if (maxRooms < 1 || maxRooms > 20 || maxSessions < 8 || maxSessions > 10000
        || sessionIdleMs < 1000 || finishedRoomIdleMs < 1000
        || maxSockets < 8 || maxSockets > 1024
        || maxSocketsPerSession < 1 || maxSocketsPerSession > 16
        || httpRequestsPerWindow < 1 || sessionsPerMinute < 1
        || sessionsPerPeerMinute < 1 || socketMessagesPerWindow < 1)
      throw new IllegalArgumentException("Invalid beta resource limits");
  }

  static BetaLimits defaults() {
    return new BetaLimits(2, 128, 3_600_000, 300_000, 64, 4, 600, 60, 20, 30);
  }
}
