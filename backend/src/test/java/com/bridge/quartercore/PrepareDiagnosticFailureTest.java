package com.bridge.quartercore;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PrepareDiagnosticFailureTest {
 @Test void onlyFixedReachedStageEscapesAndFailureRemainsTerminal(){
  for(var stage:PrepareDiagnosticFailure.Stage.values()){
   var ops=new InitialCutoverOperatorControllerTest.Ops(){
    @Override public String verifyOperator(String credential,String nonce){if(stage==PrepareDiagnosticFailure.Stage.OPERATOR_AUTH)throw new IllegalArgumentException("private-token-and-subject");return super.verifyOperator(credential,nonce);}
    @Override public Map<String,Object> prepare(String who,String dep,Runnable guard){reads++;throw new PrepareDiagnosticFailure(stage);}
   };
   var c=InitialCutoverOperatorControllerTest.controller(ops,Clock.systemUTC());String cookie=c.session().cookie();
   String proof=InitialCutoverOperatorControllerTest.proof(c,cookie,"prepare");
   assertThrows(RuntimeException.class,()->c.prepare(cookie,proof,"dep-valid"));
   assertEquals(Map.of("code","STOPPED_OR_UNCONFIRMED","prepareStage",stage.name()),c.stoppedStatus());
   assertFalse(c.stoppedStatus().toString().contains("private-token"));assertEquals(0,ops.writes);
   int reads=ops.reads;assertThrows(RuntimeException.class,()->c.prepare(cookie,proof,"dep-valid"));assertEquals(reads,ops.reads);
   assertThrows(RuntimeException.class,()->c.challenge(cookie,"prepare"));
  }
 }
 @Test void diagnosticHasNoCauseOrStackPayload(){for(var s:PrepareDiagnosticFailure.Stage.values()){var e=new PrepareDiagnosticFailure(s);assertNull(e.getCause());assertEquals(0,e.getStackTrace().length);assertEquals(s.name(),e.getMessage());}}
 @Test void realTransportSeparatesFetchFromParserEntryAndClosesWithoutRetry(){
  for(boolean parser:new boolean[]{false,true}){
   var calls=new java.util.concurrent.atomic.AtomicInteger();var closed=new java.util.concurrent.atomic.AtomicBoolean();var b=InitialCutoverAuthorityTest.B;
   var tx=new BatchedSheetsTransport(443,new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1)),(r,f)->{calls.incrementAndGet();assertFalse(r.write());if(!parser)throw new java.io.IOException("private-network-detail");return new java.io.ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));},b.manualBinding());
   var view=new QuarterGoogleFactory.LegacyReadOnly(tx,()->{closed.set(true);tx.close();},b);
   var failure=assertThrows(PrepareDiagnosticFailure.class,view::readVerified);
   assertEquals(parser?PrepareDiagnosticFailure.Stage.LEGACY_PARSE:PrepareDiagnosticFailure.Stage.LEGACY_FETCH,failure.stage);
   assertTrue(closed.get());assertEquals(1,calls.get());assertThrows(RuntimeException.class,view::readVerified);assertEquals(1,calls.get());
  }
 }

}
