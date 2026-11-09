package com.bridge.persistence;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SyntheticCleanupManualQuarterWebTest {
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="bridge.supervised.runtime",matches="true")
  @Test void disabledGraphHasNoGameSecretReadsOrMutation() throws Exception {
    String port;try(var socket=new ServerSocket(0)){port=""+socket.getLocalPort();}
    AtomicInteger reads=new AtomicInteger();
    var app=SyntheticCleanupManualQuarterWeb.application(new String[0],key->{
      if(key.equals("PORT"))return port;
      if(key.equals("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")){reads.incrementAndGet();throw new AssertionError();}
      return null;
    });
    try(var context=app.run()){
      assertEquals(0,reads.get());
      assertTrue(context.getBeansOfType(com.bridge.room.RoomService.class).isEmpty());
      assertTrue(context.getBeansOfType(SheetsGateway.class).isEmpty());
      assertFalse(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"));
      var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context)
          .addFilters(context.getBean(SyntheticCleanupManualQuarterWebGuard.class)).build();
      mvc.perform(get("/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
          .andExpect(jsonPath("$.productionCleanupCertified").value(false));
      mvc.perform(get("/")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(header().exists("Content-Security-Policy"));
      for(String action:new String[]{"challenge","preview","confirm"})
        mvc.perform(post("/quarter/"+action).header("Origin","https://localhost").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
      mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
      mvc.perform(get("/?credential=redacted")).andExpect(status().isNotFound());
      mvc.perform(post("/quarter/reset")).andExpect(status().isNotFound());
      assertEquals(0,reads.get());
    }
  }
  @Test void lockedConfigurationPreventsBindingOverrideAndGraphSelection() {
    assertThrows(IllegalArgumentException.class,()->SyntheticCleanupManualQuarterWeb.application(new String[]{"--server.address=0.0.0.0"},key->null));
    var env=new SyntheticCleanupManualQuarterWeb.LockedEnvironment(SyntheticCleanupManualQuarterWeb.lockedProperties("8080"));
    assertEquals("0.0.0.0",env.getProperty("server.address"));assertEquals("manual-quarter-trial",env.getActiveProfiles()[0]);
    assertTrue(env.getSystemProperties().isEmpty());assertTrue(env.getSystemEnvironment().isEmpty());
    assertFalse(SyntheticCleanupManualQuarterWeb.Configuration.class.isAnnotationPresent(org.springframework.context.annotation.ComponentScan.class));
    env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("attack",Map.of("server.address","127.0.0.1")));
    assertThrows(IllegalStateException.class,env::recheckAndLock);
  }
  @Test void conciseUiBindsCheckboxAndDisclosesTwoWritesAndScope(){
    var html=SyntheticCleanupManualQuarterWeb.HTML;var js=SyntheticCleanupManualQuarterWeb.JS;
    assertTrue(html.contains("兩次寫入"));assertTrue(html.contains("7 張資料表"));assertTrue(html.contains("不改格式"));
    assertTrue(js.contains("planId:plan.planId,confirmed:true"));assertTrue(js.contains("PREVIEW_EXPIRED_NO_WRITE"));
    assertFalse(js.contains("localStorage"));assertFalse(js.contains("innerHTML"));assertFalse(js.contains("/reset"));
  }
  @Test void enabledGuardBoundsOriginBodyAndStrictJsonBeforeAnyCredentialAccess() throws Exception {
    var settings=new SyntheticCleanupManualQuarterHost.Settings(true,"https://example.test",
        SyntheticCleanupManualQuarterHostTest.CLIENT,SyntheticCleanupExecutorTest.target(),"a".repeat(64));
    AtomicInteger verification=new AtomicInteger(),opens=new AtomicInteger();
    var host=new SyntheticCleanupManualQuarterHost(settings,(token,audience,nonce)->{verification.incrementAndGet();throw new AssertionError("No verifier access");},
        ()->{opens.incrementAndGet();throw new AssertionError("No credential access");},java.time.Clock.systemUTC());
    var builder=new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
    new SyntheticCleanupManualQuarterWeb.Configuration().strictJson().customize(builder);
    var mapper=builder.build();
    var mvc=MockMvcBuilders.standaloneSetup(new SyntheticCleanupManualQuarterWeb.Controller(host,settings))
        .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new SyntheticCleanupManualQuarterWebGuard(settings)).build();
    mvc.perform(post("/quarter/challenge").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/quarter/challenge").header("Origin","https://evil.test").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/quarter/challenge").header("Origin","https://example.test","https://example.test").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/quarter/preview").header("Origin","https://example.test").contentType("application/json").content("x".repeat(16385)))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(post("/quarter/preview").header("Origin","https://example.test").contentType("text/plain").content("{}"))
        .andExpect(status().isUnsupportedMediaType());
    for(String json:new String[]{"{\"target\":\"arbitrary-target\"}","{\"consent\":true,\"consent\":false}","{} {}","{\"consent\":\"true\"}"})
      mvc.perform(post("/quarter/preview").header("Origin","https://example.test").contentType("application/json").content(json))
          .andExpect(status().isBadRequest());
    assertEquals(0,opens.get());assertEquals(0,verification.get());
  }
}
