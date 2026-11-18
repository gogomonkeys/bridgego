package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

class RoomVoiceTest {
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
  @Test void consentRosterRelayAndLeave() throws Exception {
    var sa=connect(a,room);
    var b=service.createSession("B"); service.join(room,b);
    var sb=connect(b,room);
    assertNull(last(sa,"voice-state"));
    String pa=join(sa); assertEquals(0,last(sa,"voice-state").path("peers").size());
    String pb=join(sb); assertEquals(pb,last(sa,"voice-state").path("peers").get(0).path("peerId").asText());
    send(sa,Map.of("type","voice-signal","from",pa,"to",pb,"signal",Map.of("type","offer","sdp","v=0")));
    assertEquals(pa,last(sb,"voice-signal").path("from").asText());
    send(sb,Map.of("type","voice-leave","peerId",pb));
    assertEquals(0,last(sa,"voice-state").path("peers").size());
    assertNull(sa.closed); assertNull(sb.closed);
  }
  @Test void staleSenderAndCrossRoomCannotRelay() throws Exception {
    var sa=connect(a,room); String old=join(sa);
    send(sa,Map.of("type","voice-leave","peerId",old)); String current=join(sa);
    assertNotEquals(old,current);
    var b=service.createSession("B"); String other=service.create(b).roomId();
    var sb=connect(b,other); String pb=join(sb);
    send(sa,Map.of("type","voice-signal","from",old,"to",pb,"signal",Map.of("type","offer","sdp","v=0")));
    assertEquals("VOICE_STALE_SESSION",last(sa,"voice-error").path("code").asText());
    send(sa,Map.of("type","voice-signal","from",current,"to",pb,"signal",Map.of("type","offer","sdp","v=0")));
    assertEquals("VOICE_PEER_GONE",last(sa,"voice-error").path("code").asText());
    assertNull(last(sb,"voice-signal")); assertNull(sa.closed);
  }
  @Test void disconnectCleansRosterAndNewTabCannotJoinTwice() throws Exception {
    var sa=connect(a,room); join(sa);
    var second=connect(a,room);
    send(second,Map.of("type","voice-join","requestId","newtab"));
    assertEquals("VOICE_ALREADY_JOINED",last(second,"voice-error").path("code").asText());
    var b=service.createSession("B"); service.join(room,b); var sb=connect(b,room); join(sb);
    handler.afterConnectionClosed(sa,CloseStatus.NORMAL);
    assertEquals(0,last(sb,"voice-state").path("peers").size());
    assertNotNull(join(second));
  }
  @Test void unauthenticatedAndRateLimitedVoiceNeverClosesGameSocket() throws Exception {
    var unknown=new RoomSocketTest.Socket();handler.afterConnectionEstablished(unknown);
    send(unknown,Map.of("type","voice-join","requestId","x"));
    assertEquals("VOICE_NOT_MEMBER",last(unknown,"voice-error").path("code").asText());
    var sa=connect(a,room); join(sa);
    for(int i=0;i<121;i++)send(sa,Map.of("type","voice-join","requestId","x"));
    assertEquals("VOICE_RATE_LIMITED",last(sa,"voice-error").path("code").asText());
    send(sa,Map.of("type","ping")); assertNotNull(last(sa,"pong")); assertNull(sa.closed);
  }
  @Test void startEndsVoiceAndRefusesRejoin() throws Exception {
    var sa=connect(a,room); join(sa);
    for(int i=0;i<3;i++) service.addComputer(room,a,UUID.randomUUID().toString(),service.get(room,a).revision(),i+1);
    service.ready(room,a,true);
    service.start(room,a,UUID.randomUUID().toString(),service.get(room,a).revision());
    assertEquals("GAME_STARTED",last(sa,"voice-ended").path("reason").asText());
    send(sa,Map.of("type","voice-join","requestId","after-start"));
    assertEquals("VOICE_NOT_WAITING",last(sa,"voice-error").path("code").asText());
    assertNull(sa.closed);
  }
  @Test void invalidSignalAndStaleLeaveAreNonDestructive() throws Exception {
    var sa=connect(a,room); String pa=join(sa);
    var b=service.createSession("B"); service.join(room,b); var sb=connect(b,room); String pb=join(sb);
    send(sa,Map.of("type","voice-signal","from",pa,"to",pb,"signal",Map.of("type","offer","sdp","x".repeat(16001))));
    assertEquals("VOICE_INVALID_SIGNAL",last(sa,"voice-error").path("code").asText());
    assertNull(last(sb,"voice-signal"));
    send(sa,Map.of("type","voice-leave","peerId","old"));
    send(sa,Map.of("type","voice-signal","from",pa,"to",pb,"signal",Map.of("type","answer","sdp","v=0")));
    assertNotNull(last(sb,"voice-signal")); assertNull(sa.closed);
  }
  @Test void roomRemovalAndVideoNegotiationStayIsolated() throws Exception {
    var sa=connect(a,room); String pa=join(sa);
    var b=service.createSession("B"); service.join(room,b); var sb=connect(b,room); String pb=join(sb);
    send(sa,Map.of("type","voice-signal","from",pa,"to",pb,"signal",Map.of("type","offer","sdp","v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")));
    assertEquals("VOICE_INVALID_SIGNAL",last(sa,"voice-error").path("code").asText());
    assertNull(last(sb,"voice-signal"));
    service.leave(room,b);
    assertEquals(0,last(sa,"voice-state").path("peers").size());
    service.leave(room,a);
    assertNotNull(sa.closed); assertNotNull(sb.closed);
  }
  @Test void exhaustedBudgetCannotBlockConsentWithdrawalOrLeaveGhostPeer() throws Exception {
    var sa=connect(a,room); String pa=join(sa);
    var b=service.createSession("B"); service.join(room,b); var sb=connect(b,room); join(sb);
    for(int i=0;i<121;i++) send(sa,Map.of("type","voice-signal","from","stale","to","none"));
    assertEquals("VOICE_RATE_LIMITED",last(sa,"voice-error").path("code").asText());
    send(sa,Map.of("type","voice-leave","peerId","old-session"));
    assertEquals(1,last(sb,"voice-state").path("peers").size());
    send(sa,Map.of("type","voice-leave","peerId",pa));
    assertEquals(0,last(sb,"voice-state").path("peers").size());
    int delivered=sb.sent.size();
    send(sa,Map.of("type","voice-leave","peerId",pa));
    assertEquals(delivered,sb.sent.size());
    var replacement=connect(a,room); assertNotNull(join(replacement));
    assertEquals(1,last(sb,"voice-state").path("peers").size());
    send(sa,Map.of("type","ping")); assertNotNull(last(sa,"pong")); assertNull(sa.closed);
  }
  @Test void malformedOrTransportOversizeClosesSocketAndRemovesVoicePeer() throws Exception {
    var sa=connect(a,room); join(sa);
    var b=service.createSession("B"); service.join(room,b); var sb=connect(b,room); join(sb);
    handler.handleTextMessage(sa,new TextMessage("x".repeat(20001)));
    assertNotNull(sa.closed);
    assertEquals(0,last(sb,"voice-state").path("peers").size());
    service.join(room,a); // Closing the only waiting-room transport also removes its seat.
    var replacement=connect(a,room); join(replacement);
    handler.handleTextMessage(replacement,new TextMessage("{"));
    assertNotNull(replacement.closed);
    assertEquals(0,last(sb,"voice-state").path("peers").size());
    assertNull(sb.closed);
  }
}
