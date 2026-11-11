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
    assertEquals(Set.of("completeScan", "counts", "formats"), rootKeys);
    assertTrue(encoded.get("completeScan").isBoolean());
    assertEquals(Counter.values().length, encoded.path("counts").size());
    for (Counter c : Counter.values()) assertBounded(encoded.path("counts").get(c.name()));
    assertEquals(Source.values().length, encoded.path("formats").size());
    for (Source source : Source.values()) {
      JsonNode f = encoded.path("formats").path(source.name()); assertEquals(Feature.values().length, f.size());
      for (Feature feature : Feature.values()) assertBounded(f.get(feature.name()));
    }
    String json = JSON.writeValueAsString(s);
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
    var root = fixture(); cell(root, "accounts", 0, 26); assertEquals(0, count(audit(root), Counter.CELLS_ENUMERATED));
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
}
