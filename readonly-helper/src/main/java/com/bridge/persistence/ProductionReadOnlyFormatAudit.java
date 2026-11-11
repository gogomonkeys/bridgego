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
    MALFORMED_STRUCTURE, UNKNOWN_STRUCTURE, LIMIT_EXCEEDED, OVERLAPPING_RANGES,
    STRING_CELLS, NUMERIC_CELLS, OTHER_VALUE_CELLS,
    USER_TEXT_TYPE_ONLY_EXACT, USER_TEXT_AT_PATTERN_EXACT,
    EFFECTIVE_DEFAULT_LEFT_STRING_EXACT, EFFECTIVE_DEFAULT_RIGHT_NUMBER_EXACT,
    EFFECTIVE_DEFAULT_PLUS_TEXT_TYPE_ONLY_EXACT, EFFECTIVE_DEFAULT_PLUS_TEXT_AT_PATTERN_EXACT,
    EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_TYPE_ONLY_EXACT, EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_AT_PATTERN_EXACT,
    TEXT_FORMAT_PAIR_EXACT, NUMERIC_TEXT_FORMAT_PAIR_EXACT, KNOWN_ISSUER_LINK_STYLE_EXACT, POTENTIAL_AUTOMATIC_EMAIL_LINK_STYLE_EXACT,
    UNRECOGNIZED_LINK, HYPERLINK_ABSENT, HYPERLINK_EMPTY, HYPERLINK_NONEMPTY, HYPERLINK_OTHER
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
   * The general default-plus-TEXT counters include both string LEFT and numeric RIGHT styles;
   * numeric-only counters distinguish the latter without reporting their positions or values.
   */
  public record Summary(boolean completeScan, Map<Counter, Integer> counts,
      Map<Source, Map<Feature, Integer>> formats) {
    public Summary {
      counts = immutableCounts(Counter.class, counts);
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
    if (!audit.preflight(root)) return audit.summary();
    Templates templates = Templates.from(root.path("properties"));
    for (JsonNode sheet : root.get("sheets")) {
      audit.add(Counter.SHEETS_ENUMERATED);
      for (JsonNode block : sheet.get("data")) {
        audit.add(Counter.BLOCKS_ENUMERATED);
        JsonNode rows = block.get("rowData");
        if (rows == null) continue;
        for (JsonNode row : rows) {
          audit.add(Counter.ROWS_ENUMERATED);
          JsonNode cells = row.get("values");
          if (cells == null) continue;
          for (JsonNode cell : cells) audit.cell(cell, templates);
        }
      }
    }
    return audit.summary();
  }

  private static final class Audit {
    final int[] counts = new int[Counter.values().length];
    final int[][] formats = new int[Source.values().length][Feature.values().length];
    boolean complete = true;
    void add(Counter key) { counts[key.ordinal()] = Math.min(MAX_CELLS, counts[key.ordinal()] + 1); }
    void add(Source source, Feature key) { int[] c = formats[source.ordinal()]; c[key.ordinal()] = Math.min(MAX_CELLS, c[key.ordinal()] + 1); }
    boolean problem(Counter key) { complete = false; add(key); return false; }
    Summary summary() {
      EnumMap<Counter, Integer> c = new EnumMap<>(Counter.class);
      for (Counter key : Counter.values()) c.put(key, counts[key.ordinal()]);
      EnumMap<Source, Map<Feature, Integer>> f = new EnumMap<>(Source.class);
      for (Source source : Source.values()) {
        EnumMap<Feature, Integer> values = new EnumMap<>(Feature.class);
        for (Feature key : Feature.values()) values.put(key, formats[source.ordinal()][key.ordinal()]);
        f.put(source, values);
      }
      return new Summary(complete, c, f);
    }
    boolean object(JsonNode node, Set<String> allowed) {
      if (node == null || !node.isObject()) return problem(Counter.MALFORMED_STRUCTURE);
      if (node.size() > allowed.size()) return problem(Counter.UNKNOWN_STRUCTURE);
      boolean good = true;
      for (Iterator<String> it = node.fieldNames(); it.hasNext();) if (!allowed.contains(it.next())) {
        problem(Counter.UNKNOWN_STRUCTURE); good = false;
      }
      return good;
    }
    // Only bounded arrays of rows and dimensions are visited here, never their cell contents.
    // A later, single cell enumeration is allowed only after all ranges and totals are proven bounded.
    boolean preflight(JsonNode root) {
      if (!object(root, ROOT)) return false;
      if (!object(root.get("properties"), PROPERTIES)) return false;
      JsonNode sheets = root.get("sheets");
      if (sheets == null || !sheets.isArray() || sheets.isEmpty()) return problem(Counter.MALFORMED_STRUCTURE);
      if (sheets.size() > 8) return problem(Counter.LIMIT_EXCEEDED);
      if (sheets.size() != 8) problem(Counter.MALFORMED_STRUCTURE);
      Set<Integer> ids = new HashSet<>(), indices = new HashSet<>();
      Set<String> titles = new HashSet<>();
      int totalCells = 0, totalRows = 0;
      for (String key : List.of("developerMetadata", "namedRanges", "dataSources", "dataSourceSchedules", "comments")) if (nonempty(root.get(key))) problem(Counter.UNKNOWN_STRUCTURE);
      for (JsonNode sheet : sheets) {
        if (!object(sheet, SHEET) || !object(sheet.get("properties"), SHEET_PROPERTIES)) return false;
        JsonNode sp = sheet.get("properties");
        int id = integer(sp.get("sheetId"), 0, Integer.MAX_VALUE);
        int index = sp.has("index") ? integer(sp.get("index"), 0, 7) : 0;
        String title = safeText(sp.get("title"));
        if (id < 0 || index < 0 || !ids.add(id) || !indices.add(index)
            || !ProductionReadOnlyWorkbook.TABS.contains(title) || !titles.add(title)) problem(Counter.MALFORMED_STRUCTURE);
        for (String name : SHEET) if (!name.equals("properties") && !name.equals("data") && nonempty(sheet.get(name))) problem(Counter.UNKNOWN_STRUCTURE);
        JsonNode grid = sheet.path("properties").get("gridProperties");
        if (!object(grid, GRID)) return false;
        int height = integer(grid.get("rowCount"), 1, 1000), width = integer(grid.get("columnCount"), 1, 26);
        if (height < 0 || width < 0) return problem(Counter.LIMIT_EXCEEDED);
        if (height != 1000 || width != 26) problem(Counter.MALFORMED_STRUCTURE);
        JsonNode blocks = sheet.get("data");
        if (blocks == null || !blocks.isArray() || blocks.isEmpty()) return problem(Counter.MALFORMED_STRUCTURE);
        if (blocks.size() > MAX_BLOCKS) return problem(Counter.LIMIT_EXCEEDED);
        BitSet occupied = new BitSet(height * width), rowDimensions = new BitSet(height), columnDimensions = new BitSet(width);
        for (JsonNode block : blocks) {
          if (!object(block, BLOCK)) return false;
          int sr = block.has("startRow") ? integer(block.get("startRow"), 0, height - 1) : 0;
          int sc = block.has("startColumn") ? integer(block.get("startColumn"), 0, width - 1) : 0;
          if (sr < 0 || sc < 0) return problem(Counter.MALFORMED_STRUCTURE);
          if (!dimensions(block.get("rowMetadata"), sr, height, rowDimensions)
              || !dimensions(block.get("columnMetadata"), sc, width, columnDimensions)) return false;
          JsonNode rows = block.get("rowData");
          if (rows == null) continue;
          if (!rows.isArray()) return problem(Counter.MALFORMED_STRUCTURE);
          if (rows.size() > height - sr || rows.size() > MAX_CELLS - totalRows) return problem(Counter.LIMIT_EXCEEDED);
          totalRows += rows.size();
          for (int r = 0; r < rows.size(); r++) {
            JsonNode row = rows.get(r);
            if (!object(row, ROW)) return false;
            JsonNode values = row.get("values");
            if (values == null) continue;
            if (!values.isArray()) return problem(Counter.MALFORMED_STRUCTURE);
            if (values.size() > width - sc || values.size() > MAX_CELLS - totalCells) return problem(Counter.LIMIT_EXCEEDED);
            int from = (sr + r) * width + sc, to = from + values.size();
            int overlap = occupied.nextSetBit(from);
            if (overlap >= 0 && overlap < to) return problem(Counter.OVERLAPPING_RANGES);
            occupied.set(from, to);
            totalCells += values.size();
          }
        }
        if (rowDimensions.cardinality() != height || columnDimensions.cardinality() != width) problem(Counter.MALFORMED_STRUCTURE);
      }
      return true;
    }
    boolean dimensions(JsonNode nodes, int start, int limit, BitSet occupied) {
      if (nodes == null) return true;
      if (!nodes.isArray()) return problem(Counter.MALFORMED_STRUCTURE);
      if (nodes.size() > limit - start) return problem(Counter.LIMIT_EXCEEDED);
      int overlap = occupied.nextSetBit(start);
      if (overlap >= 0 && overlap < start + nodes.size()) return problem(Counter.OVERLAPPING_RANGES);
      occupied.set(start, start + nodes.size());
      for (JsonNode dimension : nodes) {
        if (!object(dimension, DIMENSION)) return false;
        for (String key : List.of("hiddenByFilter", "hiddenByUser")) if (dimension.has(key) && !dimension.get(key).isBoolean()) return problem(Counter.MALFORMED_STRUCTURE);
        if (dimension.has("pixelSize") && integer(dimension.get("pixelSize"), 1, 1000) < 0) return problem(Counter.MALFORMED_STRUCTURE);
        if (dimension.has("developerMetadata") && (!dimension.get("developerMetadata").isArray() || !dimension.get("developerMetadata").isEmpty())) return problem(Counter.UNKNOWN_STRUCTURE);
        if (dimension.has("dataSourceColumnReference") && (!dimension.get("dataSourceColumnReference").isObject() || !dimension.get("dataSourceColumnReference").isEmpty())) return problem(Counter.UNKNOWN_STRUCTURE);
      }
      return true;
    }
    void cell(JsonNode cell, Templates t) {
      add(Counter.CELLS_ENUMERATED);
      if (!object(cell, CELL)) return;
      String value = stringValue(cell);
      boolean number = singleNumber(cell.get("userEnteredValue"));
      if (value != null) add(Counter.STRING_CELLS);
      else if (number) add(Counter.NUMERIC_CELLS);
      else add(Counter.OTHER_VALUE_CELLS);
      JsonNode entered = cell.get("userEnteredFormat"), effective = cell.get("effectiveFormat");
      format(entered, Source.USER_ENTERED, value, t);
      format(effective, Source.EFFECTIVE, value, t);
      boolean textOnly = exactText(entered, false), textAt = exactText(entered, true);
      if (textOnly) add(Counter.USER_TEXT_TYPE_ONLY_EXACT);
      if (textAt) add(Counter.USER_TEXT_AT_PATTERN_EXACT);
      if (value != null && same(effective, t.left, null)) add(Counter.EFFECTIVE_DEFAULT_LEFT_STRING_EXACT);
      if (number && same(effective, t.right, null)) add(Counter.EFFECTIVE_DEFAULT_RIGHT_NUMBER_EXACT);
      boolean effectiveText = value != null && same(effective, t.text, null) || number && same(effective, t.numberText, null);
      boolean effectiveTextAt = value != null && same(effective, t.textAt, null) || number && same(effective, t.numberTextAt, null);
      if (effectiveText) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_TYPE_ONLY_EXACT);
      if (effectiveTextAt) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_AT_PATTERN_EXACT);
      boolean numericText = number && same(effective, t.numberText, null);
      boolean numericTextAt = number && same(effective, t.numberTextAt, null);
      if (numericText) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_TYPE_ONLY_EXACT);
      if (numericTextAt) add(Counter.EFFECTIVE_DEFAULT_PLUS_TEXT_RIGHT_NUMBER_AT_PATTERN_EXACT);
      if (textOnly && effectiveText || textAt && effectiveTextAt) add(Counter.TEXT_FORMAT_PAIR_EXACT);
      if (textOnly && numericText || textAt && numericTextAt) add(Counter.NUMERIC_TEXT_FORMAT_PAIR_EXACT);
      JsonNode hyperlink = cell.get("hyperlink");
      if (hyperlink == null) add(Counter.HYPERLINK_ABSENT);
      else if (!hyperlink.isTextual()) add(Counter.HYPERLINK_OTHER);
      else if (hyperlink.textValue().isEmpty()) add(Counter.HYPERLINK_EMPTY);
      else add(Counter.HYPERLINK_NONEMPTY);
      boolean issuer = ISSUER.equals(value) && textEquals(hyperlink, ISSUER)
          && same(entered, t.enteredLink, null) && same(effective, t.link, null);
      boolean email = emailCandidate(value) && mailto(hyperlink, value)
          && same(entered, t.enteredLink, value) && same(effective, t.link, value);
      if (issuer) add(Counter.KNOWN_ISSUER_LINK_STYLE_EXACT);
      if (email) add(Counter.POTENTIAL_AUTOMATIC_EMAIL_LINK_STYLE_EXACT);
      if (!issuer && !email && (nonempty(hyperlink) || hasLink(entered) || hasLink(effective))) add(Counter.UNRECOGNIZED_LINK);
    }
    boolean formatFields(JsonNode node, Set<String> fields, Source source) {
      if (node == null || !node.isObject()) { problem(Counter.MALFORMED_STRUCTURE); return false; }
      if (node.size() > fields.size()) { add(source, Feature.UNKNOWN_FIELD); problem(Counter.UNKNOWN_STRUCTURE); return false; }
      boolean valid = true;
      for (Iterator<String> it = node.fieldNames(); it.hasNext();) if (!fields.contains(it.next())) {
        add(source, Feature.UNKNOWN_FIELD); problem(Counter.UNKNOWN_STRUCTURE); valid = false;
      }
      return valid;
    }
    void format(JsonNode f, Source s, String cellValue, Templates t) {
      if (f == null) { add(s, Feature.ABSENT); return; }
      if (!f.isObject()) { add(s, Feature.NOT_OBJECT); problem(Counter.MALFORMED_STRUCTURE); return; }
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
    ObjectNode left, right, text, textAt, numberText, numberTextAt, enteredLink, link, linkColor;
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
      t.left.put("horizontalAlignment", "LEFT").put("hyperlinkDisplayType", "PLAIN_TEXT");
      t.right = t.left.deepCopy(); t.right.put("horizontalAlignment", "RIGHT");
      t.text = t.left.deepCopy(); t.text.putObject("numberFormat").put("type", "TEXT");
      t.textAt = t.text.deepCopy(); ((ObjectNode)t.textAt.get("numberFormat")).put("pattern", "@");
      t.numberText = t.text.deepCopy(); t.numberText.put("horizontalAlignment", "RIGHT");
      t.numberTextAt = t.textAt.deepCopy(); t.numberTextAt.put("horizontalAlignment", "RIGHT");
      // This exact hyperlink profile is established only for the native Arial profile.
      if (!"Arial".equals(font) || !t.left.path("textFormat").isObject()) return t;
      t.enteredLink = JsonNodeFactory.instance.objectNode();
      t.enteredLink.putObject("textFormat").putObject("link").put("uri", ISSUER);
      t.link = t.left.deepCopy(); t.link.put("hyperlinkDisplayType", "LINKED");
      ObjectNode tf = (ObjectNode)t.link.get("textFormat");
      tf.put("underline", true); tf.putObject("link").put("uri", ISSUER);
      tf.set("foregroundColor", t.linkColor); tf.putObject("foregroundColorStyle").set("rgbColor", t.linkColor);
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
