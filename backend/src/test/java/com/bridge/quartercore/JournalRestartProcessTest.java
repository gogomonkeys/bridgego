package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Actual distinct JVM with only a persisted synthetic snapshot, not a new object in this JVM. */
class JournalRestartProcessTest {
    @TempDir Path dir;
    static final Clock CLOCK=JournaledNativeWriterTest.CLOCK;
    void freshProcess(Book book,String replay) throws Exception { freshProcess(book,replay,"-"); }
    void freshProcess(Book book,String replay,String recovery) throws Exception {
        Path snapshot=dir.resolve(UUID.randomUUID()+".json");JournalRestartProbe.save(snapshot,book);
        String wirePath="-";
        if(replay!=null){Path file=dir.resolve(UUID.randomUUID()+".wire");Files.writeString(file,replay);wirePath=file.toString();}
        var root=root(new ArrayList<>(book.metadata.values()));
        var evidence=NativeCommitJournal.verify(JournaledNativeWriterTest.BINDING,book,CLOCK);
        Path output=dir.resolve(UUID.randomUUID()+".out");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                JournalRestartProbe.class.getName(),snapshot.toString(),CLOCK.instant().toString(),root.state(),
                Long.toString(evidence.sequence()),wirePath,JournalRestartProbe.fullDigest(book),recovery)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("fresh JVM timeout");}
        String result=Files.readString(output);
        assertEquals(0,process.exitValue(),result);
        assertTrue(result.contains("CHILD_OK pid="+process.pid()),result);
        assertNotEquals(ProcessHandle.current().pid(),process.pid());
        assertTrue(result.contains("ownership=UNKNOWN"),result);
    }
    @Test void persistedBootstrapAndOpenBusinessStateVerifyInFreshJvmsWithoutAuthority() throws Exception {
        var memory=JournaledNativeWriterTest.initial();
        freshProcess(memory.b,memory.wires.get(memory.wires.size()-1));
        var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,CLOCK);
        try(var service=JournaledNativeWriterTest.service(transport)){
            service.start();assertEquals(State.OPEN,service.state());
            freshProcess(memory.b,memory.wires.get(memory.wires.size()-1));
        }
    }
    @Test void unknownBeforeAndLateApplyRemainUnownedAcrossRealRestart() throws Exception {
        var memory=JournaledNativeWriterTest.initial();
        var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,CLOCK);
        try(var service=JournaledNativeWriterTest.service(transport)){
            service.start();var identity=memory.b.sheet("auth_identities").rows.get(1);memory.delay=true;
            assertEquals(NativeWriterAdapter.Outcome.UNKNOWN,new NativeWriterAdapter(service)
                    .identity("RESTART-UNKNOWN",identity.get(0),identity.get(1),"Restart test","test@example.com").outcome());
            int writes=memory.writes;
            freshProcess(memory.b,null);
            memory.b=simulate(memory.b,memory.delayed);
            freshProcess(memory.b,memory.delayed);
            assertEquals(writes,memory.writes); // No replay by either fresh process.
        }
    }
    @Test void rotationBeforeUnknownAndAppliedSnapshotsSurviveFreshJvmsButNeverGainOwnership() throws Exception {
        Book before=JournalRotationTest.open();
        var plan=JournalRotation.plan(JournalRotationTest.BINDING,before,JournalRotationTest.OWNER,CLOCK);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(Unknown.class,()->plan.dispatchOnce(w->{calls.incrementAndGet();throw new Unknown();}));
        freshProcess(before,null);
        Book late=simulate(before,plan.wire());
        freshProcess(late,plan.wire());
        assertThrows(Rejected.class,()->plan.dispatchOnce(w->calls.incrementAndGet()));
        assertEquals(1,calls.get());
    }

    static class QuarterMemory extends NativeWriterAdapterTest.WireMemory {
        int failAt; boolean applyBeforeFailure;
        final List<Book> snapshots=new ArrayList<>();
        QuarterMemory(Book book){super(book);}
        @Override public void batch(String wire) {
            wires.add(wire); writes++;
            if(writes==failAt&&!applyBeforeFailure)throw new Unknown();
            b=simulate(b,wire);snapshots.add(b.copy());
            if(writes==failAt)throw new Unknown();
        }
    }
    static Book quarterBaseline() throws Exception {
        Book book=RecoveredFixture.book();
        book.sheet("retention_ledger").rows.add(new ArrayList<>(List.of("2026-Q3","2026-06-30T16:00:00Z",
                "COMPLETE","2026-06-30T16:00:01Z","1")));
        return NativeCommitJournal.bootstrap(JournaledNativeWriterTest.BINDING,book,
                NativeCommitJournal.digest(JournaledNativeWriterTest.BINDING,book),CLOCK).expected();
    }
    @Test void allFiveQuarterSnapshotsAreStrictDecodedAndClassifiedInSeparateJvms() throws Exception {
        var memory=new QuarterMemory(quarterBaseline());freshProcess(memory.b,null,"READY_DUE");
        var protocol=new JournalQuarterProtocol(JournaledNativeWriterTest.BINDING,memory,CLOCK,"restart-quarter");
        protocol.start();assertEquals(JournalQuarterProtocol.Status.VERIFIED,protocol.status());
        assertEquals(5,memory.writes);
        for(int i=0;i<5;i++)freshProcess(memory.snapshots.get(i),memory.wires.get(i),i==4?"CLEAN_CURRENT":"REVIEW");
        assertThrows(Rejected.class,protocol::start);assertEquals(5,memory.writes);
    }
    @Test void everyUnknownQuarterDispatchRemainsStoppedAcrossFreshProcessReadback() throws Exception {
        for(int phase=1;phase<=5;phase++)for(boolean applied:new boolean[]{false,true}) {
            var memory=new QuarterMemory(quarterBaseline());memory.failAt=phase;memory.applyBeforeFailure=applied;
            var protocol=new JournalQuarterProtocol(JournaledNativeWriterTest.BINDING,memory,CLOCK,"unknown-quarter");
            protocol.start();assertEquals(JournalQuarterProtocol.Status.REVIEW,protocol.status());assertEquals(phase,memory.writes);
            String classification=!applied&&phase==1?"READY_DUE":applied&&phase==5?"CLEAN_CURRENT":"REVIEW";
            // CLEAN_CURRENT after an unacknowledged CLOSE still does not establish ownership.
            freshProcess(memory.b,applied?memory.wires.get(memory.wires.size()-1):null,classification);
            assertThrows(Rejected.class,protocol::start);assertEquals(phase,memory.writes);
        }
    }

}
