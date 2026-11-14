package com.bridge.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
class BatchedEntryProfileTest {
 static final String ID=UUID.randomUUID().toString(), DATASET=UUID.randomUUID().toString(), ISSUER="https://accounts.google.com";
 static final Instant NOW=Instant.parse("2026-11-14T00:00:00Z");
 static class Fake extends SheetsStatsStoreTest.Fake {
  int batches,singles,profiles; boolean timeout; IntConsumer hook=n->{};
  Fake(){
   SheetsStatsStoreTest.ready(this,NOW);
   tabs.put("dataset",List.of(List.of("purpose","dataset_id"),List.of(DatasetBinding.MANAGED_PURPOSE,DATASET)));
   tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LEGACY_HEADER,List.of(ID,"1"))));
   tabs.put("auth_identities",List.of(List.of("issuer","subject","account_id","schema_version"),List.of(ISSUER,"sub",ID,"1")));
  }
  @Override public synchronized List<List<String>> read(String tab){singles++;return super.read(tab);}
  @Override public synchronized Map<String,List<List<String>>> readBatch(List<String> names){
   hook.accept(++batches);var result=new LinkedHashMap<String,List<List<String>>>();
   for(String name:names)result.put(name,tabs.getOrDefault(name,List.of()));return SheetsGateway.immutableBatch(names,result);
  }
  @Override public synchronized void saveAccountProfile(int row,List<String> profile,List<String> identity){
   profiles++;assertNull(identity);assertEquals(2,row);
   var old=tabs.get("accounts").get(row-1);var saved=new ArrayList<>(profile);if(old.size()==5)saved.add(old.get(4));
   tabs.get("accounts").set(0,old.size()==5?AccountProfileRows.LIFETIME_HEADER:AccountProfileRows.HEADER);
   tabs.get("accounts").set(row-1,saved);if(timeout)throw new SheetsGateway.Failure(0,0);
  }
  SheetsIdentityStore store(){return new SheetsIdentityStore(new DatasetBinding("synthetic_sheet",DATASET,DatasetBinding.MANAGED_PURPOSE).gateway(this),Clock.fixed(NOW,ZoneOffset.UTC));}
 }
 @Test void legacyLobbyNewIdentityCanCreateThenSaveProfile(){
  var f=new SheetsAccountProfileTest.Memory();var store=new SheetsIdentityStore(f);
  var saved=store.completeEntryProfile(ISSUER,"new-sub","小白",null);
  assertNotNull(saved.accountId());assertEquals("小白",saved.nickname());
  assertEquals(saved,store.findOrCreateForEntry(ISSUER,"new-sub"));
 }
 @Test void firstProfileUsesThreeBatchesOneWriteAndDatasetWriteCheck(){
  var f=new Fake();var profile=f.store().completeEntryProfile(ISSUER,"sub","小白","a@example.test");
  assertEquals("小白",profile.nickname());assertEquals(ID,profile.accountId());
  assertEquals(3,f.batches);assertEquals(1,f.singles);assertEquals(1,f.profiles);
 }
 @Test void existingEntryUsesOneBatchAndNeverOverwrites(){
  var f=new Fake();f.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.HEADER,List.of(ID,"2","小白","a@example.test"))));
  assertEquals("小白",f.store().findOrCreateForEntry(ISSUER,"sub").nickname());assertEquals(1,f.batches);assertEquals(0,f.singles);
  assertEquals("小白",f.store().completeEntryProfile(ISSUER,"sub","小黑","other@example.test").nickname());
  assertEquals(2,f.batches);assertEquals(0,f.profiles);
 }
 @Test void lifetimeCarrySurvivesLegacyUpgrade(){
  var f=new Fake();String carry=LifetimeStatistics.encode(LifetimeStatsExportTest.carry());
  f.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LIFETIME_HEADER,List.of(ID,"1","","",carry))));
  f.store().completeEntryProfile(ISSUER,"sub","小白",null);
  assertEquals(carry,f.tabs.get("accounts").get(1).get(4));assertEquals(AccountProfileRows.LIFETIME_HEADER,f.tabs.get("accounts").get(0));
 }
 @Test void prewriteMutationOrFailureNeverWrites(){
  for(String mode:List.of("QUOTA","DATASET","ACCOUNT","ERASURE","RETENTION","IDENTITY")){
   var f=new Fake();f.hook=n->{if(n==2)switch(mode){
    case "QUOTA"->throw new SheetsGateway.Failure(429,0);
    case "DATASET"->f.tabs.put("dataset",List.of());
    case "ACCOUNT"->f.tabs.get("accounts").set(1,List.of(ID,"2","別名",""));
    case "ERASURE"->f.tabs.get("erasure_ledger").add(List.of("ACCOUNT",ErasureLedger.digest("ACCOUNT",ID),"1"));
    case "RETENTION"->f.tabs.put("retention_ledger",List.of());
    case "IDENTITY"->f.tabs.put("auth_identities",List.of());
   }};
   assertThrows(RuntimeException.class,()->f.store().completeEntryProfile(ISSUER,"sub","小白",null),mode);assertEquals(0,f.profiles,mode);
  }
 }
 @Test void readbackFailureAndUnknownWriteNeverReplayAndLaterReadReconciles(){
  for(boolean timeout:List.of(false,true)){
   var f=new Fake();f.timeout=timeout;f.hook=n->{if(n==3)throw new SheetsGateway.Failure(429,0);};
   assertThrows(SheetsGateway.Failure.class,()->f.store().completeEntryProfile(ISSUER,"sub","小白",null));assertEquals(1,f.profiles);
   f.timeout=false;f.hook=n->{};
   assertEquals("小白",f.store().completeEntryProfile(ISSUER,"sub","小黑",null).nickname());assertEquals(1,f.profiles);
  }
 }
}
