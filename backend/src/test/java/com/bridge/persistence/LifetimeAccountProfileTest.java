package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LifetimeAccountProfileTest {
 static class Memory extends SheetsAccountProfileTest.Memory {
  @Override public void saveAccountProfile(int row,List<String> account,List<String> identity){
   var header=tabs.get("accounts").get(0); String e=row>0&&tabs.get("accounts").get(row-1).size()==5?tabs.get("accounts").get(row-1).get(4):null;
   boolean uncertain=timeoutAfter; timeoutAfter=false; super.saveAccountProfile(row,account,identity);
   if(header.equals(AccountProfileRows.LIFETIME_HEADER))tabs.get("accounts").set(0,header);
   if(e!=null){var saved=new ArrayList<>(account);saved.add(e);tabs.get("accounts").set(row-1,saved);}
   if(uncertain)throw new Failure(0,0);
  }
 }
 @Test void profileUpdateNoOpAndUncertainRetryPreserveLifetimeExactly(){
  var db=new Memory();var store=new SheetsIdentityStore(db);String id=store.findOrCreateAccount("https://accounts.google.com","sub","Old",null);
  String json=LifetimeStatistics.encode(LifetimeStatisticsTest.carry());
  db.tabs.get("accounts").set(0,AccountProfileRows.LIFETIME_HEADER);db.tabs.get("accounts").set(1,List.of(id,"2","Old","",json));
  assertEquals(id,store.findOrCreateAccount("https://accounts.google.com","sub","Old",null));assertEquals(1,db.writes);
  db.timeoutAfter=true;assertThrows(SheetsGateway.Failure.class,()->store.findOrCreateAccount("https://accounts.google.com","sub","New","a@example.test"));
  assertEquals(id,store.findOrCreateAccount("https://accounts.google.com","sub","New","a@example.test"));assertEquals(2,db.writes);
  assertEquals(List.of(id,"2","New","a@example.test",json),db.tabs.get("accounts").get(1));assertEquals(AccountProfileRows.LIFETIME_HEADER,db.tabs.get("accounts").get(0));
 }
 @Test void destructiveProfileWriterIsDetectedWithoutAutomaticRetry(){
  var db=new SheetsAccountProfileTest.Memory();var store=new SheetsIdentityStore(db);String id=store.findOrCreateAccount("https://accounts.google.com","sub","Old",null);
  db.tabs.get("accounts").set(0,AccountProfileRows.LIFETIME_HEADER);db.tabs.get("accounts").set(1,List.of(id,"2","Old","",LifetimeStatistics.encode(LifetimeStatisticsTest.carry())));
  assertThrows(SheetsGateway.Conflict.class,()->store.findOrCreateAccount("https://accounts.google.com","sub","New",null));assertEquals(2,db.writes);
 }
 @Test void lifetimeHeaderAcceptsMixedLegacyProfileAndCarryRows(){
  var rows=List.of(AccountProfileRows.LIFETIME_HEADER,List.of(UUID.randomUUID().toString(),"1"),List.of(UUID.randomUUID().toString(),"2","Name",""),List.of(UUID.randomUUID().toString(),"2","Name","",LifetimeStatistics.encode(LifetimeStatisticsTest.carry())));
  assertDoesNotThrow(()->AccountProfileRows.validateTable(rows));assertEquals(LifetimeStatistics.empty(),LifetimeStatistics.readAccount(rows,rows.get(1).get(0)));assertEquals(1,LifetimeStatistics.readAccount(rows,rows.get(3).get(0)).formal().matches());
 }
 @Test void actualGoogleProfileRequestsNeverWriteOrClearColumnE(){
  var json=new ObjectMapper();var ids=Map.of("accounts",1,"auth_identities",2);var account=List.of(UUID.randomUUID().toString(),"2","=Name","email@example.test");
  for(int row:List.of(0,2)){
   var requests=json.valueToTree(GoogleSheetsGateway.profileBody(row,account,row==0?List.of("issuer","subject",account.get(0),"1"):null,ids)).get("requests");
   assertEquals(2,requests.get(0).get("updateCells").get("start").get("columnIndex").asInt());
   assertEquals(2,requests.get(0).get("updateCells").get("rows").get(0).get("values").size());
   var data=requests.get(1).get(row==0?"appendCells":"updateCells");assertEquals(4,data.get("rows").get(0).get("values").size());assertFalse(data.has("range"));
   assertEquals("=Name",data.get("rows").get(0).get("values").get(2).get("userEnteredValue").get("stringValue").asText());
  }
 }
}
