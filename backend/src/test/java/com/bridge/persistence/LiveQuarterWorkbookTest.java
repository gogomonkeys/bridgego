package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;

/** Generated local JSON only. These tests do not establish compatibility with a live Google workbook. */
class LiveQuarterWorkbookTest {
  static final ObjectMapper JSON = new ObjectMapper();
  LocalManualQuarterRehearsal.Fixture fixture;
  ObjectNode root;
  @BeforeEach void setup() { fixture = new LocalManualQuarterRehearsal.Fixture(); root = fixtureTree(fixture); }

  static byte[] fixtureJson(LocalManualQuarterRehearsal.Fixture fixture) { return bytes(fixtureTree(fixture)); }
  static ObjectNode fixtureTree(LocalManualQuarterRehearsal.Fixture fixture) {
    ObjectNode root = JSON.createObjectNode();
    root.put("spreadsheetId", fixture.binding.spreadsheetId());
    root.put("spreadsheetUrl", "https://docs.google.com/spreadsheets/d/" + fixture.binding.spreadsheetId() + "/edit");
    root.put("commentsViewMode", LiveQuarterWorkbook.COMMENTS);
    root.putObject("properties").put("title", "Local-only quarterly JSON fixture").put("locale", "en_US").put("timeZone", "Etc/UTC");
    ArrayNode sheets = root.putArray("sheets"); int index = 0;
    for (var entry : new TreeMap<>(fixture.tables).entrySet()) {
      ObjectNode sheet = sheets.addObject(), p = sheet.putObject("properties");
      p.put("sheetId", 101 + index); p.put("title", entry.getKey()); p.put("index", index++); p.put("sheetType", "GRID");
      p.putObject("gridProperties").put("rowCount", 32).put("columnCount", 26);
      ObjectNode block = sheet.putArray("data").addObject();
      ArrayNode rm = block.putArray("rowMetadata"), cm = block.putArray("columnMetadata");
      for (int i = 0; i < 32; i++) rm.addObject();
      for (int i = 0; i < 26; i++) cm.addObject();
      ArrayNode rows = block.putArray("rowData");
      for (var row : entry.getValue()) {
        ArrayNode values = rows.addObject().putArray("values");
        for (String value : row) values.add(literal(value));
      }
    }
    return root;
  }
  /** Only non-sensitive display metadata copied from connector/write-evidence.json; no actual cell/target data. */
  static void applyObservedDisplayMetadata(ObjectNode root) {
    ObjectNode props = (ObjectNode)root.path("properties");
    props.set("spreadsheetTheme", displayJson("""
        {"primaryFontFamily":"Carlito","themeColors":[{"colorType":"TEXT","color":{"rgbColor":{}}},{"colorType":"BACKGROUND","color":{"rgbColor":{"red":1,"green":1,"blue":1}}},{"colorType":"ACCENT1","color":{"rgbColor":{"red":0.08235294,"green":0.3764706,"blue":0.50980395}}},{"colorType":"ACCENT2","color":{"rgbColor":{"red":0.9137255,"green":0.44313726,"blue":0.19607843}}},{"colorType":"ACCENT3","color":{"rgbColor":{"red":0.09803922,"green":0.41960785,"blue":0.14117648}}},{"colorType":"ACCENT4","color":{"rgbColor":{"red":0.05882353,"green":0.61960787,"blue":0.8352941}}},{"colorType":"ACCENT5","color":{"rgbColor":{"red":0.627451,"green":0.16862746,"blue":0.5764706}}},{"colorType":"ACCENT6","color":{"rgbColor":{"red":0.30588236,"green":0.654902,"blue":0.18039216}}},{"colorType":"LINK","color":{"rgbColor":{"red":0.27450982,"green":0.47058824,"blue":0.5254902}}}]}
        """));
    props.set("defaultFormat", displayJson("""
        {"backgroundColor":{"red":1,"green":1,"blue":1},"padding":{"right":3,"left":3},"verticalAlignment":"BOTTOM","wrapStrategy":"OVERFLOW_CELL","textFormat":{"foregroundColor":{},"fontFamily":"Carlito","fontSize":11,"bold":false,"italic":false,"strikethrough":false,"underline":false,"foregroundColorStyle":{"rgbColor":{}}},"backgroundColorStyle":{"rgbColor":{"red":1,"green":1,"blue":1}}}
        """));
    for (JsonNode sheet : root.path("sheets")) {
      ObjectNode grid = (ObjectNode)sheet.path("properties").path("gridProperties");
      grid.put("rowGroupControlAfter", true); grid.put("columnGroupControlAfter", true);
    }
  }
  static void applyObservedCellDisplayMetadata(ObjectNode root) {
    applyObservedDisplayMetadata(root);
    for (JsonNode sheet : root.path("sheets")) for (JsonNode row : sheet.path("data").get(0).path("rowData"))
      for (JsonNode cell : row.path("values")) {
        ObjectNode format = root.path("properties").path("defaultFormat").deepCopy();
        format.put("horizontalAlignment", "LEFT"); format.put("hyperlinkDisplayType", "PLAIN_TEXT");
        ((ObjectNode)cell).set("effectiveFormat", format);
      }
  }

  @Test void profileHeaderSelectsFourColumnsAndRetainsLegacyPadding() {
    var rows = fixture.tables.get("accounts");
    rows.set(0, AccountProfileRows.HEADER);
    rows.set(1, List.of(rows.get(1).get(0), "2", "測試玩家", "player@example.test"));
    root = fixtureTree(fixture);
    var parsed = parse(root).snapshot().tables().get("accounts");
    assertEquals(rows.get(1), parsed.get(1));
    assertEquals(List.of(rows.get(2).get(0), "1", "", ""), parsed.get(2));
    rejectChange(x -> cell(x, "accounts", 0, 2).putObject("userEnteredValue").put("stringValue", "wrong"));
  }

  @Test void exactObservedCellDisplayValuesPreserveDataAndChangeFullFingerprint() {
    var before = parse(root); applyObservedCellDisplayMetadata(root); var after = parse(root);
    assertEquals(before.snapshot(), after.snapshot());
    assertNotEquals(before.metadataFingerprint(), after.metadataFingerprint());
    assertNotEquals(before.fingerprint(), after.fingerprint());
  }

  @Test void observedCellDisplayStillRejectsUnknownValuesFieldsAndNonliteralContent() {
    applyObservedCellDisplayMetadata(root);
    for (String value : List.of("RIGHT", "CENTER", "", "left"))
      rejectChange(x -> ((ObjectNode)cell(x, "accounts", 0, 0).get("effectiveFormat")).put("horizontalAlignment", value));
    for (String value : List.of("LINKED", "", "plain_text"))
      rejectChange(x -> ((ObjectNode)cell(x, "accounts", 0, 0).get("effectiveFormat")).put("hyperlinkDisplayType", value));
    rejectChange(x -> ((ObjectNode)cell(x, "accounts", 0, 0).get("effectiveFormat")).put("unknown", "ignored"));
    rejectChange(x -> cell(x, "accounts", 0, 0).putObject("userEnteredValue").put("formulaValue", "=1"));
    rejectChange(x -> cell(x, "accounts", 0, 0).putObject("userEnteredFormat").put("horizontalAlignment", "LEFT"));
    rejectChange(x -> ((ObjectNode)x.path("properties").path("defaultFormat")).put("horizontalAlignment", "LEFT"));
    rejectChange(x -> ((ObjectNode)x.path("properties").path("defaultFormat")).put("hyperlinkDisplayType", "PLAIN_TEXT"));
    rejectChange(x -> x.put("commentsViewMode", "COMMENTS_VIEW_MODE_OMITTED"));
    rejectChange(x -> ((ArrayNode)block(x, "accounts").path("rowData").get(0).path("values")).add(literal("EXTRA")));
  }

  private static JsonNode displayJson(String json) {
    try { return JSON.readTree(json); } catch (Exception impossible) { throw new AssertionError(impossible); }
  }
  static ObjectNode observedTheme(ObjectNode root) { return (ObjectNode)root.path("properties").path("spreadsheetTheme"); }
  static ArrayNode observedColors(ObjectNode root) { return (ArrayNode)observedTheme(root).path("themeColors"); }
  static ObjectNode observedFont(ObjectNode root) { return (ObjectNode)root.path("properties").path("defaultFormat").path("textFormat"); }

  static ObjectNode literal(String value) {
    ObjectNode cell = JSON.createObjectNode();
    cell.putObject("userEnteredValue").put("stringValue", value);
    cell.putObject("effectiveValue").put("stringValue", value); cell.put("formattedValue", value);
    return cell;
  }
  static byte[] bytes(JsonNode value) {
    try { return JSON.writeValueAsBytes(value); } catch (Exception impossible) { throw new AssertionError(impossible); }
  }
  static ObjectNode sheet(ObjectNode value, String title) {
    for (JsonNode sheet : value.path("sheets")) if (title.equals(sheet.path("properties").path("title").asText())) return (ObjectNode)sheet;
    throw new AssertionError("Missing test sheet");
  }
  static ObjectNode block(ObjectNode value, String title) { return (ObjectNode)sheet(value, title).path("data").get(0); }
  static ObjectNode cell(ObjectNode value, String title, int row, int column) {
    return (ObjectNode)block(value, title).path("rowData").get(row).path("values").get(column);
  }
  static ObjectNode properties(ObjectNode value, String title) { return (ObjectNode)sheet(value, title).path("properties"); }
  LiveQuarterWorkbook.Workbook parse(ObjectNode value) { return LiveQuarterWorkbook.parse(bytes(value), fixture.binding, fixture.clock); }
  void reject(ObjectNode value) { reject(bytes(value)); }
  void reject(byte[] value) {
    var failure = assertThrows(IllegalStateException.class, () -> LiveQuarterWorkbook.parse(value, fixture.binding, fixture.clock));
    assertEquals("Quarter workbook response rejected", failure.getMessage()); assertNull(failure.getCause());
  }
  void rejectChange(Consumer<ObjectNode> mutate) { ObjectNode changed = root.deepCopy(); mutate.accept(changed); reject(changed); }

  @Test void completeLiteralFixtureRoundTripsWithImmutableMetadataAndWholeFingerprint() {
    var parsed = parse(root);
    assertEquals(fixture.tables, parsed.snapshot().tables()); assertTrue(parsed.snapshot().completeKnownLayout());
    assertEquals(fixture.binding.spreadsheetId(), parsed.snapshot().spreadsheetId());
    assertEquals(8, parsed.grids().size()); assertEquals(32, parsed.grids().get("accounts").rowCount());
    assertEquals(26, parsed.grids().get("accounts").columnCount());
    assertTrue(parsed.fingerprint().matches("[0-9a-f]{64}")); assertTrue(parsed.metadataFingerprint().matches("[0-9a-f]{64}"));
    assertThrows(UnsupportedOperationException.class, () -> parsed.grids().clear());
    assertThrows(UnsupportedOperationException.class, () -> parsed.snapshot().tables().get("accounts").clear());
    assertEquals(parsed, LiveQuarterWorkbook.parse(fixtureJson(fixture), fixture.binding, fixture.clock));
  }

  @Test void sparseTrailingCellsAndRowsNormalizeButInteriorAggregateAndPendingBlanksRemain() {
    // A prior allowed watermark does not invalidate any of the fixture's Q3/Q4 envelopes.
    fixture.tables.get("retention_ledger").add(List.of("2026-Q3", "2026-06-30T16:00:00Z", "PENDING", "", "1"));
    fixture.tables.get("quarterly_aggregates").add(List.of("2026-Q2", "SUPPRESSED", "", "", "1"));
    root = fixtureTree(fixture); var expected = parse(root);
    for (String title : fixture.tables.keySet()) {
      ArrayNode rows = (ArrayNode)block(root, title).path("rowData");
      for (JsonNode row : rows) {
        ArrayNode values = (ArrayNode)row.path("values");
        for (int i = 0; i < values.size(); i++) if (values.get(i).path("userEnteredValue").path("stringValue").asText().isEmpty())
          values.set(i, JSON.createObjectNode());
        while (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) values.remove(values.size() - 1);
      }
      rows.addObject().putArray("values").addObject(); rows.addObject();
    }
    var actual = parse(root); assertEquals(expected, actual);
    assertEquals(List.of("2026-Q2", "SUPPRESSED", "", "", "1"), actual.snapshot().tables().get("quarterly_aggregates").get(1));
    assertEquals("", actual.snapshot().tables().get("retention_ledger").get(1).get(3));
  }

  @Test void splitGridOffsetsNormalizeAndMetadataCoverageIsRequiredAcrossBlocks() {
    var before = parse(root);
    ObjectNode source = block(root, "accounts"); ArrayNode original = ((ArrayNode)source.path("rowData")).deepCopy();
    source.remove("rowData");
    ArrayNode data = (ArrayNode)sheet(root, "accounts").path("data");
    for (int r = 0; r < original.size(); r++) {
      ObjectNode split = data.addObject(); split.put("startRow", r);
      split.putArray("rowData").add(original.get(r));
    }
    assertEquals(before, parse(root));
    ((ArrayNode)source.path("rowMetadata")).remove(31); reject(root);
  }

  @Test void normalizedObjectAndSheetOrderingDoNotChangeFingerprint() {
    var before = parse(root); ArrayNode sheets = (ArrayNode)root.path("sheets");
    List<JsonNode> reversed = new ArrayList<>(); sheets.forEach(reversed::add); Collections.reverse(reversed);
    sheets.removeAll(); reversed.forEach(sheets::add);
    ObjectNode props = (ObjectNode)root.path("properties");
    String title = props.remove("title").asText(); props.put("title", title);
    assertEquals(before, parse(root));
  }

  @Test void tabIdentityIndexDimensionsAndBenignMetadataAreFingerprintBound() {
    var before = parse(root);
    List<Consumer<ObjectNode>> changes = List.of(
        value -> properties(value, "accounts").put("sheetId", 999),
        value -> {
          int first = properties(value, "accounts").path("index").asInt();
          properties(value, "accounts").put("index", properties(value, "commits").path("index").asInt());
          properties(value, "commits").put("index", first);
        },
        value -> {
          ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("rowCount", 33);
          ((ArrayNode)block(value, "accounts").path("rowMetadata")).addObject();
        },
        value -> ((ObjectNode)block(value, "accounts").path("rowMetadata").get(0)).put("pixelSize", 24),
        value -> ((ObjectNode)value.path("properties")).put("title", "Changed benign title"));
    for (Consumer<ObjectNode> change : changes) {
      ObjectNode changed = root.deepCopy(); change.accept(changed); var actual = parse(changed);
      assertEquals(before.snapshot(), actual.snapshot()); assertNotEquals(before.fingerprint(), actual.fingerprint());
      assertNotEquals(before.metadataFingerprint(), actual.metadataFingerprint());
    }
    cell(root, "auth_identities", 1, 1).setAll(literal("DIFFERENT-SYNTHETIC-SUBJECT"));
    var changed = parse(root); assertNotEquals(before.fingerprint(), changed.fingerprint());
    assertEquals(before.metadataFingerprint(), changed.metadataFingerprint());
  }

  @Test void targetDatasetMarkerAndSchemaMustMatchBeforeComplete() {
    rejectChange(value -> value.put("spreadsheetId", "OTHER_SHEET_NOT_APPROVED"));
    rejectChange(value -> value.put("spreadsheetUrl", "https://docs.google.com/spreadsheets/d/OTHER/edit"));
    rejectChange(value -> cell(value, "dataset", 1, 0).setAll(literal("not-approved-purpose")));
    rejectChange(value -> cell(value, "dataset", 1, 1).setAll(literal(UUID.randomUUID().toString())));
    rejectChange(value -> cell(value, "accounts", 0, 0).setAll(literal("unknown_header")));
    rejectChange(value -> ((ArrayNode)block(value, "accounts").path("rowData")).remove(0));
    rejectChange(value -> cell(value, "accounts", 1, 1).setAll(literal("2")));
  }

  @Test void extraMissingDuplicateTabsIdsTitlesOrIndexesReject() {
    rejectChange(value -> ((ArrayNode)value.path("sheets")).add(sheet(value, "accounts").deepCopy()));
    rejectChange(value -> ((ArrayNode)value.path("sheets")).remove(0));
    rejectChange(value -> properties(value, "accounts").put("title", "unknown"));
    rejectChange(value -> properties(value, "accounts").put("title", "commits"));
    rejectChange(value -> properties(value, "accounts").put("sheetId", properties(value, "commits").path("sheetId").asInt()));
    rejectChange(value -> properties(value, "accounts").put("index", properties(value, "commits").path("index").asInt()));
    rejectChange(value -> properties(value, "accounts").put("index", 8));
    rejectChange(value -> properties(value, "accounts").put("sheetType", "DATA_SOURCE"));
  }

  @Test void valuesOutsideExactSchemaAndOutsideFormerAtoEWindowRejectIncludingLastCell() {
    for (String title : fixture.tables.keySet()) rejectChange(value -> {
      ArrayNode values = (ArrayNode)block(value, title).path("rowData").get(0).path("values");
      values.add(literal("private-outside-schema"));
    });
    for (int column : List.of(5, 25)) rejectChange(value -> {
      ObjectNode offset = ((ArrayNode)sheet(value, "envelopes").path("data")).addObject();
      offset.put("startRow", 31); offset.put("startColumn", column);
      offset.putArray("rowData").addObject().putArray("values").add(literal("unknown-last-cell"));
    });
  }

  @Test void formulasNumbersBooleansErrorsAndEffectiveOnlyValuesNeverCoerce() {
    for (String field : List.of("formulaValue", "numberValue", "boolValue", "errorValue")) {
      rejectChange(value -> cell(value, "accounts", 1, 1).putObject("userEnteredValue").put(field, "1"));
      rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveValue").put(field, "1"));
    }
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("userEnteredValue").put("numberValue", 1));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveValue").put("boolValue", true));
    rejectChange(value -> cell(value, "accounts", 1, 1).remove("userEnteredValue"));
    rejectChange(value -> cell(value, "accounts", 1, 1).remove("effectiveValue"));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveValue").put("stringValue", "different"));
    rejectChange(value -> cell(value, "accounts", 1, 1).put("formattedValue", "different"));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("userEnteredValue").putNull("stringValue"));
    cell(root, "accounts", 1, 1).remove("formattedValue"); assertDoesNotThrow(() -> parse(root));
  }

  @Test void onlyExactLiteralDerivedIssuerHyperlinkIsAllowedAndNormalized() {
    var before = parse(root);
    cell(root, "auth_identities", 1, 0).put("hyperlink", "https://accounts.google.com");
    assertEquals(before, parse(root));
    rejectChange(value -> cell(value, "auth_identities", 1, 0).put("hyperlink", "https://attacker.example"));
    rejectChange(value -> cell(value, "auth_identities", 1, 0).putObject("effectiveValue").put("stringValue", "different"));
    rejectChange(value -> cell(value, "auth_identities", 1, 1).put("hyperlink", "https://accounts.google.com"));
    rejectChange(value -> cell(value, "auth_identities", 0, 0).put("hyperlink", "https://accounts.google.com"));
    rejectChange(value -> cell(value, "accounts", 1, 0).put("hyperlink", "https://accounts.google.com"));
    rejectChange(value -> cell(value, "auth_identities", 1, 0).putArray("textFormatRuns").addObject());
    rejectChange(value -> cell(value, "auth_identities", 1, 0).putObject("userEnteredValue").put("formulaValue", "=HYPERLINK(\"https://accounts.google.com\")"));
  }

  @Test void hiddenAndMissingSparseDimensionMetadataReject() {
    for (String key : List.of("hidden", "rightToLeft")) rejectChange(value -> properties(value, "accounts").put(key, true));
    for (String key : List.of("rowMetadata", "columnMetadata")) {
      rejectChange(value -> block(value, "accounts").remove(key));
      rejectChange(value -> ((ArrayNode)block(value, "accounts").path(key)).remove(0));
      rejectChange(value -> ((ArrayNode)block(value, "accounts").path(key)).set(0, JSON.nullNode()));
      for (String hidden : List.of("hiddenByUser", "hiddenByFilter"))
        rejectChange(value -> ((ObjectNode)block(value, "accounts").path(key).get(0)).put(hidden, true));
    }
    rejectChange(value -> ((ArrayNode)sheet(value, "accounts").path("data")).add(block(value, "accounts").deepCopy()));
    rejectChange(value -> block(value, "accounts").put("startRow", 1));
    rejectChange(value -> block(value, "accounts").put("startColumn", 1));
  }

  @Test void boundedMaximumRowsAndExactSchemaWidthAreSupportedWithFullMetadata() {
    ((ObjectNode)properties(root, "accounts").path("gridProperties")).put("rowCount", 1000).put("columnCount", 2);
    ArrayNode rm = (ArrayNode)block(root, "accounts").path("rowMetadata");
    while (rm.size() < 1000) rm.addObject();
    ArrayNode cm = (ArrayNode)block(root, "accounts").path("columnMetadata");
    while (cm.size() > 2) cm.remove(cm.size() - 1);
    var parsed = parse(root); assertEquals(fixture.tables, parsed.snapshot().tables());
    assertEquals(1000, parsed.grids().get("accounts").rowCount());
    assertEquals(2, parsed.grids().get("accounts").columnCount());
    rejectChange(value -> ((ObjectNode)block(value, "accounts").path("rowMetadata").get(999)).put("hiddenByUser", true));
  }

  @Test void boundsRejectBadDimensionsOversizedRowsColumnsAndOffsets() {
    for (int count : List.of(-1, 0, 1001)) rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("rowCount", count));
    for (int count : List.of(-1, 0, 1, 27)) rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("columnCount", count));
    rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("rowCount", Long.MAX_VALUE));
    rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("columnCount", 26.0));
    rejectChange(value -> block(value, "accounts").put("startRow", 32));
    rejectChange(value -> block(value, "accounts").put("startColumn", 26));
    rejectChange(value -> {
      ArrayNode rows = (ArrayNode)block(value, "accounts").path("rowData"); while (rows.size() <= 32) rows.addObject();
    });
    rejectChange(value -> {
      ArrayNode values = (ArrayNode)block(value, "accounts").path("rowData").get(0).path("values");
      while (values.size() <= 26) values.addObject();
    });
    rejectChange(value -> {
      ArrayNode data = (ArrayNode)sheet(value, "accounts").path("data"); while (data.size() <= 1000) data.addObject();
    });
  }

  @Test void unknownFieldsNotesCommentsFormatsAndWorkbookFeaturesReject() {
    for (String key : List.of("commentsViewMode", "spreadsheetId", "spreadsheetUrl", "properties", "sheets"))
      rejectChange(value -> value.remove(key));
    rejectChange(value -> value.put("commentsViewMode", "COMMENTS_VIEW_MODE_OMITTED"));
    for (String key : List.of("comments", "namedRanges", "developerMetadata", "dataSources", "dataSourceSchedules", "nextPageToken", "unknown"))
      rejectChange(value -> value.putArray(key).addObject());
    for (String key : List.of("merges", "conditionalFormats", "filterViews", "protectedRanges", "charts", "bandedRanges",
        "developerMetadata", "rowGroups", "columnGroups", "slicers", "tables", "commentAnchors"))
      rejectChange(value -> sheet(value, "accounts").putArray(key).addObject());
    rejectChange(value -> sheet(value, "accounts").putObject("basicFilter").putObject("range"));
    rejectChange(value -> ((ObjectNode)value.path("properties")).putObject("spreadsheetTheme").put("primaryFontFamily", "Arial"));
    rejectChange(value -> cell(value, "accounts", 1, 1).put("note", "PRIVATE-DO-NOT-EXPOSE"));
    for (String key : List.of("dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula", "userEnteredFormat", "unknown"))
      rejectChange(value -> cell(value, "accounts", 1, 1).putObject(key).put("private", "data"));
    for (String key : List.of("textFormatRuns", "chipRuns"))
      rejectChange(value -> cell(value, "accounts", 1, 1).putArray(key).addObject());
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveFormat").putObject("textFormat").put("bold", true));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveFormat").putObject("backgroundColorStyle").put("themeColor", "BACKGROUND"));
  }

  @Test void minimalExplicitBenignDefaultsAreAcceptedButFingerprintBound() {
    ObjectNode format = JSON.createObjectNode();
    format.putObject("backgroundColor").put("red", 1).put("green", 1).put("blue", 1);
    format.putObject("padding").put("top", 2).put("bottom", 2).put("left", 3).put("right", 3);
    format.put("verticalAlignment", "BOTTOM"); format.put("wrapStrategy", "OVERFLOW_CELL");
    format.putObject("textFormat").put("fontFamily", "Arial").put("fontSize", 10).putObject("foregroundColor");
    var before = parse(root); cell(root, "accounts", 1, 1).set("effectiveFormat", format);
    var after = parse(root); assertEquals(before.snapshot(), after.snapshot()); assertNotEquals(before.metadataFingerprint(), after.metadataFingerprint());
    rejectChange(value -> ((ObjectNode)cell(value, "accounts", 1, 1).path("effectiveFormat").path("backgroundColor")).put("red", .5));
  }

  @Test void observedDisplayMetadataOnGeneratedFullResponseIsAcceptedAndBound() {
    var before = parse(root); applyObservedDisplayMetadata(root); var after = parse(root);
    assertEquals(before.snapshot(), after.snapshot()); assertEquals(before.grids(), after.grids());
    assertNotEquals(before.fingerprint(), after.fingerprint());
    assertNotEquals(before.metadataFingerprint(), after.metadataFingerprint());
    // The retained write response had no cells. This is a synthetic effective-format compatibility case only.
    cell(root, "accounts", 1, 1).set("effectiveFormat", root.path("properties").path("defaultFormat").deepCopy());
    assertEquals(before.snapshot(), parse(root).snapshot());
  }

  @Test void observedThemeRejectsUnknownMissingDuplicatePaletteFieldsAndWrongTypes() {
    applyObservedDisplayMetadata(root);
    rejectChange(value -> observedTheme(value).put("unknown", "no"));
    rejectChange(value -> observedTheme(value).remove("primaryFontFamily"));
    rejectChange(value -> observedTheme(value).put("primaryFontFamily", "Arial"));
    rejectChange(value -> observedTheme(value).put("primaryFontFamily", 11));
    rejectChange(value -> observedTheme(value).remove("themeColors"));
    rejectChange(value -> observedTheme(value).putObject("themeColors"));
    rejectChange(value -> observedColors(value).remove(8));
    rejectChange(value -> observedColors(value).add(observedColors(value).get(0).deepCopy()));
    rejectChange(value -> observedColors(value).set(8, observedColors(value).get(0).deepCopy()));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2)).put("colorType", "UNKNOWN"));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2)).put("colorType", 1));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2)).put("unknown", false));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2).path("color").path("rgbColor")).put("red", .1));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2).path("color").path("rgbColor")).put("red", "0.08235294"));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2).path("color").path("rgbColor")).put("red", true));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2).path("color").path("rgbColor")).remove("red"));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2).path("color").path("rgbColor")).put("alpha", 1));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2).path("color")).put("themeColor", "ACCENT1"));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(0).path("color").path("rgbColor")).put("red", .1));
    rejectChange(value -> ((ObjectNode)observedColors(value).get(2).path("color")).putNull("rgbColor"));
  }

  @Test void carlitoElevenRequiresCompleteMatchingThemeAndDefaultFontPair() {
    applyObservedDisplayMetadata(root);
    rejectChange(value -> ((ObjectNode)value.path("properties")).remove("spreadsheetTheme"));
    rejectChange(value -> ((ObjectNode)value.path("properties")).putObject("spreadsheetTheme"));
    rejectChange(value -> ((ObjectNode)value.path("properties")).remove("defaultFormat"));
    rejectChange(value -> ((ObjectNode)value.path("properties").path("defaultFormat")).remove("textFormat"));
    rejectChange(value -> observedFont(value).remove("fontFamily"));
    rejectChange(value -> observedFont(value).remove("fontSize"));
    rejectChange(value -> observedFont(value).put("fontFamily", "Arial"));
    rejectChange(value -> observedFont(value).put("fontSize", 10));
    rejectChange(value -> observedFont(value).put("fontSize", "11"));
    rejectChange(value -> observedFont(value).put("bold", true));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveFormat").putObject("textFormat").put("fontFamily", "Arial"));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveFormat").putObject("textFormat").put("fontSize", 10));
    ObjectNode unthemed = fixtureTree(fixture);
    cell(unthemed, "accounts", 1, 1).putObject("effectiveFormat").putObject("textFormat").put("fontFamily", "Carlito").put("fontSize", 11);
    reject(unthemed);
  }

  @Test void groupControlDisplayBooleansNeverAllowGroupsHiddenDataOrMissingCoverage() {
    applyObservedDisplayMetadata(root); assertDoesNotThrow(() -> parse(root));
    for (String key : List.of("rowGroupControlAfter", "columnGroupControlAfter")) {
      rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put(key, "true"));
      rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put(key, 1));
      rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).putNull(key));
    }
    for (String key : List.of("rowGroups", "columnGroups"))
      rejectChange(value -> sheet(value, "accounts").putArray(key).addObject());
    rejectChange(value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("hideGridlines", true));
    rejectChange(value -> ((ObjectNode)block(value, "accounts").path("rowMetadata").get(0)).put("hiddenByUser", true));
    rejectChange(value -> ((ObjectNode)block(value, "accounts").path("columnMetadata").get(0)).put("hiddenByFilter", true));
    rejectChange(value -> block(value, "accounts").remove("rowMetadata"));
    rejectChange(value -> block(value, "accounts").remove("columnMetadata"));
    rejectChange(value -> value.remove("commentsViewMode"));
  }

  @Test void acceptedDisplayProfileDoesNotRelaxLiteralExtraDataOrCellMetadataRules() {
    applyObservedDisplayMetadata(root);
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("userEnteredValue").put("formulaValue", "=1"));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("userEnteredValue").put("numberValue", 1));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveValue").put("stringValue", "different"));
    rejectChange(value -> cell(value, "accounts", 1, 1).put("note", "unsupported"));
    rejectChange(value -> cell(value, "accounts", 1, 1).put("hyperlink", "https://accounts.google.com"));
    rejectChange(value -> cell(value, "accounts", 1, 1).putArray("textFormatRuns").addObject());
    rejectChange(value -> cell(value, "accounts", 1, 1).set("userEnteredFormat", value.path("properties").path("defaultFormat").deepCopy()));
    rejectChange(value -> cell(value, "accounts", 1, 1).putObject("effectiveFormat").putObject("textFormat").putObject("link").put("uri", "https://accounts.google.com"));
    rejectChange(value -> ((ArrayNode)block(value, "accounts").path("rowData").get(0).path("values")).add(literal("outside-schema")));
    rejectChange(value -> {
      ObjectNode offset = ((ArrayNode)sheet(value, "envelopes").path("data")).addObject();
      offset.put("startRow", 31); offset.put("startColumn", 25);
      offset.putArray("rowData").addObject().putArray("values").add(literal("outside-former-A-E"));
    });
  }

  @Test void everyAcceptedDisplayMetadataChangeRemainsFingerprintBound() {
    applyObservedDisplayMetadata(root); var before = parse(root);
    List<Consumer<ObjectNode>> changes = List.of(
        value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("rowGroupControlAfter", false),
        value -> ((ObjectNode)properties(value, "accounts").path("gridProperties")).put("columnGroupControlAfter", false),
        value -> ((ObjectNode)value.path("properties").path("defaultFormat").path("padding")).put("top", 2),
        value -> cell(value, "accounts", 1, 1).set("effectiveFormat", value.path("properties").path("defaultFormat").deepCopy()),
        value -> {
          ((ObjectNode)value.path("properties")).remove("spreadsheetTheme");
          ((ObjectNode)value.path("properties")).remove("defaultFormat");
        });
    for (Consumer<ObjectNode> change : changes) {
      ObjectNode changed = root.deepCopy(); change.accept(changed); var after = parse(changed);
      assertEquals(before.snapshot(), after.snapshot());
      assertNotEquals(before.fingerprint(), after.fingerprint());
      assertNotEquals(before.metadataFingerprint(), after.metadataFingerprint());
    }
  }

  @Test void malformedTruncatedDuplicateTrailingDeepAndOversizedJsonFailsWithoutContentLeak() {
    String valid = new String(bytes(root), StandardCharsets.UTF_8);
    for (String invalid : List.of("", "null", "[]", "{", valid.substring(0, valid.length() - 1), valid + "{}",
        "{\"spreadsheetId\":\"a\",\"spreadsheetId\":\"b\"}",
        "{\"private\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}",
        valid.replace("\"rowCount\":32", "\"rowCount\":32,\"rowCount\":32"))) reject(invalid.getBytes(StandardCharsets.UTF_8));
    reject(new byte[LiveQuarterWorkbook.MAX_RESPONSE_BYTES + 1]); reject((byte[])null);
    rejectChange(value -> cell(value, "accounts", 1, 1).setAll(literal("x".repeat(20001))));
    assertThrows(IllegalStateException.class, () -> LiveQuarterWorkbook.parse(bytes(root), null, fixture.clock));
    assertThrows(IllegalStateException.class, () -> LiveQuarterWorkbook.parse(bytes(root), fixture.binding, null));
  }
}
