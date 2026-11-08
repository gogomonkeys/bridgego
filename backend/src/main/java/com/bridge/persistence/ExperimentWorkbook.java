package com.bridge.persistence;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Pure, bounded parser for one unmasked full-workbook response. No credentials, transport or fallback reads. */
final class ExperimentWorkbook {
  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  static final String COMMENTS = "COMMENTS_VIEW_MODE_INCLUDED";
  private static final int MAX_ROWS = 1000, MAX_COLUMNS = 26, MAX_BLOCKS = 1000;
  private static final String ISSUER = "https://accounts.google.com";
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_RESPONSE_BYTES)
          .maxNestingDepth(24).maxStringLength(20000).maxNameLength(128).maxNumberLength(20)
          .maxTokenCount(1_000_000).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> ROOT_EMPTY_ARRAYS = Set.of("namedRanges", "developerMetadata", "dataSources",
      "dataSourceSchedules", "comments");
  private static final Set<String> SHEET_EMPTY_ARRAYS = Set.of("merges", "conditionalFormats", "filterViews",
      "protectedRanges", "charts", "bandedRanges", "developerMetadata", "rowGroups", "columnGroups", "slicers",
      "tables", "commentAnchors");
  private static final Set<String> VALUE_FIELDS = Set.of("userEnteredValue", "effectiveValue", "formattedValue");

  // Exact display-only theme observed in the retained local connector write response. No arbitrary palettes.
  private static final Map<String,List<String>> CARLITO_THEME_COLORS = Map.of(
      "TEXT", List.of(), "BACKGROUND", List.of("1", "1", "1"),
      "ACCENT1", List.of("0.08235294", "0.3764706", "0.50980395"),
      "ACCENT2", List.of("0.9137255", "0.44313726", "0.19607843"),
      "ACCENT3", List.of("0.09803922", "0.41960785", "0.14117648"),
      "ACCENT4", List.of("0.05882353", "0.61960787", "0.8352941"),
      "ACCENT5", List.of("0.627451", "0.16862746", "0.5764706"),
      "ACCENT6", List.of("0.30588236", "0.654902", "0.18039216"),
      "LINK", List.of("0.27450982", "0.47058824", "0.5254902"));

  record Grid(int sheetId, int rowCount, int columnCount, int index) {}
  /** metadataFingerprint permits metadata checks across an explicitly authorized value-only rewrite. */
  record Workbook(ExperimentGateway.Snapshot snapshot, Map<String,Grid> grids, String fingerprint,
      String metadataFingerprint) {
    Workbook { grids = Collections.unmodifiableMap(new TreeMap<>(grids)); }
  }
  private record ParsedGrid(List<List<String>> rows, JsonNode metadata) {}
  private ExperimentWorkbook() {}

  /**
   * The caller must request includeGridData=true, COMMENTS_VIEW_MODE_INCLUDED, no fields and no ranges.
   * Sparse cell omission means blank only inside that full response; visibility metadata may never be omitted.
   * Real Google response/default-style compatibility remains unverified and must fail closed.
   */
  static Workbook parse(byte[] json, ExperimentStorage.Settings binding, Clock clock) {
    try {
      if (json == null || json.length == 0 || json.length > MAX_RESPONSE_BYTES || binding == null || clock == null)
        throw failure();
      JsonNode root = JSON.readTree(json);
      fields(root, union(Set.of("spreadsheetId", "spreadsheetUrl", "properties", "sheets", "commentsViewMode"),
          ROOT_EMPTY_ARRAYS));
      if (!binding.spreadsheetId().equals(text(root, "spreadsheetId"))
          || !("https://docs.google.com/spreadsheets/d/" + binding.spreadsheetId() + "/edit").equals(text(root, "spreadsheetUrl"))
          || !COMMENTS.equals(text(root, "commentsViewMode"))) throw failure();
      emptyArrays(root, ROOT_EMPTY_ARRAYS);
      boolean carlitoTheme = properties(required(root, "properties"));
      JsonNode sheets = required(root, "sheets");
      if (!sheets.isArray() || (sheets.size() < 8 || sheets.size() > 12)) throw failure();
      var tables = new TreeMap<String,List<List<String>>>();
      var grids = new TreeMap<String,Grid>();
      var sheetMetadata = new TreeMap<String,JsonNode>();
      Set<Integer> ids = new HashSet<>(), indices = new HashSet<>();
      for (JsonNode sheet : sheets) {
        fields(sheet, union(Set.of("properties", "data", "basicFilter"), SHEET_EMPTY_ARRAYS));
        emptyArrays(sheet, SHEET_EMPTY_ARRAYS); emptyObject(sheet, "basicFilter");
        JsonNode p = required(sheet, "properties");
        fields(p, Set.of("sheetId", "title", "index", "sheetType", "gridProperties", "hidden", "rightToLeft",
            "tabColor", "tabColorStyle", "dataSourceSheetProperties"));
        String title = text(p, "title");
        List<String> header = ExperimentStorage.headers(title);
        int id = integer(p, "sheetId", 0, Integer.MAX_VALUE), index = optionalInteger(p, "index", 0, 0, 11);
        int reserved = title.equals(ExperimentStorage.BOOT)?1900000001:title.equals(ExperimentStorage.IDENTITY)?1900000002:title.equals(ExperimentStorage.RESULT)?1900000003:title.equals(ExperimentStorage.RECOVERY)?1900000004:-1;
        if(reserved>=0 && id!=reserved || reserved<0 && id>=1900000001 && id<=1900000004)throw failure();
        if (header == null || !ids.add(id) || !indices.add(index)
            || p.has("sheetType") && !"GRID".equals(text(p, "sheetType"))) throw failure();
        falseBoolean(p, "hidden"); falseBoolean(p, "rightToLeft");
        for (String name : List.of("tabColor", "tabColorStyle", "dataSourceSheetProperties")) emptyObject(p, name);
        JsonNode dimensions = required(p, "gridProperties");
        fields(dimensions, Set.of("rowCount", "columnCount", "frozenRowCount", "frozenColumnCount", "hideGridlines",
            "rowGroupControlAfter", "columnGroupControlAfter"));
        int rows = integer(dimensions, "rowCount", 1, MAX_ROWS);
        int columns = integer(dimensions, "columnCount", header.size(), MAX_COLUMNS);
        optionalInteger(dimensions, "frozenRowCount", 0, 0, 0);
        optionalInteger(dimensions, "frozenColumnCount", 0, 0, 0);
        falseBoolean(dimensions, "hideGridlines");
        // These locate group controls only; groups themselves and hidden rows/columns remain forbidden.
        for (String name : List.of("rowGroupControlAfter", "columnGroupControlAfter"))
          if (dimensions.has(name) && !dimensions.get(name).isBoolean()) throw failure();
        Grid grid = new Grid(id, rows, columns, index);
        ParsedGrid parsed = parseGrid(title, grid, required(sheet, "data"), header.size(), carlitoTheme);
        if (grids.put(title, grid) != null) throw failure();
        tables.put(title, parsed.rows());
        ObjectNode metadata = without(sheet, Set.of("data"));
        metadata.set("gridDataMetadata", parsed.metadata());
        sheetMetadata.put(title, metadata);
      }
      if (!grids.keySet().containsAll(ExperimentStorage.HEADERS.keySet())) throw failure();
      // Do not expose completeKnownLayout=true until every grid, dimension and metadata field has passed.
      var snapshot = new ExperimentGateway.Snapshot(binding.spreadsheetId(), true, tables);
      ExperimentStorage.validate(snapshot, binding, clock);
      ObjectNode metadata = without(root, Set.of("sheets"));
      metadata.set("sheetsByTitle", JSON.valueToTree(sheetMetadata));
      metadata.put("boundDatasetId", binding.datasetId());
      String metadataFingerprint = hash(canonical(metadata));
      ArrayNode whole = JSON.createArrayNode(); whole.add("experiment-workbook-v1");
      whole.add(metadataFingerprint); whole.add(JSON.valueToTree(snapshot.tables()));
      return new Workbook(snapshot, grids, hash(canonical(whole)), metadataFingerprint);
    } catch (Exception | LinkageError rejected) { throw failure(); }
  }

  private static ParsedGrid parseGrid(String title, Grid grid, JsonNode blocks, int schemaWidth, boolean carlitoTheme) {
    if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw failure();
    String[][] cells = new String[grid.rowCount()][grid.columnCount()];
    for (String[] row : cells) Arrays.fill(row, "");
    boolean[][] assigned = new boolean[grid.rowCount()][grid.columnCount()];
    JsonNode[] rowMetadata = new JsonNode[grid.rowCount()], columnMetadata = new JsonNode[grid.columnCount()];
    var cellMetadata = new TreeMap<Integer,JsonNode>();
    for (JsonNode block : blocks) {
      fields(block, Set.of("startRow", "startColumn", "rowData", "rowMetadata", "columnMetadata"));
      int startRow = optionalInteger(block, "startRow", 0, 0, grid.rowCount() - 1);
      int startColumn = optionalInteger(block, "startColumn", 0, 0, grid.columnCount() - 1);
      dimensions(block, "rowMetadata", startRow, rowMetadata);
      dimensions(block, "columnMetadata", startColumn, columnMetadata);
      if (!block.has("rowData")) continue;
      JsonNode rows = block.get("rowData");
      if (!rows.isArray() || rows.size() > grid.rowCount() - startRow) throw failure();
      for (int r = 0; r < rows.size(); r++) {
        JsonNode row = rows.get(r); fields(row, Set.of("values"));
        if (!row.has("values")) continue;
        JsonNode values = row.get("values");
        if (!values.isArray() || values.size() > grid.columnCount() - startColumn) throw failure();
        for (int c = 0; c < values.size(); c++) {
          int rr = r + startRow, cc = c + startColumn;
          if (assigned[rr][cc]) throw failure();
          assigned[rr][cc] = true;
          JsonNode cell = values.get(c);
          String value = parseCell(cell, title, rr, cc, carlitoTheme);
          if (cc >= schemaWidth && !value.isEmpty()) throw failure();
          cells[rr][cc] = value;
          // The only accepted hyperlink is derived from this exact literal and carries no independent metadata.
          ObjectNode meta = without(cell, union(VALUE_FIELDS, Set.of("hyperlink")));
          if (!meta.isEmpty()) cellMetadata.put(rr * grid.columnCount() + cc, meta);
        }
      }
    }
    for (JsonNode entry : rowMetadata) if (entry == null) throw failure();
    for (JsonNode entry : columnMetadata) if (entry == null) throw failure();
    int end = grid.rowCount();
    while (end > 0 && Arrays.stream(cells[end - 1]).allMatch(String::isEmpty)) end--;
    var rows = new ArrayList<List<String>>();
    for (int r = 0; r < end; r++) rows.add(List.copyOf(Arrays.asList(cells[r]).subList(0, schemaWidth)));
    ObjectNode metadata = JSON.createObjectNode();
    metadata.set("rows", JSON.valueToTree(rowMetadata)); metadata.set("columns", JSON.valueToTree(columnMetadata));
    metadata.set("cells", JSON.valueToTree(cellMetadata));
    return new ParsedGrid(List.copyOf(rows), metadata);
  }

  private static void dimensions(JsonNode block, String key, int start, JsonNode[] coverage) {
    if (!block.has(key)) return;
    JsonNode values = block.get(key);
    if (!values.isArray() || values.size() > coverage.length - start) throw failure();
    for (int i = 0; i < values.size(); i++) {
      if (coverage[start + i] != null) throw failure();
      JsonNode dimension = values.get(i);
      fields(dimension, Set.of("hiddenByFilter", "hiddenByUser", "pixelSize", "developerMetadata", "dataSourceColumnReference"));
      falseBoolean(dimension, "hiddenByFilter"); falseBoolean(dimension, "hiddenByUser");
      if (dimension.has("pixelSize")) integer(dimension, "pixelSize", 1, 1000);
      emptyArrays(dimension, Set.of("developerMetadata")); emptyObject(dimension, "dataSourceColumnReference");
      coverage[start + i] = canonical(dimension);
    }
  }

  private static String parseCell(JsonNode cell, String title, int row, int column, boolean carlitoTheme) {
    fields(cell, union(VALUE_FIELDS, Set.of("userEnteredFormat", "effectiveFormat", "hyperlink", "note", "textFormatRuns",
        "dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula", "chipRuns")));
    if (cell.has("note") && !text(cell, "note").isEmpty()) throw failure();
    emptyArrays(cell, Set.of("textFormatRuns", "chipRuns"));
    for (String key : List.of("dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula"))
      emptyObject(cell, key);
    JsonNode entered = cell.get("userEnteredValue"), effective = cell.get("effectiveValue");
    String value;
    if (entered == null || entered.isObject() && entered.isEmpty()) {
      if (effective != null && (!effective.isObject() || !effective.isEmpty())) throw failure();
      value = "";
    } else {
      fields(entered, Set.of("stringValue")); fields(effective, Set.of("stringValue"));
      value = text(entered, "stringValue");
      if (!value.equals(text(effective, "stringValue"))) throw failure();
    }
    if (cell.has("formattedValue") && !value.equals(text(cell, "formattedValue"))) throw failure();
    if (cell.has("hyperlink") && !text(cell, "hyperlink").isEmpty()
        && !(title.equals("auth_identities") && row > 0 && column == 0
            && ISSUER.equals(value) && ISSUER.equals(text(cell, "hyperlink")))) throw failure();
    boolean issuerLink = title.equals("auth_identities") && row > 0 && column == 0
        && ISSUER.equals(value) && cell.has("hyperlink") && ISSUER.equals(text(cell, "hyperlink"));
    if (issuerLink) issuerLinkFormats(cell, carlitoTheme);
    else {
      emptyObject(cell, "userEnteredFormat");
      if (cell.has("effectiveFormat")) plainDefaultFormat(cell.get("effectiveFormat"), carlitoTheme, true);
    }
    return value;
  }

  /** Accept only Google's literal issuer auto-link presentation, never an arbitrary link or rich text. */
  private static void issuerLinkFormats(JsonNode cell, boolean carlitoTheme) {
    if (cell.has("userEnteredFormat")) {
      JsonNode format = cell.get("userEnteredFormat"); fields(format, Set.of("textFormat"));
      if (format.has("textFormat")) {
        JsonNode text = format.get("textFormat"); fields(text, Set.of("link"));
        if (text.has("link")) issuerUri(text.get("link"));
      }
    }
    if (!cell.has("effectiveFormat")) return;
    ObjectNode format = required(cell, "effectiveFormat").deepCopy();
    if (format.has("hyperlinkDisplayType") && "LINKED".equals(text(format, "hyperlinkDisplayType")))
      format.put("hyperlinkDisplayType", "PLAIN_TEXT");
    if (format.has("textFormat")) {
      JsonNode raw = format.get("textFormat"); if (!raw.isObject()) throw failure();
      ObjectNode text = (ObjectNode) raw;
      if (text.has("link")) { issuerUri(text.get("link")); text.remove("link"); }
      if (text.has("underline")) {
        if (!text.get("underline").isBoolean()) throw failure();
        text.put("underline", false);
      }
      if (text.has("foregroundColor")) normalizeIssuerColor(text, "foregroundColor", carlitoTheme);
      if (text.has("foregroundColorStyle")) {
        JsonNode style = text.get("foregroundColorStyle"); fields(style, Set.of("rgbColor"));
        normalizeIssuerColor((ObjectNode)style, "rgbColor", carlitoTheme);
      }
    }
    plainDefaultFormat(format, carlitoTheme, true);
  }
  private static void issuerUri(JsonNode link) {
    fields(link, Set.of("uri")); if (!ISSUER.equals(text(link, "uri"))) throw failure();
  }
  private static void normalizeIssuerColor(ObjectNode parent, String key, boolean carlitoTheme) {
    JsonNode rgb = parent.get(key);
    if (carlitoTheme && rgb != null && rgb.isObject() && rgb.size() == 3
        && exactChannel(rgb, "red", "0.27450982") && exactChannel(rgb, "green", "0.47058824")
        && exactChannel(rgb, "blue", "0.5254902")) parent.putObject(key);
    else color(rgb, 0);
  }
  private static boolean exactChannel(JsonNode rgb, String key, String expected) {
    return rgb.has(key) && rgb.get(key).isNumber()
        && rgb.get(key).decimalValue().compareTo(new BigDecimal(expected)) == 0;
  }

  private static boolean properties(JsonNode p) {
    fields(p, Set.of("title", "locale", "timeZone", "autoRecalc", "defaultFormat", "iterativeCalculationSettings",
        "spreadsheetTheme", "importFunctionsExternalUrlAccessAllowed"));
    String title = text(p, "title");
    if (title.isBlank() || title.length() > 256 || title.chars().anyMatch(Character::isISOControl)
        || !text(p, "locale").matches("[a-z]{2,3}(?:_[A-Z]{2})?")) throw failure();
    ZoneId.of(text(p, "timeZone"));
    if (p.has("autoRecalc") && !"ON_CHANGE".equals(text(p, "autoRecalc"))) throw failure();
    falseBoolean(p, "importFunctionsExternalUrlAccessAllowed");
    emptyObject(p, "iterativeCalculationSettings");
    boolean carlitoTheme = false;
    if (p.has("spreadsheetTheme")) {
      JsonNode theme = p.get("spreadsheetTheme"); fields(theme, Set.of("primaryFontFamily", "themeColors"));
      if (!theme.isEmpty()) { observedCarlitoTheme(theme); carlitoTheme = true; }
    }
    if (carlitoTheme) {
      JsonNode font = required(required(p, "defaultFormat"), "textFormat");
      if (!"Carlito".equals(text(font, "fontFamily")) || integer(font, "fontSize", 11, 11) != 11) throw failure();
    }
    if (p.has("defaultFormat")) plainDefaultFormat(p.get("defaultFormat"), carlitoTheme, false);
    return carlitoTheme;
  }

  private static void observedCarlitoTheme(JsonNode theme) {
    if (!"Carlito".equals(text(theme, "primaryFontFamily"))) throw failure();
    JsonNode colors = required(theme, "themeColors");
    if (!colors.isArray() || colors.size() != CARLITO_THEME_COLORS.size()) throw failure();
    Set<String> seen = new HashSet<>();
    for (JsonNode color : colors) {
      fields(color, Set.of("colorType", "color"));
      String type = text(color, "colorType"); List<String> expected = CARLITO_THEME_COLORS.get(type);
      if (expected == null || !seen.add(type)) throw failure();
      JsonNode style = required(color, "color"); fields(style, Set.of("rgbColor"));
      JsonNode rgb = required(style, "rgbColor"); fields(rgb, Set.of("red", "green", "blue"));
      if (expected.isEmpty()) { if (!rgb.isEmpty()) throw failure(); }
      else {
        List<String> channels = List.of("red", "green", "blue");
        for (int i = 0; i < channels.size(); i++) {
          JsonNode actual = required(rgb, channels.get(i));
          if (!actual.isNumber() || actual.decimalValue().compareTo(new BigDecimal(expected.get(i))) != 0) throw failure();
        }
      }
    }
    if (!seen.equals(CARLITO_THEME_COLORS.keySet())) throw failure();
  }

  /** Only the existing plain profile or the exact observed Carlito theme; no arbitrary/rich-text formats. */
  private static void plainDefaultFormat(JsonNode f, boolean carlitoTheme, boolean effectiveCell) {
    Set<String> base = Set.of("backgroundColor", "backgroundColorStyle", "padding", "verticalAlignment", "wrapStrategy", "textFormat");
    // Exact display-only values observed on literal cells; never extend root defaultFormat.
    fields(f, effectiveCell ? union(base, Set.of("horizontalAlignment", "hyperlinkDisplayType")) : base);
    if (f.has("horizontalAlignment") && !"LEFT".equals(text(f, "horizontalAlignment"))) throw failure();
    if (f.has("hyperlinkDisplayType") && !"PLAIN_TEXT".equals(text(f, "hyperlinkDisplayType"))) throw failure();
    if (f.has("backgroundColor")) color(f.get("backgroundColor"), 1);
    if (f.has("backgroundColorStyle")) {
      JsonNode style = f.get("backgroundColorStyle"); fields(style, Set.of("rgbColor")); color(required(style, "rgbColor"), 1);
    }
    if (f.has("padding")) {
      JsonNode p = f.get("padding"); fields(p, Set.of("top", "bottom", "left", "right"));
      for (String key : List.of("top", "bottom", "left", "right")) if (p.has(key)
          && integer(p, key, 0, 3) != (key.equals("top") || key.equals("bottom") ? 2 : 3)) throw failure();
    }
    if (f.has("verticalAlignment") && !"BOTTOM".equals(text(f, "verticalAlignment"))) throw failure();
    if (f.has("wrapStrategy") && !"OVERFLOW_CELL".equals(text(f, "wrapStrategy"))) throw failure();
    if (f.has("textFormat")) {
      JsonNode t = f.get("textFormat");
      fields(t, Set.of("foregroundColor", "foregroundColorStyle", "fontFamily", "fontSize", "bold", "italic", "strikethrough", "underline"));
      if (t.has("foregroundColor")) color(t.get("foregroundColor"), 0);
      if (t.has("foregroundColorStyle")) {
        JsonNode style = t.get("foregroundColorStyle"); fields(style, Set.of("rgbColor")); color(required(style, "rgbColor"), 0);
      }
      if (t.has("fontFamily") && !(carlitoTheme ? "Carlito".equals(text(t, "fontFamily"))
          : "arial".equalsIgnoreCase(text(t, "fontFamily")))) throw failure();
      if (t.has("fontSize")) integer(t, "fontSize", carlitoTheme ? 11 : 10, carlitoTheme ? 11 : 10);
      for (String key : List.of("bold", "italic", "strikethrough", "underline")) falseBoolean(t, key);
    }
  }
  private static void color(JsonNode color, int channel) {
    fields(color, Set.of("red", "green", "blue", "alpha"));
    for (String key : List.of("red", "green", "blue", "alpha")) {
      double expected = key.equals("alpha") ? 1 : channel;
      if (color.has(key) && (!color.get(key).isNumber() || color.get(key).doubleValue() != expected)) throw failure();
      if (!color.has(key) && !key.equals("alpha") && channel != 0) throw failure();
    }
  }
  /** Canonicalize ordering and absent/empty metadata representations, never drop populated fields. */
  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode result = JSON.createObjectNode(); TreeSet<String> names = new TreeSet<>();
      node.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        JsonNode value = canonical(node.get(name));
        if (value.isContainerNode() && value.isEmpty() || value.isTextual() && value.textValue().isEmpty()) continue;
        result.set(name, value);
      }
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = JSON.createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result;
    }
    return node;
  }
  private static ObjectNode without(JsonNode node, Set<String> excluded) {
    ObjectNode result = JSON.createObjectNode();
    node.fields().forEachRemaining(entry -> { if (!excluded.contains(entry.getKey())) result.set(entry.getKey(), entry.getValue()); });
    return (ObjectNode)canonical(result);
  }
  private static String hash(JsonNode node) throws Exception { return FinalMatchEnvelope.hash(JSON.writeValueAsString(node)); }
  private static JsonNode required(JsonNode node, String key) {
    if (node == null || !node.isObject() || !node.has(key) || node.get(key).isNull()) throw failure();
    return node.get(key);
  }
  private static String text(JsonNode node, String key) {
    JsonNode value = required(node, key); if (!value.isTextual()) throw failure(); return value.textValue();
  }
  private static int integer(JsonNode node, String key, int min, int max) {
    JsonNode value = required(node, key);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) throw failure();
    return value.intValue();
  }
  private static int optionalInteger(JsonNode node, String key, int fallback, int min, int max) {
    return node.has(key) ? integer(node, key, min, max) : fallback;
  }
  private static void falseBoolean(JsonNode node, String key) {
    if (node.has(key) && (!node.get(key).isBoolean() || node.get(key).booleanValue())) throw failure();
  }
  private static void fields(JsonNode node, Set<String> allowed) {
    if (node == null || !node.isObject()) throw failure();
    node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw failure(); });
  }
  private static void emptyArrays(JsonNode node, Set<String> keys) {
    for (String key : keys) if (node.has(key) && (!node.get(key).isArray() || !node.get(key).isEmpty())) throw failure();
  }
  private static void emptyObject(JsonNode node, String key) {
    if (node.has(key) && (!node.get(key).isObject() || !node.get(key).isEmpty())) throw failure();
  }
  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> result = new HashSet<>(a); result.addAll(b); return result;
  }
  private static IllegalStateException failure() { return new IllegalStateException("Quarter workbook response rejected"); }
}
