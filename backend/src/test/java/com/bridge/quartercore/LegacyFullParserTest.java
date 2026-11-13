package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
class LegacyFullParserTest {
  @TempDir Path directory;
  static final ManualQuarterMaintenance.Binding TARGET=NativeCommitJournalTest.BINDING;
  ObjectNode fixture()throws Exception {
    Book b=RecoveredFixture.book();b.metadata.clear();Path p=directory.resolve("legacy.json");JournalRestartProbe.save(p,b);
    return (ObjectNode)JSON.readTree(Files.readString(p));
  }
  Book parse(ObjectNode n,ManualQuarterMaintenance.Binding t){return StrictNativeSheetsResource.decodeLegacyFull(new ByteArrayInputStream(json(n).getBytes(java.nio.charset.StandardCharsets.UTF_8)),System.nanoTime()+20_000_000_000L,t);}
  @Test void fullNativeLegacyCanPlanWithoutWeakeningRuntimeParser()throws Exception {
    ObjectNode n=fixture();Book b=parse(n,TARGET);
    assertTrue(b.metadata.isEmpty());
    assertEquals(CutoverAssessment.Status.LEGACY_BOOTSTRAP_READY,CutoverAssessment.inspect(TARGET,b,Clock.systemUTC()).status());
    JournalPlan p=LegacyToNativeCutoverPlan.prepare(TARGET,b,Clock.systemUTC());assertEquals(b.tables(),p.expected().tables());
    assertThrows(RuntimeException.class,()->StrictNativeSheetsResource.decodeFull(new ByteArrayInputStream(json(n).getBytes()),System.nanoTime()+20_000_000_000L,TARGET));
  }
  @Test void rejectsWrongTargetDatasetExistingMetadataAndUnknownFields()throws Exception {
    ObjectNode n=fixture();assertThrows(RuntimeException.class,()->parse(n,new ManualQuarterMaintenance.Binding("wrong-target",TARGET.datasetId())));
    assertThrows(RuntimeException.class,()->parse(n,new ManualQuarterMaintenance.Binding(TARGET.spreadsheetId(),java.util.UUID.randomUUID().toString())));
    ObjectNode unknown=n.deepCopy();unknown.put("unsupported",false);assertThrows(RuntimeException.class,()->parse(unknown,TARGET));
    ObjectNode metadata=n.deepCopy();metadata.set("developerMetadata",JSON.valueToTree(RecoveredFixture.book().nativeValue().get("developerMetadata")));
    assertThrows(RuntimeException.class,()->parse(metadata,TARGET));
  }
  @Test void rejectsOverflowTruncatedCoverageAndExpiredDeadline()throws Exception {
    ObjectNode n=fixture();ObjectNode overflow=n.deepCopy();
    ((ObjectNode)overflow.path("sheets").get(0).path("properties").path("gridProperties")).put("rowCount",1001);
    assertThrows(RuntimeException.class,()->parse(overflow,TARGET));
    ObjectNode text=n.deepCopy();((ObjectNode)text.path("properties")).put("title","x".repeat(20001));
    assertThrows(RuntimeException.class,()->parse(text,TARGET));
    ObjectNode coverage=n.deepCopy();((ObjectNode)coverage.path("sheets").get(0).path("data").get(0)).remove("rowMetadata");
    assertThrows(RuntimeException.class,()->parse(coverage,TARGET));
    assertThrows(RuntimeException.class,()->StrictNativeSheetsResource.decodeLegacyFull(new ByteArrayInputStream(json(n).getBytes()),System.nanoTime()-1,TARGET));
  }
}
