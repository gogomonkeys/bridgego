package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;import java.net.*;import java.net.http.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.security.*;
import com.fasterxml.jackson.databind.JsonNode;import static org.junit.jupiter.api.Assertions.*;
/** Real Spring HTTP and signed/session/authority chain; only external services are synthetic. */
class PlannedCutoverTrueHttpFlowTest {
 @TempDir Path dir;static final String ORIGIN="https://bridge.example";int port;
 final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();final Map<String,String> cookies=new ConcurrentHashMap<>();final Set<String> signed=new HashSet<>();
 HttpResponse<String> request(String path,Object body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(70)).header("Origin",ORIGIN).header("Content-Type","application/json");if(!cookies.isEmpty())b.header("Cookie",String.join("; ",cookies.values()));
  var r=client.send(body==null?b.GET().build():b.POST(HttpRequest.BodyPublishers.ofString(NativeSheetsModel.json(body))).build(),HttpResponse.BodyHandlers.ofString());
  for(String c:r.headers().allValues("set-cookie")){String pair=c.split(";",2)[0];cookies.put(pair.split("=",2)[0],pair);}return r;
 }
 JsonNode post(String path,Object body)throws Exception{var r=request(path,body);assertEquals(200,r.statusCode(),path+" "+r.body());return NativeSheetsModel.JSON.readTree(r.body());}
 String proof(String purpose)throws Exception{return "proof:"+post("/initial-cutover/challenge",Map.of("purpose",purpose)).path("nonce").asText();}
 JsonNode observed(String path,Object body,KeyPair key,Clock clock)throws Exception{
  var f=CompletableFuture.supplyAsync(()->{try{return post(path,body);}catch(Exception e){throw new CompletionException(e);}});long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
  while(!f.isDone()&&System.nanoTime()<deadline){var r=request("/initial-cutover/observation/challenge",Map.of());if(r.statusCode()==200){var c=NativeSheetsModel.JSON.readTree(r.body());String nonce=c.path("challenge").asText();if(signed.add(nonce)){
   var p=new TreeMap<String,Object>();for(String name:List.of("version","provenance","run","target","workspace","service","deployment","revision"))p.put(name,name.equals("version")?c.path(name).intValue():c.path(name).asText());p.put("challenge",nonce);p.put("capturedAt",clock.instant().toString());p.put("services",1);p.put("instances",1);p.put("autoDeploy",false);p.put("previews",false);p.put("deployments",List.of(Map.of("id",c.path("deployment").asText(),"revision",c.path("revision").asText(),"state","live"),Map.of("id","dep-old","revision","old","state","deactivated")));
   byte[] bytes=NativeCommitJournal.canonical(p).getBytes(java.nio.charset.StandardCharsets.UTF_8);Signature sig=Signature.getInstance("Ed25519");sig.initSign(key.getPrivate());sig.update(bytes);
   post("/initial-cutover/observation/submit",Map.of("payload",Base64.getEncoder().encodeToString(bytes),"signature",Base64.getEncoder().encodeToString(sig.sign())));
  }}Thread.sleep(100);}return f.get(10,TimeUnit.SECONDS);
 }
 @Test void managedHttpFullChainTwoRealMatchesAndQuarter()throws Exception{
  var clock=new CrossQuarterNativeSettlementTest.Time();clock.now=Clock.systemUTC().instant();var fixture=new CutoverManagedFixture(clock);var binding=CutoverManagedFixture.B;var remote=new CutoverBoundedHttpFixture(binding,fixture.before,dir);
  var io=new PlannedCutoverRuntimeAssembly.IO(){public NativeSheetsModel.Book readLegacy(){return remote.readLegacy();}public NativeSheetsModel.Book readNative(){return remote.readNative();}public NativeSheetsModel.Book bootstrap(InitialCutoverAuthority a){return remote.bootstrap(a);}public NativeSheetsModel.Book migrate(HistoricalMigrationAuthority a){return remote.migrate(a);}public PlannedMaintenanceStartupFactory.Channels channels(){return remote.channels();}};
  var history=new PlannedCutoverRuntimeAssembly.History(fixture.historical.commitId(),fixture.historical.payloadHash(),fixture.approval,fixture.contribution);
  var settings=new PlannedCutoverRuntimeAssembly.Settings(ORIGIN,"synthetic-audience",Set.of("operator"),binding,"tea-fixed","srv-fixed","reviewed-source",Set.of("dep-old"),Set.of("cli"),"explicit-test-approval",history,new QuarterRetentionPolicy.Configuration(binding,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"explicit-test-policy"));
  var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var relay=new CurrentSignedObservationRelay(key.getPublic(),clock);
  var runtime=new PlannedCutoverRuntimeAssembly(settings,io,(token,audience,nonce)->{assertEquals(settings.audience(),audience);if(!token.equals("proof:"+nonce))throw new NativeSheetsModel.Rejected();return new com.bridge.room.GoogleCredentialVerifier.Principal("https://accounts.google.com","operator");},relay,clock);
  var properties=Map.of("server.port","0","BRIDGE_STATISTICS_ENABLED","true","BRIDGE_PRIVACY_CONTACT","test@example.com","BRIDGE_PRIVACY_RETENTION","Local fixture retention","BRIDGE_ERASURE_WORKFLOW_VERIFIED","true","BRIDGE_QUARTER_POLICY_VERIFIED","true");var previous=new HashMap<String,String>();properties.forEach((k,v)->{previous.put(k,System.getProperty(k));System.setProperty(k,v);});
  try(var context=PlannedCutoverLauncher.run(runtime)){
   port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();assertEquals(settings.audience(),context.getEnvironment().getProperty("GOOGLE_CLIENT_ID"));assertEquals(ORIGIN,context.getEnvironment().getProperty("PUBLIC_ORIGIN"));
   var page=request("/initial-cutover/operator.html",null);assertEquals(200,page.statusCode());assertTrue(page.headers().firstValue("cache-control").orElse("").contains("no-store"));
   assertEquals(200,request("/health/route",null).statusCode());assertEquals(503,request("/health/ready",null).statusCode());
   post("/initial-cutover/session",Map.of());
   assertEquals(409,request("/initial-cutover/prepare",Map.of("credential","bad","deploymentId","dep-current")).statusCode());assertEquals(0,remote.writes);
   // Invalid proof does not authorize a write; use a fresh issued proof for the real prepare.
   post("/initial-cutover/prepare",Map.of("credential",proof("prepare"),"deploymentId","dep-current"));assertEquals(0,remote.writes);
   for(String phase:List.of("metadata","history")){
    var preview=observed("/initial-cutover/"+phase+"/preview",Map.of("credential",proof(phase+"-preview"),"stoppedSources",List.of("cli"),"noKnownUnresolved",true),key,clock);
    observed("/initial-cutover/"+phase+"/execute",Map.of("credential",proof(phase+"-execute"),"digest",preview.path("digest").asText(),"approve",true),key,clock);
    assertEquals(503,request("/health/ready",null).statusCode());
    clock.now=clock.now.plusSeconds(301);int priorWrites=remote.writes;
    assertEquals("CHECKPOINT_VERIFIED",post("/initial-cutover/checkpoint",Map.of("credential",proof("checkpoint"))).path("code").asText());assertEquals(priorWrites,remote.writes);
   }
   assertEquals(2,remote.writes);
   var activation=observed("/initial-cutover/activate/preview",Map.of("credential",proof("activate-preview"),"stoppedSources",List.of("cli"),"noKnownUnresolved",true),key,clock);
   var coordinator=context.getBean(com.bridge.persistence.QuarterMaintenanceCoordinator.class);org.springframework.test.util.ReflectionTestUtils.setField(coordinator,"clock",clock);
   // Hold only the local timer's monitor so the real background tick cannot race this observation.
   // Activation must not itself claim that business admission has passed its independent readback.
   synchronized(coordinator){
    var activated=observed("/initial-cutover/activate/execute",Map.of("credential",proof("activate-execute"),"digest",activation.path("digest").asText(),"approve",true),key,clock);
    assertEquals("ACTIVATE_VERIFIED",activated.path("code").asText());
    assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.STARTING,runtime.gate().state());
    assertFalse(runtime.staged().enabled());assertEquals(503,request("/health/ready",null).statusCode());
    context.getBean(com.bridge.persistence.QuarterMaintenanceScheduler.class).tick();
    assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.OPEN,runtime.gate().state());
    assertEquals(200,request("/health/ready",null).statusCode());
   }
   var service=context.getBean(com.bridge.room.RoomService.class);var store=context.getBean(com.bridge.persistence.StatsStore.class);var game=new com.bridge.room.NativeRoomFlowHarness(service,clock);
   for(String type:List.of("CASUAL","FORMAL")){game.start(store,runtime.gate(),type,1,fixture.account);game.finishDeal();game.assertTerminal();long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(runtime.gate().activeOperations()!=0&&System.nanoTime()<until)Thread.sleep(10);assertEquals(0,runtime.gate().activeOperations());game.leaveFinishedRoom();}
   var saved=ManualQuarterMaintenance.decoded(remote.snapshot().tables());assertEquals(3,saved.size());assertEquals(1,saved.values().stream().filter(e->e.payload().path("match").path("match_category").asText().equals("FORMAL")).count());
   var prior=LifetimeStatistics.readAccount(remote.snapshot().tables().get("accounts"),fixture.account);var contributions=LifetimeStatistics.fromEnvelopes(fixture.account,saved.values());
   int observations=signed.size();clock.now=QuarterlyRetention.currentQuarterStart(clock.instant()).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1);coordinator.tick();
   assertTrue(runtime.gate().isOpen());assertEquals(observations,signed.size());assertEquals(200,request("/health/ready",null).statusCode());assertTrue(ManualQuarterMaintenance.decoded(remote.snapshot().tables()).isEmpty());
   var lifetime=LifetimeStatistics.readAccount(remote.snapshot().tables().get("accounts"),fixture.account);assertEquals(prior.formal().plus(contributions.formal()),lifetime.formal());assertEquals(prior.casual().plus(contributions.casual()),lifetime.casual());
  }finally{runtime.close();relay.close();previous.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
 }
}
