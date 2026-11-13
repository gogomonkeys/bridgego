package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

class BatchedStatisticsExportTest {
  static class Counting extends SheetsStatsStoreTest.Fake {
    int batches,singles;IntConsumer beforeBatch=n->{};final List<List<String>> requested=new ArrayList<>();
    @Override public synchronized List<List<String>> read(String tab){singles++;return super.read(tab);}
    @Override public synchronized Map<String,List<List<String>>> readBatch(List<String> names){
      batches++;requested.add(List.copyOf(names));beforeBatch.accept(batches);
      var result=new LinkedHashMap<String,List<List<String>>>();
      for(String name:names)result.put(name,tabs.getOrDefault(name,List.of()));
      return SheetsGateway.immutableBatch(names,result);
    }
  }
  static Counting fake(){
    var fake=new Counting();LifetimeStatsExportTest.setup(fake,LifetimeStatsExportTest.current());
    fake.tabs.put("dataset",new ArrayList<>(List.of(List.of("purpose","dataset_id"),
        List.of(DatasetBinding.MANAGED_PURPOSE,DatasetBindingTest.ID))));
    return fake;
  }
  static String account(Counting fake){return fake.tabs.get("accounts").get(1).get(0);}
  static SheetsGateway bound(Counting fake){return new DatasetBinding(DatasetBindingTest.SHEET,DatasetBindingTest.ID,DatasetBinding.MANAGED_PURPOSE).gateway(fake);}
  @Test void managedExportUsesExactlyThreeBatchRequestsIncludingDatasetAndNoWrites(){
    var f=fake();int writes=f.writes;
    try(var store=new SheetsStatsStore(bound(f),1,LifetimeStatsExportTest.CLOCK)){
      var data=store.exportStatistics(account(f));
      assertEquals(1,data.current().size());assertEquals(LifetimeStatsExportTest.carry(),data.lifetime());
      assertEquals(3,f.batches);assertEquals(0,f.singles);assertEquals(writes,f.writes);
      assertEquals(Set.of("dataset","retention_ledger","erasure_ledger","accounts"),new HashSet<>(f.requested.get(0)));
      assertEquals(Set.of("dataset","envelopes","commits"),new HashSet<>(f.requested.get(1)));
      assertEquals(f.requested.get(0),f.requested.get(2));
    }
  }
  @Test void firstControlGateRejectsBeforeAnyRawBatch(){
    for(String mode:List.of("PENDING","MISSING_ACCOUNT","ERASED_ACCOUNT","INVALID_CARRY","WRONG_DATASET")){
      var f=fake();String account=account(f);int writes=f.writes;
      switch(mode){
        case "PENDING" -> {var row=new ArrayList<>(f.tabs.get("retention_ledger").get(1));row.set(2,"PENDING");row.set(3,"");f.tabs.get("retention_ledger").set(1,row);}
        case "MISSING_ACCOUNT" -> f.tabs.get("accounts").remove(1);
        case "ERASED_ACCOUNT" -> f.tabs.get("erasure_ledger").add(List.of("ACCOUNT",ErasureLedger.digest("ACCOUNT",account),"1"));
        case "INVALID_CARRY" -> {var row=new ArrayList<>(f.tabs.get("accounts").get(1));row.set(4,"{}");f.tabs.get("accounts").set(1,row);}
        case "WRONG_DATASET" -> f.tabs.get("dataset").set(1,List.of(DatasetBinding.TEST_PURPOSE,DatasetBindingTest.ID));
      }
      try(var store=new SheetsStatsStore(bound(f),1,LifetimeStatsExportTest.CLOCK)){
        assertThrows(RuntimeException.class,()->store.exportStatistics(account),mode);
        assertEquals(1,f.batches,mode);assertEquals(0,f.singles);assertEquals(writes,f.writes);
      }
    }
  }
  @Test void anyBatchFailureStopsWithoutRetryOrPartialResults(){
    for(int batch=1;batch<=3;batch++){
      var f=fake();int failAt=batch;f.beforeBatch=n->{if(n==failAt)throw new SheetsGateway.Failure(429,0);};
      try(var store=new SheetsStatsStore(bound(f),1,LifetimeStatsExportTest.CLOCK)){
        assertThrows(SheetsGateway.Failure.class,()->store.exportStatistics(account(f)));
        assertEquals(batch,f.batches);assertEquals(0,f.singles);
      }
    }
  }
  @Test void finalControlsRejectAccountErasureRetentionDatasetAndQuarterChanges(){
    for(String mode:List.of("ACCOUNT_CHANGE","ACCOUNT_REMOVED","ERASURE_CHANGE","RETENTION_CHANGE","QUARTER_CROSS","DATASET_CHANGE")){
      var f=fake();String account=account(f);var clock=new SheetsStatsStoreTest.MutableClock(LifetimeStatsExportTest.NOW.toString());
      f.beforeBatch=n->{if(n==3)switch(mode){
        case "ACCOUNT_CHANGE" -> {var row=new ArrayList<>(f.tabs.get("accounts").get(1));row.set(4,"");f.tabs.get("accounts").set(1,row);}
        case "ACCOUNT_REMOVED" -> f.tabs.get("accounts").remove(1);
        case "ERASURE_CHANGE" -> f.tabs.get("erasure_ledger").add(List.of("ACCOUNT",ErasureLedger.digest("ACCOUNT",account),"1"));
        case "RETENTION_CHANGE" -> {var row=new ArrayList<>(f.tabs.get("retention_ledger").get(1));row.set(2,"PENDING");row.set(3,"");f.tabs.get("retention_ledger").set(1,row);}
        case "QUARTER_CROSS" -> clock.now=Instant.parse("2026-12-31T16:00:00Z");
        case "DATASET_CHANGE" -> f.tabs.get("dataset").set(1,List.of(DatasetBinding.MANAGED_PURPOSE,UUID.randomUUID().toString()));
      }};
      try(var store=new SheetsStatsStore(bound(f),1,clock)){
        assertThrows(RuntimeException.class,()->store.exportStatistics(account),mode);
        assertEquals(3,f.batches);assertEquals(0,f.singles);
      }
    }
  }
  @Test void incompleteOrCorruptedCommittedRecordsStillFailBeforeFinalControlRead(){
    for(boolean missing:List.of(false,true)){
      var f=fake();String account=account(f);
      f.beforeBatch=n->{if(n==2){
        if(missing)f.tabs.get("commits").clear();
        else {var row=new ArrayList<>(f.tabs.get("envelopes").get(0));row.set(4,"{}");f.tabs.get("envelopes").set(0,row);}
      }};
      try(var store=new SheetsStatsStore(bound(f),1,LifetimeStatsExportTest.CLOCK)){
        assertThrows(SheetsGateway.Conflict.class,()->store.exportStatistics(account));
        assertEquals(2,f.batches);
      }
    }
  }
  @Test void unrelatedValidProfileUpdateDoesNotInvalidateTheRequestedAccount(){
    var f=fake();String account=account(f),other=UUID.randomUUID().toString();
    f.tabs.get("accounts").add(List.of(other,"2","Other",""));
    f.beforeBatch=n->{if(n==3)f.tabs.get("accounts").set(2,List.of(other,"2","New",""));};
    try(var store=new SheetsStatsStore(bound(f),1,LifetimeStatsExportTest.CLOCK)){
      assertEquals(1,store.exportStatistics(account).current().size());assertEquals(3,f.batches);
    }
  }
}
