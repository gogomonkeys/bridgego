package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SheetsAccountProfileTest {
  static class Memory extends SheetsStatsStoreTest.Fake {
    Memory(){tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LEGACY_HEADER)));tabs.put("auth_identities",new ArrayList<>(List.of(List.of("issuer","subject","account_id","schema_version"))));}
    @Override public void saveAccountProfile(int row,List<String> account,List<String> identity){
      writes++;tabs.get("accounts").set(0,AccountProfileRows.HEADER);
      if(row==0){tabs.get("accounts").add(account);tabs.get("auth_identities").add(identity);}else tabs.get("accounts").set(row-1,account);
      if(timeoutAfter){timeoutAfter=false;throw new Failure(0,0);}
    }
  }
  @Test void entryPreservesStableNicknameAndIdentityAndReconcilesUncertainSave() {
    var db=new Memory();var store=new SheetsIdentityStore(db);
    var entry=store.findOrCreateForEntry("https://accounts.google.com","sub");
    assertNull(entry.nickname());
    db.timeoutAfter=true;
    assertThrows(SheetsGateway.Failure.class,()->store.completeEntryProfile("https://accounts.google.com","sub","小白","a@example.test"));
    int writes=db.writes;
    var saved=store.completeEntryProfile("https://accounts.google.com","sub","小黑","other@example.test");
    assertEquals(entry.accountId(),saved.accountId());assertEquals("小白",saved.nickname());
    assertEquals(writes,db.writes);assertEquals(2,db.read("accounts").size());
  }
  @Test void productionDatasetBindingForwardsProfileOnlyForCorrectTarget(){
    var db=new Memory();String dataset=UUID.randomUUID().toString();
    db.tabs.put("dataset",List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",dataset)));
    var bound=new DatasetBinding("synthetic_sheet_id",dataset,"bridgego-managed").gateway(db);
    var store=new SheetsIdentityStore(bound);String id=store.findOrCreateAccount("https://accounts.google.com","sub","名稱",null);
    assertEquals(id,db.read("accounts").get(1).get(0));
    db.tabs.put("dataset",List.of());assertThrows(SheetsGateway.Conflict.class,()->store.findOrCreateAccount("https://accounts.google.com","sub","名稱",null));assertEquals(1,db.writes);
  }
  @Test void createsUpdatesAndClearsMissingEmailWithoutChangingIdentity(){
    var db=new Memory();var store=new SheetsIdentityStore(db);
    var id=store.findOrCreateAccount("https://accounts.google.com","sub","GOGO","a@example.test");
    assertEquals(List.of(id,"2","GOGO","a@example.test"),db.read("accounts").get(1));
    assertEquals(id,store.findOrCreateAccount("https://accounts.google.com","sub","新暱稱",null));
    assertEquals(List.of(id,"2","新暱稱",""),db.read("accounts").get(1));assertEquals(2,db.read("auth_identities").size());
  }
  @Test void upgradesExistingLegacyAccountAndPreservesOtherAccounts(){
    var db=new Memory();var store=new SheetsIdentityStore(db);
    var id=store.findOrCreateAccount("https://accounts.google.com","sub");
    var other=store.findOrCreateAccount("https://accounts.google.com","other");
    assertEquals(id,store.findOrCreateAccount("https://accounts.google.com","sub","名字","a@example.test"));
    assertTrue(db.read("accounts").contains(List.of(other,"1")));
  }
  @Test void uncertainWriteIsNotRetriedAndLaterLoginReusesAccount(){
    var db=new Memory();db.timeoutAfter=true;var store=new SheetsIdentityStore(db);
    assertThrows(SheetsGateway.Failure.class,()->store.findOrCreateAccount("https://accounts.google.com","sub","名稱","a@example.test"));
    assertEquals(1,db.writes);var id=db.read("accounts").get(1).get(0);
    assertEquals(id,store.findOrCreateAccount("https://accounts.google.com","sub","名稱","a@example.test"));assertEquals(1,db.writes);
  }
  @Test void rejectsUnexpectedHeaderBeforeWrite(){var db=new Memory();db.tabs.get("accounts").set(0,List.of("bad"));assertThrows(SheetsGateway.Conflict.class,()->new SheetsIdentityStore(db).findOrCreateAccount("https://accounts.google.com","sub","名字",null));assertEquals(0,db.writes);}
}
