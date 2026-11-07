package com.bridge.maintenance;
import com.bridge.room.GoogleCredentialVerifier;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ExperimentBootstrapHttpTest {
  static final String ORIGIN="https://test.example";
  org.springframework.test.web.servlet.MockMvc mvc(){
    var settings=new ExperimentBootstrapSessions.Settings("client","test_sheet_123456","91a3e5a0-9102-43d5-b483-fe7c7f7614dc",new GoogleCredentialVerifier.Principal("https://accounts.google.com","test"),Instant.now().plusSeconds(600),true,false);
    var sessions=new ExperimentBootstrapSessions(new ExperimentBootstrapSessions.Storage(){public String previewEmptyDigest(){throw new AssertionError("No read expected");}public String initializeEmpty(String d,boolean s,boolean a){throw new AssertionError("No write expected");}},(t,a,n)->settings.tester(),settings,Clock.systemUTC());
    return MockMvcBuilders.standaloneSetup(new ExperimentBootstrapController(sessions,settings)).addFilters(new MaintenanceGuard(new ErasureMaintenanceApplication.MaintenanceSettings(ORIGIN,"client",false,true))).build();
  }
  @Test void onlyApprovedRoutesAndNoStoreHeaders()throws Exception{var m=mvc();m.perform(get("/maintenance")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(org.hamcrest.Matchers.containsString("空白資料集初始化")));m.perform(get("/api/rooms")).andExpect(status().isNotFound());m.perform(get("/sw.js")).andExpect(status().isNotFound());m.perform(get("/health/ready")).andExpect(jsonPath("$.mode").value("experiment-bootstrap"));}
  @Test void originBodyLimitAndSessionCookie()throws Exception{var m=mvc();m.perform(post("/maintenance/session")).andExpect(status().isForbidden());m.perform(post("/maintenance/session").header("Origin",ORIGIN)).andExpect(status().isOk()).andExpect(jsonPath("$.executionEnabled").value(false)).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("__Host-"),org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("Secure"),org.hamcrest.Matchers.containsString("SameSite=Strict"))));m.perform(post("/maintenance/preview").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());}
  @Test void noCookieCannotRequestInitialization()throws Exception{mvc().perform(post("/maintenance/execute").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"fake\",\"consent\":true}")).andExpect(status().isUnauthorized());}
  @Test void unverifiedSessionDoesNotDisclosePrivateTargetBindings()throws Exception{
    mvc().perform(post("/maintenance/session").header("Origin",ORIGIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.clientId").value("client"))
        .andExpect(jsonPath("$.target").doesNotExist()).andExpect(jsonPath("$.dataset").doesNotExist())
        .andExpect(jsonPath("$.issuer").doesNotExist()).andExpect(jsonPath("$.subject").doesNotExist())
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test_sheet_123456"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("91a3e5a0-9102-43d5-b483-fe7c7f7614dc"))));
    assertFalse(ExperimentBootstrapPage.JS.contains("config.target"));
    assertFalse(ExperimentBootstrapPage.JS.contains("config.dataset"));
  }
  @Test void browserPageUsesFreshGoogleNonceAndOneShotCallback(){String js=ExperimentBootstrapPage.JS;assertTrue(js.contains("nonce:challenge.nonce"));assertTrue(js.contains("credentialConsumed=true"));assertTrue(js.contains("purpose==='execute'&&!canExecute()"));assertFalse(js.contains("ALREADY_ERASED"));assertTrue(js.contains("config.mode!=='experiment-bootstrap'"));}
}
