package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.Rejected;
class InitialCutoverOperatorControllerTest {
 static final String ORIGIN="https://bridge.example";
 static class Ops implements InitialCutoverOperatorController.Operations {
  int reads,writes;InitialCutoverOperatorController.Outcome verified;boolean unknown;Runnable savedGuard;
  public String verifyOperator(String credential,String nonce){if(!credential.equals("proof:"+nonce))throw new Rejected();return "allowed-operator";}
  public Map<String,Object> prepare(String operator,String dep,Runnable guard){guard.run();reads++;return Map.of("scope","full-read-only");}
  public InitialCutoverOperatorController.Proposal preview(InitialCutoverOperatorController.Stage stage,String operator,Set<String> stopped,boolean none,Runnable guard){guard.run();reads++;return new InitialCutoverOperatorController.Proposal(new Object(),Map.of("stage",stage.name(),"afterToken","a".repeat(64)));}
  public InitialCutoverOperatorController.Outcome execute(InitialCutoverOperatorController.Stage stage,String operator,InitialCutoverOperatorController.Proposal p,Runnable guard){guard.run();savedGuard=guard;writes++;if(unknown)throw new Rejected();return verified=new InitialCutoverOperatorController.Outcome(stage.name()+"_VERIFIED","a".repeat(64));}
  public InitialCutoverOperatorController.Outcome lastVerified(){return verified;}
 }
 static InitialCutoverOperatorController controller(Ops o,Clock c){return new InitialCutoverOperatorController(ORIGIN,"client",Set.of("cli"),o,c);}
 static String proof(InitialCutoverOperatorController c,String cookie,String purpose){return "proof:"+c.challenge(cookie,purpose).nonce();}
 static void prepare(InitialCutoverOperatorController c,String cookie){c.prepare(cookie,proof(c,cookie,"prepare"),"dep-current");}
 static InitialCutoverOperatorController.Preview preview(InitialCutoverOperatorController c,String cookie,InitialCutoverOperatorController.Stage s){return c.preview(s,cookie,proof(c,cookie,s.name().toLowerCase()+"-preview"),Set.of("cli"),true);}
 @Test void explicitOrderedWorkflowUsesSameOpaquePreviewAndFreshNonces(){var ops=new Ops();var c=controller(ops,Clock.systemUTC());String cookie=c.session().cookie();prepare(c,cookie);
  for(var s:InitialCutoverOperatorController.Stage.values()){var p=preview(c,cookie,s);assertEquals(s.name()+"_VERIFIED",c.execute(s,cookie,proof(c,cookie,s.name().toLowerCase()+"-execute"),p.digest(),true).code());}
  assertEquals(3,ops.writes);assertThrows(RuntimeException.class,()->c.challenge(cookie,"activate-execute"));
 }
 @Test void failedAuthenticationNeverReadsAndCannotReuseNonce(){var ops=new Ops();var c=controller(ops,Clock.systemUTC());String cookie=c.session().cookie();String good=proof(c,cookie,"prepare");assertThrows(RuntimeException.class,()->c.prepare(cookie,"bad","dep"));assertEquals(0,ops.reads);assertThrows(RuntimeException.class,()->c.prepare(cookie,good,"dep"));}
 @Test void digestMismatchCannotWrite(){var ops=new Ops();var c=controller(ops,Clock.systemUTC());String cookie=c.session().cookie();prepare(c,cookie);preview(c,cookie,InitialCutoverOperatorController.Stage.METADATA);String token=proof(c,cookie,"metadata-execute");assertThrows(RuntimeException.class,()->c.execute(InitialCutoverOperatorController.Stage.METADATA,cookie,token,"wrong",true));assertEquals(0,ops.writes);}
 @Test void unknownHistoryPreservesPriorVerifiedMetadataButNeverRetries(){var ops=new Ops();var c=controller(ops,Clock.systemUTC());String cookie=c.session().cookie();prepare(c,cookie);var m=preview(c,cookie,InitialCutoverOperatorController.Stage.METADATA);c.execute(InitialCutoverOperatorController.Stage.METADATA,cookie,proof(c,cookie,"metadata-execute"),m.digest(),true);
  var h=preview(c,cookie,InitialCutoverOperatorController.Stage.HISTORY);ops.unknown=true;String token=proof(c,cookie,"history-execute");assertThrows(RuntimeException.class,()->c.execute(InitialCutoverOperatorController.Stage.HISTORY,cookie,token,h.digest(),true));assertEquals("METADATA_VERIFIED",c.stoppedStatus().get("lastVerifiedCode"));assertThrows(RuntimeException.class,()->c.challenge(cookie,"history-execute"));assertEquals(2,ops.writes);
 }
 @Test void cancellationAndWallExpiryInvalidateSavedFirstClaimGuard(){var time=new CrossQuarterNativeSettlementTest.Time();time.now=Instant.now();var ops=new Ops();var c=controller(ops,time);String cookie=c.session().cookie();prepare(c,cookie);var p=preview(c,cookie,InitialCutoverOperatorController.Stage.METADATA);c.execute(InitialCutoverOperatorController.Stage.METADATA,cookie,proof(c,cookie,"metadata-execute"),p.digest(),true);time.now=time.now.plusSeconds(301);assertThrows(RuntimeException.class,ops.savedGuard::run);c.close();assertThrows(RuntimeException.class,ops.savedGuard::run);}
 @Test void cannotSkipMetadataOrSelfDeclareSources(){var ops=new Ops();var c=controller(ops,Clock.systemUTC());String cookie=c.session().cookie();prepare(c,cookie);assertThrows(RuntimeException.class,()->c.challenge(cookie,"history-preview"));String token=proof(c,cookie,"metadata-preview");assertThrows(RuntimeException.class,()->c.preview(InitialCutoverOperatorController.Stage.METADATA,cookie,token,Set.of("unlisted"),true));assertEquals(1,ops.reads);}
}
