package com.bridge.persistence;
import org.junit.jupiter.api.Test;import org.springframework.mock.env.MockEnvironment;import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
class InertNativeSelectionTest {
 MockEnvironment env(){return new MockEnvironment().withProperty("BRIDGE_PERSISTENCE_DRIVER",NativePersistenceSelection.NATIVE).withProperty("BRIDGE_SHEETS_ENABLED","true").withProperty("BRIDGE_TEST_SPREADSHEET_ID","synthetic-inert-sheet").withProperty("BRIDGE_TEST_DATASET_ID","b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b");}
 QuarterMaintenanceGate gate(){return new QuarterMaintenanceGate(true,Clock.systemUTC());}
 @Test void missingOrMalformedBaselineNeverCallsFactory(){for(String baseline:new String[]{"","bad"}){
  var g=gate();try(var selected=new NativePersistenceSelection(env().withProperty("BRIDGE_NATIVE_CUTOVER_BASELINE_SHA256",baseline),g,(r,x)->{throw new AssertionError("credential/factory access");})){
   assertInstanceOf(InertNativeMaintenanceBackend.class,selected.requireBackend());assertFalse(g.isOpen());assertFalse(selected.identities().enabled());
   assertEquals(StatsStore.SaveStatus.REJECTED,selected.statistics().submit(null));assertThrows(IllegalStateException.class,()->selected.identities().findOrCreateAccount("issuer","subject"));
  }
 }}
 @Test void configuredFactoryErrorIsNotHidden(){assertThrows(IllegalStateException.class,()->new NativePersistenceSelection(env().withProperty("BRIDGE_NATIVE_CUTOVER_BASELINE_SHA256","a".repeat(64)),gate(),(r,g)->{throw new IllegalStateException("uncertain");}));}
 @Test void trustedStageRequiresSameGateAndTargetWithoutAcquiringWriteAuthority(){
  var e=env();var g=gate();var stage=new StagedNativeBackend(g);var target=DatasetBinding.from(e);
  var holder=new PreinstalledNativeRuntime(g,stage,target);
  try(var selected=NativePersistenceSelection.preinstalled(e,holder.gate(),holder.staged(),holder.binding())){
   assertSame(stage,selected.requireBackend());assertTrue(selected.inertRoutingSafe());assertFalse(selected.identities().enabled());
   assertThrows(IllegalStateException.class,()->NativePersistenceSelection.preinstalled(e,gate(),stage,target));
   assertThrows(IllegalStateException.class,()->NativePersistenceSelection.preinstalled(e,g,stage,new DatasetBinding("different-target-sheet",target.datasetId(),target.purpose())));
  }
  assertThrows(IllegalStateException.class,()->new PreinstalledNativeRuntime(g,stage,target));
 }
}
