package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import static org.junit.jupiter.api.Assertions.*;
import java.time.*;import java.util.*;
class InitialCutoverHttpAdapterTest {
 @Test void exactRoutesOriginStrictBodyCookieAndNoStore()throws Exception{
  var ops=new InitialCutoverOperatorControllerTest.Ops();var c=InitialCutoverOperatorControllerTest.controller(ops,Clock.systemUTC());var mvc=MockMvcBuilders.standaloneSetup(new InitialCutoverHttpAdapter(c)).build();
  mvc.perform(get("/initial-cutover/config")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(get("/initial-cutover/operator.js")).andExpect(status().isNotFound()); // adapter does not consume static GET
  mvc.perform(post("/initial-cutover/session").header("Origin","https://evil.example").contentType("application/json").content("{}")).andExpect(status().isConflict());
  mvc.perform(post("/initial-cutover/session").header("Origin",InitialCutoverOperatorControllerTest.ORIGIN).contentType("application/json").content("{\"unknown\":1}")).andExpect(status().isConflict());
  var response=mvc.perform(post("/initial-cutover/session").header("Origin",InitialCutoverOperatorControllerTest.ORIGIN).contentType("application/json").content("{}")).andExpect(status().isOk()).andReturn().getResponse();String cookie=response.getHeader("Set-Cookie");assertTrue(cookie.contains("Secure; HttpOnly; SameSite=Strict"));assertFalse(response.getContentAsString().contains("cookie"));assertEquals(0,ops.reads);
 }
 @Test void unsignedNoiseDoesNotConsumePendingSignedObservation()throws Exception{
  var key=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var context=new SignedObservationControlPlane.Context("run","a".repeat(64),"workspace","service","dep-current","revision",Set.of("dep-old"));var receiver=new SignedObservationControlPlane(key.getPublic(),context,Clock.systemUTC());var mvc=MockMvcBuilders.standaloneSetup(new SignedObservationHttpAdapter(InitialCutoverOperatorControllerTest.ORIGIN,()->receiver)).build();String nonce=receiver.challenge().challenge();
  mvc.perform(post("/initial-cutover/observation/submit").header("Origin",InitialCutoverOperatorControllerTest.ORIGIN).contentType("application/json").content(NativeSheetsModel.json(Map.of("payload",Base64.getEncoder().encodeToString("{}".getBytes()),"signature",Base64.getEncoder().encodeToString(new byte[64]))))).andExpect(status().isConflict());assertEquals(nonce,receiver.challenge().challenge());
  mvc.perform(post("/initial-cutover/observation/challenge").header("Origin",InitialCutoverOperatorControllerTest.ORIGIN).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));receiver.close();
 }
}
