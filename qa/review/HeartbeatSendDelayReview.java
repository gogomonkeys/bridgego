package com.bridge.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.socket.*;

/** Deterministic review harness, no production edits or network. */
public class HeartbeatSendDelayReview {
  public static void main(String[] args) throws Exception {
    AtomicLong now = new AtomicLong();
    RoomService service = new RoomService();
    ObjectMapper json = new ObjectMapper();
    RoomSocket handler = new RoomSocket(service, json, now::get);
    CloseStatus[] closed = {null};
    long[] sendCompleted = {-1};
    WebSocketSession socket = (WebSocketSession) Proxy.newProxyInstance(
      WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
      (proxy, method, a) -> switch (method.getName()) {
        case "getId" -> "synthetic-delayed-send";
        case "isOpen" -> closed[0] == null;
        case "sendMessage" -> {
          if (a[0] instanceof PingMessage) {
            // Model a synchronous send blocked for 7 s before completing.
            now.addAndGet(7_000_000_000L);
            sendCompleted[0] = now.get();
          }
          yield null;
        }
        case "close" -> { closed[0] = (a == null || a.length == 0) ? CloseStatus.NORMAL : (CloseStatus)a[0]; yield null; }
        case "toString" -> "synthetic review socket";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == a[0];
        default -> null;
      });
    RoomService.Identity user = service.createSession("review synthetic");
    String room = service.create(user).roomId();
    handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(socket, new TextMessage(json.writeValueAsString(
      Map.of("type","subscribe","token",user.token(),"roomId",room))));
    now.set(2_000_000_000L);
    handler.heartbeat();
    if (closed[0] != null) throw new AssertionError("unexpected earlier close");
    handler.heartbeat();
    long afterSendMs = (now.get() - sendCompleted[0]) / 1_000_000;
    System.out.println("closeCode=" + (closed[0] == null ? "none" : closed[0].getCode()));
    System.out.println("elapsedAfterSendCompletedMs=" + afterSendMs);
    System.out.println("membershipRemoved=" + (service.me(user).roomId() == null));
    if (args.length > 0 && args[0].equals("fixed")) {
      if (closed[0] != null) throw new AssertionError("fixed handler closed immediately after send");
      // Keep normal scheduler cadence: this test isolates send delay, not the
      // separately tested bounded recovery from a six-second scheduler stall.
      for (int tick = 0; tick < 23; tick++) {
        now.addAndGet(250_000_000L);
        handler.heartbeat();
      }
      now.addAndGet(249_000_000L);
      handler.heartbeat();
      if (closed[0] != null) throw new AssertionError("fixed handler lost full response budget");
      now.addAndGet(1_000_000L);
      handler.heartbeat();
      if (closed[0] == null || closed[0].getCode() != 4001)
        throw new AssertionError("nonresponding peer not closed at full budget");
      System.out.println("fixedFullSixSecondBudget=PASS");
    } else if (closed[0] == null || closed[0].getCode() != 4001 || afterSendMs != 0) {
      throw new AssertionError("baseline schedule did not reproduce");
    }
  }
}
