package com.bridge.persistence;

import com.bridge.persistence.SyntheticSheetsErasurePreparation.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strict read-only protocol/parser boundary. No HTTP client, authentication or credential access exists here. */
public final class SyntheticSheetsReadOnlyAdapter implements WholeWorkbookGateway {
  static final int MAX_RESPONSE_BYTES = 262144;
  static final long TIMEOUT_MILLIS = 5000;
  static final String COMMENTS = "COMMENTS_VIEW_MODE_INCLUDED";
  private static final Cell EMPTY = new Cell("EMPTY", "", "");
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_RESPONSE_BYTES)
          .maxNestingDepth(24).maxStringLength(8192).maxNameLength(128).maxNumberLength(20).maxTokenCount(30000).build())
      .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> ROOT_EMPTY_ARRAYS = Set.of("namedRanges", "developerMetadata", "dataSources", "dataSourceSchedules", "comments");
  private static final Set<String> SHEET_EMPTY_ARRAYS = Set.of("merges", "conditionalFormats", "filterViews", "protectedRanges", "charts",
      "bandedRanges", "developerMetadata", "rowGroups", "columnGroups", "slicers", "tables", "commentAnchors");
  private final Manifest manifest;
  private final Transport transport;
  private final ReadRequest request;
  private final AtomicBoolean active = new AtomicBoolean();

  record ReadRequest(String method, URI uri, Map<String,String> headers, boolean followRedirects,
      Duration timeout, int maxResponseBytes) {
    ReadRequest { headers = Map.copyOf(headers); }
  }
  record ReadResponse(int status, URI finalUri, String contentType, String contentEncoding,
      long contentLength, InputStream body) implements AutoCloseable {
    public void close() throws IOException { if (body != null) body.close(); }
  }
  interface Transport {
    /** Future implementations must honor the fixed deadline/body bound and interruption, and never redirect/retry. */
    ReadResponse execute(ReadRequest request) throws Exception;
  }
  static final class ReadFailure extends RuntimeException {
    ReadFailure() { super("Whole-workbook read rejected or unavailable"); }
  }

  SyntheticSheetsReadOnlyAdapter(Manifest manifest, Transport transport) { this(manifest, transport, TIMEOUT_MILLIS); }
  SyntheticSheetsReadOnlyAdapter(Manifest manifest, Transport transport, long timeoutMillis) {
    if (manifest == null || transport == null || timeoutMillis < 1 || timeoutMillis > TIMEOUT_MILLIS) throw failure();
    this.manifest = manifest; this.transport = transport;
    String target = manifest.sheetId();
    if (!target.matches("[A-Za-z0-9_-]{10,160}")) throw failure();
    request = new ReadRequest("GET", URI.create("https://sheets.googleapis.com/v4/spreadsheets/" + target
        + "?includeGridData=true&commentsViewMode=" + COMMENTS),
        Map.of("Accept", "application/json", "Accept-Encoding", "identity"), false,
        Duration.ofMillis(timeoutMillis), MAX_RESPONSE_BYTES);
  }

  @Override public Workbook readComplete() {
    if (!active.compareAndSet(false, true)) throw failure(); // never pile up retries behind an unresponsive transport
    FutureTask<Workbook> work = new FutureTask<>(() -> {
      try { return readOnce(); }
      finally { active.set(false); }
    });
    Thread worker = new Thread(work, "synthetic-read-only-adapter"); worker.setDaemon(true); worker.start();
    try { return work.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS); }
    catch (InterruptedException interrupted) { work.cancel(true); Thread.currentThread().interrupt(); throw failure(); }
    catch (Exception rejected) { work.cancel(true); throw failure(); }
  }

  private Workbook readOnce() {
    try (ReadResponse response = transport.execute(request)) {
      if (response == null || response.status() != 200 || !request.uri().equals(response.finalUri()) || response.body() == null
          || response.contentType() == null || !response.contentType().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
          || response.contentEncoding() == null || !Set.of("", "identity").contains(response.contentEncoding())
          || response.contentLength() < -1 || response.contentLength() > MAX_RESPONSE_BYTES || Thread.currentThread().isInterrupted()) throw failure();
      byte[] body = response.body().readNBytes(MAX_RESPONSE_BYTES + 1);
      if (body.length > MAX_RESPONSE_BYTES || response.contentLength() >= 0 && response.contentLength() != body.length
          || Thread.currentThread().isInterrupted()) throw failure();
      return parse(body, manifest);
    } catch (Exception | LinkageError invalid) { throw failure(); }
  }

  @Override public void replaceAtomic(Replacement ignored) {
    throw new UnsupportedOperationException("Read-only adapter: replacement is disabled"); // before transport, unconditionally
  }

  /** Pure parser, no fallback fetches. complete is set only after every bounded grid and metadata check succeeds. */
  static Workbook parse(byte[] bytes, Manifest manifest) {
    try {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES || manifest == null) throw failure();
      JsonNode root = JSON.readTree(bytes);
      fields(root, union(Set.of("spreadsheetId", "spreadsheetUrl", "properties", "sheets", "commentsViewMode"), ROOT_EMPTY_ARRAYS));
      if (!manifest.sheetId().equals(text(root, "spreadsheetId")) || !COMMENTS.equals(text(root, "commentsViewMode"))
          || !("https://docs.google.com/spreadsheets/d/" + manifest.sheetId() + "/edit").equals(text(root, "spreadsheetUrl"))) throw failure();
      emptyArrays(root, ROOT_EMPTY_ARRAYS);
      properties(required(root, "properties"), manifest);
      JsonNode sheets = required(root, "sheets");
      if (!sheets.isArray() || sheets.size() != 8) throw failure();
      Map<String,Grid> grids = new TreeMap<>(); Set<Integer> ids = new HashSet<>(), indices = new HashSet<>();
      List<String> titles = new ArrayList<>(new TreeSet<>(SyntheticSheetsErasurePreparation.TABLES));
      for (JsonNode sheet : sheets) {
        fields(sheet, union(Set.of("properties", "data", "basicFilter"), SHEET_EMPTY_ARRAYS));
        emptyArrays(sheet, SHEET_EMPTY_ARRAYS); emptyObject(sheet, "basicFilter");
        JsonNode p = required(sheet, "properties");
        fields(p, Set.of("sheetId", "title", "index", "sheetType", "gridProperties", "hidden", "rightToLeft",
            "tabColor", "tabColorStyle", "dataSourceSheetProperties"));
        String title = text(p, "title"); Grid expected = manifest.baseline().sheets().get(title);
        int id = integer(p, "sheetId", 0, Integer.MAX_VALUE), index = optionalInteger(p, "index", 0, 0, 7);
        if (expected == null || id != expected.sheetId() || index != titles.indexOf(title) || !ids.add(id) || !indices.add(index)
            || !"GRID".equals(p.has("sheetType") ? text(p, "sheetType") : "GRID")) throw failure();
        falseBoolean(p, "hidden"); falseBoolean(p, "rightToLeft");
        for (String name : List.of("tabColor", "tabColorStyle", "dataSourceSheetProperties")) emptyObject(p, name);
        JsonNode dimensions = required(p, "gridProperties");
        fields(dimensions, Set.of("rowCount", "columnCount", "frozenRowCount", "frozenColumnCount", "hideGridlines", "rowGroupControlAfter", "columnGroupControlAfter"));
        if (integer(dimensions, "rowCount", 1, 32) != 32 || integer(dimensions, "columnCount", 1, 5) != 5
            || optionalInteger(dimensions, "frozenRowCount", 0, 0, 0) != 0 || optionalInteger(dimensions, "frozenColumnCount", 0, 0, 0) != 0) throw failure();
        for (String name : List.of("hideGridlines", "rowGroupControlAfter", "columnGroupControlAfter")) falseBoolean(dimensions, name);
        Grid grid = parseGrid(id, required(sheet, "data"));
        if (grids.put(title, grid) != null) throw failure();
      }
      Workbook result = new Workbook(manifest.sheetId(), true, false, grids);
      SyntheticSheetsErasurePreparation.validateWholeWorkbook(result, manifest);
      return result;
    } catch (Exception | LinkageError rejected) { throw failure(); }
  }

  private static Grid parseGrid(int id, JsonNode blocks) {
    if (!blocks.isArray() || blocks.size() < 1 || blocks.size() > 32) throw failure();
    List<List<Cell>> cells = new ArrayList<>();
    for (int r = 0; r < 32; r++) cells.add(new ArrayList<>(Collections.nCopies(5, EMPTY)));
    boolean[][] assigned = new boolean[32][5]; boolean[] rowMetadata = new boolean[32], columnMetadata = new boolean[5];
    for (JsonNode block : blocks) {
      fields(block, Set.of("startRow", "startColumn", "rowData", "rowMetadata", "columnMetadata"));
      int rowStart = optionalInteger(block, "startRow", 0, 0, 31), colStart = optionalInteger(block, "startColumn", 0, 0, 4);
      dimensions(block, "rowMetadata", rowStart, rowMetadata);
      dimensions(block, "columnMetadata", colStart, columnMetadata);
      if (!block.has("rowData")) continue; // no cell values in this metadata-only block
      JsonNode rows = block.get("rowData");
      if (!rows.isArray() || rows.size() > 32 - rowStart) throw failure();
      for (int r = 0; r < rows.size(); r++) {
        JsonNode row = rows.get(r); fields(row, Set.of("values"));
        if (!row.has("values")) continue;
        JsonNode values = row.get("values"); if (!values.isArray() || values.size() > 5 - colStart) throw failure();
        for (int c = 0; c < values.size(); c++) {
          if (assigned[rowStart + r][colStart + c]) throw failure();
          assigned[rowStart + r][colStart + c] = true;
          cells.get(rowStart + r).set(colStart + c, parseCell(values.get(c)));
        }
      }
    }
    // Do not infer whole-grid visibility from data dimensions alone or use ValueRange omission rules as proof.
    for (boolean present : rowMetadata) if (!present) throw failure();
    for (boolean present : columnMetadata) if (!present) throw failure();
    return new Grid(id, 32, 5, false, false, cells);
  }

  private static void dimensions(JsonNode block, String key, int start, boolean[] coverage) {
    if (!block.has(key)) return;
    JsonNode values = block.get(key);
    if (!values.isArray() || values.size() > coverage.length - start) throw failure();
    for (int n = 0; n < values.size(); n++) {
      if (coverage[start + n]) throw failure();
      coverage[start + n] = true;
      JsonNode dimension = values.get(n);
      fields(dimension, Set.of("hiddenByFilter", "hiddenByUser", "pixelSize", "developerMetadata", "dataSourceColumnReference"));
      falseBoolean(dimension, "hiddenByFilter"); falseBoolean(dimension, "hiddenByUser");
      if (dimension.has("pixelSize")) integer(dimension, "pixelSize", 1, 1000);
      emptyArrays(dimension, Set.of("developerMetadata")); emptyObject(dimension, "dataSourceColumnReference");
    }
  }

  private static Cell parseCell(JsonNode cell) {
    fields(cell, Set.of("userEnteredValue", "effectiveValue", "formattedValue", "userEnteredFormat", "effectiveFormat", "hyperlink", "note",
        "textFormatRuns", "dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula", "chipRuns"));
    for (String key : List.of("hyperlink", "note")) if (cell.has(key) && !text(cell, key).isEmpty()) throw failure();
    emptyArrays(cell, Set.of("textFormatRuns", "chipRuns"));
    for (String key : List.of("userEnteredFormat", "dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula")) emptyObject(cell, key);
    if (cell.has("effectiveFormat")) plainDefaultFormat(cell.get("effectiveFormat"));
    JsonNode entered = cell.get("userEnteredValue"), effective = cell.get("effectiveValue");
    if (entered == null || entered.isObject() && entered.isEmpty()) {
      if (effective != null && (!effective.isObject() || !effective.isEmpty())
          || cell.has("formattedValue") && !text(cell, "formattedValue").isEmpty()) throw failure();
      return EMPTY;
    }
    fields(entered, Set.of("stringValue"));
    String value = text(entered, "stringValue");
    fields(effective, Set.of("stringValue"));
    if (!value.equals(text(effective, "stringValue")) || !value.equals(text(cell, "formattedValue"))) throw failure();
    return new Cell("TEXT", value, "");
  }

  private static void properties(JsonNode p, Manifest manifest) {
    fields(p, Set.of("title", "locale", "timeZone", "autoRecalc", "defaultFormat", "iterativeCalculationSettings", "spreadsheetTheme", "importFunctionsExternalUrlAccessAllowed"));
    if (!("BridgeGo Synthetic Erasure " + manifest.datasetId()).equals(text(p, "title"))
        || !"en_US".equals(text(p, "locale")) || !"Etc/UTC".equals(text(p, "timeZone"))) throw failure();
    if (p.has("autoRecalc") && !"ON_CHANGE".equals(text(p, "autoRecalc"))) throw failure();
    falseBoolean(p, "importFunctionsExternalUrlAccessAllowed");
    emptyObject(p, "iterativeCalculationSettings"); emptyObject(p, "spreadsheetTheme");
    if (p.has("defaultFormat")) plainDefaultFormat(p.get("defaultFormat"));
  }

  /** A deliberately narrow default-style allowlist, never arbitrary hyperlinks/fonts/format metadata. */
  private static void plainDefaultFormat(JsonNode f) {
    fields(f, Set.of("backgroundColor", "backgroundColorStyle", "padding", "verticalAlignment", "wrapStrategy", "textFormat"));
    if (f.has("backgroundColor")) color(f.get("backgroundColor"), 1);
    if (f.has("backgroundColorStyle")) {
      JsonNode style = f.get("backgroundColorStyle"); fields(style, Set.of("rgbColor")); color(required(style, "rgbColor"), 1);
    }
    if (f.has("padding")) {
      JsonNode p = f.get("padding"); fields(p, Set.of("top", "bottom", "left", "right"));
      for (String k : List.of("top", "bottom", "left", "right")) if (p.has(k)) {
        int expected = k.equals("top") || k.equals("bottom") ? 2 : 3;
        if (integer(p, k, 0, 3) != expected) throw failure();
      }
    }
    if (f.has("verticalAlignment") && !"BOTTOM".equals(text(f, "verticalAlignment"))) throw failure();
    if (f.has("wrapStrategy") && !"OVERFLOW_CELL".equals(text(f, "wrapStrategy"))) throw failure();
    if (f.has("textFormat")) {
      JsonNode t = f.get("textFormat"); fields(t, Set.of("foregroundColor", "foregroundColorStyle", "fontFamily", "fontSize", "bold", "italic", "strikethrough", "underline"));
      if (t.has("foregroundColor")) color(t.get("foregroundColor"), 0);
      if (t.has("foregroundColorStyle")) {
        JsonNode style = t.get("foregroundColorStyle"); fields(style, Set.of("rgbColor")); color(required(style, "rgbColor"), 0);
      }
      if (t.has("fontFamily") && !"arial".equalsIgnoreCase(text(t, "fontFamily"))) throw failure();
      if (t.has("fontSize") && integer(t, "fontSize", 10, 10) != 10) throw failure();
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
  private static Set<String> union(Set<String> a, Set<String> b) { Set<String> result = new HashSet<>(a); result.addAll(b); return result; }
  private static ReadFailure failure() { return new ReadFailure(); }
}
