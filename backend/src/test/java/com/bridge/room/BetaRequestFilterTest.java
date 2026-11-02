package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BetaRequestFilterTest {
  AtomicLong tick=new AtomicLong();
  RoomService service=new RoomService(new BetaLimits(2,128,3600000,300000,64,4,20,10,2,30));
  BetaRequestFilter filter=new BetaRequestFilter(service,tick::get);
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new RoomController(service),new ServiceHealthController(service,"0.7.0"))
      .addFilters(filter).build();

  @Test void privateUnauthorizedResponsesAreNeverCachedAndHaveEpoch() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
      .andExpect(header().string("Cache-Control","no-store"))
      .andExpect(header().string("X-Bridge-Instance-Epoch",service.instanceEpoch()));
  }

  @Test void fakeForwardedAddressesCannotBypassPeerLimitAndWindowResets() throws Exception {
    for(int i=0;i<2;i++) mvc.perform(post("/api/sessions").header("X-Forwarded-For","fake"+i)
      .contentType("application/json").content("{\"displayName\":\"A\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/sessions").header("X-Forwarded-For","new").contentType("application/json")
      .content("{\"displayName\":\"A\"}")).andExpect(status().isTooManyRequests())
      .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().string("Cache-Control","no-store"));
    tick.addAndGet(60000);
    mvc.perform(post("/api/sessions").contentType("application/json")
      .content("{\"displayName\":\"A\"}")).andExpect(status().isOk());
  }

  @Test void healthRemainsAvailableUnderLoadAndStoppingMutationsFailClearly() throws Exception {
    for(int i=0;i<21;i++)mvc.perform(get("/api/me"));
    mvc.perform(get("/api/me")).andExpect(status().isTooManyRequests());
    mvc.perform(get("/health/ready")).andExpect(status().isOk());
    service.beginMaintenance();
    mvc.perform(post("/api/sessions").contentType("application/json").content("{}"))
      .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVER_MAINTENANCE"));
    mvc.perform(get("/health/ready")).andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.status").value("stopping"));
  }

  @Test void knownAndUnknownBodyLengthsAreBounded() throws Exception {
    mvc.perform(post("/api/sessions").contentType("application/json").content("x".repeat(17000)))
      .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    MockHttpServletRequest req=new MockHttpServletRequest() {
      @Override public int getContentLength(){return -1;}
      @Override public long getContentLengthLong(){return -1;}
    };
    req.setContent("x".repeat(17000).getBytes());
    var bounded=new BoundedBodyRequest(req,16384);
    var error=assertThrows(BoundedBodyRequest.TooLarge.class,()->bounded.getInputStream().readAllBytes());
    var exception=new org.springframework.http.converter.HttpMessageNotReadableException(
        "read failed",error,new org.springframework.http.server.ServletServerHttpRequest(req));
    var result=new RoomController(service).malformed(exception);
    assertEquals(413,result.getStatusCode().value());
  }

  @Test void ordinaryMalformedJsonStillHasSafe400() throws Exception {
    mvc.perform(post("/api/sessions").contentType("application/json").content("{"))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"))
      .andExpect(header().string("Cache-Control","no-store"));
  }
}
