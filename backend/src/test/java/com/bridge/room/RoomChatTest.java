package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.socket.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

class RoomChatTest {
  final RoomService service = new RoomService();
  final ObjectMapper json = new ObjectMapper();
  final AtomicLong clock = new AtomicLong();
  final RoomSocket handler = new RoomSocket(service, json, clock::get);
  final RoomService.Identity a = service.createSession("A");
  final String room = service.create(a).roomId();

  RoomSocketTest.Socket connect(RoomService.Identity who, String roomId) throws Exception {
    var socket = new RoomSocketTest.Socket();
    handler.afterConnectionEstablished(socket);
    send(socket, Map.of("type", "subscribe", "roomId", roomId, "token", who.token()));
    return socket;
  }
  void send(RoomSocketTest.Socket s, Object value) throws Exception {
    handler.handleTextMessage(s, new TextMessage(json.writeValueAsString(value)));
  }
  JsonNode last(RoomSocketTest.Socket s, String type) throws Exception {
    JsonNode found = null;
    for (var msg : s.sent) if (msg instanceof TextMessage text) {
      var node = json.readTree(text.getPayload());
      if (node.path("type").asText().equals(type)) found = node;
    }
    return found;
  }
  String join(RoomSocketTest.Socket s) throws Exception {
    send(s, Map.of("type","voice-join","requestId",UUID.randomUUID().toString()));
    return last(s,"voice-state").path("selfId").asText();
  }

  @Test void chatBroadcastsOnlySameRoomWithoutVoiceOptInAndUsesServerIdentity() throws Exception {
    var sa=connect(a,room);
    var b=service.createSession("B");service.join(room,b);var sb=connect(b,room);
    var c=service.createSession("C");var sc=connect(c,service.create(c).roomId());
    send(sa,Map.of("type","room-chat","roomId",room,"text","  <b>Hello</b>  ","name","Imposter","playerId","other"));
    var msg=last(sb,"room-chat"); assertEquals("A",msg.path("name").asText());
    assertEquals(a.playerId(),msg.path("playerId").asText()); assertEquals(room,msg.path("roomId").asText());
    assertEquals("<b>Hello</b>",msg.path("text").asText());
    assertFalse(msg.path("messageId").asText().isBlank());
    assertEquals(msg,last(sa,"room-chat"));assertNull(last(sc,"room-chat"));
  }
  @Test void unauthenticatedCrossRoomAndDepartedClientsCannotSend() throws Exception {
    var unknown=new RoomSocketTest.Socket();handler.afterConnectionEstablished(unknown);
    send(unknown,Map.of("type","room-chat","roomId",room,"text","hello"));
    assertEquals("CHAT_NOT_MEMBER",last(unknown,"room-chat-error").path("code").asText());
    var sa=connect(a,room);
    send(sa,Map.of("type","room-chat","roomId","other","text","hello"));
    assertEquals("CHAT_NOT_MEMBER",last(sa,"room-chat-error").path("code").asText());
    assertNull(last(sa,"room-chat"));assertNull(sa.closed);
    handler.afterConnectionClosed(sa,CloseStatus.NORMAL);
    send(sa,Map.of("type","room-chat","roomId",room,"text","hello"));
    assertNull(last(sa,"room-chat"));
  }
  @Test void rateAndSizeLimitsPreserveGameConnectionAndReset() throws Exception {
    var sa=connect(a,room);
    send(sa,Map.of("type","room-chat","roomId",room,"text","x".repeat(201)));
    assertEquals("CHAT_INVALID_TEXT",last(sa,"room-chat-error").path("code").asText());
    send(sa,Map.of("type","room-chat","roomId",room,"text","  "));
    assertEquals("CHAT_INVALID_TEXT",last(sa,"room-chat-error").path("code").asText());
    for(int i=0;i<4;i++)send(sa,Map.of("type","room-chat","roomId",room,"text","hello"));
    assertEquals("CHAT_RATE_LIMITED",last(sa,"room-chat-error").path("code").asText());
    send(sa,Map.of("type","ping"));assertNotNull(last(sa,"pong"));assertNull(sa.closed);
    clock.addAndGet(10_000_000_000L);
    send(sa,Map.of("type","room-chat","roomId",room,"text","🌲".repeat(200)));
    assertEquals("🌲".repeat(200),last(sa,"room-chat").path("text").asText());
  }
  @Test void startedGameRejectsChatAndNewSubscriptionHasNoHistory() throws Exception {
    var sa=connect(a,room);
    send(sa,Map.of("type","room-chat","roomId",room,"text","prior"));
    var anotherTab=connect(a,room);assertNull(last(anotherTab,"room-chat"));
    for(int i=1;i<4;i++)service.addComputer(room,a,UUID.randomUUID().toString(),service.get(room,a).revision(),i);
    service.ready(room,a,true);service.start(room,a,UUID.randomUUID().toString(),service.get(room,a).revision());
    send(sa,Map.of("type","room-chat","roomId",room,"text","after"));
    assertEquals("CHAT_NOT_WAITING",last(sa,"room-chat-error").path("code").asText());
    assertEquals("prior",last(sa,"room-chat").path("text").asText());assertNull(last(anotherTab,"room-chat"));
  }
}
