package com.bridge.persistence;
import java.util.*;
import java.nio.file.*;
import static com.bridge.persistence.NativeSheetsModel.*;
import static com.bridge.persistence.NarrowQuarterPoc.*;
final class RecoveredFixture {
  static final String ID="1hm2CKBAlbTMRVHPZ-7_nTcjGbdb2PNvCgn9Uq_4YY2s", DATASET="b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b";
  static Book book() throws Exception {
    var data=JSON.readTree(RecoveredFixture.class.getResourceAsStream("/seed.json"));
    var meta=JSON.readTree(RecoveredFixture.class.getResourceAsStream("/metadata.json"));Book b=new Book();
    for(var s:meta.get("sheets")){var p=s.get("properties");String title=p.get("title").asText();List<List<String>> rows=new ArrayList<>();
      for(var r:data.get(title)){List<String> row=new ArrayList<>();for(var c:r)row.add(c.textValue());rows.add(row);}
      Sheet sheet=new Sheet(p.get("sheetId").intValue(),title,rows);b.sheets.put(sheet.id,sheet);
    }
    int next=b.sheets.keySet().stream().max(Integer::compare).orElseThrow()+1;
    Root root=new Root(DATASET,1,b.sheet("retention_ledger").id,next,2,"","CLOSED","BOOTSTRAP",hash("BOOTSTRAP"));
    b.metadata.put(1,new Metadata(1,ROOT_KEY,rootText(root),null));return b;
  }
  public static void main(String[] args)throws Exception {
    Book b=book();List<Object> requests=new ArrayList<>();requests.add(create(b.metadata.get(1)));
    for(Sheet s:b.sheets.values())for(String dimension:List.of("ROWS","COLUMNS"))requests.add(Map.of("updateDimensionProperties",Map.of("range",Map.of("sheetId",s.id,"dimension",dimension,"startIndex",0,"endIndex",dimension.equals("ROWS")?1000:26),"properties",Map.of("pixelSize",dimension.equals("ROWS")?21:100),"fields","pixelSize")));
    Path out=Path.of(args[0]);Files.createDirectories(out);
    Files.writeString(out.resolve("bootstrap-payload.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("requests",requests)));
    Files.writeString(out.resolve("baseline-native-model.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(b.nativeValue()));
    Files.writeString(out.resolve("baseline.sha256"),MaintenanceRelease.digest(b)+"\n");
    System.out.println(MaintenanceRelease.digest(b));
  }
}
