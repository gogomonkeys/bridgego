package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import java.time.*;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class QuarterOperationWiringTest {
    QuarterMaintenanceGate open(){var g=new QuarterMaintenanceGate(true,Clock.systemUTC());g.openVerified(QuarterlyRetention.currentQuarterStart(Instant.now()));return g;}
    @Test void bootBlockedAndUnattachedMutationsRejected(){var g=new QuarterMaintenanceGate(true,Clock.systemUTC());var f=new SheetsStatsStoreTest.Fake();try(var s=new SheetsStatsStore(g.wrap(f),2,Clock.systemUTC(),g)){assertEquals(StatsStore.SaveStatus.REJECTED,s.submit(SheetsStatsStoreTest.envelope()));assertEquals(0,f.writes);assertThrows(QuarterMaintenanceGate.Unavailable.class,()->g.wrap(f).appendAtomic(Map.of()));}}
    @Test void unknownDoesNotRetryAndQueuedOperationCannotWrite()throws Exception {
        var g=open();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var f=new SheetsStatsStoreTest.Fake(){public void appendAtomic(Map<String,List<List<Object>>> rows){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){throw new RuntimeException(ex);}super.appendAtomic(rows);}};f.timeoutAfter=true;
        try(var s=new SheetsStatsStore(g.wrap(f),2,Clock.systemUTC(),g)) {
            var a=SheetsStatsStoreTest.envelope();var b=SheetsStatsStoreTest.envelope();s.submit(a);assertTrue(entered.await(2,TimeUnit.SECONDS));s.submit(b);
            assertEquals(2,g.activeOperations());g.beginDrain();assertFalse(g.beginExclusive());release.countDown();
            assertEquals(StatsStore.SaveStatus.FAILED,SheetsStatsStoreTest.done(s,a.commitId()));assertEquals(StatsStore.SaveStatus.FAILED,SheetsStatsStoreTest.done(s,b.commitId()));
            assertEquals(1,f.writes);assertEquals(QuarterMaintenanceGate.State.BLOCKED,g.state());
        }finally{release.countDown();}
    }
    @Test void statsAndIdentityWholeOperationsAreSerialized()throws Exception {
        var g=open();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var f=new SheetsStatsStoreTest.Fake(){public void appendAtomic(Map<String,List<List<Object>>> rows){if(rows.containsKey("envelopes")){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){throw new RuntimeException(ex);}}super.appendAtomic(rows);}};
        var gw=g.wrap(f);var pool=Executors.newSingleThreadExecutor();
        try(var s=new SheetsStatsStore(gw,2,Clock.systemUTC(),g)) {
            var a=SheetsStatsStoreTest.envelope();s.submit(a);assertTrue(entered.await(2,TimeUnit.SECONDS));
            var identity=new SheetsIdentityStore(gw,Clock.systemUTC(),g);
            var result=pool.submit(()->identity.findOrCreateAccount("https://accounts.google.com","subject"));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(g.activeOperations()<2&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(2,g.activeOperations());assertFalse(result.isDone());g.beginDrain();assertFalse(g.beginExclusive());release.countDown();
            assertEquals(StatsStore.SaveStatus.CONFIRMED,SheetsStatsStoreTest.done(s,a.commitId()));assertNotNull(result.get(2,TimeUnit.SECONDS));assertEquals(2,f.writes);
            assertEquals(0,g.activeOperations());assertTrue(g.beginExclusive());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void cancelledQueueReleasesPermitWithoutPretendingWritten()throws Exception {
        var g=open();var entered=new CountDownLatch(1);
        var f=new SheetsStatsStoreTest.Fake(){public void appendAtomic(Map<String,List<List<Object>>> rows){entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException ex){throw new SheetsGateway.Failure(0,0);}}};
        var s=new SheetsStatsStore(g.wrap(f),2,Clock.systemUTC(),g);var a=SheetsStatsStoreTest.envelope();var b=SheetsStatsStoreTest.envelope();
        s.submit(a);assertTrue(entered.await(2,TimeUnit.SECONDS));s.submit(b);assertEquals(2,g.activeOperations());s.close();
        assertEquals(StatsStore.SaveStatus.REJECTED,s.status(b.commitId()));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(g.activeOperations()>0&&System.nanoTime()<end)Thread.sleep(5);
        assertEquals(0,g.activeOperations());assertEquals(QuarterMaintenanceGate.State.BLOCKED,g.state());
    }
}
