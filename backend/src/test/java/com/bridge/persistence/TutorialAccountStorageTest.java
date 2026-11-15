package com.bridge.persistence;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TutorialAccountStorageTest {
 static final String ID=UUID.randomUUID().toString();
 static final Instant NOW=Instant.parse("2026-11-15T00:00:00Z");
 static class Memory extends SheetsStatsStoreTest.Fake {
  int writes; boolean timeout;
  Memory(){SheetsStatsStoreTest.ready(this,NOW);tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LEGACY_HEADER,List.of(ID,"1"))));}
  public void acknowledgeTutorial(int row){writes++;var rows=tabs.get("accounts");rows.set(0,AccountProfileRows.TUTORIAL_HEADER);var value=new ArrayList<>(rows.get(row-1));while(value.size()<6)value.add("");value.set(5,AccountProfileRows.TUTORIAL_ACKNOWLEDGED);rows.set(row-1,value);if(timeout)throw new SheetsGateway.Failure(0,0);}
  SheetsIdentityStore store(){return new SheetsIdentityStore(this,Clock.fixed(NOW,ZoneOffset.UTC));}
 }
 @Test void legacyAndRepeatedChoiceAreDurableAcrossStoreInstances(){var m=new Memory();assertFalse(m.store().tutorialOffered(ID));m.store().markTutorialOffered(ID);assertTrue(m.store().tutorialOffered(ID));m.store().markTutorialOffered(ID);assertEquals(1,m.writes);}
 @Test void timeoutDoesNotRetryAndLaterStatusReconciles(){var m=new Memory();m.timeout=true;assertThrows(SheetsGateway.Failure.class,()->m.store().markTutorialOffered(ID));assertEquals(1,m.writes);assertTrue(m.store().tutorialOffered(ID));}
 @Test void unknownAccountCannotCreateOrMark(){var m=new Memory();assertThrows(SheetsGateway.Conflict.class,()->m.store().markTutorialOffered(UUID.randomUUID().toString()));assertEquals(0,m.writes);}
 @Test void carryAndProfileArePreservedWhenFlagChanges(){var m=new Memory();String carry=LifetimeStatistics.encode(LifetimeStatisticsTest.carry());m.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LIFETIME_HEADER,List.of(ID,"2","名字","p@example.test",carry))));m.store().markTutorialOffered(ID);assertEquals(carry,m.tabs.get("accounts").get(1).get(4));assertEquals(LifetimeStatisticsTest.carry(),LifetimeStatistics.readAccount(m.tabs.get("accounts"),ID));}
 @Test void quarterFoldKeepsFlagAndAddsCareer(){var f=new LocalManualQuarterRehearsal.Fixture();String id=LifetimeStatisticsTest.account(f);var rows=new ArrayList<List<String>>();rows.add(AccountProfileRows.TUTORIAL_HEADER);for(var row:f.tables.get("accounts").subList(1,f.tables.get("accounts").size()))rows.add(List.of(row.get(0),"1","","","",AccountProfileRows.TUTORIAL_ACKNOWLEDGED));var folded=LifetimeStatistics.fold(rows,List.of(LifetimeStatisticsTest.marked(f.expired,"FORMAL","FORFEIT")),LifetimeStatisticsTest.Q4);assertEquals(AccountProfileRows.TUTORIAL_HEADER,folded.get(0));assertEquals(AccountProfileRows.TUTORIAL_ACKNOWLEDGED,folded.get(1).get(5));assertEquals(1,LifetimeStatistics.readAccount(folded,id).formal().matches());}
 @Test void strictSchemaRejectsUnknownFlagAndExtraData(){assertThrows(SheetsGateway.Conflict.class,()->AccountProfileRows.validateTable(List.of(AccountProfileRows.TUTORIAL_HEADER,List.of(ID,"1","","","","unknown"))));assertThrows(SheetsGateway.Conflict.class,()->AccountProfileRows.validateTable(List.of(AccountProfileRows.LIFETIME_HEADER,List.of(ID,"1","","","",AccountProfileRows.TUTORIAL_ACKNOWLEDGED))));}
 @Test void narrowBatchTouchesOnlyHeaderAndFlag(){var body=GoogleSheetsGateway.tutorialBody(2,Map.of("accounts",12));var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body);assertEquals(2,json.path("requests").size());assertEquals(5,json.path("requests").get(1).path("updateCells").path("start").path("columnIndex").asInt());assertEquals(1,json.path("requests").get(1).path("updateCells").path("rows").get(0).path("values").size());}
 @Test void erasureReplacementClearsTutorialColumnEvenWhenNoAccountsRemain(){
  Map<String,List<List<Object>>> tables=new TreeMap<>(); Map<String,Integer> ids=new TreeMap<>();int id=0;
  for(String tab:List.of("envelopes","commits","accounts","auth_identities","erasure_ledger")){tables.put(tab,List.of(List.of("header")));ids.put(tab,id++);}
  tables.put("accounts",List.of(new ArrayList<Object>(AccountProfileRows.TUTORIAL_HEADER)));
  var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(GoogleSheetsGateway.replacementBody(tables,ids));
  for(var request:json.path("requests")){var update=request.path("updateCells"); if(update.path("range").path("sheetId").asInt()==ids.get("accounts")){assertEquals(6,update.path("range").path("endColumnIndex").asInt());assertEquals(1,update.path("rows").size());}}
 }
 @Test void erasedAccountCannotReadOrAcknowledge(){var m=new Memory();m.tabs.get("erasure_ledger").add(List.of("ACCOUNT",ErasureLedger.digest("ACCOUNT",ID),"1"));assertThrows(SheetsGateway.Conflict.class,()->m.store().tutorialOffered(ID));assertThrows(SheetsGateway.Conflict.class,()->m.store().markTutorialOffered(ID));assertEquals(0,m.writes);}
 @Test void accountErasureRemovesFlagAndKeepsOtherAccount() throws Exception {
  var f=new OperatorAccountErasureTest.Fixture();
  f.db.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.TUTORIAL_HEADER,List.of(f.account,"1","","","",AccountProfileRows.TUTORIAL_ACKNOWLEDGED),List.of(f.other,"1","","","",AccountProfileRows.TUTORIAL_ACKNOWLEDGED))));
  var service=f.service(true);var plan=f.preview(service);assertEquals(OperatorAccountErasure.Outcome.VERIFIED,f.execute(service,plan,"owner"));
  assertEquals(2,f.db.tabs.get("accounts").size());assertEquals(f.other,f.db.tabs.get("accounts").get(1).get(0));assertEquals(AccountProfileRows.TUTORIAL_ACKNOWLEDGED,f.db.tabs.get("accounts").get(1).get(5));
 }
}
