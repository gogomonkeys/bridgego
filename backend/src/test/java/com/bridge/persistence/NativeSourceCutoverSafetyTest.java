package com.bridge.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Source selection contract, fake capability/backend only. No credential or Google access. */
class NativeSourceCutoverSafetyTest {
    static MockEnvironment selected(){return new MockEnvironment()
            .withProperty("BRIDGE_PERSISTENCE_DRIVER","native-journal-v1")
            .withProperty("BRIDGE_SHEETS_ENABLED","true").withProperty("BRIDGE_COLLECTION_MODE","restricted")
            .withProperty("BRIDGE_TEST_SPREADSHEET_ID","isolated-native-test-sheet")
            .withProperty("BRIDGE_TEST_DATASET_ID","b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b")
            .withProperty("BRIDGE_NATIVE_CUTOVER_BASELINE_SHA256","a".repeat(64));}
    static class Backend implements NativePersistenceBackend {
        boolean uncertain;String profile;int closes,submits;
        public boolean enabled(){return true;}
        public String findOrCreateAccount(String issuer,String subject){throw new AssertionError("profile overload lost");}
        public String findOrCreateAccount(String issuer,String subject,String nickname,String email){
            if(uncertain)throw new IllegalStateException("unknown native operation");profile=nickname+":"+email;return "account";
        }
        public SaveStatus submit(FinalMatchEnvelope envelope){submits++;return uncertain?SaveStatus.UNKNOWN:SaveStatus.CONFIRMED;}
        public SaveStatus status(String id){return uncertain?SaveStatus.UNKNOWN:SaveStatus.CONFIRMED;}
        public List<FinalMatchEnvelope> exportCommitted(){if(uncertain)throw new IllegalStateException("unknown");return List.of();}
        public StatisticsData exportStatistics(String account){if(uncertain)throw new IllegalStateException("unknown");return new StatisticsData(List.of(),LifetimeStatistics.empty());}
        public void close(){closes++;}
    }
    @Test void sameSelectedSourceHandlesBothCategoriesAndProfilesWithoutLegacyFallback() {
        var env=selected();var gate=new QuarterMaintenanceGate(true,Clock.systemUTC());var backend=new Backend();
        var opens=new AtomicInteger();
        try(var selection=new NativePersistenceSelection(env,gate,(request,g)->{opens.incrementAndGet();return backend;})) {
            var config=new PersistenceConfiguration();
            SheetsGateway legacy=new SheetsGateway(){
                public List<List<String>> read(String tab){throw new AssertionError("legacy read");}
                public void appendAtomic(Map<String,List<List<Object>>> rows){throw new AssertionError("legacy write");}
            };
            var stats=config.statsStore(env,legacy,gate,selection,null);
            var identities=config.identityStore(env,legacy,gate,selection,null);
            assertNotSame(stats,identities);
            assertFalse(stats instanceof com.bridge.room.IdentityStore);
            assertFalse(identities instanceof StatsStore);
            assertEquals("account",identities.findOrCreateAccount("https://accounts.google.com","subject","nickname","verified@example.com"));
            assertEquals("nickname:verified@example.com",backend.profile);
            var original=new LocalManualQuarterRehearsal.Fixture().expired;
            for(String category:List.of("FORMAL","CASUAL")) {
                var envelope=LifetimeStatisticsTest.marked(original,category,"SCORED");
                assertEquals(StatsStore.SaveStatus.CONFIRMED,stats.submit(envelope));
            }
            backend.uncertain=true;
            assertEquals(StatsStore.SaveStatus.UNKNOWN,stats.submit(LifetimeStatisticsTest.marked(original,"CASUAL","SCORED")));
            assertEquals(StatsStore.SaveStatus.UNKNOWN,stats.status(original.commitId()));
            assertThrows(IllegalStateException.class,()->stats.exportStatistics("account"));
            assertThrows(IllegalStateException.class,()->identities.findOrCreateAccount("issuer","subject","new","new@example.com"));
            assertEquals(1,opens.get());
        }
        assertEquals(1,backend.closes);
    }
    @Test void nativeSelectionDisablesAllLegacyGatewayMethodsBeforeCredentialLookup() {
        var env=selected(); // Deliberately no credential property.
        var config=new PersistenceConfiguration();
        var legacy=config.sheetsGateway(env,new SheetsWriteGate(false),new QuarterMaintenanceGate(true,Clock.systemUTC()));
        assertThrows(RuntimeException.class,()->legacy.read("accounts"));
        assertThrows(RuntimeException.class,()->legacy.readBatch(List.of("accounts","commits")));
        assertThrows(RuntimeException.class,()->legacy.appendAtomic(Map.of("accounts",List.of(List.of("value")))));
        assertThrows(RuntimeException.class,()->legacy.replaceAtomic(Map.of("accounts",List.of(List.of("value")))));
        assertThrows(RuntimeException.class,()->legacy.saveAccountProfile(2,List.of("account"),List.of("identity")));
    }
    @Test void capabilityFactoryFailureNeverProducesLegacyBackend() {
        var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->new NativePersistenceSelection(selected(),
                new QuarterMaintenanceGate(true,Clock.systemUTC()),(request,gate)->{calls.incrementAndGet();throw new IllegalStateException("unproven");}));
        assertEquals(1,calls.get());
    }
}
