package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import java.util.*;
class NativeStoresAssemblyTest {
  static MockEnvironment environment(String baseline){return new MockEnvironment().withProperty("BRIDGE_PERSISTENCE_DRIVER","native-journal-v1")
    .withProperty("BRIDGE_SHEETS_ENABLED","true").withProperty("BRIDGE_QUARTER_GATE_ENABLED","true").withProperty("BRIDGE_COLLECTION_MODE","restricted")
    .withProperty("BRIDGE_TEST_SPREADSHEET_ID",RecoveredFixture.ID).withProperty("BRIDGE_TEST_DATASET_ID",RecoveredFixture.DATASET)
    .withProperty("BRIDGE_NATIVE_CUTOVER_BASELINE_SHA256",baseline);}
  @Test void verifiedModelAssemblyUsesOneSourceForProfilesCurrentAndLifetime()throws Exception {
    var memory=JournaledNativeWriterTest.initial();var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,ExtractedCoreParityTest.CLOCK);
    try(var service=JournaledNativeWriterTest.service(transport)){
      service.start();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,ExtractedCoreParityTest.CLOCK);
      gate.openVerified(com.bridge.persistence.QuarterlyRetention.currentQuarterStart(ExtractedCoreParityTest.CLOCK.instant()));
      String baseline=NativeCommitJournal.digest(JournaledNativeWriterTest.BINDING,transport.snapshot());var env=environment(baseline);
      NativePersistenceSelection.Factory factory=(request,g)->{
        if(!request.binding().spreadsheetId().equals(RecoveredFixture.ID)||!request.binding().datasetId().equals(RecoveredFixture.DATASET)||!request.approvedBaselineSha256().equals(baseline))throw new IllegalStateException("Unverified model cutover");
        return new NativeStoresBackend(service,transport,ExtractedCoreParityTest.CLOCK,g);
      };
      try(var selection=new NativePersistenceSelection(env,gate,factory)){
        var config=new PersistenceConfiguration();var old=config.sheetsGateway(env);
        var stats=config.statsStore(env,old,gate,selection,null);var identity=config.identityStore(env,old,gate,selection,null);
        assertFalse(stats instanceof com.bridge.room.IdentityStore);assertFalse(identity instanceof com.bridge.persistence.StatsStore);assertThrows(IllegalStateException.class,()->old.read("accounts"));assertThrows(IllegalStateException.class,()->old.appendAtomic(Map.of()));
        var mapping=memory.b.sheet("auth_identities").rows.get(1);String account=mapping.get(2);
        assertEquals(account,identity.findOrCreateAccount(mapping.get(0),mapping.get(1),"One source","one@example.com"));
        var result=stats.exportStatistics(account);assertNotNull(result.lifetime());assertEquals(1,result.lifetime().formal().matches());
        assertEquals(1,result.current().size());assertEquals("CASUAL",result.current().get(0).statisticsCategory());
        String id=UUID.randomUUID().toString();var source=result.current().get(0);String json=source.canonicalJson().replace(source.matchId(),id);
        var match=new com.bridge.persistence.FinalMatchEnvelope(id+":1",id,1,json,FinalMatchEnvelope.hash(json));
        stats.submit(match);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(stats.status(match.commitId())==com.bridge.persistence.StatsStore.SaveStatus.PENDING&&System.nanoTime()<deadline)Thread.sleep(5);
        assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,stats.status(match.commitId()));assertEquals(2,stats.exportCommitted().size());
        assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,stats.submit(match));
      }
      assertThrows(IllegalStateException.class,()->new NativePersistenceSelection(environment("0".repeat(64)),gate,factory));
      var wrong=environment(baseline).withProperty("BRIDGE_TEST_SPREADSHEET_ID","different_target_123456");
      assertThrows(IllegalStateException.class,()->new NativePersistenceSelection(wrong,gate,factory));
    }
  }
}
