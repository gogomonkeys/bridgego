package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SheetsWriteGateTest {
    static class Fake implements SheetsGateway {
        AtomicInteger writes=new AtomicInteger();
        public List<List<String>> read(String tab){return List.of(List.of("header"));}
        public void appendAtomic(Map<String,List<List<Object>>> rows){writes.incrementAndGet();}
        public void replaceAtomic(Map<String,List<List<Object>>> rows){writes.incrementAndGet();}
        public void saveAccountProfile(int row,List<String> account,List<String> identity){writes.incrementAndGet();}
        public Optional<String> snapshotFingerprint(){return Optional.of("snapshot");}
    }
    @Test void disabledIsExactDelegateAndDefaultConfiguration() {
        var delegate=new Fake();var gate=new SheetsWriteGate(false);
        assertSame(delegate,gate.wrap(delegate));delegate.appendAtomic(Map.of());assertEquals(1,delegate.writes.get());
        assertEquals(SheetsWriteGate.State.DISABLED,new PersistenceConfiguration().sheetsWriteGate(new MockEnvironment()).state());
        assertEquals(SheetsWriteGate.State.STARTUP_BLOCKED,new PersistenceConfiguration().sheetsWriteGate(new MockEnvironment().withProperty("BRIDGE_LOCAL_WRITE_GATE_ENABLED","true")).state());
    }
    @Test void enabledBlocksEveryMutationButAllowsReads() {
        var gate=new SheetsWriteGate(true);var delegate=new Fake();var gw=gate.wrap(delegate);
        assertThrows(IllegalStateException.class,()->gw.appendAtomic(Map.of()));
        assertThrows(IllegalStateException.class,()->gw.replaceAtomic(Map.of()));
        assertThrows(IllegalStateException.class,()->gw.saveAccountProfile(0,List.of(),List.of()));
        assertEquals(0,delegate.writes.get());assertEquals(delegate.read("a"),gw.read("a"));
        assertEquals(delegate.readBatch(List.of("a")),gw.readBatch(List.of("a")));
        assertEquals(delegate.snapshotFingerprint(),gw.snapshotFingerprint());
    }
    @Test void failedVerificationAndUnknownNeverReopen() throws Exception {
        var gate=new SheetsWriteGate(true);
        assertThrows(IllegalStateException.class,()->gate.verifyAndOpenAtStartup(()->{throw new IllegalStateException();}));
        assertEquals(SheetsWriteGate.State.UNKNOWN,gate.state());
        assertThrows(IllegalStateException.class,()->gate.verifyAndOpenAtStartup(()->{}));
        assertFalse(gate.pauseAndDrain(Duration.ZERO));
    }
    @Test void failureLatchesAndPreventsStoreStyleRetry() {
        var gate=new SheetsWriteGate(true);var delegate=new Fake(){public void appendAtomic(Map<String,List<List<Object>>> rows){writes.incrementAndGet();throw new SheetsGateway.Failure(0,0);}};
        var gw=gate.wrap(delegate);gate.verifyAndOpenAtStartup(()->{});
        assertThrows(SheetsGateway.Failure.class,()->gw.appendAtomic(Map.of()));
        assertThrows(IllegalStateException.class,()->gw.appendAtomic(Map.of()));
        assertEquals(1,delegate.writes.get());assertEquals(SheetsWriteGate.State.UNKNOWN,gate.state());
    }
    @Test void drainingRejectsNewWritesWaitsAndCannotReopen() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var delegate=new Fake(){public void appendAtomic(Map<String,List<List<Object>>> rows){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new RuntimeException(e);}super.appendAtomic(rows);}};
        var gate=new SheetsWriteGate(true);var gw=gate.wrap(delegate);gate.verifyAndOpenAtStartup(()->{});
        var pool=Executors.newSingleThreadExecutor();
        try {
            var writing=pool.submit(()->gw.appendAtomic(Map.of()));assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertFalse(gate.pauseAndDrain(Duration.ZERO));assertEquals(1,gate.activeMutations());
            assertThrows(IllegalStateException.class,()->gw.saveAccountProfile(0,List.of(),List.of()));
            release.countDown();assertTrue(gate.pauseAndDrain(Duration.ofSeconds(3)));writing.get(3,TimeUnit.SECONDS);
            assertEquals(SheetsWriteGate.State.DRAINED,gate.state());assertEquals(1,delegate.writes.get());
            assertThrows(IllegalStateException.class,()->gate.verifyAndOpenAtStartup(()->{}));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void allMutationsAdmittedOnlyAfterExplicitVerification() {
        var gate=new SheetsWriteGate(true);var delegate=new Fake();var gw=gate.wrap(delegate);
        gate.verifyAndOpenAtStartup(()->{});gw.appendAtomic(Map.of());gw.replaceAtomic(Map.of());gw.saveAccountProfile(0,List.of(),List.of());
        assertEquals(3,delegate.writes.get());gate.markUnknown();assertThrows(IllegalStateException.class,()->gw.replaceAtomic(Map.of()));
    }
}
