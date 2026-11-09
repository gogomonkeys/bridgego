package com.bridge.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native Google CellData shape retained locally; all workbook values/IO below are synthetic. */
class LiveQuarterIssuerAutoLinkTest {
  static ObjectNode observed() {
    try(var in=LiveQuarterIssuerAutoLinkTest.class.getResourceAsStream("issuer-autolink-observed-cell.json")){
      return (ObjectNode)LiveQuarterWorkbookTest.JSON.readTree(Objects.requireNonNull(in));
    }catch(Exception failure){throw new AssertionError(failure);}
  }
  static ObjectNode issuer(ObjectNode root){return LiveQuarterWorkbookTest.cell(root,"auth_identities",1,0);}
  static void apply(ObjectNode root){
    LiveQuarterWorkbookTest.applyObservedDisplayMetadata(root);
    for(int row=1;row<=2;row++)LiveQuarterWorkbookTest.cell(root,"auth_identities",row,0).setAll(observed());
  }
  static LiveQuarterWorkbook.Workbook parse(ObjectNode root,LocalManualQuarterRehearsal.Fixture f){return LiveQuarterWorkbook.parse(LiveQuarterWorkbookTest.bytes(root),f.binding,f.clock);}
  @Test void exactNativeIssuerAutoLinkAcceptedWithoutChangingValuesAndRemainsFingerprintBound(){
    var f=new LocalManualQuarterRehearsal.Fixture();var root=LiveQuarterWorkbookTest.fixtureTree(f);
    LiveQuarterWorkbookTest.applyObservedDisplayMetadata(root);var before=parse(root,f);apply(root);var after=parse(root,f);
    assertEquals(before.snapshot(),after.snapshot());assertEquals(f.tables,after.snapshot().tables());
    assertNotEquals(before.metadataFingerprint(),after.metadataFingerprint());assertNotEquals(before.fingerprint(),after.fingerprint());
    assertEquals(after,parse(root.deepCopy(),f));
  }
  @Test void exactIssuerPositionUsesAbsoluteGridBlockOffsets(){
    var f=new LocalManualQuarterRehearsal.Fixture();var root=LiveQuarterWorkbookTest.fixtureTree(f);apply(root);
    var block=LiveQuarterWorkbookTest.block(root,"auth_identities");var rows=(ArrayNode)block.get("rowData");
    ArrayNode tail=LiveQuarterWorkbookTest.JSON.createArrayNode();for(int i=1;i<rows.size();i++)tail.add(rows.get(i));
    while(rows.size()>1)rows.remove(1);
    var extra=((ArrayNode)LiveQuarterWorkbookTest.sheet(root,"auth_identities").get("data")).addObject();extra.put("startRow",1);extra.put("startColumn",0);extra.set("rowData",tail);
    assertEquals(f.tables,parse(root,f).snapshot().tables());
    extra.put("startColumn",1);assertThrows(IllegalStateException.class,()->parse(root,f));
  }
  @Test void rejectsWrongLocationValueUriAndUnobservedFormatMetadata(){
    var f=new LocalManualQuarterRehearsal.Fixture();var base=LiveQuarterWorkbookTest.fixtureTree(f);apply(base);
    var changes=new ArrayList<Consumer<ObjectNode>>();
    for(String uri:List.of("http://accounts.google.com","https://accounts.google.com/","https://accounts.google.com.evil.test","javascript:alert(1)","https://evil.test")){
      changes.add(r->((ObjectNode)issuer(r).path("userEnteredFormat").path("textFormat").path("link")).put("uri",uri));
      changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat").path("link")).put("uri",uri));
      changes.add(r->issuer(r).put("hyperlink",uri));
    }
    changes.add(r->LiveQuarterWorkbookTest.cell(r,"auth_identities",0,0).setAll(observed()));
    changes.add(r->LiveQuarterWorkbookTest.cell(r,"auth_identities",1,1).setAll(observed()));
    changes.add(r->LiveQuarterWorkbookTest.cell(r,"accounts",1,0).setAll(observed()));
    changes.add(r->{for(String k:List.of("userEnteredValue","effectiveValue"))((ObjectNode)issuer(r).get(k)).put("stringValue","other");issuer(r).put("formattedValue","other");});
    changes.add(r->((ObjectNode)issuer(r).get("userEnteredFormat")).put("horizontalAlignment","LEFT"));
    changes.add(r->((ObjectNode)issuer(r).path("userEnteredFormat").path("textFormat")).put("underline",true));
    changes.add(r->((ObjectNode)issuer(r).path("userEnteredFormat").path("textFormat").path("link")).put("unknown","value"));
    changes.add(r->((ObjectNode)issuer(r).get("effectiveFormat")).put("hyperlinkDisplayType","PLAIN_TEXT"));
    changes.add(r->((ObjectNode)issuer(r).get("effectiveFormat")).put("unknown","value"));
    changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat")).put("underline",false));
    changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat")).put("bold",true));
    changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat")).put("fontFamily","Arial"));
    changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat").path("foregroundColor")).put("red",0.2));
    changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat").path("foregroundColor")).put("alpha",1));
    changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat")).putObject("foregroundColorStyle").put("themeColor","LINK"));
    changes.add(r->((ObjectNode)issuer(r).path("effectiveFormat").path("textFormat").path("foregroundColorStyle").path("rgbColor")).put("blue",0.5));
    changes.add(r->issuer(r).putArray("textFormatRuns").addObject().put("startIndex",0));
    changes.add(r->issuer(r).put("note","hidden"));
    changes.add(r->issuer(r).putObject("userEnteredValue").put("formulaValue","=HYPERLINK(\"https://accounts.google.com\")"));
    changes.add(r->issuer(r).remove("effectiveFormat"));
    changes.add(r->issuer(r).remove("userEnteredFormat"));
    changes.add(r->((ObjectNode)r.get("properties")).remove("spreadsheetTheme"));
    for(var change:changes){var copy=base.deepCopy();change.accept(copy);var e=assertThrows(IllegalStateException.class,()->parse(copy,f));assertEquals("Quarter workbook response rejected",e.getMessage());assertNull(e.getCause());}
  }
  @Test void bothRealCoreValueOnlyWritesPreserveIssuerLinkMetadata(){
    var fake=new LiveQuarterStorageTest.FakeTransport();fake.mutate=LiveQuarterIssuerAutoLinkTest::apply;
    var run=LiveQuarterStorageTest.prepared(fake);var metadata=parse(fake.last,fake.fixture).metadataFingerprint();
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,run.execute().outcome());assertEquals(2,fake.posts);fake.fixture.verify();
    assertEquals(metadata,parse(fake.last,fake.fixture).metadataFingerprint());
    for(var body:fake.bodies)for(var request:body.path("requests")){
      assertEquals("userEnteredValue",request.path("updateCells").path("fields").asText());
      assertFalse(request.toString().contains("userEnteredFormat"));assertFalse(request.toString().contains("effectiveFormat"));
    }
    assertEquals(observed(),issuer(fake.last));
  }
  @Test void linkMetadataRemovalOrAdditionAfterPreviewNeverGetsNormalizedAway(){
    for(boolean remove:List.of(false,true))for(boolean afterFirst:List.of(false,true)){
      var fake=new LiveQuarterStorageTest.FakeTransport();
      fake.mutate=remove?LiveQuarterIssuerAutoLinkTest::apply:LiveQuarterWorkbookTest::applyObservedDisplayMetadata;
      var run=LiveQuarterStorageTest.prepared(fake);
      Consumer<ObjectNode> changed=remove?LiveQuarterWorkbookTest::applyObservedDisplayMetadata:LiveQuarterIssuerAutoLinkTest::apply;
      if(afterFirst)fake.afterWrite=n->fake.mutate=changed;else fake.mutate=changed;
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN,run.execute().outcome());assertEquals(afterFirst?1:0,fake.posts);
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN,run.execute().outcome());assertEquals(afterFirst?1:0,fake.posts);
    }
  }
}
