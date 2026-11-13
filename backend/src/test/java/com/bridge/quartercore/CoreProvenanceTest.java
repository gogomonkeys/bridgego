package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class CoreProvenanceTest {
  @Test void extractedSourcesMatchRecordedSourceExceptDocumentedReleaseAndLegacyBridge() throws Exception {
    var rows=NativeSheetsModel.JSON.readTree(getClass().getResourceAsStream("/quartercore/provenance.json"));
    assertEquals(31,rows.size());
    var directory=Path.of(System.getProperty("basedir","."),"src/main/java/com/bridge/quartercore");
    for(var row:rows){
      String name=row.path("file").asText();String source=Files.readString(directory.resolve(name));
      assertEquals(row.path("extractedSha256").asText(),FinalMatchEnvelope.hash(source),name);
      var extensions=NativeSheetsModel.JSON.readTree(getClass().getResourceAsStream("/quartercore/historical-admission-extension.json"));
      String originalSource=source;
      if(extensions.has(name)) for(var delta:extensions.get(name)) {
        String after=delta.path("after").asText(),before=delta.path("before").asText();
        assertFalse(after.isEmpty());assertEquals(originalSource.indexOf(after),originalSource.lastIndexOf(after),name+" unique exact delta");
        assertTrue(originalSource.contains(after));originalSource=originalSource.replace(after,before);
      }
      String normalized=source.replace("com.bridge.quartercore","com.bridge.persistence");
      assertEquals(row.path("normalizedExtractedSha256").asText(),FinalMatchEnvelope.hash(normalized),name);
      normalized=originalSource.replace("com.bridge.quartercore","com.bridge.persistence");
      if(name.equals("StrictNativeSheetsResource.java")) {
        String original=normalized.replaceFirst("(?s)  // BEGIN EXPLICIT LEGACY PREFLIGHT EXTENSION\\n.*?  // END EXPLICIT LEGACY PREFLIGHT EXTENSION\\n\\n", "");
        assertNotEquals(normalized,original);
        assertEquals(row.path("sourceSha256").asText(),FinalMatchEnvelope.hash(original),name+" original runtime unchanged");
      } else if(name.equals("NarrowQuarterPoc.java")) {
        String original=normalized.replaceFirst("(?s)    // BEGIN EXPLICIT REGISTERED SETTLEMENT DRAIN EXTENSION\\n.*?    // END EXPLICIT REGISTERED SETTLEMENT DRAIN EXTENSION\\n", "");
        assertNotEquals(normalized,original);
        assertEquals(row.path("sourceSha256").asText(),FinalMatchEnvelope.hash(original),name+" original runtime unchanged");
      } else if(!name.equals("MaintenanceRelease.java"))assertEquals(row.path("sourceSha256").asText(),FinalMatchEnvelope.hash(normalized),name);
      assertFalse(source.contains("@Component"),name);assertFalse(source.contains("@Configuration"),name);
      assertFalse(source.contains("public static void main("),name);
    }
  }
}
