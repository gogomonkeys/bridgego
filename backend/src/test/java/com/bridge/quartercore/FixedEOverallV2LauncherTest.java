package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Real fixed-E five-phase driver behind the maintenance HTTP boundary; no remote transport. */
class FixedEOverallV2LauncherTest {
 @Test void explicitOnceExecutionExposesOnlyVerifiedExactArtifact() throws Exception {
  var memory=new JournaledNativeWriterTest.Memory(FixedEOverallV2Runner.baseline());
  var clock=new FixedERestartClockSafetyTest.Time();
  var driver=FixedEOverallV2RunnerTest.runner(memory,clock,q->{});
  var logs=new LinkedBlockingQueue<String>();
  try(var host=new SyntheticQuarterRunnerLauncher(Map.of("purpose","fixed-e-overall-v2"),driver,clock,logs::add)) {
   host.health(0);
   var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
   String base="http://127.0.0.1:"+host.port();
   assertUnavailable(client,base,"GET","/fixed-e/verified-baseline.json");
   host.start();
   String approval=logs.poll(10,TimeUnit.SECONDS);assertNotNull(approval);
   var preview=parse(approval);assertEquals("SYNTHETIC_RUNNER_PRIVATE_APPROVAL",preview.path("event").asText());
   assertEquals(SyntheticQuarterRunnerLauncher.Status.WAITING_APPROVAL,host.status());
   assertEquals(0,memory.writes);
   assertUnavailable(client,base,"GET","/fixed-e/verified-baseline.json");
   var execute=HttpRequest.newBuilder(URI.create(base+"/execute")).timeout(Duration.ofSeconds(10))
       .header("Authorization","Bearer "+preview.path("privateNonce").asText())
       .header("Content-Type","application/json")
       .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("previewDigest",preview.path("previewDigest").asText())))).build();
   assertEquals(202,client.send(execute,HttpResponse.BodyHandlers.ofString()).statusCode());
   String completion=logs.poll(20,TimeUnit.SECONDS);assertNotNull(completion);
   assertEquals("SYNTHETIC_RUNNER_VERIFIED",parse(completion).path("event").asText());
   assertEquals(SyntheticQuarterRunnerLauncher.Status.VERIFIED,host.status());assertEquals(5,memory.writes);
   var artifact=client.send(HttpRequest.newBuilder(URI.create(base+"/fixed-e/verified-baseline.json")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,artifact.statusCode());assertEquals("no-store",artifact.headers().firstValue("Cache-Control").orElseThrow());
   assertEquals(driver.verifiedToken(),parse(artifact.body()).path("fullToken").asText());
   assertEquals(parse(new String(driver.verifiedArtifact(),java.nio.charset.StandardCharsets.UTF_8)),parse(artifact.body()));
   assertUnavailable(client,base,"GET","/fixed-e/verified-baseline.json?download=1");
   assertUnavailable(client,base,"POST","/fixed-e/verified-baseline.json");
   assertEquals(409,client.send(execute,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(5,memory.writes);
  }
 }
 private static void assertUnavailable(HttpClient client,String base,String method,String path)throws Exception {
  var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(404,response.statusCode());assertFalse(response.body().contains("nativeProjection"));assertFalse(response.body().contains("fullToken"));
 }
}
