package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.util.*;import java.time.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.net.*;import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class SyntheticQuarterRunnerLauncherTest {
 @Test void unprovisionedMainRejectsBeforeAnyHttpOrCredential() {assertThrows(IllegalStateException.class,()->SyntheticQuarterRunnerMain.requireProvisioned(null));for(String mode:Arrays.asList(null,"","game","planned-cutover","SYNTHETIC-QUARTER-RUNNER"))assertThrows(IllegalStateException.class,()->SyntheticQuarterRunnerMain.requireMode(mode));assertDoesNotThrow(()->SyntheticQuarterRunnerMain.requireMode("synthetic-quarter-runner"));}
 @Test void unprovisionedFreshJvmExitsWithoutOpeningConfiguredPort()throws Exception {
  try(var reservation=new java.net.ServerSocket(0)){
   int port=reservation.getLocalPort(); // Occupied port would make any attempted HTTP bind fail differently.
   var pb=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),SyntheticQuarterRunnerMain.class.getName()).redirectErrorStream(true);
   pb.environment().remove("BRIDGE_RUNTIME_MODE");pb.environment().put("PORT",Integer.toString(port));pb.environment().put("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON","POISON_MUST_NOT_READ");
   var process=pb.start();try{assertTrue(process.waitFor(10,TimeUnit.SECONDS));String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertNotEquals(0,process.exitValue());assertTrue(output.contains("SYNTHETIC_RUNNER_DISABLED"));assertFalse(output.contains("POISON_MUST_NOT_READ"));assertFalse(output.contains("BindException"));}finally{process.destroyForcibly();}
  }
 }
 @Test void exactPrivateApprovalOnlyOnceNoPublicGameRoutes()throws Exception {
  var log=new LinkedBlockingQueue<String>();var runs=new AtomicInteger();var driver=new SyntheticQuarterRunnerLauncher.Driver(){public void arm(){}public void run(){runs.incrementAndGet();}public String verifiedToken(){return "a".repeat(64);}public void close(){}};
  try(var l=new SyntheticQuarterRunnerLauncher(Map.of("fixture","fixed"),driver,Clock.systemUTC(),log::add)){
   l.health(0);l.start();String preview=log.poll(3,TimeUnit.SECONDS);assertNotNull(preview);var n=NativeSheetsModel.JSON.readTree(preview);String nonce=n.path("privateNonce").asText(),digest=n.path("previewDigest").asText();var client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+l.port();
   for(String path:List.of("/api/rooms","/auth/google","/ws","/profile","/execute?target=other")){var r=client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(404,r.statusCode());assertFalse(r.body().contains(nonce));}
   var req=HttpRequest.newBuilder(URI.create(base+"/execute")).header("Content-Type","application/json").header("Authorization","Bearer "+nonce).header("Origin","https://untrusted.invalid").POST(HttpRequest.BodyPublishers.ofString("{\"previewDigest\":\""+digest+"\"}"));assertEquals(403,client.send(req.build(),HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(0,runs.get());
   for(String malformed:List.of("{\"previewDigest\":\""+digest+"\",\"previewDigest\":\""+digest+"\"}","{\"previewDigest\":\""+digest+"\"} {}")){
    var invalid=HttpRequest.newBuilder(URI.create(base+"/execute")).header("Content-Type","application/json").header("Authorization","Bearer "+nonce).POST(HttpRequest.BodyPublishers.ofString(malformed)).build();assertEquals(403,client.send(invalid,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(0,runs.get());
   }
   assertFalse(l.approve("wrong",digest));assertEquals(0,runs.get());assertTrue(l.approve(nonce,digest));assertFalse(l.approve(nonce,digest));assertNotNull(log.poll(3,TimeUnit.SECONDS));assertEquals(1,runs.get());assertEquals(SyntheticQuarterRunnerLauncher.Status.VERIFIED,l.status());
  }
 }
 @Test void httpApprovalRunsActualMainDriverCoreAndClosesVerified()throws Exception {
  var memory=JournaledNativeWriterTest.initial();var release=SyntheticQuarterRunnerTest.release(memory);var runner=SyntheticQuarterRunnerTest.runner(release,memory,()->true);var logs=new LinkedBlockingQueue<String>();
  try(var l=new SyntheticQuarterRunnerLauncher(Map.of("fixtureHash",release.fixtureHash(),"baseline",release.baselineToken()),SyntheticQuarterRunnerMain.driver(runner,release),Clock.systemUTC(),logs::add)){
   int before=memory.writes;l.health(0);l.start();var p=NativeSheetsModel.JSON.readTree(logs.poll(3,TimeUnit.SECONDS));assertEquals(before,memory.writes);
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+l.port()+"/execute")).header("Content-Type","application/json").header("Authorization","Bearer "+p.path("privateNonce").asText()).POST(HttpRequest.BodyPublishers.ofString("{\"previewDigest\":\""+p.path("previewDigest").asText()+"\"}")).build();
   var client=HttpClient.newHttpClient();assertEquals(202,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());String result=logs.poll(15,TimeUnit.SECONDS);assertNotNull(result);assertEquals(SyntheticQuarterRunnerLauncher.Status.VERIFIED,l.status(),result);assertEquals(SyntheticQuarterRunner.Status.VERIFIED,runner.status());assertEquals(JournalSessionAuthority.fullToken(memory.b),runner.verifiedClosedToken());assertEquals("CLOSED",NarrowQuarterPoc.root(new ArrayList<>(memory.b.metadata.values())).state());
   int writes=memory.writes;assertEquals(409,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(writes,memory.writes);
  }
 }
 @Test void closedLauncherCannotBecomeVerifiedFromLateDriverReturn()throws Exception {
  var logs=new LinkedBlockingQueue<String>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new AtomicInteger();
  var driver=new SyntheticQuarterRunnerLauncher.Driver(){public void arm(){}public void run(){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}public String verifiedToken(){return "a".repeat(64);}public void close(){closed.incrementAndGet();release.countDown();}};
  var l=new SyntheticQuarterRunnerLauncher(Map.of("fixed",true),driver,Clock.systemUTC(),logs::add);l.start();var p=NativeSheetsModel.JSON.readTree(logs.poll(3,TimeUnit.SECONDS));assertTrue(l.approve(p.path("privateNonce").asText(),p.path("previewDigest").asText()));assertTrue(entered.await(3,TimeUnit.SECONDS));l.close();Thread.sleep(50);assertEquals(SyntheticQuarterRunnerLauncher.Status.STOPPED,l.status());assertEquals(1,closed.get());assertNull(logs.poll());
 }
 @Test void unknownNeverRetriesAndRealExpiryRejects()throws Exception {
  var log=new LinkedBlockingQueue<String>();var runs=new AtomicInteger();var clock=new Clock(){Instant now=Instant.parse("2026-11-13T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}};
  var driver=new SyntheticQuarterRunnerLauncher.Driver(){public void arm(){}public void run(){runs.incrementAndGet();throw new IllegalStateException();}public String verifiedToken(){throw new IllegalStateException();}public void close(){}};
  try(var l=new SyntheticQuarterRunnerLauncher(Map.of("fixed",true),driver,clock,log::add)){l.start();var n=NativeSheetsModel.JSON.readTree(log.poll(3,TimeUnit.SECONDS));clock.now=clock.now.plusSeconds(301);assertFalse(l.approve(n.path("privateNonce").asText(),n.path("previewDigest").asText()));assertEquals(0,runs.get());}
  try(var l=new SyntheticQuarterRunnerLauncher(Map.of("fixed",true),driver,Clock.systemUTC(),log::add)){l.start();var n=NativeSheetsModel.JSON.readTree(log.poll(3,TimeUnit.SECONDS));String nonce=n.path("privateNonce").asText(),digest=n.path("previewDigest").asText();assertTrue(l.approve(nonce,digest));assertNotNull(log.poll(3,TimeUnit.SECONDS));assertFalse(l.approve(nonce,digest));assertEquals(1,runs.get());assertEquals(SyntheticQuarterRunnerLauncher.Status.REVIEW,l.status());}
 }
}
