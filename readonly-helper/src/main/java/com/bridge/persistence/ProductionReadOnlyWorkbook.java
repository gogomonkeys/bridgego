package com.bridge.persistence;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Pure, bounded parser for one unmasked full-workbook response. No credentials, transport or fallback reads. */
public final class ProductionReadOnlyWorkbook {
  public static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
  static final String COMMENTS = "COMMENTS_VIEW_MODE_INCLUDED";
  private static final int MAX_BLOCKS = 1000;
  private static final String ISSUER = "https://accounts.google.com";
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_RESPONSE_BYTES)
          .maxNestingDepth(24).maxStringLength(20000).maxNameLength(128).maxNumberLength(20)
          .maxTokenCount(4_000_000).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final Set<String> ROOT_EMPTY_ARRAYS = Set.of("namedRanges", "dataSources",
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

  private enum DisplayProfile { PLAIN, CARLITO, ARIAL_NATIVE }
  private static final Map<String,List<String>> ARIAL_THEME_COLORS=Map.of(
      "TEXT", List.of(),
      "BACKGROUND", List.of("1","1","1"),
      "ACCENT1", List.of("0.25882354","0.52156866","0.95686275"),
      "ACCENT2", List.of("0.91764706","0.2627451","0.20784314"),
      "ACCENT3", List.of("0.9843137","0.7372549","0.015686275"),
      "ACCENT4", List.of("0.20392157","0.65882355","0.3254902"),
      "ACCENT5", List.of("1","0.42745098","0.003921569"),
      "ACCENT6", List.of("0.27450982","0.7411765","0.7764706"),
      "LINK", List.of("0.06666667","0.33333334","0.8"));


  static final List<String> TABS=List.of("dataset","accounts","auth_identities","envelopes","commits","erasure_ledger","retention_ledger","quarterly_aggregates");
  static final Map<String,List<String>> HEADERS=Map.of(
      "dataset",List.of("purpose","dataset_id"),
      "accounts",List.of("account_id","schema_version","nickname","email"),
      "auth_identities",List.of("issuer","subject","account_id","schema_version"),
      "envelopes",List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk"),
      "commits",List.of("commit_id","payload_hash","chunk_count","match_id","revision"),
      "erasure_ledger",List.of("kind","identifier_hash","schema_version"),
      "retention_ledger",List.of("quarter","cutoff_utc","state","completed_at","schema_version"),
      "quarterly_aggregates",List.of("quarter","publication","matches_rounded","deals_rounded","schema_version"));
  public enum Status { STRUCTURE_OBSERVED_LAYOUT_UNAPPROVED, STRUCTURE_COMPATIBLE_ONLY }
  /** Contains no table values, target IDs, player details or authority to mutate anything. */
  public record Report(Status status,String profileVersion,String targetHash,String nativeSha256,
      String layoutHash,String normalizedDataHash,String literalTypesHash,Map<String,Integer> dataRows) {
    public Report { dataRows=Collections.unmodifiableMap(new TreeMap<>(dataRows)); }
    public boolean mutationPermitted(){return false;}
    public boolean businessSemanticsVerified(){return false;}
    public boolean writerExclusionVerified(){return false;}
    @Override public String toString(){return "ProductionReadOnlyReport["+status+", mutationPermitted=false]";}
  }
  record Grid(int sheetId,int rowCount,int columnCount,int index) {}
  private record ParsedGrid(List<List<String>> rows,JsonNode metadata) {}
  private ProductionReadOnlyWorkbook() {}

  /** Pure compatibility observation. Never establishes a reviewed layout, coherent cutover or business validity. */
  public static Report inspect(byte[] bytes,ProductionReadOnlyTarget target) { return parse(bytes,target,null); }
  /** The expected fingerprint must come from an independently reviewed layout, not this request's response. */
  public static Report verify(byte[] bytes,ProductionReadOnlyTarget target,String expectedLayoutHash) {
    if(expectedLayoutHash==null || !expectedLayoutHash.matches("[a-f0-9]{64}"))throw failure();
    return parse(bytes,target,expectedLayoutHash);
  }
  static Report inspectDiagnosed(byte[] bytes,ProductionReadOnlyTarget target,ProductionReadOnlyDiagnostics diagnostics) {return parse(bytes,target,null,diagnostics);}
  private static Report parse(byte[] bytes,ProductionReadOnlyTarget target,String expectedLayout) {return parse(bytes,target,expectedLayout,new ProductionReadOnlyDiagnostics());}
  private static Report parse(byte[] bytes,ProductionReadOnlyTarget target,String expectedLayout,ProductionReadOnlyDiagnostics diagnostics) {
    try {
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.WORKBOOK_JSON);
      if(bytes==null || bytes.length==0 || bytes.length>MAX_RESPONSE_BYTES || target==null)throw failure();
      JsonNode root=JSON.readTree(bytes);
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.ROOT_FIELDS);
      fields(root,union(Set.of("spreadsheetId","spreadsheetUrl","properties","sheets","commentsViewMode","developerMetadata"),ROOT_EMPTY_ARRAYS));
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.ROOT_BINDING);
      if(!target.spreadsheetId().equals(text(root,"spreadsheetId"))
          || !("https://docs.google.com/spreadsheets/d/"+target.spreadsheetId()+"/edit").equals(text(root,"spreadsheetUrl"))
          || !COMMENTS.equals(text(root,"commentsViewMode")))throw failure();
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.ROOT_METADATA);
      emptyArrays(root,ROOT_EMPTY_ARRAYS);emptyArrays(root,Set.of("developerMetadata")); // Legacy profile has no guarded receipts.
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.ROOT_TITLE);
      JsonNode p=required(root,"properties");
      if(!target.expectedTitle().equals(text(p,"title")))throw failure();
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.WORKBOOK_THEME);
      DisplayProfile theme=properties(p);
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.SHEET_COLLECTION);
      JsonNode sheets=required(root,"sheets");if(!sheets.isArray() || sheets.size()!=8)throw failure();
      var layouts=new TreeMap<String,JsonNode>();var tables=new TreeMap<String,List<List<String>>>();
      var counts=new TreeMap<String,Integer>();var types=new TreeMap<String,JsonNode>();
      Set<Integer> ids=new HashSet<>(),indices=new HashSet<>();
      for(JsonNode sheet:sheets) {
        diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.SHEET_FEATURES);
        fields(sheet,union(Set.of("properties","data","basicFilter"),SHEET_EMPTY_ARRAYS));
        emptyArrays(sheet,SHEET_EMPTY_ARRAYS);emptyObject(sheet,"basicFilter");
        diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.SHEET_PROPERTIES);
        JsonNode sp=required(sheet,"properties");
        fields(sp,Set.of("sheetId","title","index","sheetType","gridProperties","hidden","rightToLeft","tabColor","tabColorStyle","dataSourceSheetProperties"));
        String title=text(sp,"title");int index=optionalInteger(sp,"index",0,0,7),id=integer(sp,"sheetId",0,Integer.MAX_VALUE);
        if(!TABS.contains(title) || !ids.add(id) || !indices.add(index) || !"GRID".equals(text(sp,"sheetType")))throw failure();
        var pin=target.sheets().get(index);
        if(!pin.title().equals(title) || pin.sheetId()!=id)throw failure();
        falseBoolean(sp,"hidden");falseBoolean(sp,"rightToLeft");
        for(String key:List.of("tabColor","tabColorStyle","dataSourceSheetProperties"))emptyObject(sp,key);
        diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.SHEET_GRID);
        JsonNode gp=required(sp,"gridProperties");
        fields(gp,Set.of("rowCount","columnCount","frozenRowCount","frozenColumnCount","hideGridlines","rowGroupControlAfter","columnGroupControlAfter"));
        int rows=integer(gp,"rowCount",1000,1000),columns=integer(gp,"columnCount",26,26);
        optionalInteger(gp,"frozenRowCount",0,0,0);optionalInteger(gp,"frozenColumnCount",0,0,0);falseBoolean(gp,"hideGridlines");
        for(String key:List.of("rowGroupControlAfter","columnGroupControlAfter"))if(gp.has(key) && !gp.get(key).isBoolean())throw failure();
        var parsed=parseGrid(title,new Grid(id,rows,columns,index),required(sheet,"data"),theme,p.get("defaultFormat"),diagnostics);
        ObjectNode layout=without(sheet,Set.of("data"));layout.set("gridDataMetadata",parsed.metadata());
        layouts.put(title,layout);types.put(title,literalTypes(required(sheet,"data")));
        var logical=logicalRows(title,parsed.rows(),diagnostics);tables.put(title,logical);counts.put(title,logical.size()-1);
      }
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.DATASET_MARKER);
      if(!tables.get("dataset").equals(List.of(HEADERS.get("dataset"),List.of("bridgego-managed",target.datasetId()))))throw failure();
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.NUMERIC_ROWS);
      validateNumericRows(tables);
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.LAYOUT_HASH);
      ObjectNode layout=without(root,Set.of("spreadsheetId","spreadsheetUrl","sheets"));layout.set("sheetsByTitle",JSON.valueToTree(layouts));
      String layoutHash=hash(canonical(layout));if(expectedLayout!=null && !expectedLayout.equals(layoutHash))throw failure();
      return new Report(expectedLayout==null?Status.STRUCTURE_OBSERVED_LAYOUT_UNAPPROVED:Status.STRUCTURE_COMPATIBLE_ONLY,
          ProductionReadOnlyTarget.PROFILE,hash(canonical(JSON.valueToTree(Map.of("profile",ProductionReadOnlyTarget.PROFILE,"target",target)))),sha(bytes),layoutHash,hash(JSON.valueToTree(tables)),hash(JSON.valueToTree(types)),counts);
    }catch(Exception | LinkageError bad){diagnostics.fail(bad);throw failure();}
  }
  private static List<List<String>> logicalRows(String title,List<List<String>> full,ProductionReadOnlyDiagnostics diagnostics) {
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.TABLE_HEADERS);
    if(full.isEmpty())throw failure();int width=HEADERS.get(title).size();
    if(title.equals("accounts")) {
      var h=full.get(0);if(h.subList(0,2).equals(List.of("account_id","schema_version"))) {
        if(h.get(2).isEmpty() && h.get(3).isEmpty())width=2;
        else if(h.subList(0,4).equals(HEADERS.get(title)))width=h.get(4).equals("lifetime_statistics")?5:4;
        else throw failure();
      }else throw failure();
    }
    List<String> expected=title.equals("accounts") && width==2?List.of("account_id","schema_version"):
        title.equals("accounts") && width==5?List.of("account_id","schema_version","nickname","email","lifetime_statistics"):HEADERS.get(title);
    if(!full.get(0).subList(0,width).equals(expected))throw failure();
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.TABLE_ROWS);
    var out=new ArrayList<List<String>>();
    for(var row:full) {
      if(row.subList(width,26).stream().anyMatch(v->!v.isEmpty()))throw failure();
      if(row.get(0).isEmpty())throw failure(); // Interior missing rows are not silently compacted.
      out.add(List.copyOf(row.subList(0,width)));
    }
    return List.copyOf(out);
  }
  private static boolean numericPosition(String title,int col) {
    return title.equals("commits") && (col==2 || col==4) || title.equals("envelopes") && (col==2 || col==3);
  }
  private static boolean numericCell(JsonNode cell) {return cell.path("userEnteredValue").has("numberValue");}
  private static int bounded(String s,int min,int max) {
    if(s==null || !s.matches("0|[1-9][0-9]{0,9}"))throw failure();int n=Integer.parseInt(s);if(n<min || n>max)throw failure();return n;
  }
  private static void validateNumericRows(Map<String,List<List<String>>> tables) {
    Set<String> commits=new HashSet<>(),chunks=new HashSet<>();
    for(var r:tables.get("commits").subList(1,tables.get("commits").size())) {
      bounded(r.get(2),1,10000);bounded(r.get(4),1,Integer.MAX_VALUE);if(!commits.add(r.get(0)))throw failure();
    }
    for(var r:tables.get("envelopes").subList(1,tables.get("envelopes").size())) {
      int count=bounded(r.get(3),1,10000);bounded(r.get(2),0,count-1);if(!chunks.add(r.get(0)+":"+r.get(2)))throw failure();
    }
    // This is structural validation only. No claim about committed payload hashes, retention or account semantics.
  }
  private static JsonNode literalTypes(JsonNode blocks) {
    ObjectNode result=JSON.createObjectNode();
    for(JsonNode block:blocks) {
      int sr=block.path("startRow").asInt(),sc=block.path("startColumn").asInt(),r=0;
      for(JsonNode row:block.path("rowData")) {int c=0;for(JsonNode cell:row.path("values")) {
        JsonNode value=cell.path("userEnteredValue");
        if(!value.isMissingNode() && !value.isEmpty())result.put((sr+r)+":"+(sc+c),value.has("numberValue")?"NUMBER":"STRING");c++;
      }r++;}
    }
    return canonical(result);
  }
  private static String sha(byte[] bytes) {
    try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    catch(Exception ignored){throw failure();}
  }
  private static ParsedGrid parseGrid(String title, Grid grid, JsonNode blocks, DisplayProfile profile,
      JsonNode defaultFormat,ProductionReadOnlyDiagnostics diagnostics) {
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.GRID_BLOCK);
    if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw failure();
    String[][] cells = new String[grid.rowCount()][grid.columnCount()];
    for (String[] row : cells) Arrays.fill(row, "");
    boolean[][] assigned = new boolean[grid.rowCount()][grid.columnCount()];
    JsonNode[] rowMetadata = new JsonNode[grid.rowCount()], columnMetadata = new JsonNode[grid.columnCount()];
    var cellMetadata = new TreeMap<Integer,JsonNode>();
    for (JsonNode block : blocks) {
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.GRID_BLOCK);
      fields(block, Set.of("startRow", "startColumn", "rowData", "rowMetadata", "columnMetadata"));
      int startRow = optionalInteger(block, "startRow", 0, 0, grid.rowCount() - 1);
      int startColumn = optionalInteger(block, "startColumn", 0, 0, grid.columnCount() - 1);
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.GRID_DIMENSIONS);
      dimensions(block, "rowMetadata", startRow, rowMetadata);
      dimensions(block, "columnMetadata", startColumn, columnMetadata);
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.GRID_ROW);
      if (!block.has("rowData")) continue;
      JsonNode rows = block.get("rowData");
      if (!rows.isArray() || rows.size() > grid.rowCount() - startRow) throw failure();
      for (int r = 0; r < rows.size(); r++) {
        diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.GRID_ROW);
        JsonNode row = rows.get(r); fields(row, Set.of("values"));
        if (!row.has("values")) continue;
        JsonNode values = row.get("values");
        if (!values.isArray() || values.size() > grid.columnCount() - startColumn) throw failure();
        for (int c = 0; c < values.size(); c++) {
          diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.GRID_CELL);
          int rr = r + startRow, cc = c + startColumn;
          if (assigned[rr][cc]) throw failure();
          assigned[rr][cc] = true;
          JsonNode cell = values.get(c);
          String value = parseCell(cell, title, rr, cc, profile,defaultFormat,diagnostics);
          diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.CELL_INHERITED_FORMAT);
          boolean issuerLink=cell.has("userEnteredFormat") && !cell.get("userEnteredFormat").isEmpty();
          // Exact known links are recognized but kept in this read-only profile's layout fingerprint.
          // This parser has no write capability and never changes the native cells.
          if(!issuerLink && cell.has("effectiveFormat") && !cell.get("effectiveFormat").isEmpty()
              && !inheritedLiteralDefault(cell,defaultFormat))throw failure();
          cells[rr][cc] = value;
          ObjectNode meta = without(cell, VALUE_FIELDS);
          if(!issuerLink && inheritedLiteralDefault(cell,defaultFormat))meta.remove("effectiveFormat");
          if (!meta.isEmpty()) cellMetadata.put(rr * grid.columnCount() + cc, meta);
        }
      }
    }
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.GRID_COVERAGE);
    for (JsonNode entry : rowMetadata) if (entry == null) throw failure();
    for (JsonNode entry : columnMetadata) if (entry == null) throw failure();
    int end = grid.rowCount();
    while (end > 0 && Arrays.stream(cells[end - 1]).allMatch(String::isEmpty)) end--;
    var rows = new ArrayList<List<String>>();
    for (int r = 0; r < end; r++) rows.add(List.copyOf(Arrays.asList(cells[r]).subList(0, grid.columnCount())));
    ObjectNode metadata = JSON.createObjectNode();
    metadata.set("rows", JSON.valueToTree(rowMetadata)); metadata.set("columns", JSON.valueToTree(columnMetadata));
    metadata.set("cells", JSON.valueToTree(cellMetadata));
    return new ParsedGrid(List.copyOf(rows), metadata);
  }

  private static boolean inheritedLiteralDefault(JsonNode cell, JsonNode defaultFormat) {
    // Called only after parseCell and root properties validated all field names, types and values.
    if (defaultFormat == null || !defaultFormat.isObject() || defaultFormat.isEmpty()
        || !cell.has("effectiveFormat")
        || cell.has("userEnteredFormat") && !cell.get("userEnteredFormat").isEmpty()) return false;
    ObjectNode expected = effectiveDefault(defaultFormat);
    expected.put("horizontalAlignment", numericCell(cell) ? "RIGHT" : "LEFT");
    expected.put("hyperlinkDisplayType", "PLAIN_TEXT");
    // In the observed response, default black RGB {} may be omitted. canonical() equates only
    // those empty representations; populated style fields are compared, not broadly stripped.
    return canonical(expected).equals(canonical(cell.get("effectiveFormat")));
  }

  private static ObjectNode effectiveDefault(JsonNode defaultFormat) {
    ObjectNode out=defaultFormat.deepCopy();
    JsonNode text=out.get("textFormat");
    if(text!=null && "arial,sans,sans-serif".equals(text.path("fontFamily").asText()))
      ((ObjectNode)text).put("fontFamily","Arial"); // Exact observed native root/effective alias only.
    return out;
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

  private static String parseCell(JsonNode cell, String title, int row, int column, DisplayProfile profile,JsonNode defaultFormat,ProductionReadOnlyDiagnostics diagnostics) {
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.CELL_FEATURES);
    fields(cell, union(VALUE_FIELDS, Set.of("userEnteredFormat", "effectiveFormat", "hyperlink", "note", "textFormatRuns",
        "dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula", "chipRuns")));
    if (cell.has("note") && !text(cell, "note").isEmpty()) throw failure();
    emptyArrays(cell, Set.of("textFormatRuns", "chipRuns"));
    for (String key : List.of("dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula"))
      emptyObject(cell, key);
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.CELL_VALUE);
    JsonNode entered = cell.get("userEnteredValue"), effective = cell.get("effectiveValue");
    String value;
    if (entered == null || entered.isObject() && entered.isEmpty()) {
      if (effective != null && (!effective.isObject() || !effective.isEmpty())) throw failure();
      value = "";
    } else {
      if(entered.has("numberValue")) {
        if(row<=0 || !numericPosition(title,column))throw failure();
        fields(entered,Set.of("numberValue"));fields(effective,Set.of("numberValue"));
        JsonNode a=required(entered,"numberValue"),b=required(effective,"numberValue");
        if(!a.isNumber() || !b.isNumber() || !Double.isFinite(a.doubleValue()) || !Double.isFinite(b.doubleValue())
            || a.decimalValue().compareTo(b.decimalValue())!=0)throw failure();
        int exact=a.decimalValue().intValueExact();
        if(exact<(title.equals("envelopes") && column==2?0:1)
            || (column!=4 && exact>10000))throw failure();
        value=Integer.toString(exact);
        if(!value.equals(text(cell,"formattedValue")))throw failure();
      } else {
        fields(entered, Set.of("stringValue")); fields(effective, Set.of("stringValue"));
        value = text(entered, "stringValue");
        if (!value.equals(text(effective, "stringValue"))) throw failure();
        if(row>0 && numericPosition(title,column) && !value.matches("0|[1-9][0-9]{0,9}"))throw failure();
      }
    }
    if (cell.has("formattedValue") && !value.equals(text(cell, "formattedValue"))) throw failure();
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.CELL_FORMAT);
    boolean link=cell.has("userEnteredFormat") && !cell.get("userEnteredFormat").isEmpty();
    if(link) {
      if(profile!=DisplayProfile.ARIAL_NATIVE || !title.equals("auth_identities") || row<=0 || column!=0
          || !ISSUER.equals(value) || !ISSUER.equals(text(cell,"hyperlink")))throw failure();
      observedNativeIssuerLink(cell,defaultFormat);
    }else {
      emptyObject(cell,"userEnteredFormat");
      if(cell.has("effectiveFormat")) {
        JsonNode format=cell.get("effectiveFormat");
        if(numericCell(cell) && format.has("horizontalAlignment")) {
          if(!"RIGHT".equals(text(format,"horizontalAlignment")))throw failure();
          ObjectNode local=format.deepCopy();local.put("horizontalAlignment","LEFT");
          plainDefaultFormat(local,profile,true);
        } else plainDefaultFormat(format,profile,true);
      }
    }
    if (cell.has("hyperlink") && !text(cell, "hyperlink").isEmpty()
        && !(title.equals("auth_identities") && row > 0 && column == 0
            && ISSUER.equals(value) && ISSUER.equals(text(cell, "hyperlink")))) throw failure();
    return value;
  }

  private static void observedNativeIssuerLink(JsonNode cell,JsonNode defaultFormat) {
    JsonNode entered=JSON.valueToTree(Map.of("textFormat",Map.of("link",Map.of("uri",ISSUER))));
    if(!canonical(required(cell,"userEnteredFormat")).equals(canonical(entered)))throw failure();
    ObjectNode expected=effectiveDefault(defaultFormat);
    expected.put("horizontalAlignment","LEFT");expected.put("hyperlinkDisplayType","LINKED");
    ObjectNode tf=(ObjectNode)expected.get("textFormat");tf.put("underline",true);tf.putObject("link").put("uri",ISSUER);
    var color=JSON.createObjectNode();var channels=List.of("red","green","blue");
    for(int i=0;i<3;i++)color.put(channels.get(i),new BigDecimal(ARIAL_THEME_COLORS.get("LINK").get(i)));
    tf.set("foregroundColor",color);tf.putObject("foregroundColorStyle").set("rgbColor",color.deepCopy());
    if(!canonical(required(cell,"effectiveFormat")).equals(canonical(expected)))throw failure();
  }

  private static DisplayProfile properties(JsonNode p) {
    fields(p, Set.of("title", "locale", "timeZone", "autoRecalc", "defaultFormat", "iterativeCalculationSettings",
        "spreadsheetTheme", "importFunctionsExternalUrlAccessAllowed"));
    String title = text(p, "title");
    if (title.isBlank() || title.length() > 256 || title.chars().anyMatch(Character::isISOControl)
        || !text(p, "locale").matches("[a-z]{2,3}(?:_[A-Z]{2})?")) throw failure();
    ZoneId.of(text(p, "timeZone"));
    if (p.has("autoRecalc") && !"ON_CHANGE".equals(text(p, "autoRecalc"))) throw failure();
    falseBoolean(p, "importFunctionsExternalUrlAccessAllowed");emptyObject(p, "iterativeCalculationSettings");
    DisplayProfile profile=DisplayProfile.PLAIN;
    if(p.has("spreadsheetTheme")) {
      JsonNode theme=p.get("spreadsheetTheme");fields(theme,Set.of("primaryFontFamily","themeColors"));
      if(!theme.isEmpty()) {
        String font=text(theme,"primaryFontFamily");
        if(font.equals("Carlito")){observedTheme(theme,"Carlito",CARLITO_THEME_COLORS);profile=DisplayProfile.CARLITO;}
        else if(font.equals("Arial")){observedTheme(theme,"Arial",ARIAL_THEME_COLORS);profile=DisplayProfile.ARIAL_NATIVE;}
        else throw failure();
      }
    }
    if(profile!=DisplayProfile.PLAIN) {
      JsonNode font=required(required(p,"defaultFormat"),"textFormat");
      String expected=profile==DisplayProfile.CARLITO?"Carlito":"arial,sans,sans-serif";
      int size=profile==DisplayProfile.CARLITO?11:10;
      if(!expected.equals(text(font,"fontFamily")) || integer(font,"fontSize",size,size)!=size)throw failure();
    }
    if(p.has("defaultFormat"))plainDefaultFormat(p.get("defaultFormat"),profile,false);
    return profile;
  }

  private static void observedTheme(JsonNode theme,String font,Map<String,List<String>> palette) {
    if (!font.equals(text(theme, "primaryFontFamily"))) throw failure();
    JsonNode colors = required(theme, "themeColors");
    if (!colors.isArray() || colors.size() != palette.size()) throw failure();
    Set<String> seen = new HashSet<>();
    for (JsonNode color : colors) {
      fields(color, Set.of("colorType", "color"));
      String type = text(color, "colorType"); List<String> expected = palette.get(type);
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
    if (!seen.equals(palette.keySet())) throw failure();
  }

  /** Only plain, observed Carlito or observed native Arial profiles; no arbitrary/rich-text formats. */
  private static void plainDefaultFormat(JsonNode f, DisplayProfile profile, boolean effectiveCell) {
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
      if(t.has("fontFamily")) {
        String actual=text(t,"fontFamily");
        boolean valid=switch(profile){case CARLITO->actual.equals("Carlito");
          case ARIAL_NATIVE->actual.equals(effectiveCell?"Arial":"arial,sans,sans-serif");
          case PLAIN->actual.equalsIgnoreCase("arial");};
        if(!valid)throw failure();
      }
      if(t.has("fontSize")){int size=profile==DisplayProfile.CARLITO?11:10;integer(t,"fontSize",size,size);}
      for (String key : List.of("bold", "italic", "strikethrough", "underline")) falseBoolean(t, key);
    }
  }
  private static void color(JsonNode color, int channel) {
    fields(color, Set.of("red", "green", "blue", "alpha"));
    for (String key : List.of("red", "green", "blue", "alpha")) {
      double expected = key.equals("alpha") ? 1 : channel;
      if (color.has(key) && (!color.get(key).isNumber() || color.get(key).decimalValue().compareTo(BigDecimal.valueOf(expected)) != 0)) throw failure();
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
  private static String hash(JsonNode node) throws Exception { return sha(JSON.writeValueAsBytes(node)); }
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
  private static IllegalStateException failure() { return new IllegalStateException("Production read-only resource rejected"); }
}
