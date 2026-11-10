package com.bridge.persistence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DatasetBatchBindingTest {
 static class Batch implements SheetsGateway {
  int batches;List<String> requested;boolean wrong,missing,extra;
  public List<List<String>> read(String tab){throw new AssertionError("No individual GET permitted");}
  public void appendAtomic(Map<String,List<List<Object>>> rows){throw new AssertionError("No write permitted");}
  public Map<String,List<List<String>>> readBatch(List<String> tabs){
   batches++;requested=List.copyOf(tabs);var result=new LinkedHashMap<String,List<List<String>>>();
   for(String tab:tabs)result.put(tab,tab.equals("dataset")?List.of(List.of("purpose","dataset_id"),List.of(wrong?"bridgego-restricted-test":"bridgego-managed",DatasetBindingTest.ID)):List.of());
   if(missing)result.remove("accounts");if(extra)result.put("unexpected",List.of());return result;
  }
 }
 @Test void oneBoundBatchIncludesMarkerWithoutExtraReadsAndStripsOnlyImplicitMarker(){
  var fake=new Batch();var gateway=new DatasetBinding(DatasetBindingTest.SHEET,DatasetBindingTest.ID,"bridgego-managed").gateway(fake);
  var result=gateway.readBatch(List.of("accounts","retention_ledger","erasure_ledger"));
  assertEquals(1,fake.batches);assertEquals(List.of("accounts","retention_ledger","erasure_ledger","dataset"),fake.requested);
  assertEquals(Set.of("accounts","retention_ledger","erasure_ledger"),result.keySet());
  var explicit=gateway.readBatch(List.of("dataset","accounts"));assertEquals(List.of("dataset","accounts"),fake.requested);assertEquals(2,fake.batches);assertTrue(explicit.containsKey("dataset"));
  assertThrows(UnsupportedOperationException.class,()->explicit.get("dataset").get(0).clear());
 }
 @Test void invalidInputsNeverReadAndEveryBatchVerifiesDatasetAndExactKeys(){
  var fake=new Batch();var gateway=new DatasetBinding(DatasetBindingTest.SHEET,DatasetBindingTest.ID,"bridgego-managed").gateway(fake);
  assertThrows(IllegalArgumentException.class,()->gateway.readBatch(List.of("dataset","dataset")));assertEquals(0,fake.batches);
  fake.wrong=true;assertThrows(SheetsGateway.Conflict.class,()->gateway.readBatch(List.of("accounts")));
  fake.wrong=false;fake.missing=true;assertThrows(SheetsGateway.Conflict.class,()->gateway.readBatch(List.of("accounts")));
  fake.missing=false;fake.extra=true;assertThrows(SheetsGateway.Conflict.class,()->gateway.readBatch(List.of("accounts")));assertEquals(3,fake.batches);
 }
}
