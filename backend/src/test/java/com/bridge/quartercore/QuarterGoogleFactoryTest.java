package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QuarterGoogleFactoryTest {
    @Test void defaultAndNullRejectBeforeCredentials() {
        AtomicInteger reads = new AtomicInteger();
        var config = new QuarterGoogleFactory.ReadConfiguration(null,null,null,null,()-> { reads.incrementAndGet(); throw new AssertionError(); });
        assertThrows(ManagedGoogleServiceAccount.Rejected.class, ()->QuarterGoogleFactory.openReadOnly(config));
        assertThrows(ManagedGoogleServiceAccount.Rejected.class, ()->QuarterGoogleFactory.openReadOnly(null));
        assertEquals(0,reads.get());
        assertEquals("QuarterGoogleReadConfiguration[REDACTED]",config.toString());
    }
    @Test void enabledReadWithInvalidBindingStillCannotReadCredentials() {
        AtomicInteger reads = new AtomicInteger();
        var config = new QuarterGoogleFactory.ReadConfiguration(true,"invalid","invalid","invalid","invalid",()-> { reads.incrementAndGet(); throw new AssertionError(); });
        assertThrows(RuntimeException.class,()->QuarterGoogleFactory.openReadOnly(config));
        assertEquals(0,reads.get());
    }
    @Test void readViewCannotDispatchEvenIfUnderlyingTransportCanWrite() {
        AtomicInteger writes = new AtomicInteger(), closes = new AtomicInteger();
        NarrowSheetsTransport delegate = new NarrowSheetsTransport() {
            public NativeSheetsModel.Book snapshot() { return new NativeSheetsModel.Book(); }
            public List<NativeSheetsModel.Metadata> control() { return List.of(); }
            public List<List<String>> related(String tab) { return List.of(List.of("header")); }
            public void batch(String wire) { writes.incrementAndGet(); }
            public boolean unsupportedStructure() { return false; }
        };
        var handle = new QuarterGoogleFactory.ReadOnly(delegate,closes::incrementAndGet, QuarterNativeControlTest.TARGET);
        var view = handle.transport();
        assertNotNull(view.snapshot());
        assertEquals(List.of(List.of("header")),view.related("accounts"));
        assertThrows(ManagedGoogleServiceAccount.Rejected.class,()->view.batch("anything"));
        assertEquals(0,writes.get());
        handle.close(); handle.close();
        assertEquals(1,closes.get());
        assertThrows(ManagedGoogleServiceAccount.Rejected.class,view::snapshot);
        assertThrows(ManagedGoogleServiceAccount.Rejected.class,handle::transport);
    }
    @Test void unprovisionedReleaseAndMissingPermitCannotAuthorizeLiveWrites() {
        AtomicInteger reads = new AtomicInteger();
        assertThrows(RuntimeException.class,()->MaintenanceRelease.BUNDLED.requireLiveBinding());
        assertThrows(RuntimeException.class,()->QuarterGoogleFactory.openAuthorizedMaintenance(
                MaintenanceRelease.BUNDLED,()-> { reads.incrementAndGet(); throw new AssertionError(); },SharedSheetsBudget.HOST,null));
        assertEquals(0,reads.get());
        assertTrue(java.util.Arrays.stream(QuarterGoogleFactory.class.getMethods()).noneMatch(m->m.getName().equals("openAuthorizedMaintenance")));
    }
    @Test void publicControlUsesFactoryBoundTargetAndNeverGetsWriteSessions() throws Exception {
        var memory = new QuarterNativeControlTest.Memory();
        var fixed = QuarterNativeControlTest.TARGET;
        var handle = new QuarterGoogleFactory.ReadOnly(memory, ()->{}, fixed);
        var seen = new java.util.ArrayList<QuarterNativeControl.Target>();
        var control = handle.control((target, token)->seen.add(target), (target, token)->{
            seen.add(target);
            if (!memory.committed.contains(token)) throw new IllegalStateException();
        }, QuarterNativeControlTest.CLOCK);
        var observation = control.inspect(QuarterNativeControlTest.QUARTER);
        assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Phase.DUE, observation.phase());
        assertFalse(seen.isEmpty());
        assertTrue(seen.stream().allMatch(fixed::equals));
        assertThrows(RuntimeException.class, ()->control.executeOnce(QuarterNativeControlTest.QUARTER,
                observation, java.util.UUID.randomUUID().toString()));
        assertEquals(0, memory.writes);
        assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Phase.UNKNOWN,
                handle.control(QuarterNativeControlTest.CLOCK).inspect(QuarterNativeControlTest.QUARTER).phase());
        assertTrue(java.util.Arrays.stream(QuarterGoogleFactory.ReadOnly.class.getMethods())
                .filter(m->m.getName().equals("control"))
                .noneMatch(m->java.util.Arrays.asList(m.getParameterTypes()).contains(QuarterNativeControl.Target.class)));
        handle.close();
        assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Phase.UNKNOWN,
                control.inspect(QuarterNativeControlTest.QUARTER).phase());
        assertThrows(ManagedGoogleServiceAccount.Rejected.class, ()->handle.control(QuarterNativeControlTest.CLOCK));
    }

    @Test void journalControlVerifiesStoredProofWithoutInMemoryCommitSet() throws Exception {
        var memory=new QuarterNativeControlTest.Memory();
        var fixed=QuarterNativeControlTest.TARGET;
        var handle=new QuarterGoogleFactory.ReadOnly(memory,()->{},fixed);
        var control=handle.controlWithJournal((target,token)->{},QuarterNativeControlTest.CLOCK);
        assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Phase.UNKNOWN,
                control.inspect(QuarterNativeControlTest.QUARTER).phase());
        memory.book=NativeCommitJournal.bootstrap(fixed.binding(),memory.book,
                NativeCommitJournal.digest(fixed.binding(),memory.book),QuarterNativeControlTest.CLOCK).expected();
        memory.committed.clear(); // No callback Set is involved in this path.
        assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Phase.DUE,
                control.inspect(QuarterNativeControlTest.QUARTER).phase());
        memory.book.sheet("accounts").rows.get(1).set(2,"Unjournaled nickname");
        assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Phase.UNKNOWN,
                control.inspect(QuarterNativeControlTest.QUARTER).phase());
        assertEquals(0,memory.writes);
        handle.close();
    }

}
