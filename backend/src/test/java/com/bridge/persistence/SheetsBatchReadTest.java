package com.bridge.persistence;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SheetsBatchReadTest {
 static final ObjectMapper JSON=new ObjectMapper();
 static ObjectNode response(String... tabs){
  var root=JSON.createObjectNode();root.put("spreadsheetId","synthetic");var ranges=root.putArray("valueRanges");
  for(String tab:tabs)ranges.addObject().put("range","'"+tab+"'!A1:E1000").put("majorDimension","ROWS");return root;
 }
 static ObjectNode range(ObjectNode root,int index){return (ObjectNode)root.path("valueRanges").get(index);}
 @Test void defaultBatchValidatesBeforeReadsAndReturnsDeepImmutableCopies(){
  var source=new ArrayList<List<String>>();source.add(new ArrayList<>(List.of("value")));
  int[] reads={0};var gateway=new SheetsGateway(){public List<List<String>> read(String tab){reads[0]++;return source;}public void appendAtomic(Map<String,List<List<Object>>> x){fail();}};
  assertThrows(IllegalArgumentException.class,()->gateway.readBatch(List.of()));
  assertThrows(IllegalArgumentException.class,()->gateway.readBatch(List.of("accounts","accounts")));assertEquals(0,reads[0]);
  var batch=gateway.readBatch(List.of("accounts","commits"));assertEquals(2,reads[0]);source.get(0).set(0,"changed");
  assertEquals("value",batch.get("accounts").get(0).get(0));
  assertThrows(UnsupportedOperationException.class,()->batch.clear());
  assertThrows(UnsupportedOperationException.class,()->batch.get("accounts").clear());
  assertThrows(UnsupportedOperationException.class,()->batch.get("accounts").get(0).clear());
 }
 @Test void batchPathHasOnlyAllowlistedFixedRangesAndOneEndpoint(){
  assertEquals("/values:batchGet?valueRenderOption=UNFORMATTED_VALUE&majorDimension=ROWS&ranges=accounts%21A%3AE&ranges=commits%21A%3AE",GoogleSheetsGateway.batchPath(List.of("accounts","commits")));
  for(var tabs:List.of(List.<String>of(),List.of("accounts","accounts"),List.of("accounts!F:Z"),List.of("private")))
   assertThrows(IllegalArgumentException.class,()->GoogleSheetsGateway.batchPath(tabs));
 }
 @Test void parserMatchesRangeNamesNotOrderAndSupportsEmptyRowsAndQuotedOrWholeRanges(){
  var root=response("commits","accounts");range(root,0).put("range","commits!A:E");
  range(root,1).putArray("values").addArray().add("id").add(2).add(true);
  var result=GoogleSheetsGateway.decodeBatch(root,List.of("accounts","commits"),"synthetic");
  assertEquals(List.of(List.of("id","2","true")),result.get("accounts"));assertEquals(List.of(),result.get("commits"));
  assertThrows(UnsupportedOperationException.class,()->result.get("accounts").get(0).clear());
 }
 @Test void malformedMissingDuplicateUnexpectedRangesDimensionsAndValuesFailClosed(){
  for(String invalid:List.of("accounts!B1:E9","accounts!A2:E9","accounts!A1:F9","other!A1:E9","accounts!A:E9","accounts!A1:E","accounts!A1:E0")){
   var root=response("accounts");range(root,0).put("range",invalid);assertThrows(SheetsGateway.Conflict.class,()->GoogleSheetsGateway.decodeBatch(root,List.of("accounts"),"synthetic"));
  }
  var duplicate=response("accounts","accounts");assertThrows(SheetsGateway.Conflict.class,()->GoogleSheetsGateway.decodeBatch(duplicate,List.of("accounts","commits"),"synthetic"));
  assertThrows(SheetsGateway.Conflict.class,()->GoogleSheetsGateway.decodeBatch(response("accounts"),List.of("accounts","commits"),"synthetic"));
  assertThrows(SheetsGateway.Conflict.class,()->GoogleSheetsGateway.decodeBatch(response("accounts"),List.of("accounts"),"wrong"));
  for(int mode=0;mode<6;mode++){
   var root=response("accounts");var r=range(root,0);
   switch(mode){case 0->r.put("majorDimension","COLUMNS");case 1->r.putNull("values");case 2->r.putArray("values").add("notRow");case 3->{var cells=r.putArray("values").addArray();for(int i=0;i<6;i++)cells.add("x");}case 4->r.putArray("values").addArray().addObject();case 5->{r.put("range","accounts!A1:E1");r.putArray("values").addArray();((ArrayNode)r.get("values")).addArray();}}
   assertThrows(SheetsGateway.Conflict.class,()->GoogleSheetsGateway.decodeBatch(root,List.of("accounts"),"synthetic"));
  }
 }
 @Test void eachRangeRetainsFourMiBCapacityWithoutAnUnboundedCombinedBody(){
  var root=response("accounts","commits");for(int i=0;i<2;i++)range(root,i).putArray("values").addArray().add("x".repeat(3*1024*1024));
  assertEquals(2,GoogleSheetsGateway.decodeBatch(root,List.of("accounts","commits"),"synthetic").size());
  range(root,0).putArray("values").addArray().add("x".repeat(4*1024*1024));
  assertThrows(SheetsGateway.Conflict.class,()->GoogleSheetsGateway.decodeBatch(root,List.of("accounts","commits"),"synthetic"));
 }
 @Test void retryAfterSecondsDatesMalformedAndOverflowAreSafe(){
  var now=Instant.parse("2026-11-10T00:00:00Z");
  assertEquals(120000,GoogleSheetsGateway.retryAfterMillis("120",now));
  assertEquals(1000,GoogleSheetsGateway.retryAfterMillis("0".repeat(100)+"1",now));
  assertEquals(120000,GoogleSheetsGateway.retryAfterMillis("Tue, 10 Nov 2026 00:02:00 GMT",now));
  for(String v:List.of("","-1","1.5","nonsense","Mon, 09 Nov 2026 00:00:00 GMT"))assertEquals(0,GoogleSheetsGateway.retryAfterMillis(v,now));
  assertEquals(Long.MAX_VALUE,GoogleSheetsGateway.retryAfterMillis("9223372036854775807",now));
  assertEquals(Long.MAX_VALUE,GoogleSheetsGateway.retryAfterMillis("9".repeat(100),now));
 }
}
