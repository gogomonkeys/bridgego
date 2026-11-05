package com.bridge.maintenance;

import com.bridge.persistence.*;
import com.bridge.room.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MaintenanceRuntimeTest {
  static final String ORIGIN="https://bridge.example";
  static class Memory implements SheetsGateway {
    Map<String,List<List<String>>> tabs=new HashMap<>(); int writes;
    Memory(){
      tabs.put("envelopes",new ArrayList<>(List.of(List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk"))));
      tabs.put("commits",new ArrayList<>(List.of(List.of("commit_id","payload_hash","chunk_count","match_id","revision"))));
      String owner=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
      tabs.put("accounts",new ArrayList<>(List.of(List.of("account_id","schema_version"),List.of(owner,"1"),List.of(other,"1"))));
      tabs.put("auth_identities",new ArrayList<>(List.of(List.of("issuer","subject","account_id","schema_version"),List.of("https://accounts.google.com","owner",owner,"1"),List.of("https://accounts.google.com","other",other,"1"))));
      tabs.put("erasure_ledger",new ArrayList<>(List.of(List.of("kind","identifier_hash","schema_version"),List.of("META","erasure-ledger-v1","1"))));
      tabs.put("dataset",List.of(List.of("purpose","bridgego-restricted-test"),List.of("dataset_id","fixture")));
    }
    public List<List<String>> read(String tab){return tabs.get(tab);}
    public void appendAtomic(Map<String,List<List<Object>>> rows){throw new AssertionError("No normal writer allowed");}
    public void replaceAtomic(Map<String,List<List<Object>>> rows){writes++;rows.forEach((k,v)->tabs.put(k,new ArrayList<>(v.stream().map(r->r.stream().map(Object::toString).toList()).toList())));}
  }
  GoogleCredentialVerifier verifier=(credential,audience,nonce)->{
    if(!"client".equals(audience)||credential==null||!credential.endsWith(":"+nonce))throw new IllegalArgumentException("secret");
    return new GoogleCredentialVerifier.Principal("https://accounts.google.com",credential.substring(0,credential.indexOf(':')));
  };
  @Test void actualMaintenanceContextHasNoGameBeansOrRoutesAndExecutionDefaultsOff() {
    Memory memory=new Memory();
    new WebApplicationContextRunner().withUserConfiguration(ErasureMaintenanceApplication.class)
        .withBean(SheetsGateway.class,()->memory).withBean(GoogleCredentialVerifier.class,()->verifier)
        .withPropertyValues("spring.profiles.active=erasure-maintenance","PUBLIC_ORIGIN="+ORIGIN,"GOOGLE_CLIENT_ID=client","spring.web.resources.add-mappings=false")
        .run(context->{
          assertFalse(context.getBeansOfType(RoomService.class).size()>0);assertTrue(context.getBeansOfType(StatsStore.class).isEmpty());
          assertTrue(context.getBeansOfType(IdentityStore.class).isEmpty());assertTrue(context.getBeansOfType(RoomController.class).isEmpty());
          assertFalse(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"));
          var settings=context.getBean(ErasureMaintenanceApplication.MaintenanceSettings.class);assertFalse(settings.executionEnabled());assertFalse(settings.writerStoppedAcknowledged());
          var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(MaintenanceGuard.class)).build();
          mvc.perform(get("/maintenance")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("測試資料刪除維護")));
          mvc.perform(get("/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("erasure"));
          mvc.perform(get("/api/rooms")).andExpect(status().isNotFound());
          mvc.perform(get("/index.html")).andExpect(status().isNotFound());mvc.perform(get("/sw.js")).andExpect(status().isNotFound());
          mvc.perform(post("/maintenance/challenge").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
              .content("{\"purpose\":\"preview\",\"accountId\":\"victim\"}")).andExpect(status().isBadRequest());
          mvc.perform(post("/api/sessions").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isNotFound());
          mvc.perform(post("/maintenance/session").header("Origin",ORIGIN)).andExpect(status().isOk()).andExpect(jsonPath("$.executionEnabled").value(false))
              .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("Secure"),org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("SameSite=Strict"))));
          mvc.perform(post("/maintenance/execute").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"secret\",\"consent\":true}"))
              .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ERASURE_DRY_RUN_ONLY"));assertEquals(0,memory.writes);
        });
  }
  MockMvc mvc(Memory memory,boolean execution){
    var settings=new ErasureMaintenanceApplication.MaintenanceSettings(ORIGIN,"client",execution,true);
    var engine=new OperatorAccountErasure(memory,verifier,"client",Clock.systemUTC(),()->true,execution);
    return MockMvcBuilders.standaloneSetup(new MaintenanceController(new MaintenanceSessions(engine,settings,Clock.systemUTC()),settings))
        .addFilters(new MaintenanceGuard(settings)).build();
  }
  Cookie session(MockMvc mvc)throws Exception{
    var response=mvc.perform(post("/maintenance/session").header("Origin",ORIGIN)).andExpect(status().isOk()).andReturn().getResponse();
    String value=response.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1];return new Cookie(MaintenanceController.COOKIE,value);
  }
  String nonce(MockMvc mvc,Cookie session,String purpose)throws Exception {
    var response=mvc.perform(post("/maintenance/challenge").cookie(session).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"purpose\":\""+purpose+"\"}")).andExpect(status().isOk()).andReturn().getResponse();
    return new ObjectMapper().readTree(response.getContentAsString()).get("nonce").asText();
  }
  @Test void csrfMissingSessionOversizedBodiesAndCrossSessionNonceAreRejected()throws Exception {
    Memory memory=new Memory();var mvc=mvc(memory,true);
    mvc.perform(post("/maintenance/session")).andExpect(status().isForbidden());
    mvc.perform(post("/maintenance/session").header("Origin","https://evil.test")).andExpect(status().isForbidden());
    mvc.perform(post("/maintenance/challenge").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"preview\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/maintenance/preview").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content("x".repeat(16385)))
        .andExpect(status().isPayloadTooLarge());
    var first=session(mvc);var second=session(mvc);String firstNonce=nonce(mvc,first,"preview");nonce(mvc,second,"preview");
    mvc.perform(post("/maintenance/preview").cookie(second).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"credential\":\"owner:"+firstNonce+"\",\"consent\":true}")) .andExpect(status().isBadRequest());assertEquals(0,memory.writes);
  }
  @Test void previewDoesNotWriteAndExecutionRequiresSameOwnerFreshProofAndExactSessionPlan()throws Exception {
    Memory memory=new Memory();var mvc=mvc(memory,true);var session=session(mvc);String nonce=nonce(mvc,session,"preview");
    var response=mvc.perform(post("/maintenance/preview").cookie(session).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"credential\":\"owner:"+nonce+"\",\"consent\":true}")) .andExpect(status().isOk()).andReturn().getResponse();
    assertEquals(0,memory.writes);var plan=new ObjectMapper().readTree(response.getContentAsString());
    String fresh=nonce(mvc,session,"execute");
    mvc.perform(post("/maintenance/execute").cookie(session).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
        .content("{\"credential\":\"owner:"+fresh+"\",\"consent\":false}"))
        .andExpect(status().isBadRequest());assertEquals(0,memory.writes);
    fresh=nonce(mvc,session,"execute");
    var body=new HashMap<String,Object>();body.put("credential","other:"+fresh);body.put("consent",true);body.put("planId",plan.get("planId").asText());body.put("confirmation",plan.get("confirmationText").asText());
    mvc.perform(post("/maintenance/execute").cookie(session).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsBytes(body)))
        .andExpect(status().isBadRequest());assertEquals(0,memory.writes);
    fresh=nonce(mvc,session,"execute");body.put("credential","owner:"+fresh);body.put("planId","another-plan");
    mvc.perform(post("/maintenance/execute").cookie(session).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsBytes(body)))
        .andExpect(status().isBadRequest());assertEquals(0,memory.writes);
    fresh=nonce(mvc,session,"execute");body.put("credential","owner:"+fresh);body.put("planId",plan.get("planId").asText());
    mvc.perform(post("/maintenance/execute").cookie(session).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsBytes(body)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("VERIFIED"));assertEquals(1,memory.writes);
    mvc.perform(post("/maintenance/execute").cookie(session).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsBytes(body)))
        .andExpect(status().isUnauthorized());assertEquals(1,memory.writes);
  }
  @Test void missingShutdownAckAndMissingPreviewCannotReadOrWrite()throws Exception {
    Memory memory=new Memory();var settings=new ErasureMaintenanceApplication.MaintenanceSettings(ORIGIN,"client",true,false);
    var engine=new OperatorAccountErasure(memory,verifier,"client",Clock.systemUTC(),()->false,true);
    var sessions=new MaintenanceSessions(engine,settings,Clock.systemUTC());String session=sessions.create();
    assertEquals("WRITER_SHUTDOWN_REQUIRED",assertThrows(MaintenanceSessions.Failure.class,()->sessions.challenge(session,"preview")).code);
    var enabled=new MaintenanceSessions(new OperatorAccountErasure(memory,verifier,"client",Clock.systemUTC(),()->true,true),
        new ErasureMaintenanceApplication.MaintenanceSettings(ORIGIN,"client",true,true),Clock.systemUTC());String other=enabled.create();
    assertEquals("PREVIEW_REQUIRED",assertThrows(MaintenanceSessions.Failure.class,()->enabled.challenge(other,"execute")).code);
    assertEquals(0,memory.writes);
  }

}
