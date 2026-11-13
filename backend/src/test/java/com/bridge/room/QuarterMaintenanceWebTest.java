package com.bridge.room;

import com.bridge.persistence.QuarterMaintenanceGate;
import com.bridge.persistence.QuarterlyRetention;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import static org.junit.jupiter.api.Assertions.*;

class QuarterMaintenanceWebTest {
  QuarterMaintenanceGate open() {
    var gate = new QuarterMaintenanceGate(true, Clock.systemUTC());
    gate.openVerified(QuarterlyRetention.currentQuarterStart(Clock.systemUTC().instant()));
    return gate;
  }

  @Test void disabledAndOpenPassThroughAndDrainWaitsForAdmittedRequest() throws Exception {
    for (var gate : new QuarterMaintenanceGate[]{QuarterMaintenanceGate.disabled(), open()}) {
      var filter = new QuarterMaintenanceFilter(gate);
      var response = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest("POST", "/api/rooms"), response, (req, res) -> {
        assertEquals(gate.enabled() ? 1 : 0, gate.activeOperations());
        gate.beginDrain();
        assertFalse(gate.beginExclusive());
        res.getWriter().write("game");
      });
      assertEquals("game", response.getContentAsString());
      assertEquals(0, gate.activeOperations());
      if (gate.enabled()) assertTrue(gate.beginExclusive());
    }
  }

  @Test void closedHomepageAndApiAreNoStore503WithoutInvokingBusinessCode() throws Exception {
    var gate = new QuarterMaintenanceGate(true, Clock.systemUTC());
    var filter = new QuarterMaintenanceFilter(gate);
    for (String path : new String[]{"/", "/index.html", "/api/rooms", "/ws", "/assets/app.js", "/health/other"}) {
      var response = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest("GET", path), response,
          (req, res) -> fail("Maintenance request entered game"));
      assertEquals(503, response.getStatus());
      assertEquals("no-store", response.getHeader("Cache-Control"));
      assertTrue(response.getContentAsString().contains("系統維護中"));
      assertTrue(response.getContentType().startsWith(path.equals("/") || path.equals("/index.html") ? "text/html" : "application/json"));
    }
  }

  @Test void headHasNoBodyAndHealthBypassesOnlyExactEndpoints() throws Exception {
    var gate = new QuarterMaintenanceGate(true, Clock.systemUTC());
    var filter = new QuarterMaintenanceFilter(gate);
    var response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("HEAD", "/"), response, (req,res)->fail());
    assertEquals(503,response.getStatus());assertEquals("",response.getContentAsString());
    for (String path : new String[]{"/health/live", "/health/ready"}) {
      var result = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest("GET",path),result,(req,res)->res.getWriter().write("health"));
      assertEquals("health",result.getContentAsString());
    }
    var health = new ServiceHealthController(new RoomService(), gate, "test");
    assertEquals(200,health.live().getStatusCode().value());
    assertEquals(503,health.ready().getStatusCode().value());
    assertEquals("no-store",health.ready().getHeaders().getCacheControl());
    var domain = new RoomService();
    var openHealth = new ServiceHealthController(domain, open(), "test");
    domain.freezeForQuarterMaintenance();
    assertEquals(503, openHealth.ready().getStatusCode().value());
    assertEquals(200, openHealth.live().getStatusCode().value());
  }

  @Test void admittedSocketActionRemainsCountedUntilCompletion() throws Exception {
    var gate = open();
    var service = new RoomService() {
      @Override public synchronized Identity authenticate(String token) {
        assertEquals(1, gate.activeOperations());
        gate.beginDrain();
        assertFalse(gate.beginExclusive());
        return super.authenticate(token);
      }
    };
    var json = new ObjectMapper();
    var handler = new RoomSocket(service, json, System::nanoTime, Runnable::run, false, gate);
    var player = service.createSession("Test");var room = service.create(player).roomId();
    var socket = new RoomSocketTest.Socket();handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(socket, new TextMessage(json.writeValueAsString(
        Map.of("type", "subscribe", "token", player.token(), "roomId", room))));
    assertEquals(0, gate.activeOperations());
    assertTrue(gate.beginExclusive());
  }

  @Test void newSocketRejectedButExistingMemberNotForfeitedByMaintenanceNoticeOrClose() throws Exception {
    var gate = open();var service = new RoomService();var json = new ObjectMapper();
    var handler = new RoomSocket(service,json,System::nanoTime,Runnable::run,false,gate);
    var player = service.createSession("Test");var room = service.create(player).roomId();
    var socket = new RoomSocketTest.Socket();handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(socket,new TextMessage(json.writeValueAsString(Map.of("type","subscribe","token",player.token(),"roomId",room))));
    int before=socket.sent.size();gate.beginDrain();
    for(int i=0;i<5;i++) handler.handleTextMessage(socket,new TextMessage("{\"type\":\"ping\"}"));
    assertEquals(before+1,socket.sent.size());assertNull(socket.closed);
    assertEquals(room,service.me(player).roomId());
    handler.heartbeat();assertNull(socket.closed);
    var newcomer=new RoomSocketTest.Socket();handler.afterConnectionEstablished(newcomer);
    assertEquals(1013,newcomer.closed.getCode());
    handler.afterConnectionClosed(socket,CloseStatus.NORMAL);
    assertEquals(room,service.me(player).roomId(),"Maintenance close must not become domain forfeit");
  }
}
