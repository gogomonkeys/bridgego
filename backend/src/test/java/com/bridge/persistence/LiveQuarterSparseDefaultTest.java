package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reconstructed fixture-before + retained observed after CellData fragments and full pending values.
 * There is no retained complete native before response; no live replay or external IO is claimed. */
class LiveQuarterSparseDefaultTest {
  static JsonNode resource(String name){try(var in=LiveQuarterSparseDefaultTest.class.getResourceAsStream(name)){
    return LiveQuarterWorkbookTest.JSON.readTree(Objects.requireNonNull(in));
  }catch(Exception e){throw new AssertionError(e);}}
  static ObjectNode defaultCellFormat(){return resource("sparse-default-observed-after-cells.json").get(1).path("data").get(0).path("rowData").get(0).path("values").get(0).path("effectiveFormat").deepCopy();}
  static void sparseFormats(ObjectNode root){
    LiveQuarterWorkbookTest.applyObservedDisplayMetadata(root);
    for(JsonNode sheet:root.path("sheets"))for(JsonNode row:sheet.path("data").get(0).path("rowData"))for(JsonNode c:row.path("values")){
      var cell=(ObjectNode)c;if(!cell.path("userEnteredValue").path("stringValue").asText().isEmpty())cell.set("effectiveFormat",defaultCellFormat());
    }
    LiveQuarterIssuerAutoLinkTest.apply(root);
  }
  static void overlayObservedAfterFragments(ObjectNode root){
    for(JsonNode observed:resource("sparse-default-observed-after-cells.json")){
      String tab=observed.path("title").asText();ArrayNode fullRows=(ArrayNode)LiveQuarterWorkbookTest.block(root,tab).get("rowData");
      for(JsonNode block:observed.path("data")){
        int start=block.path("startRow").asInt();
        if(!block.has("rowData")){assertTrue(fullRows.size()<=start,"Observed cleared commits tail must stay absent");continue;}
        for(int r=0;r<block.path("rowData").size();r++){
          var actual=block.path("rowData").get(r);assertTrue(fullRows.size()>start+r);
          fullRows.set(start+r,actual.deepCopy());
        }
      }
    }
  }
  @Test void exactObservedInheritedDefaultAndSparseAbsenceHaveSameMetadataSemantics(){
    var f=new LocalManualQuarterRehearsal.Fixture();var root=LiveQuarterWorkbookTest.fixtureTree(f);
    LiveQuarterIssuerAutoLinkTest.apply(root);var before=LiveQuarterIssuerAutoLinkTest.parse(root,f);
    sparseFormats(root);var after=LiveQuarterIssuerAutoLinkTest.parse(root,f);
    assertEquals(before.snapshot(),after.snapshot());assertEquals(before.metadataFingerprint(),after.metadataFingerprint());assertEquals(before.fingerprint(),after.fingerprint());
    // Explicit default black {} in root, absent black components in observed effective text style.
    assertFalse(defaultCellFormat().path("textFormat").has("foregroundColor"));
    var inherited=root.path("properties").path("defaultFormat");assertTrue(inherited.path("textFormat").has("foregroundColor"));
  }
  @Test void reconstructedFixedBeforeAndObservedPendingAfterRunBothRealCoreWrites(){
    var fixture=SyntheticCleanupManualQuarterTrial.defaultFixture(SyntheticCleanupExecutorTest.target());
    var fake=new SyntheticCleanupManualQuarterTrialTest.Wire();fake.fixture.tables=new TreeMap<>();
    fixture.tables().forEach((name,rows)->fake.fixture.tables.put(name,new ArrayList<>(rows)));
    fake.mutate=root->{sparseFormats(root);if(fake.posts==1)overlayObservedAfterFragments(root);};
    fake.afterWrite=count->{if(count==1)assertEquals(resource("sparse-default-observed-pending-values.json"),LiveQuarterWorkbookTest.JSON.valueToTree(fake.fixture.tables));};
    var trial=new SyntheticCleanupManualQuarterTrial(fixture,fake,Clock.fixed(SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH,ZoneOffset.UTC),()->true);
    var view=trial.preview();assertEquals(1,view.removeMatches());assertEquals(2,view.keepMatches());
    var before=LiveQuarterWorkbook.parse(LiveQuarterWorkbookTest.bytes(fake.last),fixture.binding(),Clock.fixed(SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH,ZoneOffset.UTC));
    assertEquals("SYNTHETIC_QUARTER_VERIFIED",trial.confirm(view.planId(),true));assertEquals(2,fake.posts);
    var after=LiveQuarterWorkbook.parse(LiveQuarterWorkbookTest.bytes(fake.last),fixture.binding(),Clock.fixed(SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH,ZoneOffset.UTC));
    assertEquals(before.metadataFingerprint(),after.metadataFingerprint());assertNotEquals(before.fingerprint(),after.fingerprint());
    for(String tab:ManualQuarterMaintenance.PRESERVED)assertEquals(fixture.tables().get(tab),fake.fixture.tables.get(tab));
    assertEquals(2,ManualQuarterMaintenance.decoded(fake.fixture.tables).size());assertTrue(new RetentionLedger(fake.fixture.tables.get("retention_ledger")).complete());
    for(var body:fake.bodies)for(var req:body.path("requests"))assertEquals("userEnteredValue",req.path("updateCells").path("fields").asText());
    assertEquals(LiveQuarterIssuerAutoLinkTest.observed(),LiveQuarterIssuerAutoLinkTest.issuer(fake.last));
  }
  @Test void nonInheritedAcceptedFormatStillFingerprintBoundBeforeAndAfterFirstWrite(){
    for(boolean afterFirst:List.of(false,true)){
      var fake=new LiveQuarterStorageTest.FakeTransport();fake.mutate=LiveQuarterSparseDefaultTest::sparseFormats;
      var run=LiveQuarterStorageTest.prepared(fake);
      java.util.function.Consumer<ObjectNode> changed=root->{sparseFormats(root);((ObjectNode)LiveQuarterWorkbookTest.cell(root,"accounts",1,1).path("effectiveFormat").path("padding")).put("top",2);};
      if(afterFirst)fake.afterWrite=n->fake.mutate=changed;else fake.mutate=changed;
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN,run.execute().outcome());assertEquals(afterFirst?1:0,fake.posts);
    }
  }
  @Test void normalizationNeverBypassesUnvalidatedFormatOrHiddenMetadata(){
    var f=new LocalManualQuarterRehearsal.Fixture();var base=LiveQuarterWorkbookTest.fixtureTree(f);sparseFormats(base);
    for(String attack:List.of("user","bold","link","hidden","unknown")){
      var root=base.deepCopy();var cell=LiveQuarterWorkbookTest.cell(root,"accounts",1,1);
      switch(attack){
        case "user"->cell.set("userEnteredFormat",defaultCellFormat());
        case "bold"->((ObjectNode)cell.path("effectiveFormat").path("textFormat")).put("bold",true);
        case "link"->((ObjectNode)cell.path("effectiveFormat").path("textFormat")).putObject("link").put("uri","https://evil.test");
        case "hidden"->((ObjectNode)LiveQuarterWorkbookTest.block(root,"accounts").path("rowMetadata").get(1)).put("hiddenByUser",true);
        case "unknown"->((ObjectNode)cell.path("effectiveFormat")).put("unknown","private");
      }
      assertThrows(IllegalStateException.class,()->LiveQuarterIssuerAutoLinkTest.parse(root,f));
    }
  }
  @Test void issuerAndRootDefaultsRemainBoundWhileOrdinaryCellsAreNormalized(){
    var f=new LocalManualQuarterRehearsal.Fixture();var root=LiveQuarterWorkbookTest.fixtureTree(f);sparseFormats(root);
    var before=LiveQuarterIssuerAutoLinkTest.parse(root,f);
    var withoutLink=root.deepCopy();LiveQuarterWorkbookTest.cell(withoutLink,"auth_identities",1,0).remove(List.of("userEnteredFormat","effectiveFormat","hyperlink"));
    assertNotEquals(before.metadataFingerprint(),LiveQuarterIssuerAutoLinkTest.parse(withoutLink,f).metadataFingerprint());
    var rootChange=root.deepCopy();((ObjectNode)rootChange.path("properties").path("defaultFormat").path("padding")).put("top",2);
    assertNotEquals(before.metadataFingerprint(),LiveQuarterIssuerAutoLinkTest.parse(rootChange,f).metadataFingerprint());
  }
}
