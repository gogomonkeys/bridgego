package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Pure literal-value Sheets model and preflight simulation. No endpoint or fault injection. */
class NativeSheetsModel {
  static final ObjectMapper JSON = new ObjectMapper();
  static final int METADATA_LIMIT = 30_000;
  static String json(Object value) { try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException(); } }
  static JsonNode parse(String value) { try { return JSON.readTree(value); } catch(Exception e) { throw new Rejected(); } }
  static int bytes(Object value) { return json(value).getBytes(StandardCharsets.UTF_8).length; }
  static final class Rejected extends RuntimeException {}
  static final class Unknown extends RuntimeException {}
  record Metadata(int id, String key, String value, Integer sheet) {
    Metadata { if (id <= 0 || key == null || key.isEmpty() || value == null) throw new Rejected(); }
    int characters() { return key.length() + value.length(); }
    Map<String,Object> nativeValue() { return Map.of("metadataId",id,"metadataKey",key,"metadataValue",value,"visibility","DOCUMENT","location",sheet == null ? Map.of("spreadsheet",true) : Map.of("sheetId",sheet)); }
  }
  static final class Sheet {
    int id, rowCount=1000, columnCount=26; String title; List<List<String>> rows;
    Sheet(int id,String title,List<List<String>> rows) { this.id=id;this.title=title;this.rows=copyRows(rows); }
    Sheet copy() { Sheet s=new Sheet(id,title,rows);s.rowCount=rowCount;s.columnCount=columnCount;return s; }
  }
  static final class Book {
    Map<Integer,Sheet> sheets = new TreeMap<>(); Map<Integer,Metadata> metadata = new TreeMap<>();
    Book copy() { Book b=new Book();sheets.forEach((id,s)->b.sheets.put(id,s.copy()));b.metadata.putAll(metadata);return b; }
    Sheet sheet(int id) { Sheet s=sheets.get(id);if(s==null)throw new Rejected();return s; }
    Sheet sheet(String title) { return sheets.values().stream().filter(s->s.title.equals(title)).findFirst().orElseThrow(Rejected::new); }
    Map<String,List<List<String>>> tables() { var t=new TreeMap<String,List<List<String>>>();sheets.values().forEach(s->t.put(s.title,List.copyOf(s.rows)));return ManualQuarterMaintenance.immutable(t); }
    Map<String,Object> nativeValue() {
      List<Object> ss=new ArrayList<>(); for(Sheet s:sheets.values()) {
        var item=new LinkedHashMap<String,Object>();item.put("properties",Map.of("sheetId",s.id,"title",s.title,"gridProperties",Map.of("rowCount",s.rowCount,"columnCount",s.columnCount)));
        item.put("data",List.of(Map.of("startRow",0,"startColumn",0,"rowData",rowData(s.rows))));
        item.put("developerMetadata",metadata.values().stream().filter(m->Objects.equals(m.sheet,s.id)).map(Metadata::nativeValue).toList());ss.add(item);
      }
      return Map.of("spreadsheetId","SYNTHETIC_NARROW_ONLY","sheets",ss,"developerMetadata",metadata.values().stream().filter(m->m.sheet==null).map(Metadata::nativeValue).toList());
    }
    void budgets() {
      Map<Integer,Integer> used=new HashMap<>(); for(Metadata m:metadata.values()) {
        if(m.sheet!=null)sheet(m.sheet);int id=m.sheet==null?-1:m.sheet;
        int n=used.merge(id,m.characters(),Integer::sum);if(n>METADATA_LIMIT)throw new Rejected();
      }
    }
  }
  static List<List<String>> copyRows(List<List<String>> rows) { return new ArrayList<>(rows.stream().map(r->new ArrayList<>(r)).toList()); }
  static List<Map<String,Object>> rowData(List<List<String>> rows) { return rows.stream().map(row->Map.<String,Object>of("values",row.stream().map(v->v==null?Map.of():Map.of("userEnteredValue",Map.of("stringValue",v))).toList())).toList(); }
  /** Shared parser used for preflight; only the real endpoint batch increments dispatch counters. */
  static Book simulate(Book original,String wire) {
    JsonNode envelope=parse(wire);keys(envelope,Set.of("requests"));JsonNode requests=envelope.get("requests");if(requests==null||!requests.isArray()||requests.isEmpty())throw new Rejected();
    Book b=original.copy();for(JsonNode request:requests) { if(!request.isObject()||request.size()!=1)throw new Rejected();String kind=request.fieldNames().next();JsonNode v=request.get(kind);
      switch(kind) {
        case "updateSheetProperties" -> {
          keys(v,Set.of("properties","fields"));if(!text(v,"fields").equals("title"))throw new Rejected();JsonNode p=v.get("properties");keys(p,Set.of("sheetId","title"));Sheet s=b.sheet(integer(p,"sheetId"));String title=text(p,"title");
          if(b.sheets.values().stream().anyMatch(other->other.id!=s.id&&other.title.equals(title)))throw new Rejected();s.title=title;
        }
        case "addSheet" -> {
          keys(v,Set.of("properties"));JsonNode p=v.get("properties");keys(p,Set.of("sheetId","title","gridProperties"));int id=integer(p,"sheetId");String title=text(p,"title");
          if(id<0||b.sheets.containsKey(id)||b.sheets.values().stream().anyMatch(s->s.title.equals(title)))throw new Rejected();Sheet s=new Sheet(id,title,List.of());
          JsonNode g=p.get("gridProperties");keys(g,Set.of("rowCount","columnCount"));s.rowCount=integer(g,"rowCount");s.columnCount=integer(g,"columnCount");if(s.rowCount!=1000||s.columnCount!=26)throw new Rejected();b.sheets.put(id,s);
        }
        case "deleteSheet" -> {keys(v,Set.of("sheetId"));int id=integer(v,"sheetId");b.sheet(id);b.sheets.remove(id);b.metadata.values().removeIf(m->Objects.equals(m.sheet,id));}
        case "createDeveloperMetadata" -> {
          keys(v,Set.of("developerMetadata"));Metadata m=metadata(v.get("developerMetadata"));if(b.metadata.putIfAbsent(m.id,m)!=null)throw new Rejected();
        }
        case "updateDeveloperMetadata" -> {
          keys(v,Set.of("dataFilters","developerMetadata","fields"));if(!text(v,"fields").equals("metadataValue"))throw new Rejected();int id=lookup(v);Metadata m=b.metadata.get(id);JsonNode d=v.get("developerMetadata");keys(d,Set.of("metadataValue"));String value=text(d,"metadataValue");if(m!=null)b.metadata.put(id,new Metadata(id,m.key,value,m.sheet));
        }
        case "deleteDeveloperMetadata" -> {keys(v,Set.of("dataFilter"));JsonNode filter=v.get("dataFilter");keys(filter,Set.of("developerMetadataLookup"));JsonNode l=filter.get("developerMetadataLookup");keys(l,Set.of("metadataId"));b.metadata.remove(integer(l,"metadataId"));}
        case "appendCells" -> {
          keys(v,Set.of("sheetId","rows","fields"));literalFields(v);Sheet s=b.sheet(integer(v,"sheetId"));List<List<String>> rows=rows(v.get("rows"));s.rows.addAll(rows);validateGrid(s);
        }
        case "updateCells" -> {
          keys(v,Set.of("range","rows","fields"));literalFields(v);JsonNode r=v.get("range");keys(r,Set.of("sheetId","startRowIndex","endRowIndex","startColumnIndex","endColumnIndex"));Sheet s=b.sheet(integer(r,"sheetId"));int start=integer(r,"startRowIndex"),end=integer(r,"endRowIndex"),col=integer(r,"startColumnIndex"),cols=integer(r,"endColumnIndex");
          if(start<0||end<=start||end>s.rowCount||col<0||cols<=col||cols>s.columnCount)throw new Rejected();List<List<String>> data=rows(v.get("rows"));if(data.size()>end-start||data.stream().anyMatch(row->row.size()>cols-col))throw new Rejected();
          while(s.rows.size()<end)s.rows.add(new ArrayList<>());
          for(int i=start;i<end;i++){var target=new ArrayList<>(s.rows.get(i));while(target.size()<cols)target.add("");for(int c=col;c<cols;c++)target.set(c,i-start<data.size()&&c-col<data.get(i-start).size()?data.get(i-start).get(c-col):"");int explicitWidth=i-start<data.size()?col+data.get(i-start).size():0;int untouchedWidth=s.rows.get(i).size()>cols?s.rows.get(i).size():0;int preserveWidth=Math.max(explicitWidth,untouchedWidth);while(target.size()>preserveWidth&&target.get(target.size()-1).isEmpty())target.remove(target.size()-1);s.rows.set(i,target);}
          // Preserve meaningful declared empty cells in rowData; trailing grid-empty rows are omitted.
          for(int i=0;i<data.size();i++){int index=start+i;if(col==0&&data.get(i).size()==cols)s.rows.set(index,new ArrayList<>(data.get(i)));}
          while(!s.rows.isEmpty()&&s.rows.get(s.rows.size()-1).isEmpty())s.rows.remove(s.rows.size()-1);validateGrid(s);
        }
        default -> throw new Rejected();
      }
      b.budgets(); // quota checked at each request prefix, including the temporary pre-GC receipt.
    }
    return b;
  }
  static List<String> trimRangeRow(List<String> row){int n=row.size();while(n>0&&row.get(n-1).isEmpty())n--;return List.copyOf(row.subList(0,n));}
  static void validateGrid(Sheet s){if(s.rows.size()>s.rowCount||s.rows.stream().anyMatch(r->r.size()>s.columnCount))throw new Rejected();}
  static void literalFields(JsonNode n){if(!text(n,"fields").equals("userEnteredValue"))throw new Rejected();}
  static List<List<String>> rows(JsonNode a){if(a==null||!a.isArray())throw new Rejected();List<List<String>> result=new ArrayList<>();for(JsonNode r:a){keys(r,Set.of("values"));if(!r.path("values").isArray())throw new Rejected();List<String> row=new ArrayList<>();for(JsonNode c:r.get("values")){keys(c,Set.of("userEnteredValue"));JsonNode u=c.get("userEnteredValue");keys(u,Set.of("stringValue"));row.add(text(u,"stringValue"));}result.add(row);}return result;}
  static Metadata metadata(JsonNode n){keys(n,Set.of("metadataId","metadataKey","metadataValue","visibility","location"));if(!text(n,"visibility").equals("DOCUMENT"))throw new Rejected();JsonNode l=n.get("location");Integer sheet=null;if(l.has("sheetId")){keys(l,Set.of("sheetId"));sheet=integer(l,"sheetId");}else {keys(l,Set.of("spreadsheet"));if(!l.path("spreadsheet").isBoolean()||!l.path("spreadsheet").booleanValue())throw new Rejected();}return new Metadata(integer(n,"metadataId"),text(n,"metadataKey"),text(n,"metadataValue"),sheet);}
  static int lookup(JsonNode n){JsonNode fs=n.get("dataFilters");if(fs==null||!fs.isArray()||fs.size()!=1)throw new Rejected();keys(fs.get(0),Set.of("developerMetadataLookup"));JsonNode l=fs.get(0).get("developerMetadataLookup");keys(l,Set.of("metadataId"));return integer(l,"metadataId");}
  static int integer(JsonNode n,String k){JsonNode v=n==null?null:n.get(k);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt())throw new Rejected();return v.intValue();}
  static String text(JsonNode n,String k){JsonNode v=n==null?null:n.get(k);if(v==null||!v.isTextual())throw new Rejected();return v.textValue();}
  static void keys(JsonNode n,Set<String> allowed){if(n==null||!n.isObject())throw new Rejected();n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new Rejected();});}
}
