package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Entirely fabricated local 8x1000x26 resources. No production content or live requests. */
class ProductionReadOnlyWorkbookTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String ID="local_synthetic_production_shape_12345",TITLE="Local synthetic production shape";
  static final String DATASET="00000000-0000-0000-0000-000000000001",ACCOUNT="00000000-0000-0000-0000-000000000002";
  static final List<String> TABS=ProductionReadOnlyWorkbook.TABS;
  static final ProductionReadOnlyTarget TARGET=new ProductionReadOnlyTarget(ID,TITLE,DATASET,
      java.util.stream.IntStream.range(0,8).mapToObj(i->new ProductionReadOnlyTarget.Sheet(TABS.get(i),100+i)).toList());
  static byte[] bytes(JsonNode n){try{return JSON.writeValueAsBytes(n);}catch(Exception e){throw new AssertionError(e);}}
  static ObjectNode fixture() {
    ObjectNode root=JSON.createObjectNode();root.put("spreadsheetId",ID);root.put("spreadsheetUrl","https://docs.google.com/spreadsheets/d/"+ID+"/edit");
    root.put("commentsViewMode","COMMENTS_VIEW_MODE_INCLUDED");root.putObject("properties").put("title",TITLE).put("locale","en_US").put("timeZone","Asia/Taipei");
    ArrayNode sheets=root.putArray("sheets");
    for(int i=0;i<8;i++) {
      String title=TABS.get(i);ObjectNode sheet=sheets.addObject(),p=sheet.putObject("properties");
      p.put("sheetId",100+i).put("title",title).put("index",i).put("sheetType","GRID");
      p.putObject("gridProperties").put("rowCount",1000).put("columnCount",26);
      ObjectNode data=sheet.putArray("data").addObject();ArrayNode rows=data.putArray("rowData"),rm=data.putArray("rowMetadata"),cm=data.putArray("columnMetadata");
      for(int r=0;r<1000;r++)rm.addObject().put("pixelSize",21);for(int c=0;c<26;c++)cm.addObject().put("pixelSize",100);
      addRow(rows,ProductionReadOnlyWorkbook.HEADERS.get(title));
      switch(title) {
        case "dataset"->addRow(rows,List.of("bridgego-managed",DATASET));
        case "accounts"->addRow(rows,List.of(ACCOUNT,"2","Synthetic Person","synthetic@example.invalid"));
        case "auth_identities"->addRow(rows,List.of("https://accounts.google.com","SYNTHETIC_SUBJECT",ACCOUNT,"1"));
        case "commits"->addRow(rows,List.of("synthetic-commit","a".repeat(64),"1","synthetic-match","1"));
        case "envelopes"->addRow(rows,List.of("synthetic-commit","a".repeat(64),"0","1","SYNTHETIC_NOT_A_REAL_PAYLOAD"));
        case "erasure_ledger"->addRow(rows,List.of("META","erasure-ledger-v1","1"));
      }
    }
    number(cell(root,"commits",1,2),"1");number(cell(root,"commits",1,4),"1");
    number(cell(root,"envelopes",1,2),"0");number(cell(root,"envelopes",1,3),"1");return root;
  }
  static void addRow(ArrayNode rows,List<String> values){ArrayNode cells=rows.addObject().putArray("values");for(String v:values)string(cells.addObject(),v);}
  static void string(ObjectNode cell,String v){cell.putObject("userEnteredValue").put("stringValue",v);cell.putObject("effectiveValue").put("stringValue",v);cell.put("formattedValue",v);}
  static void number(ObjectNode cell,String n){cell.putObject("userEnteredValue").put("numberValue",new BigDecimal(n));cell.putObject("effectiveValue").put("numberValue",new BigDecimal(n));cell.put("formattedValue",new BigDecimal(n).toBigIntegerExact().toString());}
  static ObjectNode sheet(ObjectNode root,String title){for(JsonNode s:root.path("sheets"))if(title.equals(s.path("properties").path("title").asText()))return (ObjectNode)s;throw new AssertionError();}
  static ObjectNode block(ObjectNode root,String title){return (ObjectNode)sheet(root,title).path("data").get(0);}
  static ObjectNode cell(ObjectNode root,String title,int row,int col){ArrayNode rows=(ArrayNode)block(root,title).get("rowData");while(rows.size()<=row)rows.addObject().putArray("values");ArrayNode values=(ArrayNode)rows.get(row).get("values");while(values.size()<=col)values.addObject();return (ObjectNode)values.get(col);}
  static ProductionReadOnlyWorkbook.Report inspect(ObjectNode n){return ProductionReadOnlyWorkbook.inspect(bytes(n),TARGET);}
  static void reject(Consumer<ObjectNode> change){var n=fixture();change.accept(n);var e=assertThrows(IllegalStateException.class,()->inspect(n));assertEquals("Production read-only resource rejected",e.getMessage());assertNull(e.getCause());}

  @Test void exactEightTabThousandBy26ShapeAndFourNativeNumbersPassStructuralOnly(){
    var n=fixture();byte[] original=bytes(n);var report=inspect(n);
    assertEquals(ProductionReadOnlyWorkbook.Status.STRUCTURE_OBSERVED_LAYOUT_UNAPPROVED,report.status());
    assertEquals(8,report.dataRows().size());assertEquals(1,report.dataRows().get("commits"));
    var verified=ProductionReadOnlyWorkbook.verify(original,TARGET,report.layoutHash());assertEquals(ProductionReadOnlyWorkbook.Status.STRUCTURE_COMPATIBLE_ONLY,verified.status());
    assertFalse(verified.mutationPermitted());assertFalse(verified.businessSemanticsVerified());assertFalse(verified.writerExclusionVerified());assertArrayEquals(original,bytes(n));
    // Deliberately non-business-valid payload cannot be mislabeled verified by this structural-only parser.
    assertFalse(verified.toString().contains("SYNTHETIC_SUBJECT"));
  }
  @Test void numericAndStringEquivalentCellsRetainDifferentNativeTypeFingerprint(){var n=fixture();var a=inspect(n);string(cell(n,"commits",1,2),"1");var b=inspect(n);assertEquals(a.normalizedDataHash(),b.normalizedDataHash());assertNotEquals(a.literalTypesHash(),b.literalTypesHash());assertNotEquals(a.nativeSha256(),b.nativeSha256());}
  @Test void exactDecimalIntegralNumbersAcceptedWithoutTruncation(){var n=fixture();number(cell(n,"commits",1,2),"1.000");assertDoesNotThrow(()->inspect(n));number(cell(n,"commits",1,4),"2147483647");assertDoesNotThrow(()->inspect(n));}
  @Test void fractionsLargeBoundsAndRoundingAttackRejected(){
    for(String v:List.of("1.5","1.00000000000000001","2147483648","9007199254740993","-1"))reject(n->{var c=cell(n,"commits",1,4);c.putObject("userEnteredValue").put("numberValue",new BigDecimal(v));c.putObject("effectiveValue").put("numberValue",new BigDecimal(v));c.put("formattedValue","1");});
    reject(n->number(cell(n,"commits",1,2),"0"));reject(n->number(cell(n,"envelopes",1,3),"10001"));reject(n->number(cell(n,"envelopes",1,2),"1"));
  }
  @Test void nativeTypeEffectiveAndFormattedAgreementRequired(){
    reject(n->((ObjectNode)cell(n,"commits",1,2).get("effectiveValue")).put("numberValue",2));
    reject(n->cell(n,"commits",1,2).putObject("effectiveValue").put("stringValue","1"));
    reject(n->cell(n,"commits",1,2).put("formattedValue","1.0"));
    reject(n->cell(n,"commits",1,2).put("formattedValue","1,000"));
    reject(n->cell(n,"commits",1,2).putObject("userEnteredValue").put("numberValue",1).put("stringValue","1"));
    reject(n->string(cell(n,"commits",1,2),"01"));reject(n->string(cell(n,"commits",1,2),"1.0"));
  }
  @Test void numberOnlyPermittedAtFourDataCoordinates(){reject(n->number(cell(n,"accounts",1,1),"2"));reject(n->number(cell(n,"commits",0,2),"1"));reject(n->number(cell(n,"commits",1,1),"1"));reject(n->number(cell(n,"envelopes",1,25),"1"));reject(n->number(cell(n,"erasure_ledger",1,2),"1"));}
  @Test void all26ColumnsAndAll1000RowsInspected(){reject(n->string(cell(n,"commits",1,25),"hidden"));reject(n->string(cell(n,"commits",999,8),"hidden"));reject(n->cell(n,"commits",999,25).put("note","hidden"));reject(n->cell(n,"dataset",999,25).putObject("userEnteredValue").put("formulaValue","=1"));reject(n->cell(n,"dataset",999,25).putObject("userEnteredFormat").put("wrapStrategy","CLIP"));}
  @Test void datasetMarkerExactImmutableShape(){reject(n->string(cell(n,"dataset",1,1),ACCOUNT));reject(n->string(cell(n,"dataset",1,0),"bridgego-restricted-test"));reject(n->string(cell(n,"dataset",2,0),"extra"));reject(n->string(cell(n,"dataset",0,2),"extra"));}
  @Test void pinIdentityDimensionsOrderAndSheetIds(){reject(n->n.put("spreadsheetId","other_target_12345"));reject(n->((ObjectNode)n.get("properties")).put("title","Other"));reject(n->((ObjectNode)sheet(n,"commits").get("properties")).put("sheetId",888));reject(n->((ObjectNode)sheet(n,"commits").get("properties")).put("index",1));reject(n->((ObjectNode)sheet(n,"commits").path("properties").path("gridProperties")).put("columnCount",8));reject(n->((ObjectNode)sheet(n,"commits").path("properties").path("gridProperties")).put("rowCount",999));reject(n->((ArrayNode)n.get("sheets")).remove(0));}
  @Test void metadataNamespacesAndUnsupportedFeaturesRejected(){reject(n->n.putArray("developerMetadata").addObject().put("metadataKey","bg-writer-v2:1"));reject(n->n.putArray("comments").addObject().put("content","private"));reject(n->n.put("commentsViewMode","COMMENTS_VIEW_MODE_UNSPECIFIED"));reject(n->sheet(n,"accounts").putArray("charts").addObject());reject(n->cell(n,"accounts",1,0).put("unknown",true));reject(n->((ObjectNode)sheet(n,"accounts").get("properties")).put("hidden",true));}
  @Test void completeDimensionsAndNoDuplicateCoverage(){reject(n->((ArrayNode)block(n,"accounts").get("rowMetadata")).remove(999));reject(n->((ArrayNode)block(n,"accounts").get("columnMetadata")).remove(25));reject(n->((ArrayNode)sheet(n,"accounts").get("data")).add(block(n,"accounts").deepCopy()));reject(n->block(n,"accounts").put("startRow",1));}
  @Test void formulasBooleanErrorsAndUnequalStringsRejected(){reject(n->cell(n,"accounts",1,0).putObject("userEnteredValue").put("formulaValue","=1"));reject(n->cell(n,"accounts",1,0).putObject("userEnteredValue").put("boolValue",true));reject(n->cell(n,"accounts",1,0).putObject("effectiveValue").putObject("errorValue").put("type","ERROR"));reject(n->cell(n,"accounts",1,0).putObject("effectiveValue").put("stringValue","other"));}
  @Test void unknownAndStaleLinksReject(){reject(n->cell(n,"accounts",1,0).put("hyperlink","https://example.invalid"));reject(n->cell(n,"auth_identities",2,0).put("hyperlink","https://accounts.google.com"));reject(n->cell(n,"auth_identities",1,0).putObject("userEnteredFormat").putObject("textFormat").putObject("link").put("uri","https://accounts.google.com"));}
  @Test void layoutPinRequiredForVerifiedStatus(){var n=fixture();assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.verify(bytes(n),TARGET,null));assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.verify(bytes(n),TARGET,"0".repeat(64)));var layout=inspect(n).layoutHash();((ObjectNode)block(n,"commits").path("rowMetadata").get(2)).put("pixelSize",22);assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.verify(bytes(n),TARGET,layout));}
  @Test void allThreeAccountHeaderVersionsSupportedButUnknownRejected(){var n=fixture();for(int c=2;c<4;c++){cell(n,"accounts",0,c).removeAll();cell(n,"accounts",1,c).removeAll();}assertDoesNotThrow(()->inspect(n));var lifetime=fixture();string(cell(lifetime,"accounts",0,4),"lifetime_statistics");assertDoesNotThrow(()->inspect(lifetime));reject(x->string(cell(x,"accounts",0,4),"other"));}
  @Test void malformedDuplicatedTrailingAndOversizeJSONRejected(){byte[] good=bytes(fixture());assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspect((new String(good,StandardCharsets.UTF_8)+" {}").getBytes(StandardCharsets.UTF_8),TARGET));assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspect("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8),TARGET));assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspect(new byte[ProductionReadOnlyWorkbook.MAX_RESPONSE_BYTES+1],TARGET));assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspect(null,TARGET));}
  @Test void independentSheetOrderNotArrayOrderIsPinned(){var n=fixture();ArrayNode sheets=(ArrayNode)n.get("sheets");JsonNode first=sheets.remove(0);sheets.add(first);assertDoesNotThrow(()->inspect(n));}
  @Test void targetDefensiveCopyAndRedactedStrings(){var ids=new ArrayList<>(TARGET.sheets());var t=new ProductionReadOnlyTarget(ID,TITLE,DATASET,ids);ids.clear();assertEquals(8,t.sheets().size());assertFalse(t.toString().contains(DATASET));assertThrows(UnsupportedOperationException.class,()->t.sheets().clear());assertThrows(IllegalArgumentException.class,()->new ProductionReadOnlyTarget(ID,TITLE,"bad",TARGET.sheets()));}

  static ObjectNode nativeStyleFixture() throws Exception {
    ObjectNode source;
    try(var in=ProductionReadOnlyWorkbookTest.class.getResourceAsStream("guarded-native-full-resource.json")){source=(ObjectNode)JSON.readTree(Objects.requireNonNull(in));}
    var n=fixture();ObjectNode p=source.path("properties").deepCopy();p.put("title",TITLE);n.set("properties",p);
    JsonNode ordinary=source.path("sheets").get(0).path("data").get(0).path("rowData").get(0).path("values").get(0).path("effectiveFormat");
    for(JsonNode sheet:n.path("sheets"))for(JsonNode row:sheet.path("data").get(0).path("rowData"))for(JsonNode raw:row.path("values")) {
      ObjectNode cell=(ObjectNode)raw,format=ordinary.deepCopy();
      if(cell.path("userEnteredValue").has("numberValue"))format.put("horizontalAlignment","RIGHT");
      cell.set("effectiveFormat",format);
    }
    return n;
  }
  @Test void capturedSyntheticNativeStringDefaultsAndFabricatedNumericAlignmentAreStrict() throws Exception {
    var n=nativeStyleFixture();assertNotNull(inspect(n));
    ((ObjectNode)cell(n,"commits",1,2).get("effectiveFormat")).put("horizontalAlignment","LEFT");var badAlignment=n;assertThrows(IllegalStateException.class,()->inspect(badAlignment));
    n=nativeStyleFixture();((ObjectNode)cell(n,"commits",1,2).get("effectiveFormat")).putObject("numberFormat").put("type","NUMBER");
    var unknown=n;assertThrows(IllegalStateException.class,()->inspect(unknown));
  }
  @Test void exactDefaultChannelsRejectDoubleRoundingAndUnderflow(){
    reject(n->n.withObject("properties").putObject("defaultFormat").putObject("backgroundColor").put("red",new BigDecimal("1.00000000000000001")).put("green",1).put("blue",1));
    reject(n->n.withObject("properties").putObject("defaultFormat").putObject("textFormat").putObject("foregroundColor").put("red",new BigDecimal("1e-999")));
  }
  @Test void numericFormattedValueIsRequired(){reject(n->cell(n,"commits",1,2).remove("formattedValue"));}

  @Test void exactPreviouslyCapturedSyntheticNativeIssuerLinkIsAcceptedAndPinned() throws Exception {
    ObjectNode source;
    try(var in=ProductionReadOnlyWorkbookTest.class.getResourceAsStream("guarded-native-full-resource.json")){source=(ObjectNode)JSON.readTree(Objects.requireNonNull(in));}
    ObjectNode link=null;for(JsonNode sh:source.path("sheets"))if(sh.path("properties").path("title").asText().equals("auth_identities"))
      link=(ObjectNode)sh.path("data").get(0).path("rowData").get(1).path("values").get(0);
    assertNotNull(link);var n=nativeStyleFixture();var plain=inspect(n);cell(n,"auth_identities",1,0).setAll(link.deepCopy());var linked=inspect(n);
    assertEquals(plain.normalizedDataHash(),linked.normalizedDataHash());assertNotEquals(plain.layoutHash(),linked.layoutHash());
    ((ObjectNode)cell(n,"auth_identities",1,0).path("userEnteredFormat").path("textFormat").path("link")).put("uri","https://other.invalid");
    assertThrows(IllegalStateException.class,()->inspect(n));
  }

  @Test void targetHashHasCanonicalOrderIndependentOfJvmMapOrdering() throws Exception {
    var target=new TreeMap<String,Object>();target.put("spreadsheetId",ID);target.put("expectedTitle",TITLE);target.put("datasetId",DATASET);
    var pins=new ArrayList<Map<String,Object>>();for(var pin:TARGET.sheets()) {var p=new TreeMap<String,Object>();p.put("sheetId",pin.sheetId());p.put("title",pin.title());pins.add(p);}target.put("sheets",pins);
    var expected=new TreeMap<String,Object>();expected.put("target",target);expected.put("profile",ProductionReadOnlyTarget.PROFILE);
    String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(expected)));
    assertEquals(hash,inspect(fixture()).targetHash());
  }
}
