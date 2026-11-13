package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class FixedEVerifiedArtifactTest {
 @Test void failedOrUnverifiedSnapshotNeverDownloadsOrRetries()throws Exception{
  var a=FixedEVerifiedArtifact.initial();assertNull(a.response("GET",FixedEVerifiedArtifact.PATH,null));var reads=new AtomicInteger();
  var synthetic=FixedERestartHttpSafetyTest.baseline();
  assertNotEquals(FixedERestartPreflight.INITIAL_TOKEN,JournalSessionAuthority.fullToken(synthetic));
  assertThrows(RuntimeException.class,()->a.verifyOnce(()->{reads.incrementAndGet();return synthetic;}));
  assertNull(a.response("GET",FixedEVerifiedArtifact.PATH,null));assertThrows(RuntimeException.class,()->a.verifyOnce(()->{reads.incrementAndGet();return synthetic;}));assertEquals(1,reads.get());
 }
 @Test void exactSyntheticReadProducesBoundedImmutableNormalizedArtifactOnlyAtExactGetPath()throws Exception{
  var b=FixedERestartHttpSafetyTest.baseline();var token=JournalSessionAuthority.fullToken(b);var a=FixedEVerifiedArtifact.isolatedFixture(token);a.verifyOnce(()->b);
  byte[] bytes=a.response("GET",FixedEVerifiedArtifact.PATH,null);assertNotNull(bytes);assertTrue(bytes.length<=FixedEVerifiedArtifact.MAX_BYTES);
  var json=NativeSheetsModel.JSON.readTree(bytes);assertEquals(token,json.path("fullToken").asText());assertEquals(FixedERestartClock.binding().spreadsheetId(),json.path("binding").path("spreadsheetId").asText());assertEquals("fixed-e-validated-normalized-projection-v1",json.path("format").asText());assertTrue(json.path("notice").asText().contains("not the original raw Google"));
  assertEquals(NativeCommitJournal.canonical(b.nativeValue()),NativeCommitJournal.canonical(json.path("nativeProjection")));
  bytes[0]=0;assertNotEquals(0,a.response("GET",FixedEVerifiedArtifact.PATH,null)[0]);
  for(String method:java.util.List.of("POST","PUT","HEAD"))assertNull(a.response(method,FixedEVerifiedArtifact.PATH,null));
  assertNull(a.response("GET",FixedEVerifiedArtifact.PATH,""));assertNull(a.response("GET",FixedEVerifiedArtifact.PATH,"target=anything"));assertNull(a.response("GET",FixedEVerifiedArtifact.PATH+"/",null));
 }
}
