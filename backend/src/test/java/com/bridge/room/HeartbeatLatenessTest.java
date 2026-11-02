package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.PongMessage;

/** Regression design for witnessed sub-six-second server scheduling stalls; no wall sleeps. */
class HeartbeatLatenessTest {
  final RoomSocketTest f = new RoomSocketTest();

  void regular(long ms) {
    while (ms > 0) {
      long step = Math.min(250, ms);
      f.advance(step);
      ms -= step;
    }
  }

  @Test
  void earlierServerStallStillCountsWhenExpiryTickIsOnTime() throws Exception {
    var socket = f.subscribe();
    f.advance(2000);
    byte[] original = socket.lastPing().getPayload().array().clone();
    f.advance(2600);
    regular(3400);
    assertNull(
        socket.closed, "Earlier local delay must not be forgotten at an on-time expiry tick");
    assertFalse(Arrays.equals(original, socket.lastPing().getPayload().array()));
    f.handler.handlePongMessage(
        socket, new PongMessage(socket.lastPing().getPayload().duplicate()));
    regular(1000);
    assertNull(socket.closed);
    assertEquals(f.room, f.service.me(f.player).roomId());
  }

  @Test
  void cumulativeSubThresholdStallsAllowOneFreshVerification() throws Exception {
    var socket = f.subscribe();
    f.advance(2000);
    byte[] original = socket.lastPing().getPayload().array().clone();
    for (int i = 0; i < 4; i++) f.advance(1500);
    assertNull(socket.closed, "Cumulative local lateness was misclassified as a dead peer");
    assertFalse(Arrays.equals(original, socket.lastPing().getPayload().array()));
    f.handler.handlePongMessage(
        socket, new PongMessage(socket.lastPing().getPayload().duplicate()));
    assertEquals(f.room, f.service.me(f.player).roomId());
  }

  @Test
  void observedGapsCannotRenewAnActuallyDeadPeerForever() throws Exception {
    var socket = f.subscribe();
    f.advance(2000);
    f.advance(2600);
    f.advance(5200);
    assertNull(socket.closed, "One local-delay verification expected");
    f.advance(2600);
    f.advance(5200);
    assertEquals(4001, socket.closed.getCode());
    assertNull(f.service.me(f.player).roomId());
  }

  @Test
  void queuedOldPongCannotSatisfyFreshVerification() throws Exception {
    var socket = f.subscribe();
    f.advance(2000);
    var old = socket.lastPing().getPayload().duplicate();
    f.advance(2600);
    regular(3400);
    assertNull(socket.closed);
    f.handler.handlePongMessage(socket, new PongMessage(old));
    regular(5999);
    assertNull(socket.closed);
    regular(1);
    assertEquals(4001, socket.closed.getCode());
  }

  @Test
  void delayBeforeProbeWasSentDoesNotCreateExtraAllowance() throws Exception {
    var socket = f.subscribe();
    f.advance(7000);
    regular(6000);
    assertEquals(4001, socket.closed.getCode());
  }

  @Test
  void smallSchedulerJitterDoesNotAutomaticallyExcuseDeadPeer() throws Exception {
    var socket = f.subscribe();
    f.advance(2000);
    for (int i = 0; i < 18; i++) f.advance(350);
    assertEquals(4001, socket.closed.getCode());
  }
}
