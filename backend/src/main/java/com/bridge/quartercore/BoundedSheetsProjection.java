package com.bridge.quartercore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Only the requested literal projection. DOES NOT validate a full native workbook resource. */
final class BoundedSheetsProjection {
  static final int MAX_BYTES=2*1024*1024;
  static final ObjectMapper MAPPER=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_BYTES).maxNestingDepth(24).maxStringLength(20000).maxNameLength(128).maxNumberLength(20).maxTokenCount(250000).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String,Set<Integer>> INTEGER_COLUMNS=Map.of("accounts",Set.of(1),"auth_identities",Set.of(3),"envelopes",Set.of(2,3),"commits",Set.of(2,4),"erasure_ledger",Set.of(2),"retention_ledger",Set.of(4),"quarterly_aggregates",Set.of(4),"dataset",Set.of());
  /** Full path requires actual native resource metadata and full dimension coverage. */
  static Book decodeFullSupportedModel(byte[] bytes){return StrictNativeSheetsResource.decodeFull(bytes);}
  static Book decode(byte[] bytes,Set<String> requested){return decode(bytes,requested,null);}
  static Book decode(byte[] bytes,Set<String> requested,ManualQuarterMaintenance.Binding binding){
    try{
      if(bytes==null||bytes.length>MAX_BYTES||requested.isEmpty()||!INTEGER_COLUMNS.keySet().containsAll(requested))throw new Rejected();
      JsonNode root=MAPPER.readTree(bytes);keys(root,Set.of("spreadsheetId","developerMetadata","sheets"));
      if(!text(root,"spreadsheetId").equals(binding==null?NarrowQuarterPoc.TARGET:binding.spreadsheetId()))throw new Rejected();
      Book b=new Book();metadata(root.get("developerMetadata"),b,null);
      JsonNode sheets=root.path("sheets");if(!sheets.isArray()||sheets.size()!=8)throw new Rejected();Set<String> seen=new HashSet<>();
      for(JsonNode s:sheets){keys(s,Set.of("properties","data","developerMetadata"));JsonNode p=s.path("properties");keys(p,Set.of("sheetId","title","gridProperties"));
        String title=text(p,"title");int id=integer(p,"sheetId");if(id<0||!INTEGER_COLUMNS.containsKey(title)||!seen.add(title)||b.sheets.containsKey(id))throw new Rejected();
        JsonNode grid=p.path("gridProperties");keys(grid,Set.of("rowCount","columnCount"));if(integer(grid,"rowCount")!=1000||integer(grid,"columnCount")!=26)throw new Rejected();
        List<List<String>> rows=new ArrayList<>();JsonNode data=s.get("data");
        if(data!=null){if(!data.isArray()||data.size()>1)throw new Rejected();if(!data.isEmpty()){
          JsonNode block=data.get(0);keys(block,Set.of("startRow","startColumn","rowData"));if(block.has("startRow")&&integer(block,"startRow")!=0||block.has("startColumn")&&integer(block,"startColumn")!=0)throw new Rejected();
          JsonNode rawRows=block.get("rowData");if(rawRows!=null){if(!rawRows.isArray()||rawRows.size()>1000)throw new Rejected();int rowIndex=0;
            for(JsonNode raw:rawRows){keys(raw,Set.of("values"));List<String> row=new ArrayList<>();JsonNode values=raw.get("values");if(values!=null){if(!values.isArray()||values.size()>26)throw new Rejected();int column=0;
              for(JsonNode cell:values){keys(cell,Set.of("userEnteredValue","effectiveValue"));String entered=value(cell.get("userEnteredValue"),title,rowIndex,column),effective=value(cell.get("effectiveValue"),title,rowIndex,column);
                if(!entered.equals(effective)||!sameValueKind(cell.get("userEnteredValue"),cell.get("effectiveValue")))throw new Rejected();row.add(entered);column++;}}
              rows.add(trimRangeRow(row));rowIndex++;}}
        }}
        while(!rows.isEmpty()&&rows.get(rows.size()-1).isEmpty())rows.remove(rows.size()-1);
        if(!requested.contains(title)&&!rows.isEmpty())throw new Rejected();
        b.sheets.put(id,new Sheet(id,title,rows));metadata(s.get("developerMetadata"),b,id);
      }
      if(!seen.equals(INTEGER_COLUMNS.keySet()))throw new Rejected();b.budgets();validateNarrowControl(b);if(binding!=null){var control=NarrowQuarterPoc.root(new ArrayList<>(b.metadata.values()));if(!binding.datasetId().equals(control.dataset()))throw new Rejected();if(requested.contains("dataset")&&!b.sheet("dataset").rows.equals(List.of(ManualQuarterMaintenance.HEADERS.get("dataset"),List.of(binding.purpose(),binding.datasetId()))))throw new Rejected();}return b;
    }catch(Exception failure){throw new BatchedSheetsTransport.Invalid();}
  }
  static void metadata(JsonNode values,Book b,Integer expectedSheet){
    if(values==null)return;if(!values.isArray()||values.size()>10000)throw new Rejected();
    for(JsonNode n:values){Metadata m=nativeMetadata(n);strictControlValue(m);if(!Objects.equals(m.sheet(),expectedSheet)||b.metadata.putIfAbsent(m.id(),m)!=null)throw new Rejected();}
  }
  /** locationType is read-only derived Google metadata, validated before private normalization. */
  static Metadata nativeMetadata(JsonNode n){
    keys(n,Set.of("metadataId","metadataKey","metadataValue","visibility","location"));
    if(!text(n,"visibility").equals("DOCUMENT"))throw new Rejected();
    JsonNode loc=n.get("location");keys(loc,Set.of("spreadsheet","sheetId","locationType"));Integer sheet=null;
    if(loc.has("sheetId")){
      if(loc.has("spreadsheet"))throw new Rejected();sheet=integer(loc,"sheetId");if(sheet<0)throw new Rejected();
      if(loc.has("locationType")&&!text(loc,"locationType").equals("SHEET"))throw new Rejected();
    }else {
      if(!loc.path("spreadsheet").isBoolean()||!loc.path("spreadsheet").booleanValue())throw new Rejected();
      if(loc.has("locationType")&&!text(loc,"locationType").equals("SPREADSHEET"))throw new Rejected();
    }
    return new Metadata(integer(n,"metadataId"),text(n,"metadataKey"),text(n,"metadataValue"),sheet);
  }
  static void validateNarrowControl(Book b){
    var r=NarrowQuarterPoc.root(new ArrayList<>(b.metadata.values()));
    if(!Set.of("OPEN","CLOSED","PENDING","APPLIED").contains(r.state())
        ||b.sheet("retention_ledger").id!=r.anchor()||b.sheets.keySet().stream().anyMatch(id->id>=r.nextSheet()))throw new Rejected();
    if(r.next()==2&&(!r.state().equals("CLOSED")||r.epoch()!=1||!r.owner().isEmpty()
        ||!r.operation().equals("BOOTSTRAP")||!r.hash().equals(NarrowQuarterPoc.hash("BOOTSTRAP"))))throw new Rejected();
    for(Metadata m:b.metadata.values())if(m.key().equals(NarrowQuarterPoc.RECEIPT_KEY)){
      JsonNode receipt;try{receipt=MAPPER.readTree(m.value());}catch(Exception invalid){throw new Rejected();}
      if(integer(receipt,"epoch")!=r.epoch()||!Set.of("OPEN","CLOSED","PENDING","APPLIED").contains(text(receipt,"phase")))throw new Rejected();
      // A CLAIM receipt names its exact predecessor owner, while root names the new owner.
      // Runtime reconciliation checks that predecessor via the immutable ticket. No general owner waiver.
      boolean claim=r.state().equals("OPEN")&&r.operation().equals("CLAIM-"+r.owner())&&r.hash().equals(NarrowQuarterPoc.hash(r.owner()));
      if(m.id()==r.next()-1&&(!claim&&!text(receipt,"owner").equals(r.owner())||!text(receipt,"operation").equals(r.operation())
          ||!text(receipt,"hash").equals(r.hash())||!text(receipt,"phase").equals(r.state())))throw new Rejected();
    }
  }
  static void strictControlValue(Metadata m){
    try{
      if(m.key().equals(NarrowQuarterPoc.ROOT_KEY)){
        if(m.characters()>NarrowQuarterPoc.CONTROL_MAX)throw new Rejected();
        MAPPER.readValue(m.value(),NarrowQuarterPoc.Root.class); // strict duplicate, trailing and unknown fields
      }else if(m.key().equals(NarrowQuarterPoc.RECEIPT_KEY)){
        if(m.characters()>NarrowQuarterPoc.CONTROL_MAX)throw new Rejected();JsonNode r=MAPPER.readTree(m.value());
        keys(r,Set.of("epoch","owner","operation","hash","phase"));if(integer(r,"epoch")<1)throw new Rejected();
        NarrowQuarterPoc.ascii(text(r,"owner"),40);NarrowQuarterPoc.ascii(text(r,"operation"),96);NarrowQuarterPoc.ascii(text(r,"phase"),24);
        if(!text(r,"hash").matches("[0-9a-f]{64}"))throw new Rejected();
      }
    }catch(Exception invalid){throw new Rejected();}
  }
  static boolean sameValueKind(JsonNode a,JsonNode b){
    boolean emptyA=a==null||a.isObject()&&a.isEmpty(),emptyB=b==null||b.isObject()&&b.isEmpty();
    return emptyA&&emptyB||!emptyA&&!emptyB&&a.fieldNames().next().equals(b.fieldNames().next());
  }
  static String value(JsonNode value,String tab,int row,int column){
    if(value==null||value.isObject()&&value.isEmpty())return "";
    if(!value.isObject()||value.size()!=1)throw new Rejected();if(value.path("stringValue").isTextual())return value.get("stringValue").textValue();
    JsonNode number=value.get("numberValue");if(number==null||!number.isNumber()||row==0||!INTEGER_COLUMNS.get(tab).contains(column))throw new Rejected();
    BigInteger integer;try{integer=number.decimalValue().toBigIntegerExact();}catch(ArithmeticException bad){throw new Rejected();}if(integer.signum()<0||integer.bitLength()>31)throw new Rejected();return integer.toString();
  }
}
