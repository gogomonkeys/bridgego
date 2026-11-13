package com.bridge.persistence;

import com.bridge.room.IdentityStore;
import com.bridge.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring bean resolution, no component scan, scheduling, credential access or network. */
class NativeSpringCutoverContextTest {
    ApplicationContextRunner base() {
        return new ApplicationContextRunner().withInitializer(context->{
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }).withBean(com.bridge.room.CollectionPolicy.class,com.bridge.room.CollectionPolicy::restrictedEmpty)
          .withBean(RoomService.class,RoomService::new).withPropertyValues(
                "BRIDGE_PERSISTENCE_DRIVER=native-journal-v1", "BRIDGE_QUARTER_GATE_ENABLED=true",
                "BRIDGE_SHEETS_ENABLED=true", "BRIDGE_COLLECTION_MODE=restricted",
                "BRIDGE_TEST_SPREADSHEET_ID=isolated-native-test-sheet",
                "BRIDGE_TEST_DATASET_ID=b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b",
                "BRIDGE_NATIVE_CUTOVER_BASELINE_SHA256="+"a".repeat(64));
    }
    @Test void suppliedFactoryProducesUniqueInterfaceBeansSharingOneBackend() {
        var backend=new NativeSourceCutoverSafetyTest.Backend();var opens=new java.util.concurrent.atomic.AtomicInteger();
        base().withBean(NativePersistenceSelection.Factory.class,()->(request,gate)->{opens.incrementAndGet();return backend;})
                .withUserConfiguration(PersistenceConfiguration.class).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(NativePersistenceSelection.Factory.class)
                    .hasSingleBean(IdentityStore.class).hasSingleBean(StatsStore.class);
            var identity=context.getBean(IdentityStore.class);var statistics=context.getBean(StatsStore.class);
            assertEquals("account",identity.findOrCreateAccount("issuer","subject","nickname","email@example.com"));
            assertEquals("nickname:email@example.com",backend.profile);
            assertEquals(StatsStore.SaveStatus.CONFIRMED,statistics.status("commit"));
            backend.uncertain=true;
            assertEquals(StatsStore.SaveStatus.UNKNOWN,statistics.status("commit"));
            assertThrows(IllegalStateException.class,()->identity.findOrCreateAccount("issuer","subject","new","new@example.com"));
            assertThrows(IllegalStateException.class,()->context.getBean(SheetsGateway.class).read("accounts"));
            assertEquals(1,opens.get());
        });
        assertEquals(1,backend.closes);
    }
    @Test void selectedNativeWithoutCapabilityBootsOnlyMaintenance() {
        base().withUserConfiguration(PersistenceConfiguration.class).run(context->{
            assertThat(context).hasNotFailed();
            assertInstanceOf(InertNativeMaintenanceBackend.class,context.getBean(NativePersistenceSelection.class).requireBackend());
            assertFalse(context.getBean(QuarterMaintenanceGate.class).isOpen());
        });
    }
    @Configuration(proxyBeanMethods=false)
    static class ExplicitFactoryConfiguration {
        @Bean NativePersistenceSelection.Factory explicitlyProvisionedFactory() {
            return (request,gate)->new NativeSourceCutoverSafetyTest.Backend();
        }
    }
    @Test void regularConfigurationOrderingCannotCreateTwoFactories() {
        for(boolean suppliedFirst:new boolean[]{true,false}) {
            Class<?>[] order=suppliedFirst?new Class<?>[]{ExplicitFactoryConfiguration.class,PersistenceConfiguration.class}
                    :new Class<?>[]{PersistenceConfiguration.class,ExplicitFactoryConfiguration.class};
            base().withUserConfiguration(order).run(context->{
                assertThat(context).hasNotFailed().hasSingleBean(NativePersistenceSelection.Factory.class)
                        .hasSingleBean(IdentityStore.class).hasSingleBean(StatsStore.class);
            });
        }
    }
    @Test void maintenanceFactoryReceivesSameSelectedBackendExactlyOnce() {
        var backend=new NativeSourceCutoverSafetyTest.Backend();
        var creations=new java.util.concurrent.atomic.AtomicInteger();
        var expected=QuarterMaintenanceCoordinator.DurableControl.unavailable();
        base().withBean(NativePersistenceSelection.Factory.class,()->(request,gate)->backend)
            .withBean(NativeQuarterLifecycleFactory.class,()->(selected,gate)->{
                assertSame(backend,selected);assertTrue(gate.enabled());creations.incrementAndGet();return expected;
            }).withUserConfiguration(PersistenceConfiguration.class).run(context->{
                assertThat(context).hasNotFailed().hasSingleBean(QuarterMaintenanceCoordinator.DurableControl.class);
                assertSame(expected,context.getBean(QuarterMaintenanceCoordinator.DurableControl.class));
                assertEquals(1,creations.get());
                assertThrows(IllegalStateException.class,()->context.getBean(SheetsGateway.class).appendAtomic(java.util.Map.of()));
            });
    }
    @Test void legacySelectionNeverInvokesNativeMaintenanceFactory() {
        base().withPropertyValues("BRIDGE_PERSISTENCE_DRIVER=legacy","BRIDGE_SHEETS_ENABLED=false")
            .withBean(NativeQuarterLifecycleFactory.class,()->(selected,gate)->{throw new AssertionError("must remain inert");})
            .withUserConfiguration(PersistenceConfiguration.class).run(context->{
                assertThat(context).hasNotFailed();
                assertEquals(QuarterMaintenanceCoordinator.Phase.UNKNOWN,
                    context.getBean(QuarterMaintenanceCoordinator.DurableControl.class).inspect(java.time.Instant.now()).phase());
            });
    }
}
