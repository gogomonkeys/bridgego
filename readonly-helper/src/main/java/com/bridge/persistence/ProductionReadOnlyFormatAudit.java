package com.bridge.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Observational, format-only totals over an already parsed resource. Never an acceptance decision. */
final class ProductionReadOnlyFormatAudit {
  static final int MAX_CELLS = 8 * 1000 * 26;
  private static final int MAX_BLOCKS = 1000;
  private static final String ISSUER = "https://accounts.google.com";
  private static final Set<String> ROOT = Set.of("spreadsheetId", "spreadsheetUrl", "properties", "sheets", "commentsViewMode", "developerMetadata", "namedRanges", "dataSources", "dataSourceSchedules", "comments");
  private static final Set<String> PROPERTIES = Set.of("title", "locale", "timeZone", "autoRecalc", "defaultFormat", "iterativeCalculationSettings", "spreadsheetTheme", "importFunctionsExternalUrlAccessAllowed");
  private static final Set<String> SHEET = Set.of("properties", "data", "basicFilter", "merges", "conditionalFormats", "filterViews", "protectedRanges", "charts", "bandedRanges", "developerMetadata", "rowGroups", "columnGroups", "slicers", "tables", "commentAnchors");
  private static final Set<String> SHEET_PROPERTIES = Set.of("sheetId", "title", "index", "sheetType", "gridProperties", "hidden", "rightToLeft", "tabColor", "tabColorStyle", "dataSourceSheetProperties");
  private static final Set<String> GRID = Set.of("rowCount", "columnCount", "frozenRowCount", "frozenColumnCount", "hideGridlines", "rowGroupControlAfter", "columnGroupControlAfter");
  private static final Set<String> BLOCK = Set.of("startRow", "startColumn", "rowData", "rowMetadata", "columnMetadata");
  private static final Set<String> ROW = Set.of("values");
  private static final Set<String> DIMENSION = Set.of("hiddenByFilter", "hiddenByUser", "pixelSize", "developerMetadata", "dataSourceColumnReference");
  private static final Set<String> CELL = Set.of("userEnteredValue", "effectiveValue", "formattedValue", "userEnteredFormat", "effectiveFormat", "hyperlink", "note", "textFormatRuns", "dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula", "chipRuns");
  private static final Set<String> FORMAT = Set.of("numberFormat", "backgroundColor", "backgroundColorStyle", "padding", "verticalAlignment", "horizontalAlignment", "wrapStrategy", "textFormat", "hyperlinkDisplayType");
  private static final Set<String> TEXT_FORMAT = Set.of("foregroundColor", "foregroundColorStyle", "fontFamily", "fontSize", "bold", "italic", "strikethrough", "underline", "link");
  private static final Set<String> RGB = Set.of("red", "green", "blue", "alpha");
  private static final Set<String> COLOR_STYLE = Set.of("rgbColor", "themeColor");
  private static final Set<String> NUMBER_FORMAT = Set.of("type", "pattern");
  private static final Set<String> ENTERED_TEXT_FORMAT = Set.of("numberFormat");
  private static final Set<String> LINK = Set.of("uri");
  private static final Set<String> PADDING = Set.of("top", "bottom", "left", "right");
  private static final Set<String> VALUES = Set.of("stringValue", "numberValue", "boolValue", "formulaValue", "errorValue");

  public enum Counter {
    SHEETS_ENUMERATED, BLOCKS_ENUMERATED, ROWS_ENUMERATED, CELLS_ENUMERATED,
    SHEETS_SKIPPED, BLOCKS_SKIPPED, ROWS_SKIPPED, CELL_SLOTS_SKIPPED,
    MALFORMED_STRUCTURE, UNKNOWN_STRUCTURE, LIMIT_EXCEEDED, OVERLAPPING_RANGES,
    STRING_CELLS, NUMERIC_CELLS, BLANK_CELLS, OTHER_VALUE_CELLS,
    USER_TEXT_TYPE_ONLY_EXACT, USER_TEXT_AT_PATTERN_EXACT,
    EFFECTIVE_DEFAULT_LEFT_STRING_EXACT, EFFECTIVE_DEFAULT_RIGHT_NUMBER_EXACT,
    EFFECTIVE_DEFAULT_PLUS_TEXT_TYPE_ONLY_EXACT, EFFECTIVE_DEFAULT_PLUS_TEXT_AT_PATTERN_EXACT,
    EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_TYPE_ONLY_EXACT, EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_AT_PATTERN_EXACT,
    EFFECTIVE_DEFAULT_BLANK_EXACT, EFFECTIVE_DEFAULT_PLUS_TEXT_BLANK_TYPE_ONLY_EXACT,
    EFFECTIVE_DEFAULT_PLUS_TEXT_BLANK_AT_PATTERN_EXACT,
    TEXT_FORMAT_PAIR_EXACT, NUMERIC_TEXT_FORMAT_PAIR_EXACT, BLANK_TEXT_FORMAT_PAIR_EXACT, KNOWN_ISSUER_LINK_STYLE_EXACT, POTENTIAL_AUTOMATIC_EMAIL_LINK_STYLE_EXACT,
    UNRECOGNIZED_LINK, HYPERLINK_ABSENT, HYPERLINK_EMPTY, HYPERLINK_NONEMPTY, HYPERLINK_OTHER
  }
  /** Reasons identify only fixed structural branches, never a resource, table, field value or position. */
  public enum Reason {
    ROOT_NOT_OBJECT, ROOT_FIELDS_UNKNOWN, ROOT_PROPERTIES_NOT_OBJECT, ROOT_PROPERTIES_FIELDS_UNKNOWN, ROOT_EXTRA_CONTENT,
    SHEETS_ABSENT, SHEETS_NOT_ARRAY, SHEETS_EMPTY, SHEET_COUNT_MISMATCH, SHEET_COUNT_LIMIT,
    SHEET_NOT_OBJECT, SHEET_FIELDS_UNKNOWN, SHEET_PROPERTIES_NOT_OBJECT, SHEET_PROPERTIES_FIELDS_UNKNOWN,
    SHEET_ID_INVALID, SHEET_ID_DUPLICATE, SHEET_INDEX_INVALID, SHEET_INDEX_DUPLICATE,
    SHEET_TITLE_INVALID, SHEET_TITLE_DUPLICATE, SHEET_EXTRA_CONTENT,
    GRID_NOT_OBJECT, GRID_FIELDS_UNKNOWN, GRID_ROW_COUNT_INVALID, GRID_COLUMN_COUNT_INVALID, GRID_DIMENSIONS_NOT_PRODUCTION,
    BLOCKS_ABSENT, BLOCKS_NOT_ARRAY, BLOCKS_EMPTY, BLOCK_COUNT_LIMIT, BLOCK_NOT_OBJECT, BLOCK_FIELDS_UNKNOWN,
    START_ROW_INVALID, START_COLUMN_INVALID,
    ROW_METADATA_NOT_ARRAY, ROW_METADATA_RANGE_LIMIT, ROW_METADATA_OVERLAP, ROW_METADATA_ITEM_NOT_OBJECT,
    ROW_METADATA_FIELDS_UNKNOWN, ROW_METADATA_BOOLEAN_INVALID,
    ROW_METADATA_PIXEL_SIZE_NON_NUMERIC, ROW_METADATA_PIXEL_SIZE_NON_INTEGRAL_TYPE,
    ROW_METADATA_PIXEL_SIZE_NONPOSITIVE, ROW_METADATA_PIXEL_SIZE_ABOVE1000_TO10000, ROW_METADATA_PIXEL_SIZE_ABOVE10000,
    ROW_METADATA_EXTRA_CONTENT, ROW_METADATA_COVERAGE_GAP,
    COLUMN_METADATA_NOT_ARRAY, COLUMN_METADATA_RANGE_LIMIT, COLUMN_METADATA_OVERLAP, COLUMN_METADATA_ITEM_NOT_OBJECT,
    COLUMN_METADATA_FIELDS_UNKNOWN, COLUMN_METADATA_BOOLEAN_INVALID,
    COLUMN_METADATA_PIXEL_SIZE_NON_NUMERIC, COLUMN_METADATA_PIXEL_SIZE_NON_INTEGRAL_TYPE,
    COLUMN_METADATA_PIXEL_SIZE_NONPOSITIVE, COLUMN_METADATA_PIXEL_SIZE_ABOVE1000_TO10000, COLUMN_METADATA_PIXEL_SIZE_ABOVE10000,
    COLUMN_METADATA_EXTRA_CONTENT, COLUMN_METADATA_COVERAGE_GAP,
    ROWS_NOT_ARRAY, ROW_RANGE_LIMIT, SHEET_ROW_LIMIT, TOTAL_ROW_LIMIT, ROW_NOT_OBJECT, ROW_FIELDS_UNKNOWN,
    VALUES_NOT_ARRAY, COLUMN_RANGE_LIMIT, TOTAL_CELL_LIMIT, CELL_RANGE_OVERLAP,
    CELL_NOT_OBJECT, CELL_FIELDS_UNKNOWN, FORMAT_NOT_OBJECT, FORMAT_FIELDS_UNKNOWN,
    FORMAT_COMPONENT_NOT_OBJECT, FORMAT_COMPONENT_FIELDS_UNKNOWN
  }
  public enum Source { USER_ENTERED, EFFECTIVE }
  public enum Feature {
    ABSENT, EMPTY, NONEMPTY, NOT_OBJECT, UNKNOWN_FIELD,
    NUMBER_FORMAT_PRESENT, NUMBER_TEXT, NUMBER_NUMBER, NUMBER_DATE, NUMBER_OTHER,
    NUMBER_PATTERN_AT, NUMBER_PATTERN_OTHER,
    TEXT_FORMAT_PRESENT, FONT_ARIAL, FONT_CARLITO, FONT_ROOT_ALIAS, FONT_OTHER,
    FONT_SIZE_DEFAULT, FONT_SIZE_OTHER,
    HORIZONTAL_LEFT, HORIZONTAL_RIGHT, HORIZONTAL_CENTER, HORIZONTAL_OTHER,
    VERTICAL_BOTTOM, VERTICAL_OTHER,
    WRAP_OVERFLOW_CELL, WRAP_WRAP, WRAP_CLIP, WRAP_OTHER,
    BOLD_TRUE, BOLD_FALSE, BOLD_OTHER, UNDERLINE_TRUE, UNDERLINE_FALSE, UNDERLINE_OTHER,
    ITALIC_TRUE, ITALIC_FALSE, ITALIC_OTHER, STRIKETHROUGH_TRUE, STRIKETHROUGH_FALSE, STRIKETHROUGH_OTHER,
    BACKGROUND_DEFAULT, BACKGROUND_THEME_LINK, BACKGROUND_OTHER,
    BACKGROUND_STYLE_DEFAULT, BACKGROUND_STYLE_THEME_LINK, BACKGROUND_STYLE_OTHER,
    FOREGROUND_DEFAULT, FOREGROUND_THEME_LINK, FOREGROUND_OTHER,
    FOREGROUND_STYLE_DEFAULT, FOREGROUND_STYLE_THEME_LINK, FOREGROUND_STYLE_OTHER,
    PADDING_DEFAULT, PADDING_OTHER, HYPERLINK_DISPLAY_PLAIN_TEXT, HYPERLINK_DISPLAY_LINKED, HYPERLINK_DISPLAY_OTHER,
    LINK_ISSUER, LINK_MAILTO_SAME_STRING, LINK_OTHER
  }

  /** Every leaf is a saturated integer or boolean; keys come exclusively from fixed enums.
   * completeScan means bounded enumeration of the recognized production envelope, never schema,
   * business, format acceptance, mutation permission, or writer-exclusion verification.
   * The general default-plus-TEXT counters include string LEFT, numeric RIGHT and exact blank styles;
   * numeric-only and blank-only counters distinguish those cases without reporting positions or values.
   * When completeScan is false, totals cover only the safely bounded, nonduplicated subset.
   */
  public record Summary(boolean completeScan, Map<Counter, Integer> counts,
      Map<Source, Map<Feature, Integer>> formats, Map<Reason, Integer> reasons) {
    public Summary(boolean completeScan, Map<Counter, Integer> counts, Map<Source, Map<Feature, Integer>> formats) {
      this(completeScan, counts, formats, Map.of());
    }
    public Summary {
      counts = immutableCounts(Counter.class, counts);
      reasons = immutableCounts(Reason.class, reasons);
      EnumMap<Source, Map<Feature, Integer>> copy = new EnumMap<>(Source.class);
      for (Source source : Source.values()) copy.put(source,
          immutableCounts(Feature.class, formats == null ? null : formats.get(source)));
      formats = Collections.unmodifiableMap(copy);
    }
  }

  private static <E extends Enum<E>> Map<E, Integer> immutableCounts(Class<E> type, Map<E, Integer> input) {
    EnumMap<E, Integer> copy = new EnumMap<>(type);
    for (E key : type.getEnumConstants()) {
      Integer value = input == null ? null : input.get(key);
      copy.put(key, value == null ? 0 : Math.max(0, Math.min(MAX_CELLS, value)));
    }
    return Collections.unmodifiableMap(copy);
  }

  private ProductionReadOnlyFormatAudit() {}

  static Summary inspect(JsonNode root) {
    Audit audit = new Audit();
    audit.preflight(root);
    Templates templates = Templates.from(root != null && root.isObject() ? root.path("properties") : JsonNodeFactory.instance.objectNode());
    // The plan holds only bounded row-array references until this call returns. No cell data is copied.
    for (JsonNode cells : audit.safeRows) for (JsonNode cell : cells) audit.cell(cell, templates);
    return audit.summary();
  }

  private static final class Audit {
    final int[] counts = new int[Counter.values().length];
    final int[][] formats = new int[Source.values().length][Feature.values().length];
    final int[] reasons = new int[Reason.values().length];
    final List<JsonNode> safeRows = new ArrayList<>();
    int totalCells, totalRows;
    boolean complete = true;
    void add(Counter key) { add(key, 1); }
    void add(Counter key, int amount) { counts[key.ordinal()] = (int)Math.min(MAX_CELLS, (long)counts[key.ordinal()] + Math.max(0, amount)); }
    void add(Source source, Feature key) { int[] c = formats[source.ordinal()]; c[key.ordinal()] = Math.min(MAX_CELLS, c[key.ordinal()] + 1); }
    void problem(Counter category, Reason reason) {
      complete = false; add(category); reasons[reason.ordinal()] = Math.min(MAX_CELLS, reasons[reason.ordinal()] + 1);
    }
    Summary summary() {
      EnumMap<Counter, Integer> c = new EnumMap<>(Counter.class);
      for (Counter key : Counter.values()) c.put(key, counts[key.ordinal()]);
      EnumMap<Source, Map<Feature, Integer>> f = new EnumMap<>(Source.class);
      for (Source source : Source.values()) {
        EnumMap<Feature, Integer> values = new EnumMap<>(Feature.class);
        for (Feature key : Feature.values()) values.put(key, formats[source.ordinal()][key.ordinal()]);
        f.put(source, values);
      }
      EnumMap<Reason, Integer> r = new EnumMap<>(Reason.class);
      for (Reason key : Reason.values()) r.put(key, reasons[key.ordinal()]);
      return new Summary(complete, c, f, r);
    }
    // Unknown keys are never descended into. Even a large object may expose bounded known children
    // through fixed-key lookups; its arbitrary key set is not iterated when it exceeds this bound.
    boolean object(JsonNode node, Set<String> allowed, Reason wrongType, Reason unknown) {
      if (node == null || !node.isObject()) { problem(Counter.MALFORMED_STRUCTURE, wrongType); return false; }
      if (node.size() > allowed.size()) { problem(Counter.UNKNOWN_STRUCTURE, unknown); return true; }
      for (Iterator<String> it = node.fieldNames(); it.hasNext();) if (!allowed.contains(it.next())) problem(Counter.UNKNOWN_STRUCTURE, unknown);
      return true;
    }
    // Build a bounded plan of nonoverlapping, in-range cell arrays. A malformed sibling or
    // dimension annotation never suppresses another independently bounded row/block/sheet.
    void preflight(JsonNode root) {
      if (!object(root, ROOT, Reason.ROOT_NOT_OBJECT, Reason.ROOT_FIELDS_UNKNOWN)) return;
      object(root.get("properties"), PROPERTIES, Reason.ROOT_PROPERTIES_NOT_OBJECT, Reason.ROOT_PROPERTIES_FIELDS_UNKNOWN);
      for (String key : List.of("developerMetadata", "namedRanges", "dataSources", "dataSourceSchedules", "comments")) if (nonempty(root.get(key))) problem(Counter.UNKNOWN_STRUCTURE, Reason.ROOT_EXTRA_CONTENT);
      JsonNode sheets = root.get("sheets");
      if (sheets == null) { problem(Counter.MALFORMED_STRUCTURE, Reason.SHEETS_ABSENT); return; }
      if (!sheets.isArray()) { problem(Counter.MALFORMED_STRUCTURE, Reason.SHEETS_NOT_ARRAY); return; }
      if (sheets.isEmpty()) { problem(Counter.MALFORMED_STRUCTURE, Reason.SHEETS_EMPTY); return; }
      if (sheets.size() > 8) { problem(Counter.LIMIT_EXCEEDED, Reason.SHEET_COUNT_LIMIT); add(Counter.SHEETS_SKIPPED, sheets.size() - 8); }
      if (sheets.size() != 8) problem(Counter.MALFORMED_STRUCTURE, Reason.SHEET_COUNT_MISMATCH);
      Set<Integer> ids = new HashSet<>(), indices = new HashSet<>();
      Set<String> titles = new HashSet<>();
      for (int i = 0; i < Math.min(8, sheets.size()); i++) {
        JsonNode sheet = sheets.get(i);
        if (!object(sheet, SHEET, Reason.SHEET_NOT_OBJECT, Reason.SHEET_FIELDS_UNKNOWN)) { add(Counter.SHEETS_SKIPPED); continue; }
        add(Counter.SHEETS_ENUMERATED);
        JsonNode sp = sheet.get("properties");
        if (!object(sp, SHEET_PROPERTIES, Reason.SHEET_PROPERTIES_NOT_OBJECT, Reason.SHEET_PROPERTIES_FIELDS_UNKNOWN)) { add(Counter.SHEETS_SKIPPED); continue; }
        int id = integer(sp.get("sheetId"), 0, Integer.MAX_VALUE);
        int index = sp.has("index") ? integer(sp.get("index"), 0, 7) : 0;
        String title = safeText(sp.get("title"));
        if (id < 0) problem(Counter.MALFORMED_STRUCTURE, Reason.SHEET_ID_INVALID);
        else if (!ids.add(id)) {
          problem(Counter.MALFORMED_STRUCTURE, Reason.SHEET_ID_DUPLICATE);
          add(Counter.SHEETS_SKIPPED); continue; // Do not count the same declared physical sheet twice.
        }
        if (index < 0) problem(Counter.MALFORMED_STRUCTURE, Reason.SHEET_INDEX_INVALID);
        else if (!indices.add(index)) problem(Counter.MALFORMED_STRUCTURE, Reason.SHEET_INDEX_DUPLICATE);
        if (!ProductionReadOnlyWorkbook.TABS.contains(title)) problem(Counter.MALFORMED_STRUCTURE, Reason.SHEET_TITLE_INVALID);
        else if (!titles.add(title)) problem(Counter.MALFORMED_STRUCTURE, Reason.SHEET_TITLE_DUPLICATE);
        for (String name : SHEET) if (!name.equals("properties") && !name.equals("data") && nonempty(sheet.get(name))) problem(Counter.UNKNOWN_STRUCTURE, Reason.SHEET_EXTRA_CONTENT);
        JsonNode grid = sp.get("gridProperties");
        if (!object(grid, GRID, Reason.GRID_NOT_OBJECT, Reason.GRID_FIELDS_UNKNOWN)) { add(Counter.SHEETS_SKIPPED); continue; }
        int height = integer(grid.get("rowCount"), 1, 1000), width = integer(grid.get("columnCount"), 1, 26);
        if (height < 0) problem(Counter.LIMIT_EXCEEDED, Reason.GRID_ROW_COUNT_INVALID);
        if (width < 0) problem(Counter.LIMIT_EXCEEDED, Reason.GRID_COLUMN_COUNT_INVALID);
        if (height < 0 || width < 0) { add(Counter.SHEETS_SKIPPED); continue; }
        if (height != 1000 || width != 26) problem(Counter.MALFORMED_STRUCTURE, Reason.GRID_DIMENSIONS_NOT_PRODUCTION);
        scanSheet(sheet, height, width);
      }
    }
    void scanSheet(JsonNode sheet, int height, int width) {
      JsonNode blocks = sheet.get("data");
      if (blocks == null) { problem(Counter.MALFORMED_STRUCTURE, Reason.BLOCKS_ABSENT); return; }
      if (!blocks.isArray()) { problem(Counter.MALFORMED_STRUCTURE, Reason.BLOCKS_NOT_ARRAY); return; }
      if (blocks.isEmpty()) { problem(Counter.MALFORMED_STRUCTURE, Reason.BLOCKS_EMPTY); return; }
      if (blocks.size() > MAX_BLOCKS) { problem(Counter.LIMIT_EXCEEDED, Reason.BLOCK_COUNT_LIMIT); add(Counter.BLOCKS_SKIPPED, blocks.size() - MAX_BLOCKS); }
      BitSet occupied = new BitSet(height * width), rowDimensions = new BitSet(height), columnDimensions = new BitSet(width);
      int sheetRows = 0;
      // At most 8 sheets * 1000 block entries are examined globally, including invalid/skipped entries.
      for (int b = 0; b < Math.min(MAX_BLOCKS, blocks.size()); b++) {
        JsonNode block = blocks.get(b);
        if (!object(block, BLOCK, Reason.BLOCK_NOT_OBJECT, Reason.BLOCK_FIELDS_UNKNOWN)) { add(Counter.BLOCKS_SKIPPED); continue; }
        add(Counter.BLOCKS_ENUMERATED);
        int sr = block.has("startRow") ? integer(block.get("startRow"), 0, height - 1) : 0;
        int sc = block.has("startColumn") ? integer(block.get("startColumn"), 0, width - 1) : 0;
        if (sr < 0) problem(Counter.MALFORMED_STRUCTURE, Reason.START_ROW_INVALID);
        if (sc < 0) problem(Counter.MALFORMED_STRUCTURE, Reason.START_COLUMN_INVALID);
        if (sr < 0 || sc < 0) { add(Counter.BLOCKS_SKIPPED); continue; }
        dimensions(block.get("rowMetadata"), sr, height, rowDimensions, true);
        dimensions(block.get("columnMetadata"), sc, width, columnDimensions, false);
        JsonNode rows = block.get("rowData");
        if (rows == null) continue; // Native sparse/default blocks may contain dimensions only.
        if (!rows.isArray()) { problem(Counter.MALFORMED_STRUCTURE, Reason.ROWS_NOT_ARRAY); add(Counter.BLOCKS_SKIPPED); continue; }
        if (rows.size() > height - sr) { problem(Counter.LIMIT_EXCEEDED, Reason.ROW_RANGE_LIMIT); add(Counter.ROWS_SKIPPED, rows.size()); continue; }
        // Reserve attempted row slots, including empty, malformed and overlapping rows. The local
        // share prevents one adversarial sheet from consuming the other seven sheets' work budget.
        if (rows.size() > height * width - sheetRows) { problem(Counter.LIMIT_EXCEEDED, Reason.SHEET_ROW_LIMIT); add(Counter.ROWS_SKIPPED, rows.size()); continue; }
        if (rows.size() > MAX_CELLS - totalRows) { problem(Counter.LIMIT_EXCEEDED, Reason.TOTAL_ROW_LIMIT); add(Counter.ROWS_SKIPPED, rows.size()); continue; }
        totalRows += rows.size(); sheetRows += rows.size();
        for (int r = 0; r < rows.size(); r++) {
          JsonNode row = rows.get(r); add(Counter.ROWS_ENUMERATED);
          if (!object(row, ROW, Reason.ROW_NOT_OBJECT, Reason.ROW_FIELDS_UNKNOWN)) { add(Counter.ROWS_SKIPPED); continue; }
          JsonNode values = row.get("values");
          if (values == null) continue; // Empty native row: no supplied cell slots to enumerate.
          if (!values.isArray()) { problem(Counter.MALFORMED_STRUCTURE, Reason.VALUES_NOT_ARRAY); add(Counter.ROWS_SKIPPED); continue; }
          if (values.size() > width - sc) { problem(Counter.LIMIT_EXCEEDED, Reason.COLUMN_RANGE_LIMIT); add(Counter.CELL_SLOTS_SKIPPED, values.size()); continue; }
          if (values.size() > MAX_CELLS - totalCells) { problem(Counter.LIMIT_EXCEEDED, Reason.TOTAL_CELL_LIMIT); add(Counter.CELL_SLOTS_SKIPPED, values.size()); continue; }
          int from = (sr + r) * width + sc, to = from + values.size();
          int overlap = occupied.nextSetBit(from);
          if (overlap >= 0 && overlap < to) { problem(Counter.OVERLAPPING_RANGES, Reason.CELL_RANGE_OVERLAP); add(Counter.CELL_SLOTS_SKIPPED, values.size()); continue; }
          occupied.set(from, to); totalCells += values.size();
          if (!values.isEmpty()) safeRows.add(values);
        }
      }
      if (rowDimensions.cardinality() != height) problem(Counter.MALFORMED_STRUCTURE, Reason.ROW_METADATA_COVERAGE_GAP);
      if (columnDimensions.cardinality() != width) problem(Counter.MALFORMED_STRUCTURE, Reason.COLUMN_METADATA_COVERAGE_GAP);
    }
    void dimensions(JsonNode nodes, int start, int limit, BitSet occupied, boolean row) {
      if (nodes == null) return;
      if (!nodes.isArray()) { problem(Counter.MALFORMED_STRUCTURE, row ? Reason.ROW_METADATA_NOT_ARRAY : Reason.COLUMN_METADATA_NOT_ARRAY); return; }
      if (nodes.size() > limit - start) { problem(Counter.LIMIT_EXCEEDED, row ? Reason.ROW_METADATA_RANGE_LIMIT : Reason.COLUMN_METADATA_RANGE_LIMIT); return; }
      int overlap = occupied.nextSetBit(start);
      if (overlap >= 0 && overlap < start + nodes.size()) { problem(Counter.OVERLAPPING_RANGES, row ? Reason.ROW_METADATA_OVERLAP : Reason.COLUMN_METADATA_OVERLAP); return; }
      occupied.set(start, start + nodes.size());
      for (JsonNode dimension : nodes) {
        if (!object(dimension, DIMENSION, row ? Reason.ROW_METADATA_ITEM_NOT_OBJECT : Reason.COLUMN_METADATA_ITEM_NOT_OBJECT,
            row ? Reason.ROW_METADATA_FIELDS_UNKNOWN : Reason.COLUMN_METADATA_FIELDS_UNKNOWN)) continue;
        for (String key : List.of("hiddenByFilter", "hiddenByUser")) if (dimension.has(key) && !dimension.get(key).isBoolean()) problem(Counter.MALFORMED_STRUCTURE, row ? Reason.ROW_METADATA_BOOLEAN_INVALID : Reason.COLUMN_METADATA_BOOLEAN_INVALID);
        if (dimension.has("pixelSize")) {
          Reason pixelReason = pixelReason(dimension.get("pixelSize"), row);
          if (pixelReason != null) problem(Counter.MALFORMED_STRUCTURE, pixelReason);
        }
        if (dimension.has("developerMetadata") && (!dimension.get("developerMetadata").isArray() || !dimension.get("developerMetadata").isEmpty())) problem(Counter.UNKNOWN_STRUCTURE, row ? Reason.ROW_METADATA_EXTRA_CONTENT : Reason.COLUMN_METADATA_EXTRA_CONTENT);
        if (dimension.has("dataSourceColumnReference") && (!dimension.get("dataSourceColumnReference").isObject() || !dimension.get("dataSourceColumnReference").isEmpty())) problem(Counter.UNKNOWN_STRUCTURE, row ? Reason.ROW_METADATA_EXTRA_CONTENT : Reason.COLUMN_METADATA_EXTRA_CONTENT);
      }
    }
    void cell(JsonNode cell, Templates t) {
      add(Counter.CELLS_ENUMERATED);
      if (!object(cell, CELL, Reason.CELL_NOT_OBJECT, Reason.CELL_FIELDS_UNKNOWN)) return;
      String value = stringValue(cell);
      boolean number = singleNumber(cell.get("userEnteredValue")), blank = blankCell(cell);
      if (value != null) add(Counter.STRING_CELLS);
      else if (number) add(Counter.NUMERIC_CELLS);
      else if (blank) add(Counter.BLANK_CELLS);
      else add(Counter.OTHER_VALUE_CELLS);
      JsonNode entered = cell.get("userEnteredFormat"), effective = cell.get("effectiveFormat");
      format(entered, Source.USER_ENTERED, value, t);
      format(effective, Source.EFFECTIVE, value, t);
      boolean textOnly = exactText(entered, false), textAt = exactText(entered, true);
      if (textOnly) add(Counter.USER_TEXT_TYPE_ONLY_EXACT);
      if (textAt) add(Counter.USER_TEXT_AT_PATTERN_EXACT);
      if (value != null && same(effective, t.left, null)) add(Counter.EFFECTIVE_DEFAULT_LEFT_STRING_EXACT);
      if (number && same(effective, t.right, null)) add(Counter.EFFECTIVE_DEFAULT_RIGHT_NUMBER_EXACT);
      boolean blankDefault = blank && (same(effective, t.blank, null) || same(effective, t.left, null));
      boolean blankText = blank && (same(effective, t.blankText, null) || same(effective, t.text, null));
      boolean blankTextAt = blank && (same(effective, t.blankTextAt, null) || same(effective, t.textAt, null));
      if (blankDefault) add(Counter.EFFECTIVE_DEFAULT_BLANK_EXACT);
      if (blankText) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_BLANK_TYPE_ONLY_EXACT);
      if (blankTextAt) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_BLANK_AT_PATTERN_EXACT);
      boolean effectiveText = value != null && same(effective, t.text, null) || number && same(effective, t.numberText, null) || blankText;
      boolean effectiveTextAt = value != null && same(effective, t.textAt, null) || number && same(effective, t.numberTextAt, null) || blankTextAt;
      if (effectiveText) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_TYPE_ONLY_EXACT);
      if (effectiveTextAt) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_AT_PATTERN_EXACT);
      boolean numericText = number && same(effective, t.numberText, null);
      boolean numericTextAt = number && same(effective, t.numberTextAt, null);
      if (numericText) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_TYPE_ONLY_EXACT);
      if (numericTextAt) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_AT_PATTERN_EXACT);
      if (textOnly && effectiveText || textAt && effectiveTextAt) add(Counter.TEXT_FORMAT_PAIR_EXACT);
      if (textOnly && numericText || textAt && numericTextAt) add(Counter.NUMERIC_TEXT_FORMAT_PAIR_EXACT);
      if (textOnly && blankText || textAt && blankTextAt) add(Counter.BLANK_TEXT_FORMAT_PAIR_EXACT);
      JsonNode hyperlink = cell.get("hyperlink");
      if (hyperlink == null) add(Counter.HYPERLINK_ABSENT);
      else if (!hyperlink.isTextual()) add(Counter.HYPERLINK_OTHER);
      else if (hyperlink.textValue().isEmpty()) add(Counter.HYPERLINK_EMPTY);
      else add(Counter.HYPERLINK_NONEMPTY);
      boolean issuer = ISSUER.equals(value) && textEquals(hyperlink, ISSUER)
          && ProductionReadOnlyWorkbook.sameKnownFormat(entered, t.enteredLink)
          && ProductionReadOnlyWorkbook.sameKnownFormat(effective, t.link);
      boolean email = emailCandidate(value) && mailto(hyperlink, value)
          && same(entered, t.enteredLink, value) && same(effective, t.link, value);
      if (issuer) add(Counter.KNOWN_ISSUER_LINK_STYLE_EXACT);
      if (email) add(Counter.POTENTIAL_AUTOMATIC_EMAIL_LINK_STYLE_EXACT);
      if (!issuer && !email && (nonempty(hyperlink) || hasLink(entered) || hasLink(effective))) add(Counter.UNRECOGNIZED_LINK);
    }
    boolean formatFields(JsonNode node, Set<String> fields, Source source) {
      if (node == null || !node.isObject()) { problem(Counter.MALFORMED_STRUCTURE, Reason.FORMAT_COMPONENT_NOT_OBJECT); return false; }
      if (node.size() > fields.size()) { add(source, Feature.UNKNOWN_FIELD); problem(Counter.UNKNOWN_STRUCTURE, fields == FORMAT ? Reason.FORMAT_FIELDS_UNKNOWN : Reason.FORMAT_COMPONENT_FIELDS_UNKNOWN); return false; }
      boolean valid = true;
      for (Iterator<String> it = node.fieldNames(); it.hasNext();) if (!fields.contains(it.next())) {
        add(source, Feature.UNKNOWN_FIELD); problem(Counter.UNKNOWN_STRUCTURE, fields == FORMAT ? Reason.FORMAT_FIELDS_UNKNOWN : Reason.FORMAT_COMPONENT_FIELDS_UNKNOWN); valid = false;
      }
      return valid;
    }
    void format(JsonNode f, Source s, String cellValue, Templates t) {
      if (f == null) { add(s, Feature.ABSENT); return; }
      if (!f.isObject()) { add(s, Feature.NOT_OBJECT); problem(Counter.MALFORMED_STRUCTURE, Reason.FORMAT_NOT_OBJECT); return; }
      if (f.isEmpty()) { add(s, Feature.EMPTY); return; }
      add(s, Feature.NONEMPTY);
      if (!formatFields(f, FORMAT, s)) return;
      JsonNode nf = f.get("numberFormat");
      if (nf != null) {
        add(s, Feature.NUMBER_FORMAT_PRESENT);
        if (formatFields(nf, NUMBER_FORMAT, s)) {
          add(s, switch (safeText(nf.get("type"))) { case "TEXT" -> Feature.NUMBER_TEXT; case "NUMBER" -> Feature.NUMBER_NUMBER; case "DATE" -> Feature.NUMBER_DATE; default -> Feature.NUMBER_OTHER; });
          if (nf.has("pattern")) add(s, textEquals(nf.get("pattern"), "@") ? Feature.NUMBER_PATTERN_AT : Feature.NUMBER_PATTERN_OTHER);
        }
      }
      if (f.has("horizontalAlignment")) add(s, switch (safeText(f.get("horizontalAlignment"))) { case "LEFT" -> Feature.HORIZONTAL_LEFT; case "RIGHT" -> Feature.HORIZONTAL_RIGHT; case "CENTER" -> Feature.HORIZONTAL_CENTER; default -> Feature.HORIZONTAL_OTHER; });
      if (f.has("verticalAlignment")) add(s, textEquals(f.get("verticalAlignment"), "BOTTOM") ? Feature.VERTICAL_BOTTOM : Feature.VERTICAL_OTHER);
      if (f.has("wrapStrategy")) add(s, switch (safeText(f.get("wrapStrategy"))) { case "OVERFLOW_CELL" -> Feature.WRAP_OVERFLOW_CELL; case "WRAP" -> Feature.WRAP_WRAP; case "CLIP" -> Feature.WRAP_CLIP; default -> Feature.WRAP_OTHER; });
      if (f.has("hyperlinkDisplayType")) add(s, switch (safeText(f.get("hyperlinkDisplayType"))) { case "PLAIN_TEXT" -> Feature.HYPERLINK_DISPLAY_PLAIN_TEXT; case "LINKED" -> Feature.HYPERLINK_DISPLAY_LINKED; default -> Feature.HYPERLINK_DISPLAY_OTHER; });
      colorFeature(f, "backgroundColor", s, false, true, t);
      colorFeature(f, "backgroundColorStyle", s, true, true, t);
      if (f.has("padding")) {
        JsonNode p = f.get("padding"); boolean fields = formatFields(p, PADDING, s);
        add(s, fields && defaultPadding(p) ? Feature.PADDING_DEFAULT : Feature.PADDING_OTHER);
      }
      JsonNode tf = f.get("textFormat");
      if (tf == null) return;
      add(s, Feature.TEXT_FORMAT_PRESENT);
      if (!formatFields(tf, TEXT_FORMAT, s)) return;
      if (tf.has("fontFamily")) add(s, switch (safeText(tf.get("fontFamily"))) { case "Arial" -> Feature.FONT_ARIAL; case "Carlito" -> Feature.FONT_CARLITO; case "arial,sans,sans-serif" -> Feature.FONT_ROOT_ALIAS; default -> Feature.FONT_OTHER; });
      if (tf.has("fontSize")) add(s, integer(tf.get("fontSize"), t.fontSize, t.fontSize) >= 0 ? Feature.FONT_SIZE_DEFAULT : Feature.FONT_SIZE_OTHER);
      booleanFeature(tf, "bold", s, Feature.BOLD_TRUE, Feature.BOLD_FALSE, Feature.BOLD_OTHER);
      booleanFeature(tf, "underline", s, Feature.UNDERLINE_TRUE, Feature.UNDERLINE_FALSE, Feature.UNDERLINE_OTHER);
      booleanFeature(tf, "italic", s, Feature.ITALIC_TRUE, Feature.ITALIC_FALSE, Feature.ITALIC_OTHER);
      booleanFeature(tf, "strikethrough", s, Feature.STRIKETHROUGH_TRUE, Feature.STRIKETHROUGH_FALSE, Feature.STRIKETHROUGH_OTHER);
      colorFeature(tf, "foregroundColor", s, false, false, t);
      colorFeature(tf, "foregroundColorStyle", s, true, false, t);
      if (tf.has("link")) {
        JsonNode link = tf.get("link"); boolean fields = formatFields(link, LINK, s);
        JsonNode uri = fields ? link.get("uri") : null;
        add(s, textEquals(uri, ISSUER) ? Feature.LINK_ISSUER : cellValue != null && mailto(uri, cellValue) ? Feature.LINK_MAILTO_SAME_STRING : Feature.LINK_OTHER);
      }
    }
    void booleanFeature(JsonNode n, String key, Source s, Feature yes, Feature no, Feature other) {
      JsonNode value = n.get(key);
      if (value != null) add(s, value.isBoolean() ? value.booleanValue() ? yes : no : other);
    }
    void colorFeature(JsonNode parent, String key, Source s, boolean style, boolean background, Templates t) {
      JsonNode color = parent.get(key);
      if (color == null) return;
      boolean fields = formatFields(color, style ? COLOR_STYLE : RGB, s);
      if (fields && style && color.has("rgbColor")) fields = formatFields(color.get("rgbColor"), RGB, s);
      boolean normal = fields && (style ? color.size() == 1 && color.has("rgbColor") && defaultRgb(color.get("rgbColor"), background ? 1 : 0) : defaultRgb(color, background ? 1 : 0));
      boolean linked = fields && (style && color.size() == 1 && textEquals(color.get("themeColor"), "LINK")
          || (!style || color.size() == 1) && same(style ? color.get("rgbColor") : color, t.linkColor, null));
      Feature category;
      if (background && style) category = normal ? Feature.BACKGROUND_STYLE_DEFAULT : linked ? Feature.BACKGROUND_STYLE_THEME_LINK : Feature.BACKGROUND_STYLE_OTHER;
      else if (background) category = normal ? Feature.BACKGROUND_DEFAULT : linked ? Feature.BACKGROUND_THEME_LINK : Feature.BACKGROUND_OTHER;
      else if (style) category = normal ? Feature.FOREGROUND_STYLE_DEFAULT : linked ? Feature.FOREGROUND_STYLE_THEME_LINK : Feature.FOREGROUND_STYLE_OTHER;
      else category = normal ? Feature.FOREGROUND_DEFAULT : linked ? Feature.FOREGROUND_THEME_LINK : Feature.FOREGROUND_OTHER;
      add(s, category);
    }
  }

  private static final class Templates {
    ObjectNode left, right, text, textAt, numberText, numberTextAt, blank, blankText, blankTextAt, enteredLink, link, linkColor;
    int fontSize = 10;
    static Templates from(JsonNode properties) {
      Templates t = new Templates();
      String font = safeText(properties.path("spreadsheetTheme").get("primaryFontFamily"));
      t.fontSize = "Carlito".equals(font) ? 11 : 10;
      if ("Arial".equals(font)) t.linkColor = rgb("0.06666667", "0.33333334", "0.8");
      else if ("Carlito".equals(font)) t.linkColor = rgb("0.27450982", "0.47058824", "0.5254902");
      JsonNode rootDefault = properties.get("defaultFormat");
      if (!safeDefault(rootDefault)) return t;
      t.left = rootDefault.deepCopy();
      if (textEquals(t.left.path("textFormat").get("fontFamily"), "arial,sans,sans-serif")) ((ObjectNode)t.left.get("textFormat")).put("fontFamily", "Arial");
      t.blank = t.left.deepCopy();
      t.blankText = t.blank.deepCopy(); t.blankText.putObject("numberFormat").put("type", "TEXT");
      t.blankTextAt = t.blankText.deepCopy(); ((ObjectNode)t.blankTextAt.get("numberFormat")).put("pattern", "@");
      t.left.put("horizontalAlignment", "LEFT").put("hyperlinkDisplayType", "PLAIN_TEXT");
      t.right = t.left.deepCopy(); t.right.put("horizontalAlignment", "RIGHT");
      t.text = t.left.deepCopy(); t.text.putObject("numberFormat").put("type", "TEXT");
      t.textAt = t.text.deepCopy(); ((ObjectNode)t.textAt.get("numberFormat")).put("pattern", "@");
      t.numberText = t.text.deepCopy(); t.numberText.put("horizontalAlignment", "RIGHT");
      t.numberTextAt = t.textAt.deepCopy(); t.numberTextAt.put("horizontalAlignment", "RIGHT");
      // Reuse the parser's exact validated Arial/Carlito theme and root default, never just its font name.
      try { t.link = ProductionReadOnlyWorkbook.nativeIssuerLinkFormat(properties); }
      catch (RuntimeException invalidProfile) { return t; }
      t.enteredLink = JsonNodeFactory.instance.objectNode();
      t.enteredLink.putObject("textFormat").putObject("link").put("uri", ISSUER);
      return t;
    }
  }

  private static ObjectNode rgb(String r, String g, String b) {
    return JsonNodeFactory.instance.objectNode().put("red", new BigDecimal(r)).put("green", new BigDecimal(g)).put("blue", new BigDecimal(b));
  }
  private static boolean safeDefault(JsonNode f) {
    if (!hasOnly(f, FORMAT) || f.isEmpty() || f.has("numberFormat") || f.has("horizontalAlignment") || f.has("hyperlinkDisplayType")) return false;
    for (String key : List.of("backgroundColor", "backgroundColorStyle")) if (f.has(key)) {
      JsonNode c = f.get(key);
      if (key.endsWith("Style")) { if (!hasOnly(c, Set.of("rgbColor")) || !c.has("rgbColor")) return false; c = c.get("rgbColor"); }
      if (!defaultRgb(c, 1)) return false;
    }
    if (f.has("padding") && !defaultPadding(f.get("padding"))) return false;
    if (f.has("verticalAlignment") && !textEquals(f.get("verticalAlignment"), "BOTTOM")) return false;
    if (f.has("wrapStrategy") && !textEquals(f.get("wrapStrategy"), "OVERFLOW_CELL")) return false;
    JsonNode tf = f.get("textFormat");
    if (tf == null) return true;
    if (!hasOnly(tf, TEXT_FORMAT) || tf.has("link")) return false;
    if (tf.has("fontFamily") && !Set.of("Arial", "arial", "Carlito", "arial,sans,sans-serif").contains(safeText(tf.get("fontFamily")))) return false;
    if (tf.has("fontSize") && integer(tf.get("fontSize"), 10, 11) < 0) return false;
    for (String key : List.of("bold", "italic", "underline", "strikethrough")) if (tf.has(key) && (!tf.get(key).isBoolean() || tf.get(key).booleanValue())) return false;
    for (String key : List.of("foregroundColor", "foregroundColorStyle")) if (tf.has(key)) {
      JsonNode c = tf.get(key);
      if (key.endsWith("Style")) { if (!hasOnly(c, Set.of("rgbColor")) || !c.has("rgbColor")) return false; c = c.get("rgbColor"); }
      if (!defaultRgb(c, 0)) return false;
    }
    return true;
  }
  private static boolean defaultPadding(JsonNode n) {
    if (!hasOnly(n, PADDING)) return false;
    for (String key : PADDING) if (n.has(key) && integer(n.get(key), key.equals("top") || key.equals("bottom") ? 2 : 3, key.equals("top") || key.equals("bottom") ? 2 : 3) < 0) return false;
    return true;
  }
  private static boolean defaultRgb(JsonNode n, int channel) {
    if (!hasOnly(n, RGB)) return false;
    for (String key : RGB) {
      JsonNode value = n.get(key);
      if (value == null) { if (channel == 1 && !key.equals("alpha")) return false; }
      else if (!value.isNumber() || value.decimalValue().compareTo(BigDecimal.valueOf(key.equals("alpha") ? 1 : channel)) != 0) return false;
    }
    return true;
  }
  private static boolean exactText(JsonNode f, boolean at) {
    if (!hasOnly(f, ENTERED_TEXT_FORMAT) || f.size() != 1) return false;
    JsonNode n = f.get("numberFormat");
    return hasOnly(n, NUMBER_FORMAT) && n.size() == (at ? 2 : 1) && textEquals(n.get("type"), "TEXT") && (!at || textEquals(n.get("pattern"), "@"));
  }
  private static String stringValue(JsonNode cell) {
    JsonNode u = cell.get("userEnteredValue"), e = cell.get("effectiveValue");
    if (!hasOnly(u, VALUES) || u.size() != 1 || !u.has("stringValue") || !u.get("stringValue").isTextual()) return null;
    String value = u.get("stringValue").textValue();
    if (!hasOnly(e, VALUES) || e.size() != 1 || !textEquals(e.get("stringValue"), value)
        || cell.has("formattedValue") && !textEquals(cell.get("formattedValue"), value)) return null;
    return value;
  }
  private static Reason pixelReason(JsonNode value, boolean row) {
    if (!value.isNumber()) return row ? Reason.ROW_METADATA_PIXEL_SIZE_NON_NUMERIC : Reason.COLUMN_METADATA_PIXEL_SIZE_NON_NUMERIC;
    if (!value.isIntegralNumber()) return row ? Reason.ROW_METADATA_PIXEL_SIZE_NON_INTEGRAL_TYPE : Reason.COLUMN_METADATA_PIXEL_SIZE_NON_INTEGRAL_TYPE;
    if (value.bigIntegerValue().signum() <= 0) return row ? Reason.ROW_METADATA_PIXEL_SIZE_NONPOSITIVE : Reason.COLUMN_METADATA_PIXEL_SIZE_NONPOSITIVE;
    if (integer(value, 1, row ? 1000 : 10000) >= 0) return null;
    // Retain the fixed legacy column bucket in the output schema, but supported widths no longer fail completeness.
    if (integer(value, 1001, 10000) >= 0) return Reason.ROW_METADATA_PIXEL_SIZE_ABOVE1000_TO10000;
    return row ? Reason.ROW_METADATA_PIXEL_SIZE_ABOVE10000 : Reason.COLUMN_METADATA_PIXEL_SIZE_ABOVE10000;
  }
  private static boolean blankCell(JsonNode cell) {
    JsonNode u = cell.get("userEnteredValue"), e = cell.get("effectiveValue"), f = cell.get("formattedValue");
    return (u == null || u.isObject() && u.isEmpty()) && (e == null || e.isObject() && e.isEmpty())
        && (f == null || f.isTextual() && f.textValue().isEmpty());
  }
  private static boolean singleNumber(JsonNode n) { return hasOnly(n, VALUES) && n.size() == 1 && n.has("numberValue") && n.get("numberValue").isNumber(); }
  private static boolean emailCandidate(String value) {
    if (value == null || value.length() > 20000) return false;
    int at = value.indexOf('@');
    if (at < 1 || at == value.length() - 1 || at != value.lastIndexOf('@')) return false;
    for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) return false;
    return true;
  }
  private static boolean mailto(JsonNode uri, String value) {
    if (value == null || uri == null || !uri.isTextual()) return false;
    String actual = uri.textValue();
    return actual.length() == 7 + value.length() && actual.startsWith("mailto:") && actual.regionMatches(7, value, 0, value.length());
  }
  private static boolean hasLink(JsonNode f) { return f != null && f.isObject() && f.path("textFormat").isObject() && f.path("textFormat").has("link"); }
  private static boolean nonempty(JsonNode n) { return n != null && !(n.isContainerNode() && n.isEmpty()) && !(n.isTextual() && n.textValue().isEmpty()); }
  private static String safeText(JsonNode n) { return n != null && n.isTextual() ? n.textValue() : ""; }
  private static boolean textEquals(JsonNode n, String value) { return n != null && n.isTextual() && value.equals(n.textValue()); }
  private static int integer(JsonNode n, int min, int max) { return n != null && n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= min && n.intValue() <= max ? n.intValue() : -1; }
  private static boolean hasOnly(JsonNode n, Set<String> fields) {
    if (n == null || !n.isObject() || n.size() > fields.size()) return false;
    for (Iterator<String> it = n.fieldNames(); it.hasNext();) if (!fields.contains(it.next())) return false;
    return true;
  }
  // Compare only the small prevalidated template tree. Never canonicalize, copy, hash, or serialize a cell.
  // The only omission equivalence is an empty known object, matching the parser's default RGB omission.
  private static boolean same(JsonNode actual, JsonNode expected, String email) {
    if (actual == null || expected == null) return false;
    if (expected.isObject()) {
      if (!actual.isObject() || actual.size() > expected.size()) return false;
      for (Iterator<String> it = actual.fieldNames(); it.hasNext();) if (!expected.has(it.next())) return false;
      for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext();) {
        Map.Entry<String, JsonNode> field = it.next(); JsonNode a = actual.get(field.getKey());
        if (a == null && emptyKnown(field.getValue())) continue;
        if (email != null && field.getKey().equals("uri")) { if (!mailto(a, email)) return false; }
        else if (!same(a, field.getValue(), email)) return false;
      }
      return true;
    }
    if (expected.isNumber()) return actual.isNumber() && actual.decimalValue().compareTo(expected.decimalValue()) == 0;
    return expected.equals(actual);
  }
  private static boolean emptyKnown(JsonNode n) {
    if (!n.isObject()) return false;
    for (JsonNode child : n) if (!emptyKnown(child)) return false;
    return true;
  }
}
