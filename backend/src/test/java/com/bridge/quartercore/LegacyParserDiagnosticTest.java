package com.bridge.quartercore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;import java.nio.file.*;import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class LegacyParserDiagnosticTest {
 @TempDir Path directory;
 ObjectNode fixture()throws Exception{var f=new LegacyFullParserTest();f.directory=directory;return f.fixture();}
 void rejected(ObjectNode n,StrictNativeSheetsResource.LegacyStage expected)throws Exception{
  var stage=new AtomicReference<StrictNativeSheetsResource.LegacyStage>();
  var failure=assertThrows(RuntimeException.class,()->StrictNativeSheetsResource.decodeLegacyFull(new ByteArrayInputStream(json(n).getBytes(java.nio.charset.StandardCharsets.UTF_8)),System.nanoTime()+20_000_000_000L,LegacyFullParserTest.TARGET,stage::set));
  assertEquals(expected,stage.get());assertNull(failure.getCause());assertFalse(failure.toString().contains("PRIVATE"));
  var mapped=new PrepareDiagnosticFailure(PrepareDiagnosticFailure.Stage.valueOf("LEGACY_PARSE_"+stage.get().name()));assertNull(mapped.getCause());assertEquals(0,mapped.getStackTrace().length);
 }
 @Test void fixedRegionsNeverReturnRawFieldsTitlesValuesOrCauses()throws Exception{
  var root=fixture();root.put("PRIVATE_UNKNOWN_FIELD","PRIVATE_VALUE");rejected(root,StrictNativeSheetsResource.LegacyStage.ROOT);
  var props=fixture();((ObjectNode)props.path("properties")).put("PRIVATE_PROPERTY","PRIVATE_VALUE");rejected(props,StrictNativeSheetsResource.LegacyStage.ROOT_PROPERTIES);
  var comments=fixture();comments.putArray("comments").add("PRIVATE_COMMENT");rejected(comments,StrictNativeSheetsResource.LegacyStage.COMMENTS);
  var sheet=fixture();((ObjectNode)sheet.path("sheets").get(0).path("properties")).put("title","PRIVATE_TITLE");rejected(sheet,StrictNativeSheetsResource.LegacyStage.SHEET);
  var dim=fixture();((ObjectNode)dim.path("sheets").get(0).path("properties").path("gridProperties")).put("rowCount",1001);rejected(dim,StrictNativeSheetsResource.LegacyStage.DIMENSION);
  var cell=fixture();((ObjectNode)cell.path("sheets").get(0).path("data").get(0).path("rowData").get(0).path("values").get(0)).put("PRIVATE_CELL","PRIVATE_EMAIL@example.test");rejected(cell,StrictNativeSheetsResource.LegacyStage.CELL);
  var style=fixture();((ObjectNode)style.path("sheets").get(0).path("data").get(0).path("rowData").get(0).path("values").get(0)).put("note","PRIVATE_NOTE");rejected(style,StrictNativeSheetsResource.LegacyStage.STYLE);
  var control=fixture();control.set("developerMetadata",JSON.valueToTree(RecoveredFixture.book().nativeValue().get("developerMetadata")));rejected(control,StrictNativeSheetsResource.LegacyStage.CONTROL);
 }
 @Test void budgetAndBrokenReporterNeverExposeOriginalCauseOrChangeRejection()throws Exception{
  var stage=new AtomicReference<StrictNativeSheetsResource.LegacyStage>();var bytes=json(fixture()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  assertThrows(RuntimeException.class,()->StrictNativeSheetsResource.decodeLegacyFull(new ByteArrayInputStream(bytes),System.nanoTime()-1,LegacyFullParserTest.TARGET,stage::set));assertEquals(StrictNativeSheetsResource.LegacyStage.BUDGET,stage.get());
  var bad=fixture();bad.put("PRIVATE_KEY","PRIVATE_CONTENT");
  var e=assertThrows(RuntimeException.class,()->StrictNativeSheetsResource.decodeLegacyFull(new ByteArrayInputStream(json(bad).getBytes()),System.nanoTime()+20_000_000_000L,LegacyFullParserTest.TARGET,s->{throw new IllegalArgumentException("PRIVATE_REPORTER");}));
  assertNull(e.getCause());assertFalse(e.toString().contains("PRIVATE"));
  assertThrows(AssertionError.class,()->StrictNativeSheetsResource.decodeLegacyFull(new ByteArrayInputStream(json(bad).getBytes()),System.nanoTime()+20_000_000_000L,LegacyFullParserTest.TARGET,s->{throw new AssertionError("reporter error is not swallowed");}));
 }
 @Test void validLegacyReadIsUnchangedAndNoDiagnosticIsPublished()throws Exception{
  var original=fixture();var f=new LegacyFullParserTest();f.directory=directory;var expected=f.parse(original,LegacyFullParserTest.TARGET);
  var actual=StrictNativeSheetsResource.decodeLegacyFull(new ByteArrayInputStream(json(original).getBytes()),System.nanoTime()+20_000_000_000L,LegacyFullParserTest.TARGET,s->fail("valid read has no failure stage"));
  assertEquals(NativeCommitJournal.canonical(expected.nativeValue()),NativeCommitJournal.canonical(actual.nativeValue()));
 }
}
