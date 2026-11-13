package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OperatorPrePrepareRestartTest {
 @Test void abandonedAuthCanRestartEvenAfterExpiryButOldCookieAndNonceCannotPrepare(){
  var time=new CrossQuarterNativeSettlementTest.Time();time.now=Instant.now();var ops=new InitialCutoverOperatorControllerTest.Ops();var c=InitialCutoverOperatorControllerTest.controller(ops,time);
  String old=c.session().cookie(),oldProof=InitialCutoverOperatorControllerTest.proof(c,old,"prepare");time.now=time.now.plusSeconds(301);
  String fresh=c.restartBeforePrepare(old).cookie();assertNotEquals(old,fresh);assertThrows(RuntimeException.class,()->c.prepare(old,oldProof,"dep-current"));
  InitialCutoverOperatorControllerTest.prepare(c,fresh);assertEquals(1,ops.reads);assertEquals(0,ops.writes);assertThrows(RuntimeException.class,()->c.restartBeforePrepare(fresh));
 }
 @Test void enteredPrepareEvenFailedAuthenticationIsNeverReset(){var ops=new InitialCutoverOperatorControllerTest.Ops();var c=InitialCutoverOperatorControllerTest.controller(ops,Clock.systemUTC());String cookie=c.session().cookie();c.challenge(cookie,"prepare");assertThrows(RuntimeException.class,()->c.prepare(cookie,"bad","dep-current"));assertThrows(RuntimeException.class,()->c.restartBeforePrepare(cookie));assertEquals(0,ops.reads);}
 @Test void unknownWriteCannotReset(){var ops=new InitialCutoverOperatorControllerTest.Ops();var c=InitialCutoverOperatorControllerTest.controller(ops,Clock.systemUTC());String cookie=c.session().cookie();InitialCutoverOperatorControllerTest.prepare(c,cookie);var p=InitialCutoverOperatorControllerTest.preview(c,cookie,InitialCutoverOperatorController.Stage.METADATA);ops.unknown=true;String token=InitialCutoverOperatorControllerTest.proof(c,cookie,"metadata-execute");assertThrows(RuntimeException.class,()->c.execute(InitialCutoverOperatorController.Stage.METADATA,cookie,token,p.digest(),true));assertThrows(RuntimeException.class,()->c.restartBeforePrepare(cookie));assertEquals(1,ops.writes);}
 @Test void wrongCookieRollbackAndClosedCannotReset(){var time=new CrossQuarterNativeSettlementTest.Time();time.now=Instant.now();var c=InitialCutoverOperatorControllerTest.controller(new InitialCutoverOperatorControllerTest.Ops(),time);String cookie=c.session().cookie();assertThrows(RuntimeException.class,()->c.restartBeforePrepare("bad"));time.now=time.now.minusSeconds(1);assertThrows(RuntimeException.class,()->c.restartBeforePrepare(cookie));time.now=time.now.plusSeconds(1);assertThrows(RuntimeException.class,()->c.restartBeforePrepare(cookie));c.close();assertThrows(RuntimeException.class,()->c.restartBeforePrepare(cookie));}
 @Test void retryBudgetIsBounded(){var c=InitialCutoverOperatorControllerTest.controller(new InitialCutoverOperatorControllerTest.Ops(),Clock.systemUTC());String cookie=c.session().cookie();for(int i=0;i<3;i++)cookie=c.restartBeforePrepare(cookie).cookie();String exhausted=cookie;assertThrows(RuntimeException.class,()->c.restartBeforePrepare(exhausted));}
 @Test void realHttpRestartRotatesSecureCookieAndKeepsSourcesUncalled()throws Exception{
  var ops=new InitialCutoverOperatorControllerTest.Ops();var c=InitialCutoverOperatorControllerTest.controller(ops,Clock.systemUTC());String cookie=c.session().cookie();c.challenge(cookie,"prepare");
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new InitialCutoverHttpAdapter(c)).build();
  var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/initial-cutover/session/restart").header("Origin",InitialCutoverOperatorControllerTest.ORIGIN).cookie(new jakarta.servlet.http.Cookie("__Host-bridge_cutover",cookie)).contentType("application/json").content("{}"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
  assertTrue(result.getHeader("Set-Cookie").contains("Secure; HttpOnly; SameSite=Strict"));assertFalse(result.getHeader("Set-Cookie").contains(cookie));assertEquals(0,ops.reads);assertEquals(0,ops.writes);
 }
}
