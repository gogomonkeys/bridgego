package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;import java.time.*;import java.util.*;import java.security.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real embedded HTTP negative paths. No bypass of the production session or gate. */
class PlannedCutoverHttpFailureTest {
 @TempDir Path dir;
 @Test void checkpointFreshNativeMismatchCannotRenewOrActivate()throws Exception{run("checkpoint-mismatch");}
 @Test void expiredOperatorScopeCannotExecuteOrActivate()throws Exception{run("expiry");}
 @Test void appliedMetadataUnknownCannotRetryOrActivate()throws Exception{run("metadata");}
 @Test void appliedHistoryUnknownCannotRetryOrActivate()throws Exception{run("history");}
 void run(String failure)throws Exception{
  var clock=new CrossQuarterNativeSettlementTest.Time();clock.now=Clock.systemUTC().instant();var fixture=new CutoverManagedFixture(clock);var binding=CutoverManagedFixture.B;
  var remote=new CutoverBoundedHttpFixture(binding,fixture.before,dir);var rejectedPath=new java.util.concurrent.atomic.AtomicReference<String>();
  var http=new PlannedCutoverTrueHttpFlowTest(){
   @Override com.fasterxml.jackson.databind.JsonNode post(String path,Object body)throws Exception{
    if(!path.equals(rejectedPath.get()))return super.post(path,body);
    var response=request(path,body);assertEquals(409,response.statusCode(),response.body());return NativeSheetsModel.JSON.readTree(response.body());
   }
  };
  var io=new PlannedCutoverRuntimeAssembly.IO(){public NativeSheetsModel.Book readLegacy(){return remote.readLegacy();}public NativeSheetsModel.Book readNative(){return remote.readNative();}public NativeSheetsModel.Book bootstrap(InitialCutoverAuthority a){return remote.bootstrap(a);}public NativeSheetsModel.Book migrate(HistoricalMigrationAuthority a){return remote.migrate(a);}public PlannedMaintenanceStartupFactory.Channels channels(){return remote.channels();}};
  var history=new PlannedCutoverRuntimeAssembly.History(fixture.historical.commitId(),fixture.historical.payloadHash(),fixture.approval,fixture.contribution);
  var settings=new PlannedCutoverRuntimeAssembly.Settings(PlannedCutoverTrueHttpFlowTest.ORIGIN,"synthetic-audience",Set.of("operator"),binding,"tea-fixed","srv-fixed","reviewed-source",Set.of("dep-old"),Set.of("cli"),"explicit-test-approval",history,new QuarterRetentionPolicy.Configuration(binding,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"explicit-test-policy"));
  var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var relay=new CurrentSignedObservationRelay(key.getPublic(),clock);
  var runtime=new PlannedCutoverRuntimeAssembly(settings,io,(token,audience,nonce)->{assertEquals(settings.audience(),audience);if(!token.equals("proof:"+nonce))throw new NativeSheetsModel.Rejected();return new com.bridge.room.GoogleCredentialVerifier.Principal("https://accounts.google.com","operator");},relay,clock);
  var properties=Map.of("server.port","0","BRIDGE_STATISTICS_ENABLED","true","BRIDGE_PRIVACY_CONTACT","test@example.com","BRIDGE_PRIVACY_RETENTION","Local fixture retention","BRIDGE_ERASURE_WORKFLOW_VERIFIED","true","BRIDGE_QUARTER_POLICY_VERIFIED","true");var previous=new HashMap<String,String>();properties.forEach((k,v)->{previous.put(k,System.getProperty(k));System.setProperty(k,v);});
  try(var context=PlannedCutoverLauncher.run(runtime)){
   http.port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();http.post("/initial-cutover/session",Map.of());
   http.post("/initial-cutover/prepare",Map.of("credential",http.proof("prepare"),"deploymentId","dep-current"));
   Map<String,Object> terminalRequest=null;String terminalPath=null;
   for(String phase:List.of("metadata","history")){
    var preview=http.observed("/initial-cutover/"+phase+"/preview",Map.of("credential",http.proof(phase+"-preview"),"stoppedSources",List.of("cli"),"noKnownUnresolved",true),key,clock);
    var execute=Map.<String,Object>of("credential",http.proof(phase+"-execute"),"digest",preview.path("digest").asText(),"approve",true);String path="/initial-cutover/"+phase+"/execute";
    if(failure.equals(phase)||failure.equals("expiry")){
     terminalRequest=execute;terminalPath=path;rejectedPath.set(path);
     if(failure.equals("expiry")){clock.now=clock.now.plusSeconds(301);http.post(path,execute);}else{remote.unknownNextWrite=true;http.observed(path,execute,key,clock);}
     break;
    }
    http.observed(path,execute,key,clock);
    if(failure.equals("checkpoint-mismatch")){
     // Out-of-band replacement still passes the complete native JSON parser, but no longer matches verified B1.
     org.springframework.test.util.ReflectionTestUtils.setField(remote,"actual",fixture.before.copy());clock.now=clock.now.plusSeconds(301);
     terminalPath="/initial-cutover/checkpoint";terminalRequest=Map.of("credential",http.proof("checkpoint"));rejectedPath.set(terminalPath);http.post(terminalPath,terminalRequest);break;
    }
   }
   int writes=remote.writes;assertEquals(failure.equals("expiry")?0:(failure.equals("metadata")||failure.equals("checkpoint-mismatch"))?1:2,writes);
   assertEquals(409,http.request(terminalPath,terminalRequest).statusCode());
   assertEquals(409,http.request("/initial-cutover/challenge",Map.of("purpose","activate-preview")).statusCode());
   assertEquals(409,http.request("/initial-cutover/challenge",Map.of("purpose","checkpoint")).statusCode());
   assertEquals(409,http.request("/initial-cutover/activate/execute",Map.of("credential","unissued","digest","0".repeat(64),"approve",true)).statusCode());
   assertEquals(writes,remote.writes);assertFalse(runtime.staged().enabled());assertFalse(runtime.gate().isOpen());
   assertEquals(503,http.request("/health/ready",null).statusCode());assertEquals(503,http.request("/api/rooms",Map.of()).statusCode());
   assertEquals(com.bridge.persistence.StatsStore.SaveStatus.REJECTED,context.getBean(com.bridge.persistence.StatsStore.class).submit(null));
   assertThrows(IllegalStateException.class,()->context.getBean(com.bridge.room.IdentityStore.class).findOrCreateAccount("issuer","subject"));
   // The remote side really applied the unknown batch; no proof or activation follows its visible bytes.
   if(!failure.equals("expiry")&&!failure.equals("checkpoint-mismatch"))assertFalse(remote.snapshot().metadata.isEmpty());
   if(failure.equals("history"))assertEquals(2,ManualQuarterMaintenance.decoded(remote.snapshot().tables()).values().iterator().next().revision());
  }finally{runtime.close();relay.close();previous.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
 }
}
