package com.bridge.persistence;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class DatasetBindingTest {
  static final String SHEET="managed_fixture_not_real_12345",ID="123e4567-e89b-12d3-a456-426614174000";
  static class Fake implements SheetsGateway {
    List<List<String>> marker=List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",ID));
    int reads,writes;
    public List<List<String>> read(String tab){if(tab.equals("dataset"))return marker;reads++;return List.of();}
    public void appendAtomic(Map<String,List<List<Object>>> rows){writes++;}
    public void replaceAtomic(Map<String,List<List<Object>>> rows){writes++;}
  }
  static MockEnvironment managed(){return new MockEnvironment().withProperty("BRIDGE_COLLECTION_MODE","managed")
      .withProperty("BRIDGE_SHEETS_SPREADSHEET_ID",SHEET).withProperty("BRIDGE_DATASET_ID",ID);}
  @Test void managedRequiresExplicitTargetAndDatasetBeforeConnectorAccess(){
    var opened=new AtomicInteger();
    for(var env:List.of(new MockEnvironment().withProperty("BRIDGE_COLLECTION_MODE","managed"),
        managed().withProperty("BRIDGE_SHEETS_SPREADSHEET_ID",""),managed().withProperty("BRIDGE_DATASET_ID","not-a-uuid"),
        managed().withProperty("BRIDGE_TEST_SPREADSHEET_ID",SHEET))){
      assertThrows(RuntimeException.class,()->DatasetBinding.open(env,sheet->{opened.incrementAndGet();return new Fake();}));
    }
    assertEquals(0,opened.get());
    var gateway=DatasetBinding.open(managed(),sheet->{assertEquals(SHEET,sheet);opened.incrementAndGet();return new Fake();});
    assertEquals(1,opened.get());assertEquals(List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",ID)),gateway.read("dataset"));
  }
  @Test void restrictedSelectionRemainsDistinctAndDoesNotAcceptManagedMarker(){
    var env=new MockEnvironment().withProperty("BRIDGE_TEST_SPREADSHEET_ID","restricted_fixture_12345").withProperty("BRIDGE_TEST_DATASET_ID",ID);
    var binding=DatasetBinding.from(env);assertEquals("bridgego-restricted-test",binding.purpose());
    var fake=new Fake();assertThrows(SheetsGateway.Conflict.class,()->binding.gateway(fake).read("accounts"));assertEquals(0,fake.reads);
    env.setProperty("BRIDGE_SHEETS_SPREADSHEET_ID","restricted_fixture_12345");assertThrows(IllegalArgumentException.class,()->DatasetBinding.from(env));
    assertThrows(IllegalArgumentException.class,()->DatasetBinding.from(managed().withProperty("BRIDGE_COLLECTION_MODE","anything")));
  }
  @Test void everyManagedReadAndMutationChecksExactPurposeAndIdAndCannotRewriteMarker(){
    var fake=new Fake();var gateway=DatasetBinding.from(managed()).gateway(fake);
    gateway.read("accounts");gateway.appendAtomic(Map.of());gateway.replaceAtomic(Map.of());assertEquals(1,fake.reads);assertEquals(2,fake.writes);
    assertThrows(IllegalArgumentException.class,()->gateway.appendAtomic(Map.of("dataset",List.of())));
    assertThrows(IllegalArgumentException.class,()->gateway.replaceAtomic(Map.of("dataset",List.of())));
    for(var bad:List.of(List.<List<String>>of(),List.of(List.of("purpose","dataset_id"),List.of("bridgego-restricted-test",ID)),
        List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",UUID.randomUUID().toString())),
        List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",ID),List.of("extra","row")))){
      fake.marker=bad;assertThrows(SheetsGateway.Conflict.class,()->gateway.read("accounts"));
      assertThrows(SheetsGateway.Conflict.class,()->gateway.appendAtomic(Map.of()));assertThrows(SheetsGateway.Conflict.class,()->gateway.replaceAtomic(Map.of()));
    }
    assertEquals(1,fake.reads);assertEquals(2,fake.writes);
  }
  @Test void gameDisabledDoesNotLoadDestinationOrCredentials(){
    var env=new MockEnvironment().withProperty("BRIDGE_COLLECTION_MODE","managed");var gateway=new PersistenceConfiguration().sheetsGateway(env);
    assertThrows(IllegalStateException.class,()->gateway.read("accounts"));
    env.setProperty("BRIDGE_SHEETS_ENABLED","true");assertThrows(IllegalStateException.class,()->new PersistenceConfiguration().sheetsGateway(env));
  }
}
