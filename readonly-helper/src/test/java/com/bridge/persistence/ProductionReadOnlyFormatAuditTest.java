package com.bridge.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyWorkbookTest.*;
import static com.bridge.persistence.ProductionReadOnlyFormatAudit.*;

/** Fabricated fixtures only. Output tests reject every non-enumerated key and non-count leaf. */
class ProductionReadOnlyFormatAuditTest {
  static final String CANARY = "PRIVATE_CANARY_not_a_real_name_email_font_note_or_url";
  static Summary audit(ObjectNode root) { return ProductionReadOnlyFormatAudit.inspect(root); }
  static int count(Summary s, Counter c) { return s.counts().get(c); }
  static int feature(Summary s, Source source, Feature f) { return s.formats().get(source).get(f); }
  static int cellCount(ObjectNode root) {
    int n = 0; for (JsonNode sheet : root.path("sheets")) for (JsonNode block : sheet.path("data"))
      for (JsonNode row : block.path("rowData")) n += row.path("values").size(); return n;
  }
  static ObjectNode effective(ObjectNode root, String title, int row, int column) {
    return (ObjectNode)cell(root, title, row, column).get("effectiveFormat");
  }
  static void textFormat(ObjectNode root, String title, int row, int column, boolean at) {
    ObjectNode c = cell(root, title, row, column);
    ObjectNode nf = c.putObject("userEnteredFormat").putObject("numberFormat"); nf.put("type", "TEXT"); if (at) nf.put("pattern", "@");
    effective(root, title, row, column).set("numberFormat", nf.deepCopy());
  }
  static ObjectNode linkCell() throws Exception {
    try (var in = ProductionReadOnlyFormatAuditTest.class.getResourceAsStream("guarded-native-full-resource.json")) {
      ObjectNode source = (ObjectNode)JSON.readTree(Objects.requireNonNull(in));
      return cell(source, "auth_identities", 1, 0).deepCopy();
    }
  }
  static void emailLink(ObjectNode c, String value) throws Exception {
    c.setAll(linkCell()); string(c, value); c.put("hyperlink", "mailto:" + value);
    ((ObjectNode)c.path("userEnteredFormat").path("textFormat").path("link")).put("uri", "mailto:" + value);
    ((ObjectNode)c.path("effectiveFormat").path("textFormat").path("link")).put("uri", "mailto:" + value);
  }
  static void assertSafe(Summary s) throws Exception {
    JsonNode encoded = JSON.valueToTree(s);
    Set<String> rootKeys = new HashSet<>(); encoded.fieldNames().forEachRemaining(rootKeys::add);
    assertEquals(Set.of("completeScan", "counts", "formats", "reasons"), rootKeys);
    assertTrue(encoded.get("completeScan").isBoolean());
    assertEquals(Counter.values().length, encoded.path("counts").size());
    for (Counter c : Counter.values()) assertBounded(encoded.path("counts").get(c.name()));
    assertEquals(Reason.values().length, encoded.path("reasons").size());
    for (Reason reason : Reason.values()) assertBounded(encoded.path("reasons").get(reason.name()));
    assertEquals(Source.values().length, encoded.path("formats").size());
    for (Source source : Source.values()) {
      JsonNode f = encoded.path("formats").path(source.name()); assertEquals(Feature.values().length, f.size());
      for (Feature feature : Feature.values()) assertBounded(f.get(feature.name()));
    }
    String json = JSON.writeValueAsString(s); assertTrue(json.length() < 16384);
    for (String secret : List.of(CANARY, ID, TITLE, ACCOUNT, DATASET, "synthetic@example.invalid", "https://", "mailto:", "java.lang", "JsonNode", "rowData", "sheetId")) {
      assertFalse(json.contains(secret), secret); assertFalse(s.toString().contains(secret), secret);
    }
  }
  static void assertBounded(JsonNode n) { assertNotNull(n); assertTrue(n.isInt()); assertTrue(n.intValue() >= 0 && n.intValue() <= MAX_CELLS); }
  static void incomplete(Consumer<ObjectNode> mutate, Counter expected) throws Exception {
    var root = fixture(); mutate.accept(root); Summary s = audit(root);
    assertFalse(s.completeScan()); assertTrue(count(s, expected) > 0); assertSafe(s);
  }

  @Test void normalSparseResourceEnumeratesEveryPresentCellButDoesNotClaimValidation() throws Exception {
    var root = fixture(); byte[] before = bytes(root); Summary s = audit(root);
    assertTrue(s.completeScan()); assertEquals(8, count(s, Counter.SHEETS_ENUMERATED));
    assertEquals(cellCount(root), count(s, Counter.CELLS_ENUMERATED));
    assertEquals(4, count(s, Counter.NUMERIC_CELLS));
    assertEquals(cellCount(root), feature(s, Source.USER_ENTERED, Feature.ABSENT));
    assertArrayEquals(before, bytes(root)); assertSafe(s);
    // Deliberately invalid business values are immaterial to enumeration completeness.
    string(cell(root, "dataset", 1, 0), CANARY); assertTrue(audit(root).completeScan());
  }
  @Test void exactNativeDefaultsAreClassifiedAcrossEveryTable() throws Exception {
    var root = nativeStyleFixture(); Summary s = audit(root);
    assertTrue(s.completeScan());
    assertEquals(4, count(s, Counter.EFFECTIVE_DEFAULT_RIGHT_NUMBER_EXACT));
    assertEquals(cellCount(root) - 4, count(s, Counter.EFFECTIVE_DEFAULT_LEFT_STRING_EXACT));
    assertEquals(cellCount(root), feature(s, Source.EFFECTIVE, Feature.FONT_ARIAL)); assertSafe(s);
  }
  @Test void exactTextTypeAndAtPatternPairsIncludingNumericRightAreDistinct() throws Exception {
    var root = nativeStyleFixture();
    for (String tab : TABS) textFormat(root, tab, 0, 0, false);
    textFormat(root, "accounts", 1, 3, true); textFormat(root, "commits", 1, 2, true);
    Summary s = audit(root);
    assertEquals(8, count(s, Counter.USER_TEXT_TYPE_ONLY_EXACT));
    assertEquals(2, count(s, Counter.USER_TEXT_AT_PATTERN_EXACT));
    assertEquals(8, count(s, Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_TYPE_ONLY_EXACT));
    assertEquals(2, count(s, Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_AT_PATTERN_EXACT));
    assertEquals(10, count(s, Counter.TEXT_FORMAT_PAIR_EXACT));
    assertEquals(1, count(s, Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_AT_PATTERN_EXACT));
    assertEquals(1, count(s, Counter.NUMERIC_TEXT_FORMAT_PAIR_EXACT)); assertSafe(s);
    // These observations must not relax the strict parser.
    assertThrows(IllegalStateException.class, () -> inspect(root));
  }
  @Test void textNearMissesNeverBecomeExactMatches() throws Exception {
    var root = nativeStyleFixture(); textFormat(root, "accounts", 1, 0, false);
    ((ObjectNode)cell(root, "accounts", 1, 0).path("userEnteredFormat").path("numberFormat")).put("pattern", CANARY);
    ((ObjectNode)effective(root, "accounts", 1, 0).get("textFormat")).put("fontFamily", CANARY);
    Summary s = audit(root); assertEquals(0, count(s, Counter.TEXT_FORMAT_PAIR_EXACT));
    assertEquals(0, count(s, Counter.USER_TEXT_TYPE_ONLY_EXACT));
    assertEquals(1, feature(s, Source.USER_ENTERED, Feature.NUMBER_PATTERN_OTHER));
    assertEquals(1, feature(s, Source.EFFECTIVE, Feature.FONT_OTHER)); assertSafe(s);
  }
  @Test void issuerLinkAndPotentialSameStringEmailAreExactOnlyAndNeverExposeUri() throws Exception {
    var root = nativeStyleFixture(); cell(root, "auth_identities", 1, 0).setAll(linkCell());
    emailLink(cell(root, "accounts", 1, 3), CANARY + "@example.invalid");
    Summary s = audit(root); assertEquals(1, count(s, Counter.KNOWN_ISSUER_LINK_STYLE_EXACT));
    assertEquals(1, count(s, Counter.POTENTIAL_AUTOMATIC_EMAIL_LINK_STYLE_EXACT));
    assertEquals(0, count(s, Counter.UNRECOGNIZED_LINK)); assertSafe(s);
    ((ObjectNode)cell(root, "accounts", 1, 3).path("effectiveFormat").path("textFormat")).put("bold", true);
    s = audit(root); assertEquals(0, count(s, Counter.POTENTIAL_AUTOMATIC_EMAIL_LINK_STYLE_EXACT));
    assertEquals(1, count(s, Counter.UNRECOGNIZED_LINK)); assertSafe(s);
  }
  @Test void mailtoMustEqualTheSameCellStringAtEveryLinkLocation() throws Exception {
    for (int mutation = 0; mutation < 4; mutation++) {
      var root = nativeStyleFixture(); ObjectNode c = cell(root, "accounts", 1, 3); emailLink(c, "fabricated@example.invalid");
      switch (mutation) {
        case 0 -> c.put("hyperlink", "mailto:" + CANARY);
        case 1 -> ((ObjectNode)c.path("userEnteredFormat").path("textFormat").path("link")).put("uri", "mailto:" + CANARY);
        case 2 -> ((ObjectNode)c.path("effectiveFormat").path("textFormat").path("link")).put("uri", "mailto:" + CANARY);
        case 3 -> c.putObject("effectiveValue").put("stringValue", CANARY);
      }
      Summary s = audit(root); assertEquals(0, count(s, Counter.POTENTIAL_AUTOMATIC_EMAIL_LINK_STYLE_EXACT));
      assertEquals(1, count(s, Counter.UNRECOGNIZED_LINK)); assertSafe(s);
    }
  }
  @Test void maliciousStringsNotesAndUnknownKeysCannotEscapeFiniteOutput() throws Exception {
    var root = nativeStyleFixture(); ObjectNode c = cell(root, "accounts", 1, 0); string(c, CANARY);
    c.put("note", CANARY); c.put("hyperlink", "https://" + CANARY + ".invalid");
    ObjectNode f = c.putObject("userEnteredFormat"); f.put("horizontalAlignment", CANARY); f.put("wrapStrategy", CANARY);
    f.putObject("textFormat").put("fontFamily", CANARY).putObject("link").put("uri", CANARY);
    Summary s = audit(root); assertEquals(1, count(s, Counter.UNRECOGNIZED_LINK)); assertSafe(s);
    f.put(CANARY, CANARY); s = audit(root); assertFalse(s.completeScan());
    assertEquals(1, feature(s, Source.USER_ENTERED, Feature.UNKNOWN_FIELD)); assertSafe(s);
  }
  @Test void knownFieldValuesUseOnlyFixedCategories() throws Exception {
    var root = fixture(); ObjectNode f = cell(root, "accounts", 1, 0).putObject("userEnteredFormat");
    f.putObject("numberFormat").put("type", "DATE"); f.put("horizontalAlignment", "CENTER").put("wrapStrategy", "CLIP");
    f.putObject("textFormat").put("fontFamily", "Carlito").put("bold", true).put("underline", false)
        .putObject("foregroundColorStyle").put("themeColor", "LINK");
    Summary s = audit(root);
    for (Feature feature : List.of(Feature.NUMBER_DATE, Feature.HORIZONTAL_CENTER, Feature.WRAP_CLIP, Feature.FONT_CARLITO, Feature.BOLD_TRUE, Feature.UNDERLINE_FALSE, Feature.FOREGROUND_STYLE_THEME_LINK)) assertEquals(1, feature(s, Source.USER_ENTERED, feature));
    assertSafe(s);
  }
  @Test void malformedRootSheetsRowsAndCellsAreIncompleteAndNeverThrow() throws Exception {
    assertFalse(ProductionReadOnlyFormatAudit.inspect(null).completeScan());
    assertFalse(ProductionReadOnlyFormatAudit.inspect(JSON.createArrayNode()).completeScan());
    incomplete(n -> n.put("sheets", CANARY), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> block(n, "accounts").put("rowData", CANARY), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> ((ArrayNode)block(n, "accounts").get("rowData")).set(0, TextNode.valueOf(CANARY)), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> ((ArrayNode)block(n, "accounts").path("rowData").get(0).get("values")).set(0, NullNode.instance), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> cell(n, "accounts", 1, 0).put("effectiveFormat", CANARY), Counter.MALFORMED_STRUCTURE);
  }
  @Test void dimensionsOffsetsBlockCountsAndCellWidthsAreBoundedBeforeCells() throws Exception {
    incomplete(n -> ((ArrayNode)n.get("sheets")).add(sheet(n, "accounts")), Counter.LIMIT_EXCEEDED);
    incomplete(n -> ((ObjectNode)sheet(n, "accounts").path("properties").get("gridProperties")).put("rowCount", 1001), Counter.LIMIT_EXCEEDED);
    incomplete(n -> ((ObjectNode)sheet(n, "accounts").path("properties").get("gridProperties")).put("columnCount", 27), Counter.LIMIT_EXCEEDED);
    incomplete(n -> block(n, "accounts").put("startRow", -1), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> block(n, "accounts").put("startColumn", Integer.MAX_VALUE), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> cell(n, "accounts", 0, 26), Counter.LIMIT_EXCEEDED);
    incomplete(n -> { ArrayNode a = (ArrayNode)sheet(n, "accounts").get("data"); for (int i = 0; i < 1000; i++) a.addObject(); }, Counter.LIMIT_EXCEEDED);
    var root = fixture(); cell(root, "accounts", 0, 26); assertTrue(count(audit(root), Counter.CELLS_ENUMERATED) > 0);
  }
  @Test void overlapAndMissingOrMalformedMetadataCannotClaimCompleteness() throws Exception {
    incomplete(n -> { ObjectNode b = ((ArrayNode)sheet(n, "accounts").get("data")).addObject(); b.putArray("rowData").addObject().putArray("values").addObject(); }, Counter.OVERLAPPING_RANGES);
    incomplete(n -> ((ArrayNode)sheet(n, "accounts").get("data")).addObject().putArray("rowMetadata").addObject(), Counter.OVERLAPPING_RANGES);
    incomplete(n -> ((ArrayNode)sheet(n, "accounts").get("data")).addObject().putArray("columnMetadata").addObject(), Counter.OVERLAPPING_RANGES);
    incomplete(n -> block(n, "accounts").put("rowMetadata", CANARY), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> ((ArrayNode)n.get("sheets")).remove(0), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> ((ObjectNode)sheet(n, "accounts").get("properties")).put("sheetId", 100), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> ((ObjectNode)sheet(n, "accounts").get("properties")).put("index", 0), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> ((ArrayNode)block(n, "accounts").get("columnMetadata")).remove(25), Counter.MALFORMED_STRUCTURE);
    incomplete(n -> ((ObjectNode)block(n, "accounts").path("rowMetadata").get(0)).put("pixelSize", CANARY), Counter.MALFORMED_STRUCTURE);
  }
  @Test void separateNonoverlappingSparseBlocksEnumerateAllTables() throws Exception {
    var root = fixture();
    for (String title : TABS) {
      ObjectNode b = ((ArrayNode)sheet(root, title).get("data")).addObject(); b.put("startRow", 999).put("startColumn", 25);
      ObjectNode c = b.putArray("rowData").addObject().putArray("values").addObject();
      c.putObject("userEnteredFormat").putObject("numberFormat").put("type", "TEXT");
    }
    Summary s = audit(root); assertTrue(s.completeScan()); assertEquals(16, count(s, Counter.BLOCKS_ENUMERATED));
    assertEquals(8, count(s, Counter.USER_TEXT_TYPE_ONLY_EXACT)); assertEquals(cellCount(root), count(s, Counter.CELLS_ENUMERATED)); assertSafe(s);
  }
  @Test void hostileNestedFormatsDoNotCauseRecursiveTraversalOrRawRetention() throws Exception {
    var root = fixture(); ObjectNode c = cell(root, "accounts", 1, 0);
    ObjectNode f = c.putObject("userEnteredFormat"); ObjectNode nesting = f;
    for (int i = 0; i < 100; i++) nesting = nesting.putObject(CANARY);
    nesting.put("fontFamily", CANARY);
    Summary s = audit(root); assertFalse(s.completeScan()); assertSafe(s);
    f.removeAll(); ArrayNode a = f.putArray("textFormat"); for (int i = 0; i < 10000; i++) a.add(CANARY);
    s = audit(root); assertFalse(s.completeScan()); assertSafe(s);
  }
  @Test void summaryIsDeeplyImmutableClampedAndDetachedFromInput() throws Exception {
    EnumMap<Counter,Integer> c = new EnumMap<>(Counter.class); c.put(Counter.CELLS_ENUMERATED, Integer.MAX_VALUE); c.put(Counter.STRING_CELLS, -10);
    EnumMap<Feature,Integer> f = new EnumMap<>(Feature.class); f.put(Feature.FONT_OTHER, Integer.MAX_VALUE);
    EnumMap<Source,Map<Feature,Integer>> outer = new EnumMap<>(Source.class); outer.put(Source.EFFECTIVE, f);
    Summary s = new Summary(false, c, outer); c.clear(); f.clear(); outer.clear();
    assertEquals(MAX_CELLS, count(s, Counter.CELLS_ENUMERATED)); assertEquals(0, count(s, Counter.STRING_CELLS));
    assertEquals(MAX_CELLS, feature(s, Source.EFFECTIVE, Feature.FONT_OTHER));
    assertThrows(UnsupportedOperationException.class, () -> s.counts().clear());
    assertThrows(UnsupportedOperationException.class, () -> s.formats().clear());
    assertThrows(UnsupportedOperationException.class, () -> s.formats().get(Source.EFFECTIVE).clear()); assertSafe(s);
    var root = fixture(); Summary detached = audit(root); root.removeAll(); assertEquals(8, count(detached, Counter.SHEETS_ENUMERATED));
  }
  @Test void nonemptyExtraSheetStructuresAreExplicitlyIncomplete() throws Exception {
    incomplete(n -> sheet(n, "accounts").putArray("charts").addObject().put("title", CANARY), Counter.UNKNOWN_STRUCTURE);
    incomplete(n -> n.put(CANARY, CANARY), Counter.UNKNOWN_STRUCTURE);
    incomplete(n -> cell(n, "accounts", 1, 0).putObject("effectiveFormat").putObject("textFormat").put(CANARY, CANARY), Counter.UNKNOWN_STRUCTURE);
  }
  static void reason(Reason reason, Consumer<ObjectNode> mutation, boolean expectCells) throws Exception {
    ObjectNode root = fixture(); mutation.accept(root); Summary summary = audit(root);
    assertFalse(summary.completeScan()); assertTrue(summary.reasons().get(reason) > 0, reason.name());
    if (expectCells) assertTrue(count(summary, Counter.CELLS_ENUMERATED) > 0, reason.name());
    assertSafe(summary);
  }
  @Test void everyEnvelopeLevelHasFixedReasonsAndPreservesSafeSiblings() throws Exception {
    reason(Reason.ROOT_FIELDS_UNKNOWN, n -> n.put(CANARY, CANARY), true);
    reason(Reason.ROOT_PROPERTIES_NOT_OBJECT, n -> n.put("properties", CANARY), true);
    reason(Reason.ROOT_PROPERTIES_FIELDS_UNKNOWN, n -> n.withObject("properties").put(CANARY, CANARY), true);
    reason(Reason.ROOT_EXTRA_CONTENT, n -> n.putArray("comments").add(CANARY), true);
    reason(Reason.SHEETS_ABSENT, n -> n.remove("sheets"), false);
    reason(Reason.SHEETS_NOT_ARRAY, n -> n.put("sheets", CANARY), false);
    reason(Reason.SHEETS_EMPTY, n -> n.putArray("sheets"), false);
    reason(Reason.SHEET_COUNT_MISMATCH, n -> ((ArrayNode)n.get("sheets")).remove(7), true);
    reason(Reason.SHEET_COUNT_LIMIT, n -> ((ArrayNode)n.get("sheets")).add(sheet(n, "accounts")), true);
    reason(Reason.SHEET_NOT_OBJECT, n -> ((ArrayNode)n.get("sheets")).set(0, TextNode.valueOf(CANARY)), true);
    reason(Reason.SHEET_FIELDS_UNKNOWN, n -> sheet(n, "accounts").put(CANARY, CANARY), true);
    reason(Reason.SHEET_PROPERTIES_NOT_OBJECT, n -> sheet(n, "accounts").put("properties", CANARY), true);
    reason(Reason.SHEET_PROPERTIES_FIELDS_UNKNOWN, n -> sheet(n, "accounts").withObject("properties").put(CANARY, CANARY), true);
    reason(Reason.SHEET_ID_INVALID, n -> sheet(n, "accounts").withObject("properties").put("sheetId", CANARY), true);
    reason(Reason.SHEET_ID_DUPLICATE, n -> sheet(n, "accounts").withObject("properties").put("sheetId", 100), true);
    reason(Reason.SHEET_INDEX_INVALID, n -> sheet(n, "accounts").withObject("properties").put("index", -1), true);
    reason(Reason.SHEET_INDEX_DUPLICATE, n -> sheet(n, "accounts").withObject("properties").put("index", 0), true);
    reason(Reason.SHEET_TITLE_INVALID, n -> sheet(n, "accounts").withObject("properties").put("title", CANARY), true);
    reason(Reason.SHEET_TITLE_DUPLICATE, n -> sheet(n, "accounts").withObject("properties").put("title", "dataset"), true);
    reason(Reason.SHEET_EXTRA_CONTENT, n -> sheet(n, "accounts").putArray("charts").add(CANARY), true);
    reason(Reason.GRID_NOT_OBJECT, n -> sheet(n, "accounts").withObject("properties").put("gridProperties", CANARY), true);
    reason(Reason.GRID_FIELDS_UNKNOWN, n -> ((ObjectNode)sheet(n, "accounts").path("properties").path("gridProperties")).put(CANARY, CANARY), true);
    reason(Reason.GRID_ROW_COUNT_INVALID, n -> ((ObjectNode)sheet(n, "accounts").path("properties").path("gridProperties")).put("rowCount", Integer.MAX_VALUE), true);
    reason(Reason.GRID_COLUMN_COUNT_INVALID, n -> ((ObjectNode)sheet(n, "accounts").path("properties").path("gridProperties")).put("columnCount", 0), true);
    reason(Reason.GRID_DIMENSIONS_NOT_PRODUCTION, n -> ((ObjectNode)sheet(n, "accounts").path("properties").path("gridProperties")).put("rowCount", 999), true);
  }
  @Test void everyBlockAndRowFailureHasItsOwnReasonAndDoesNotHideOtherTables() throws Exception {
    reason(Reason.BLOCKS_ABSENT, n -> sheet(n, "accounts").remove("data"), true);
    reason(Reason.BLOCKS_NOT_ARRAY, n -> sheet(n, "accounts").put("data", CANARY), true);
    reason(Reason.BLOCKS_EMPTY, n -> sheet(n, "accounts").putArray("data"), true);
    reason(Reason.BLOCK_COUNT_LIMIT, n -> { ArrayNode a=(ArrayNode)sheet(n,"accounts").get("data"); for(int i=0;i<1000;i++)a.addObject(); }, true);
    reason(Reason.BLOCK_NOT_OBJECT, n -> ((ArrayNode)sheet(n, "accounts").get("data")).set(0, TextNode.valueOf(CANARY)), true);
    reason(Reason.BLOCK_FIELDS_UNKNOWN, n -> block(n, "accounts").put(CANARY, CANARY), true);
    reason(Reason.START_ROW_INVALID, n -> block(n, "accounts").put("startRow", -1), true);
    reason(Reason.START_COLUMN_INVALID, n -> block(n, "accounts").put("startColumn", Integer.MAX_VALUE), true);
    reason(Reason.ROWS_NOT_ARRAY, n -> block(n, "accounts").put("rowData", CANARY), true);
    reason(Reason.ROW_RANGE_LIMIT, n -> { ArrayNode a=(ArrayNode)block(n,"accounts").get("rowData"); while(a.size()<=1000)a.addObject(); }, true);
    reason(Reason.ROW_NOT_OBJECT, n -> ((ArrayNode)block(n,"accounts").get("rowData")).set(0, TextNode.valueOf(CANARY)), true);
    reason(Reason.ROW_FIELDS_UNKNOWN, n -> ((ObjectNode)block(n,"accounts").path("rowData").get(0)).put(CANARY,CANARY), true);
    reason(Reason.VALUES_NOT_ARRAY, n -> ((ObjectNode)block(n,"accounts").path("rowData").get(0)).put("values",CANARY), true);
    reason(Reason.COLUMN_RANGE_LIMIT, n -> cell(n,"accounts",0,26), true);
    reason(Reason.CELL_RANGE_OVERLAP, n -> ((ArrayNode)sheet(n,"accounts").get("data")).addObject().putArray("rowData").addObject().putArray("values").addObject(), true);
    reason(Reason.CELL_NOT_OBJECT, n -> ((ArrayNode)block(n,"accounts").path("rowData").get(0).get("values")).set(0,TextNode.valueOf(CANARY)), true);
    reason(Reason.CELL_FIELDS_UNKNOWN, n -> cell(n,"accounts",1,0).put(CANARY,CANARY), true);
    reason(Reason.FORMAT_NOT_OBJECT, n -> cell(n,"accounts",1,0).put("effectiveFormat",CANARY), true);
    reason(Reason.FORMAT_FIELDS_UNKNOWN, n -> cell(n,"accounts",1,0).putObject("effectiveFormat").put(CANARY,CANARY), true);
    reason(Reason.FORMAT_COMPONENT_NOT_OBJECT, n -> cell(n,"accounts",1,0).putObject("effectiveFormat").put("textFormat",CANARY), true);
    reason(Reason.FORMAT_COMPONENT_FIELDS_UNKNOWN, n -> cell(n,"accounts",1,0).putObject("effectiveFormat").putObject("textFormat").put(CANARY,CANARY), true);
  }
  @Test void metadataReasonsAreSeparateByAxisAndNeverBlockBoundedCells() throws Exception {
    for (boolean rows : List.of(true,false)) {
      String key = rows ? "rowMetadata" : "columnMetadata";
      Reason[] reasons = rows ? new Reason[]{Reason.ROW_METADATA_NOT_ARRAY,Reason.ROW_METADATA_RANGE_LIMIT,Reason.ROW_METADATA_OVERLAP,
          Reason.ROW_METADATA_ITEM_NOT_OBJECT,Reason.ROW_METADATA_FIELDS_UNKNOWN,Reason.ROW_METADATA_BOOLEAN_INVALID,
          Reason.ROW_METADATA_EXTRA_CONTENT,Reason.ROW_METADATA_COVERAGE_GAP}
          : new Reason[]{Reason.COLUMN_METADATA_NOT_ARRAY,Reason.COLUMN_METADATA_RANGE_LIMIT,Reason.COLUMN_METADATA_OVERLAP,
          Reason.COLUMN_METADATA_ITEM_NOT_OBJECT,Reason.COLUMN_METADATA_FIELDS_UNKNOWN,Reason.COLUMN_METADATA_BOOLEAN_INVALID,
          Reason.COLUMN_METADATA_EXTRA_CONTENT,Reason.COLUMN_METADATA_COVERAGE_GAP};
      List<Consumer<ObjectNode>> changes = List.of(
          n -> block(n,"accounts").put(key,CANARY),
          n -> ((ArrayNode)block(n,"accounts").get(key)).addObject(),
          n -> ((ArrayNode)sheet(n,"accounts").get("data")).addObject().putArray(key).addObject(),
          n -> ((ArrayNode)block(n,"accounts").get(key)).set(0,TextNode.valueOf(CANARY)),
          n -> ((ObjectNode)block(n,"accounts").get(key).get(0)).put(CANARY,CANARY),
          n -> ((ObjectNode)block(n,"accounts").get(key).get(0)).put("hiddenByUser",CANARY),
          n -> ((ObjectNode)block(n,"accounts").get(key).get(0)).putArray("developerMetadata").add(CANARY),
          n -> ((ArrayNode)block(n,"accounts").get(key)).remove(0));
      for (int i=0;i<changes.size();i++) {
        ObjectNode root=fixture();int expected=cellCount(root);changes.get(i).accept(root);Summary summary=audit(root);
        assertFalse(summary.completeScan());assertTrue(summary.reasons().get(reasons[i])>0,reasons[i].name());
        assertEquals(expected,count(summary,Counter.CELLS_ENUMERATED));assertSafe(summary);
      }
    }
  }
  @Test void pixelFailuresHaveNonValueBucketsAndDoNotSuppressFormats() throws Exception {
    List<JsonNode> samples=List.of(TextNode.valueOf(CANARY),DecimalNode.valueOf(new java.math.BigDecimal("21.0")),IntNode.valueOf(0),IntNode.valueOf(-1),IntNode.valueOf(1001),IntNode.valueOf(10000),IntNode.valueOf(10001),BigIntegerNode.valueOf(java.math.BigInteger.ONE.shiftLeft(100)));
    for(boolean row:List.of(true,false))for(int i=0;i<samples.size();i++){
      ObjectNode root=fixture();int expected=cellCount(root);
      ((ObjectNode)block(root,"accounts").path(row?"rowMetadata":"columnMetadata").get(0)).set("pixelSize",samples.get(i));
      Reason expectedReason=switch(i){
        case 0->row?Reason.ROW_METADATA_PIXEL_SIZE_NON_NUMERIC:Reason.COLUMN_METADATA_PIXEL_SIZE_NON_NUMERIC;
        case 1->row?Reason.ROW_METADATA_PIXEL_SIZE_NON_INTEGRAL_TYPE:Reason.COLUMN_METADATA_PIXEL_SIZE_NON_INTEGRAL_TYPE;
        case 2,3->row?Reason.ROW_METADATA_PIXEL_SIZE_NONPOSITIVE:Reason.COLUMN_METADATA_PIXEL_SIZE_NONPOSITIVE;
        case 4,5->row?Reason.ROW_METADATA_PIXEL_SIZE_ABOVE1000_TO10000:Reason.COLUMN_METADATA_PIXEL_SIZE_ABOVE1000_TO10000;
        default->row?Reason.ROW_METADATA_PIXEL_SIZE_ABOVE10000:Reason.COLUMN_METADATA_PIXEL_SIZE_ABOVE10000;};
      Summary summary=audit(root);
      if(!row && (i==4 || i==5)) {
        assertTrue(summary.completeScan());assertTrue(summary.reasons().values().stream().allMatch(n->n==0));
      } else { assertFalse(summary.completeScan());assertEquals(1,summary.reasons().get(expectedReason)); }
      assertEquals(expected,count(summary,Counter.CELLS_ENUMERATED));assertSafe(summary);
    }
  }
  @Test void sparseAbsentValuesAndDimensionOnlyBlocksAreSafeButMissingSheetDataIsExplained() throws Exception {
    ObjectNode root=fixture();int expected=cellCount(root);
    ObjectNode empty=((ArrayNode)sheet(root,"accounts").get("data")).addObject();empty.put("startRow",999);
    empty.putArray("rowData").addObject();assertTrue(audit(root).completeScan());assertEquals(expected,count(audit(root),Counter.CELLS_ENUMERATED));
    empty.remove("rowData");assertTrue(audit(root).completeScan());
    sheet(root,"quarterly_aggregates").remove("data");Summary summary=audit(root);
    assertFalse(summary.completeScan());assertEquals(1,summary.reasons().get(Reason.BLOCKS_ABSENT));assertTrue(count(summary,Counter.CELLS_ENUMERATED)>0);assertSafe(summary);
  }
  @Test void oversizedUnknownObjectsAreNotTraversedButKnownBoundedChildrenRemainUseful() throws Exception {
    ObjectNode root=fixture();int expected=cellCount(root);ObjectNode opaque=JSON.createObjectNode();
    for(int i=0;i<10000;i++)opaque.put(CANARY+i,CANARY);
    root.set(CANARY,opaque);sheet(root,"accounts").set(CANARY,opaque);block(root,"accounts").set(CANARY,opaque);
    Summary summary=audit(root);assertFalse(summary.completeScan());assertEquals(expected,count(summary,Counter.CELLS_ENUMERATED));assertSafe(summary);
  }
  @Test void globalRowBudgetIncludesEmptyAndOverlappingRowsBeforeCellClassification() throws Exception {
    ObjectNode root=fixture();ArrayNode blocks=(ArrayNode)sheet(root,"accounts").get("data");
    ObjectNode repeat=JSON.createObjectNode();ArrayNode rows=repeat.putArray("rowData");for(int i=0;i<1000;i++)rows.addObject();
    for(int i=0;i<999;i++)blocks.add(repeat);
    Summary summary=audit(root);assertFalse(summary.completeScan());assertTrue(summary.reasons().get(Reason.SHEET_ROW_LIMIT)>0);
    assertTrue(count(summary,Counter.ROWS_ENUMERATED)<=MAX_CELLS);assertTrue(count(summary,Counter.ROWS_SKIPPED)>0);
    assertEquals(cellCount(fixture()),count(summary,Counter.CELLS_ENUMERATED));assertSafe(summary);
  }
  @Test void overlapDoesNotDuplicateCountsAndLaterSafeRowsStillContribute() throws Exception {
    ObjectNode root=fixture();int expected=cellCount(root);
    ObjectNode b=((ArrayNode)sheet(root,"accounts").get("data")).addObject();ArrayNode rows=b.putArray("rowData");
    rows.addObject().putArray("values").addObject();rows.addObject();
    rows.addObject().putArray("values").addObject().putObject("userEnteredFormat").putObject("numberFormat").put("type","TEXT");
    Summary summary=audit(root);assertFalse(summary.completeScan());assertEquals(expected+1,count(summary,Counter.CELLS_ENUMERATED));
    assertEquals(1,count(summary,Counter.USER_TEXT_TYPE_ONLY_EXACT));assertEquals(1,summary.reasons().get(Reason.CELL_RANGE_OVERLAP));assertSafe(summary);
  }
  @Test void blankTextStylesAreCountedOnlyForStrictlyBlankValueFields() throws Exception {
    for(boolean at:List.of(false,true)){
      ObjectNode root=nativeStyleFixture();ObjectNode c=cell(root,"accounts",1,0);textFormat(root,"accounts",1,0,at);
      c.remove(List.of("userEnteredValue","effectiveValue","formattedValue"));Summary summary=audit(root);
      assertEquals(1,count(summary,Counter.BLANK_CELLS));assertEquals(1,count(summary,Counter.BLANK_TEXT_FORMAT_PAIR_EXACT));
      assertEquals(1,count(summary,at?Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_BLANK_AT_PATTERN_EXACT:Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_BLANK_TYPE_ONLY_EXACT));
      c.putObject("userEnteredValue");c.putObject("effectiveValue");c.put("formattedValue","");assertEquals(1,count(audit(root),Counter.BLANK_TEXT_FORMAT_PAIR_EXACT));
      for(int mutation=0;mutation<4;mutation++){
        ObjectNode copy=root.deepCopy(),bad=cell(copy,"accounts",1,0);
        switch(mutation){case 0->bad.putObject("effectiveValue").putObject("errorValue").put("message",CANARY);case 1->bad.putNull("userEnteredValue");case 2->bad.put("formattedValue",CANARY);case 3->bad.putObject("userEnteredValue").put("boolValue",false);}
        summary=audit(copy);assertEquals(0,count(summary,Counter.BLANK_CELLS));assertEquals(0,count(summary,Counter.BLANK_TEXT_FORMAT_PAIR_EXACT));assertSafe(summary);
      }
    }
    ObjectNode root=nativeStyleFixture();ObjectNode c=cell(root,"accounts",1,0);c.remove(List.of("userEnteredValue","effectiveValue","formattedValue"));
    assertEquals(1,count(audit(root),Counter.EFFECTIVE_DEFAULT_BLANK_EXACT));
  }
  @Test void reasonsAreDeeplyImmutableSaturatedAndNeverRetainTheirInputMap() throws Exception {
    EnumMap<Reason,Integer> input=new EnumMap<>(Reason.class);input.put(Reason.BLOCKS_ABSENT,Integer.MAX_VALUE);input.put(Reason.ROWS_NOT_ARRAY,-1);
    Summary summary=new Summary(false,Map.of(),Map.of(),input);input.clear();
    assertEquals(MAX_CELLS,summary.reasons().get(Reason.BLOCKS_ABSENT));assertEquals(0,summary.reasons().get(Reason.ROWS_NOT_ARRAY));
    assertThrows(UnsupportedOperationException.class,()->summary.reasons().clear());assertSafe(summary);
  }
  @Test void retainedIsolatedSevenSheetNativeResourcesExerciseRealSparseDefaultsWithoutBecomingProductionPositives() throws Exception {
    String base=System.getProperty("formatAuditIsolatedNativeDirectory");org.junit.jupiter.api.Assumptions.assumeTrue(base!=null);
    String[] files={"transport-readonly-native-resource.json","supervised-live-state/bootstrap-pending-native.json"};
    String[] hashes={"dcd0291779f87fde6b6a8befce6112fb6f7b3fab07c041303a02bd895f102a5a","8ae4ab15e989afd8f5dfd30021b4c7f4433148a652eb913d46fcd5a9ae477b9d"};
    int[] expectedCells={2800,112};
    for(int i=0;i<files.length;i++){
      byte[] nativeBytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(base).resolve(files[i]));
      assertEquals(hashes[i],HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(nativeBytes)));
      JsonNode original=JSON.readTree(nativeBytes);Summary summary=ProductionReadOnlyFormatAudit.inspect(original);
      assertFalse(summary.completeScan());assertEquals(7,count(summary,Counter.SHEETS_ENUMERATED));
      assertEquals(1,summary.reasons().get(Reason.SHEET_COUNT_MISMATCH));
      assertEquals(expectedCells[i],count(summary,Counter.CELLS_ENUMERATED));assertSafe(summary);
      // The retained native fixture is not rewritten, normalized, uploaded or treated as production.
      assertArrayEquals(nativeBytes,java.nio.file.Files.readAllBytes(java.nio.file.Path.of(base).resolve(files[i])));
    }
  }

  @Test void duplicateKnownSheetIdIsSkippedInsteadOfDoubleCountingItsCells() throws Exception {
    ObjectNode root=fixture();int expected=cellCount(root);
    int replaced=0;for(JsonNode row:block(root,"accounts").path("rowData"))replaced+=row.path("values").size();
    ((ArrayNode)root.get("sheets")).set(1,sheet(root,"dataset").deepCopy());
    Summary summary=audit(root);assertFalse(summary.completeScan());
    assertEquals(1,summary.reasons().get(Reason.SHEET_ID_DUPLICATE));assertEquals(1,count(summary,Counter.SHEETS_SKIPPED));
    assertEquals(expected-replaced,count(summary,Counter.CELLS_ENUMERATED));assertSafe(summary);
  }

}
