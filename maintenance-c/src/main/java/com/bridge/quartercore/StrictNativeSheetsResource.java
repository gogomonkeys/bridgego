package com.bridge.quartercore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.io.*;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Strict native structure parser. Whole unmasked resources only; no I/O or credentials.
 * Native formatting helpers derive from reviewed historical parsers. This is a supported native
 * subset, not support for arbitrary Google workbook features. Unknown/populated features reject.
 * Full resources stream with explicit byte/token/cell/value/style limits; small capture inspection remains bounded.
 */
final class StrictNativeSheetsResource {
  static final int MAX_RESPONSE_BYTES=128*1024*1024;
  static final int MAX_CELLS=208000, MAX_VALUE_CHARS=4*1024*1024, MAX_STYLES=256;
  static final int MAX_STYLE_CHARS=4*1024*1024, MAX_SMALL_NODES=4096, MAX_SMALL_CHARS=65536, MAX_ROW_RECORDS=16000;
  static final long MAX_TOKENS=20_000_000;
  private static final JsonFactory STREAM=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_RESPONSE_BYTES).maxNestingDepth(24)
      .maxStringLength(20000).maxNameLength(128).maxNumberLength(20).maxTokenCount(MAX_TOKENS).build()).build();
  static final String COMMENTS="COMMENTS_VIEW_MODE_INCLUDED";
  private static final int MAX_BLOCKS=1000;
  private static final String ISSUER="https://accounts.google.com";
  private static final ObjectMapper JSON=BoundedSheetsProjection.MAPPER;
  private static final Set<String> ROOT_EMPTY_ARRAYS = Set.of("namedRanges", "dataSources",
      "dataSourceSchedules", "comments");
  private static final Set<String> SHEET_EMPTY_ARRAYS = Set.of("merges", "conditionalFormats", "filterViews",
      "protectedRanges", "charts", "bandedRanges", "rowGroups", "columnGroups", "slicers",
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



  record Grid(int sheetId,int rowCount,int columnCount,int index) {}
  private record ParsedGrid(List<List<String>> rows) {}
  /** Structure-only observation. Never usable as a narrow runtime proof. */
  record Observation(int sheets,int literalRows) {}
  private StrictNativeSheetsResource() {}

  /** Authentic historical fixtures are inspected untouched, with a caller-provided observation target/title.
   * Seven-sheet historical shapes can be structurally observed but never passed to decodeFull(). */
  static Observation inspectCaptured(byte[] bytes,String expectedId,String expectedTitle) {
    Parsed p=parse(bytes,expectedId,expectedTitle,false);
    return new Observation(p.book.sheets.size(),p.book.sheets.values().stream().mapToInt(s->s.rows.size()).sum());
  }
  private record Parsed(Book book) {}
  static Book decodeFull(byte[] bytes) {
    if(bytes==null)throw failure();
    return decodeFull(new ByteArrayInputStream(bytes),System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20));
  }
  static Book decodeFull(InputStream input,long deadline) {return decodeFull(input,deadline,null);}
  static Book decodeFull(InputStream input,long deadline,ManualQuarterMaintenance.Binding binding) {
    try {
    Book book=new Streaming(input,deadline,binding==null?NarrowQuarterPoc.TARGET:binding.spreadsheetId()).read();
    BoundedSheetsProjection.validateNarrowControl(book);
    NarrowQuarterPoc.Root root=NarrowQuarterPoc.root(new ArrayList<>(book.metadata.values()));
    if(book.sheet("retention_ledger").id!=root.anchor() || book.sheets.keySet().stream().anyMatch(id->id>=root.nextSheet()))throw failure();
    var dataset=book.sheet("dataset").rows;
    if(dataset.size()!=2 || !dataset.get(0).equals(ManualQuarterMaintenance.HEADERS.get("dataset"))
        || dataset.get(1).size()!=2 || !dataset.get(1).get(1).equals(root.dataset())
        || !dataset.get(1).get(0).equals(binding==null?new ManualQuarterMaintenance.Binding(NarrowQuarterPoc.TARGET,root.dataset()).purpose():binding.purpose())
        || binding!=null&&!root.dataset().equals(binding.datasetId()))throw failure();
    return book;
    }catch(Exception rejected){throw failure();}
  }

  // BEGIN EXPLICIT LEGACY PREFLIGHT EXTENSION
  /** Full legacy observation only. Does not relax decodeFull or infer ownership.
   * Existing metadata is forbidden, including unrelated metadata: this deliberately
   * supports only the reviewed empty-control bootstrap subset. No network is performed. */
  static Book decodeLegacyFull(InputStream input,long deadline,ManualQuarterMaintenance.Binding binding) {
    try {
      Objects.requireNonNull(binding);
      Book book=new Streaming(input,deadline,binding.spreadsheetId()).read();
      if(!book.metadata.isEmpty())throw failure();
      if(!book.sheet("dataset").rows.equals(List.of(ManualQuarterMaintenance.HEADERS.get("dataset"),
          List.of(binding.purpose(),binding.datasetId()))))throw failure();
      return book;
    }catch(Exception rejected){throw failure();}
  }
  // END EXPLICIT LEGACY PREFLIGHT EXTENSION

  /** One bounded subtree at a time. Offsets, sheet properties and root defaults may occur AFTER data.
   * Therefore only compact literal values and interned exact display descriptors survive a cell.
   * No field is skipped, including unknown fields carrying empty/default values. */
  private static final class Streaming {
    final InputStream input;final long deadline;final String target;JsonParser p;int cells,valueChars,styleChars,nodes,nodeChars,rowRecords;
    final Map<Style,Style> styles=new HashMap<>();final List<PendingSheet> sheets=new ArrayList<>();
    Streaming(InputStream input,long deadline,String target){if(input==null)throw failure();this.input=input;this.deadline=deadline;this.target=target;}
    void time(){BatchedSheetsTransport.remaining(deadline);}
    JsonToken next()throws IOException{time();return p.nextToken();}
    void token(JsonToken expected){if(p.currentToken()!=expected)throw failure();}
    JsonNode small()throws IOException{nodes=0;nodeChars=0;return node();}
    JsonNode readEmptyArray()throws IOException{token(JsonToken.START_ARRAY);if(next()!=JsonToken.END_ARRAY)throw failure();return JSON.createArrayNode();}
    JsonNode readEmptyObject()throws IOException{token(JsonToken.START_OBJECT);if(next()!=JsonToken.END_OBJECT)throw failure();return JSON.createObjectNode();}
    void chars(int n){nodeChars=Math.addExact(nodeChars,n);if(nodeChars>MAX_SMALL_CHARS)throw failure();}
    JsonNode node()throws IOException{
      if(++nodes>MAX_SMALL_NODES)throw failure();time();JsonToken t=p.currentToken();
      if(t==JsonToken.START_OBJECT){ObjectNode n=JSON.createObjectNode();while(next()!=JsonToken.END_OBJECT){token(JsonToken.FIELD_NAME);String key=p.currentName();chars(key.length());next();n.set(key,node());}return n;}
      if(t==JsonToken.START_ARRAY){ArrayNode n=JSON.createArrayNode();while(next()!=JsonToken.END_ARRAY)n.add(node());return n;}
      if(t==JsonToken.VALUE_STRING){String text=p.getText();chars(text.length());return TextNode.valueOf(text);}
      if(t==JsonToken.VALUE_NUMBER_INT)return BigIntegerNode.valueOf(p.getBigIntegerValue());
      if(t==JsonToken.VALUE_NUMBER_FLOAT)return DecimalNode.valueOf(p.getDecimalValue());
      if(t==JsonToken.VALUE_TRUE)return BooleanNode.TRUE;if(t==JsonToken.VALUE_FALSE)return BooleanNode.FALSE;if(t==JsonToken.VALUE_NULL)return NullNode.instance;
      throw failure();
    }
    Book read()throws IOException{
      // The stream owner closes the connection; parser close does not transfer ownership.
      try(JsonParser parser=STREAM.createParser(new ByteLimit(input,MAX_RESPONSE_BYTES,deadline))){p=parser;p.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
        next();token(JsonToken.START_OBJECT);ObjectNode root=JSON.createObjectNode();
        while(next()!=JsonToken.END_OBJECT){token(JsonToken.FIELD_NAME);String key=p.currentName();next();
          if(key.equals("sheets")){token(JsonToken.START_ARRAY);while(next()!=JsonToken.END_ARRAY){if(sheets.size()>=8)throw failure();sheets.add(sheet());}}
          else {if(!union(Set.of("spreadsheetId","spreadsheetUrl","properties","commentsViewMode","developerMetadata"),ROOT_EMPTY_ARRAYS).contains(key))throw failure();root.set(key,key.equals("developerMetadata")?metadataArray():ROOT_EMPTY_ARRAYS.contains(key)?readEmptyArray():small());}}
        if(next()!=null||sheets.size()!=8)throw failure();
        if(!target.equals(text(root,"spreadsheetId"))||!("https://docs.google.com/spreadsheets/d/"+target+"/edit").equals(text(root,"spreadsheetUrl"))||!COMMENTS.equals(text(root,"commentsViewMode")))throw failure();
        emptyArrays(root,ROOT_EMPTY_ARRAYS);JsonNode props=required(root,"properties");DisplayProfile profile=properties(props);JsonNode defaultFormat=props.get("defaultFormat");
        // Validate each exact descriptor once. Location and numeric eligibility are validated per cell below.
        for(Style style:styles.keySet())style.validate(profile,defaultFormat);
        Book b=new Book();BoundedSheetsProjection.metadata(root.get("developerMetadata"),b,null);Set<String> titles=new HashSet<>();Set<Integer> indices=new HashSet<>();
        for(PendingSheet pending:sheets){time();ObjectNode h=pending.header;emptyArrays(h,SHEET_EMPTY_ARRAYS);emptyObject(h,"basicFilter");JsonNode sp=required(h,"properties");
          fields(sp,Set.of("sheetId","title","index","sheetType","gridProperties","hidden","rightToLeft","tabColor","tabColorStyle","dataSourceSheetProperties"));
          String title=text(sp,"title");int id=integer(sp,"sheetId",0,Integer.MAX_VALUE),index=optionalInteger(sp,"index",0,0,7);
          if(!ManualQuarterMaintenance.HEADERS.containsKey(title)||!titles.add(title)||!indices.add(index)||b.sheets.containsKey(id)||!"GRID".equals(text(sp,"sheetType")))throw failure();
          falseBoolean(sp,"hidden");falseBoolean(sp,"rightToLeft");for(String key:List.of("tabColor","tabColorStyle","dataSourceSheetProperties"))emptyObject(sp,key);
          JsonNode gp=required(sp,"gridProperties");fields(gp,Set.of("rowCount","columnCount","frozenRowCount","frozenColumnCount","hideGridlines","rowGroupControlAfter","columnGroupControlAfter"));
          int rows=integer(gp,"rowCount",1000,1000),columns=integer(gp,"columnCount",26,26);optionalInteger(gp,"frozenRowCount",0,0,0);optionalInteger(gp,"frozenColumnCount",0,0,0);falseBoolean(gp,"hideGridlines");
          for(String key:List.of("rowGroupControlAfter","columnGroupControlAfter"))if(gp.has(key)&&!gp.get(key).isBoolean())throw failure();
          String[][] values=new String[rows][columns];boolean[][] assigned=new boolean[rows][columns];boolean[] rd=new boolean[rows],cd=new boolean[columns];
          for(PendingBlock block:pending.blocks){int sr=optionalInteger(block.header,"startRow",0,0,rows-1),sc=optionalInteger(block.header,"startColumn",0,0,columns-1);
            cover(rd,sr,block.rowDimensions);cover(cd,sc,block.columnDimensions);if(block.rows.size()>rows-sr)throw failure();
            for(int r=0;r<block.rows.size();r++){List<Cell> row=block.rows.get(r);if(row.size()>columns-sc)throw failure();for(int c=0;c<row.size();c++){
              int rr=sr+r,cc=sc+c;if(assigned[rr][cc])throw failure();assigned[rr][cc]=true;Cell cell=row.get(c);
              if(cell.style.kind==2&&(rr==0||!numericPosition(title,cc)))throw failure();
              if(cell.style.link&&(!title.equals("auth_identities")||rr==0||cc!=0||!ISSUER.equals(cell.value)))throw failure();
              if(cell.style.hyperlink&&!cell.style.link&&(!title.equals("auth_identities")||rr==0||cc!=0||!ISSUER.equals(cell.value)))throw failure();
              values[rr][cc]=cell.value;
            }}}
          for(boolean found:rd)if(!found)throw failure();for(boolean found:cd)if(!found)throw failure();
          int end=rows;while(end>0&&Arrays.stream(values[end-1]).allMatch(v->v==null||v.isEmpty()))end--;
          List<List<String>> literal=new ArrayList<>();for(int r=0;r<end;r++){List<String> row=new ArrayList<>();for(String value:values[r])row.add(value==null?"":value);literal.add(trimRangeRow(row));}
          Sheet parsed=new Sheet(id,title,literal);parsed.rowCount=rows;parsed.columnCount=columns;b.sheets.put(id,parsed);BoundedSheetsProjection.metadata(h.get("developerMetadata"),b,id);
        }
        if(!titles.equals(ManualQuarterMaintenance.HEADERS.keySet()))throw failure();b.budgets();time();return b;
      }
    }
    JsonNode metadataArray()throws IOException{token(JsonToken.START_ARRAY);ArrayNode array=JSON.createArrayNode();int chars=0;while(next()!=JsonToken.END_ARRAY){if(array.size()>=10000)throw failure();JsonNode n=small();Metadata m=BoundedSheetsProjection.nativeMetadata(n);BoundedSheetsProjection.strictControlValue(m);chars+=m.characters();if(chars>NativeSheetsModel.METADATA_LIMIT)throw failure();array.add(n);}return array;}
    PendingSheet sheet()throws IOException{token(JsonToken.START_OBJECT);PendingSheet result=new PendingSheet();boolean data=false;
      while(next()!=JsonToken.END_OBJECT){token(JsonToken.FIELD_NAME);String key=p.currentName();next();if(key.equals("data")){data=true;token(JsonToken.START_ARRAY);while(next()!=JsonToken.END_ARRAY){if(result.blocks.size()>=MAX_BLOCKS)throw failure();result.blocks.add(block());}}
        else{if(!union(Set.of("properties","basicFilter","developerMetadata"),SHEET_EMPTY_ARRAYS).contains(key))throw failure();result.header.set(key,key.equals("developerMetadata")?metadataArray():SHEET_EMPTY_ARRAYS.contains(key)?readEmptyArray():key.equals("basicFilter")?readEmptyObject():small());}}
      if(!data||result.blocks.isEmpty())throw failure();return result;}
    PendingBlock block()throws IOException{token(JsonToken.START_OBJECT);PendingBlock result=new PendingBlock();while(next()!=JsonToken.END_OBJECT){token(JsonToken.FIELD_NAME);String key=p.currentName();next();switch(key){
      case "startRow","startColumn"->{token(JsonToken.VALUE_NUMBER_INT);int offset=p.getIntValue();if(offset<0||offset>(key.equals("startRow")?999:25))throw failure();result.header.put(key,offset);}case "rowMetadata"->result.rowDimensions=dimensionCount(1000);case "columnMetadata"->result.columnDimensions=dimensionCount(26);
      case "rowData"->{token(JsonToken.START_ARRAY);while(next()!=JsonToken.END_ARRAY){if(result.rows.size()>=1000||++rowRecords>MAX_ROW_RECORDS)throw failure();token(JsonToken.START_OBJECT);List<Cell> row=new ArrayList<>();while(next()!=JsonToken.END_OBJECT){token(JsonToken.FIELD_NAME);if(!"values".equals(p.currentName()))throw failure();next();token(JsonToken.START_ARRAY);while(next()!=JsonToken.END_ARRAY){if(row.size()>=26||++cells>MAX_CELLS)throw failure();row.add(cell());}}result.rows.add(row);}}
      default->throw failure();}}return result;}
    int dimensionCount(int cap)throws IOException{token(JsonToken.START_ARRAY);int count=0;while(next()!=JsonToken.END_ARRAY){if(++count>cap)throw failure();JsonNode d=small();fields(d,Set.of("hiddenByFilter","hiddenByUser","pixelSize","developerMetadata","dataSourceColumnReference"));falseBoolean(d,"hiddenByFilter");falseBoolean(d,"hiddenByUser");if(d.has("pixelSize"))integer(d,"pixelSize",1,1000);emptyArrays(d,Set.of("developerMetadata"));emptyObject(d,"dataSourceColumnReference");}return count;}
    void cover(boolean[] coverage,int start,int count){if(count>coverage.length-start)throw failure();for(int i=0;i<count;i++){if(coverage[start+i])throw failure();coverage[start+i]=true;}}
    Cell cell()throws IOException{JsonNode raw=small();fields(raw,union(VALUE_FIELDS,Set.of("userEnteredFormat","effectiveFormat","hyperlink","note","textFormatRuns","dataValidation","pivotTable","dataSourceTable","dataSourceFormula","chipRuns")));
      JsonNode entered=raw.get("userEnteredValue"),effective=raw.get("effectiveValue");String value;int kind;
      if(entered==null||entered.isObject()&&entered.isEmpty()){if(effective!=null&&(!effective.isObject()||!effective.isEmpty()))throw failure();value="";kind=0;}
      else if(entered.has("numberValue")){fields(entered,Set.of("numberValue"));fields(effective,Set.of("numberValue"));JsonNode a=required(entered,"numberValue"),b=required(effective,"numberValue");if(!a.isNumber()||!b.isNumber()||a.decimalValue().compareTo(b.decimalValue())!=0)throw failure();int exact=a.decimalValue().intValueExact();if(exact<0)throw failure();value=Integer.toString(exact);kind=2;}
      else{fields(entered,Set.of("stringValue"));fields(effective,Set.of("stringValue"));value=text(entered,"stringValue");if(!value.equals(text(effective,"stringValue")))throw failure();kind=1;}
      if(raw.has("formattedValue")&&!value.equals(text(raw,"formattedValue")))throw failure();valueChars=Math.addExact(valueChars,value.length());if(valueChars>MAX_VALUE_CHARS)throw failure();
      ObjectNode descriptor=(ObjectNode)raw;for(String key:VALUE_FIELDS)descriptor.remove(key);Style candidate=new Style(kind,descriptor);Style style=styles.get(candidate);
      if(style==null){if(styles.size()>=MAX_STYLES)throw failure();styleChars=Math.addExact(styleChars,descriptor.toString().length());if(styleChars>MAX_STYLE_CHARS)throw failure();styles.put(candidate,candidate);style=candidate;}
      return new Cell(value,style);
    }
  }
  private static final class PendingSheet {final ObjectNode header=JSON.createObjectNode();final List<PendingBlock> blocks=new ArrayList<>();}
  private static final class PendingBlock {final ObjectNode header=JSON.createObjectNode();final List<List<Cell>> rows=new ArrayList<>();int rowDimensions,columnDimensions;}
  private record Cell(String value,Style style) {}
  private static final class Style {
    final int kind;final ObjectNode descriptor;final boolean link,hyperlink;final int hash;
    Style(int kind,ObjectNode descriptor){this.kind=kind;this.descriptor=descriptor;link=descriptor.has("userEnteredFormat")&&!descriptor.path("userEnteredFormat").isEmpty();hyperlink=descriptor.has("hyperlink")&&!text(descriptor,"hyperlink").isEmpty();hash=31*kind+descriptor.hashCode();}
    @Override public int hashCode(){return hash;}
    @Override public boolean equals(Object other){return other instanceof Style s&&kind==s.kind&&descriptor.equals(s.descriptor);}
    void validate(DisplayProfile profile,JsonNode defaults){ObjectNode cell=descriptor.deepCopy();String value=link||hyperlink?ISSUER:kind==2?"1":"";
      if(kind!=0){String field=kind==2?"numberValue":"stringValue";ObjectNode entered=cell.putObject("userEnteredValue"),effective=cell.putObject("effectiveValue");if(kind==2){entered.put(field,1);effective.put(field,1);}else{entered.put(field,value);effective.put(field,value);}}
      String title=link||hyperlink?"auth_identities":"accounts";int column=kind==2?1:0;parseCell(cell,title,1,column,profile,defaults);
      if(!link&&cell.has("effectiveFormat")&&!cell.path("effectiveFormat").isEmpty()&&!inheritedLiteralDefault(cell,defaults))throw failure();
    }
  }
  /** Byte cap includes trailing whitespace; does not close caller ownership. Deadline is checked every read. */
  static final class ByteLimit extends FilterInputStream {
    final long cap,deadline;long count;ByteLimit(InputStream in,long cap,long deadline){super(in);this.cap=cap;this.deadline=deadline;}
    @Override public int read()throws IOException{BatchedSheetsTransport.remaining(deadline);int n=in.read();if(n>=0&&++count>cap)throw failure();return n;}
    @Override public int read(byte[] bytes,int offset,int length)throws IOException{BatchedSheetsTransport.remaining(deadline);int n=in.read(bytes,offset,(int)Math.min(length,Math.max(1,cap-count+1)));if(n>0&&(count+=n)>cap)throw failure();return n;}
    @Override public void close(){} // stream is closed by its owning Flight
  }
  private static Parsed parse(byte[] bytes,String expectedId,String expectedTitle,boolean narrow) {
    try {
      if(bytes==null||bytes.length==0||bytes.length>MAX_RESPONSE_BYTES||expectedId==null)throw failure();
      JsonNode root=JSON.readTree(bytes);
      fields(root,union(Set.of("spreadsheetId","spreadsheetUrl","properties","sheets","commentsViewMode","developerMetadata"),ROOT_EMPTY_ARRAYS));
      if(!expectedId.equals(text(root,"spreadsheetId"))
          ||!("https://docs.google.com/spreadsheets/d/"+expectedId+"/edit").equals(text(root,"spreadsheetUrl"))
          ||!COMMENTS.equals(text(root,"commentsViewMode")))throw failure();
      emptyArrays(root,ROOT_EMPTY_ARRAYS);
      JsonNode props=required(root,"properties");DisplayProfile profile=properties(props);
      if(expectedTitle!=null&&!expectedTitle.equals(text(props,"title")))throw failure();
      JsonNode sheets=required(root,"sheets");
      if(!sheets.isArray()||narrow&&sheets.size()!=8||!narrow&&(sheets.size()<7||sheets.size()>8))throw failure();
      Book book=new Book();Set<String> titles=new HashSet<>();Set<Integer> indices=new HashSet<>();
      BoundedSheetsProjection.metadata(root.get("developerMetadata"),book,null);
      for(JsonNode sheet:sheets) {
        fields(sheet,union(Set.of("properties","data","basicFilter","developerMetadata"),SHEET_EMPTY_ARRAYS));
        emptyArrays(sheet,SHEET_EMPTY_ARRAYS);emptyObject(sheet,"basicFilter");
        JsonNode p=required(sheet,"properties");
        fields(p,Set.of("sheetId","title","index","sheetType","gridProperties","hidden","rightToLeft","tabColor","tabColorStyle","dataSourceSheetProperties"));
        String title=text(p,"title");int id=integer(p,"sheetId",0,Integer.MAX_VALUE),index=optionalInteger(p,"index",0,0,sheets.size()-1);
        if(!ManualQuarterMaintenance.HEADERS.containsKey(title)||!titles.add(title)||!indices.add(index)||book.sheets.containsKey(id)
            ||!"GRID".equals(text(p,"sheetType")))throw failure();
        falseBoolean(p,"hidden");falseBoolean(p,"rightToLeft");
        for(String key:List.of("tabColor","tabColorStyle","dataSourceSheetProperties"))emptyObject(p,key);
        JsonNode gp=required(p,"gridProperties");
        fields(gp,Set.of("rowCount","columnCount","frozenRowCount","frozenColumnCount","hideGridlines","rowGroupControlAfter","columnGroupControlAfter"));
        int rowCount=integer(gp,"rowCount",narrow?1000:1,1000),columns=integer(gp,"columnCount",narrow?26:ManualQuarterMaintenance.HEADERS.get(title).size(),26);
        optionalInteger(gp,"frozenRowCount",0,0,0);optionalInteger(gp,"frozenColumnCount",0,0,0);falseBoolean(gp,"hideGridlines");
        for(String key:List.of("rowGroupControlAfter","columnGroupControlAfter"))if(gp.has(key)&&!gp.get(key).isBoolean())throw failure();
        ParsedGrid grid=parseGrid(title,new Grid(id,rowCount,columns,index),required(sheet,"data"),profile,props.get("defaultFormat"));
        List<List<String>> rows=grid.rows.stream().map(NativeSheetsModel::trimRangeRow).toList();
        Sheet parsed=new Sheet(id,title,rows);parsed.rowCount=rowCount;parsed.columnCount=columns;book.sheets.put(id,parsed);
        BoundedSheetsProjection.metadata(sheet.get("developerMetadata"),book,id);
      }
      if(narrow&&!titles.equals(ManualQuarterMaintenance.HEADERS.keySet()))throw failure();
      book.budgets();return new Parsed(book);
    }catch(Exception rejected){throw failure();}
  }
  private static boolean numericPosition(String title,int column) {return BoundedSheetsProjection.INTEGER_COLUMNS.get(title).contains(column);}
  private static boolean numericCell(JsonNode cell) {return cell.path("userEnteredValue").has("numberValue");}
  private static ParsedGrid parseGrid(String title, Grid grid, JsonNode blocks, DisplayProfile profile,
      JsonNode defaultFormat) {
    if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw failure();
    String[][] cells = new String[grid.rowCount()][grid.columnCount()];
    for (String[] row : cells) Arrays.fill(row, "");
    boolean[][] assigned = new boolean[grid.rowCount()][grid.columnCount()];
    boolean[] rowMetadata = new boolean[grid.rowCount()], columnMetadata = new boolean[grid.columnCount()];
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
          String value = parseCell(cell, title, rr, cc, profile,defaultFormat);
          boolean issuerLink=cell.has("userEnteredFormat") && !cell.get("userEnteredFormat").isEmpty();
          // Known issuer links are checked exactly; no arbitrary rich text or write authority.
          if(!issuerLink && cell.has("effectiveFormat") && !cell.get("effectiveFormat").isEmpty()
              && !inheritedLiteralDefault(cell,defaultFormat))throw failure();
          cells[rr][cc] = value;

        }
      }
    }
    for (boolean covered : rowMetadata) if (!covered) throw failure();
    for (boolean covered : columnMetadata) if (!covered) throw failure();
    int end = grid.rowCount();
    while (end > 0 && Arrays.stream(cells[end - 1]).allMatch(String::isEmpty)) end--;
    var rows = new ArrayList<List<String>>();
    for (int r = 0; r < end; r++) rows.add(List.copyOf(Arrays.asList(cells[r]).subList(0, grid.columnCount())));
    return new ParsedGrid(List.copyOf(rows));
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
    JsonNode actual=cell.get("effectiveFormat");
    // Empty native cells carry inherited defaults without a literal alignment/display marker.
    if(!cell.has("userEnteredValue") || cell.path("userEnteredValue").isEmpty()) {
      if(!actual.has("horizontalAlignment"))expected.remove("horizontalAlignment");
      if(!actual.has("hyperlinkDisplayType"))expected.remove("hyperlinkDisplayType");
    }
    return canonical(expected).equals(canonical(actual));
  }

  private static ObjectNode effectiveDefault(JsonNode defaultFormat) {
    ObjectNode out=defaultFormat.deepCopy();
    JsonNode text=out.get("textFormat");
    if(text!=null && "arial,sans,sans-serif".equals(text.path("fontFamily").asText()))
      ((ObjectNode)text).put("fontFamily","Arial"); // Exact observed native root/effective alias only.
    return out;
  }

  private static void dimensions(JsonNode block, String key, int start, boolean[] coverage) {
    if (!block.has(key)) return;
    JsonNode values = block.get(key);
    if (!values.isArray() || values.size() > coverage.length - start) throw failure();
    for (int i = 0; i < values.size(); i++) {
      if (coverage[start + i]) throw failure();
      JsonNode dimension = values.get(i);
      fields(dimension, Set.of("hiddenByFilter", "hiddenByUser", "pixelSize", "developerMetadata", "dataSourceColumnReference"));
      falseBoolean(dimension, "hiddenByFilter"); falseBoolean(dimension, "hiddenByUser");
      if (dimension.has("pixelSize")) integer(dimension, "pixelSize", 1, 1000);
      emptyArrays(dimension, Set.of("developerMetadata")); emptyObject(dimension, "dataSourceColumnReference");
      coverage[start + i] = true;
    }
  }

  private static String parseCell(JsonNode cell, String title, int row, int column, DisplayProfile profile,JsonNode defaultFormat) {
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
      if(entered.has("numberValue")) {
        if(row<=0 || !numericPosition(title,column))throw failure();
        fields(entered,Set.of("numberValue"));fields(effective,Set.of("numberValue"));
        JsonNode a=required(entered,"numberValue"),b=required(effective,"numberValue");
        if(!a.isNumber() || !b.isNumber() || !Double.isFinite(a.doubleValue()) || !Double.isFinite(b.doubleValue())
            || a.decimalValue().compareTo(b.decimalValue())!=0)throw failure();
        int exact=a.decimalValue().intValueExact();
        if(exact<0)throw failure();
        value=Integer.toString(exact);
        if(cell.has("formattedValue")&&!value.equals(text(cell,"formattedValue")))throw failure();
      } else {
        fields(entered, Set.of("stringValue")); fields(effective, Set.of("stringValue"));
        value = text(entered, "stringValue");
        if (!value.equals(text(effective, "stringValue"))) throw failure();
      }
    }
    if (cell.has("formattedValue") && !value.equals(text(cell, "formattedValue"))) throw failure();
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
    knownShape(required(cell,"userEnteredFormat"),entered);
    if(!canonical(required(cell,"userEnteredFormat")).equals(canonical(entered)))throw failure();
    ObjectNode expected=effectiveDefault(defaultFormat);
    expected.put("horizontalAlignment","LEFT");expected.put("hyperlinkDisplayType","LINKED");
    ObjectNode tf=(ObjectNode)expected.get("textFormat");tf.put("underline",true);tf.putObject("link").put("uri",ISSUER);
    var color=JSON.createObjectNode();var channels=List.of("red","green","blue");
    for(int i=0;i<3;i++)color.put(channels.get(i),new BigDecimal(ARIAL_THEME_COLORS.get("LINK").get(i)));
    tf.set("foregroundColor",color);tf.putObject("foregroundColorStyle").set("rgbColor",color.deepCopy());
    knownShape(required(cell,"effectiveFormat"),expected);
    if(!canonical(required(cell,"effectiveFormat")).equals(canonical(expected)))throw failure();
  }

  // Canonical empty/default equivalence must never hide an unknown key, even an empty one.
  private static void knownShape(JsonNode actual,JsonNode expected){
    if(actual.isObject()){
      if(!expected.isObject())throw failure();
      actual.fields().forEachRemaining(e->{if(!expected.has(e.getKey()))throw failure();knownShape(e.getValue(),expected.get(e.getKey()));});
    }else if(actual.isArray()||actual.isNull()||actual.getNodeType()!=expected.getNodeType()&&!(actual.isNumber()&&expected.isNumber()))throw failure();
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
  private static BatchedSheetsTransport.Invalid failure() { return new BatchedSheetsTransport.Invalid(); }
}
