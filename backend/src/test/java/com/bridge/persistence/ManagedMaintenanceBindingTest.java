package com.bridge.persistence;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedMaintenanceBindingTest {
  @Test void explicitManagedPurposeRunsActualQuarterCoreWithoutAlteringOldRestrictedDefaults(){
    var f=new LocalManualQuarterRehearsal.Fixture();var restricted=f.binding;
    var binding=new ManualQuarterMaintenance.Binding(restricted.spreadsheetId(),restricted.datasetId(),"bridgego-managed");
    f.tables.put("dataset",List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",binding.datasetId())));
    var preserved=new TreeMap<String,List<List<String>>>();for(String tab:ManualQuarterMaintenance.PRESERVED)preserved.put(tab,List.copyOf(f.tables.get(tab)));
    var writes=new int[1];
    var storage=new ManualQuarterMaintenance.Storage(){
      public ManualQuarterMaintenance.Snapshot readComplete(ManualQuarterMaintenance.Binding b){assertEquals(binding,b);return new ManualQuarterMaintenance.Snapshot(b.spreadsheetId(),true,f.tables);}
      public void replaceAtomic(ManualQuarterMaintenance.Binding b,String digest,Map<String,List<List<String>>> tables){assertEquals(binding,b);writes[0]++;tables.forEach((k,v)->f.tables.put(k,new ArrayList<>(v)));}
    };
    assertEquals("bridgego-restricted-test",restricted.purpose());
    assertThrows(IllegalStateException.class,()->ManualQuarterMaintenance.validate(storage.readComplete(binding),restricted,f.clock));
    var service=new ManualQuarterMaintenance(binding,storage,f.clock,()->true,true);var p=service.preview(binding.stoppedConfirmation());
    assertEquals(1,p.expiredCommits());assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,service.execute(p.planId(),p.confirmationText()).outcome());assertEquals(2,writes[0]);
    for(String tab:ManualQuarterMaintenance.PRESERVED)assertEquals(preserved.get(tab),f.tables.get(tab));
    assertEquals(f.retained,ManualQuarterMaintenance.decoded(f.tables));
    assertThrows(RuntimeException.class,()->new ManualQuarterMaintenance.Binding(binding.spreadsheetId(),binding.datasetId(),"untrusted"));
  }
  @Test void managedErasureUsesSameBoundMarkerAndExistingOwnerVerificationAndExecutionGuards() throws Exception {
    var f=new OperatorAccountErasureTest.Fixture();String id=UUID.randomUUID().toString();
    f.db.tabs.put("dataset",List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",id)));
    var bound=new DatasetBinding("managed_fixture_not_real_12345",id,"bridgego-managed").gateway(f.db);
    var service=new OperatorAccountErasure(bound,f.verifier,"expected-audience",Clock.systemUTC(),()->true,true);
    var plan=f.preview(service);assertEquals(0,f.db.replacements);
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED,f.execute(service,plan,"owner"));assertEquals(1,f.db.replacements);
    assertEquals(List.of(List.of("purpose","dataset_id"),List.of("bridgego-managed",id)),f.db.tabs.get("dataset"));
    assertTrue(f.db.tabs.get("accounts").stream().noneMatch(r->r.contains(f.account)));assertTrue(f.db.tabs.get("accounts").stream().anyMatch(r->r.contains(f.other)));
  }
}
