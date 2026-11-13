package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
class NativeLifecycleRestartProcessTest {
  @TempDir Path dir;
  void child(Book book,CrossQuarterNativeSettlementTest.Time time,String replay)throws Exception {
    Path snapshot=dir.resolve(UUID.randomUUID()+".json"),output=dir.resolve(UUID.randomUUID()+".out");JournalRestartProbe.save(snapshot,book);
    String wire="-";if(replay!=null){Path p=dir.resolve(UUID.randomUUID()+".wire");Files.writeString(p,replay);wire=p.toString();}
    var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),NativeLifecycleRestartProbe.class.getName(),snapshot.toString(),time.instant().toString(),JournalRestartProbe.fullDigest(book),wire).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("child timeout");}
    String result=Files.readString(output);assertEquals(0,process.exitValue(),result);assertNotEquals(ProcessHandle.current().pid(),process.pid());assertTrue(result.contains("LIFECYCLE_CHILD_OK pid="+process.pid()+" writes=0"),result);
  }
  @Test void drainedClosedAndVerifiedQuarterSnapshotsDoNotAuthorizeFreshProcess()throws Exception {
    var time=new CrossQuarterNativeSettlementTest.Time();var memory=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);
    var service=new Service(tx,time,JournaledNativeWriterTest.BINDING,"restart-life",Limits.defaults());service.start();
    var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
    try(var backend=new NativeStoresBackend(service,tx,time,gate)){
      Book old=backend.lifecycleSnapshot();child(old,time,null);
      Root r=root(new ArrayList<>(old.metadata.values()));Root next=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,r.owner(),"OPEN","LATE-OLD",hash("late"));
      String late=NativeCommitJournal.append(JournaledNativeWriterTest.BINDING,old,QuarterPlan.phaseWire(r,next,List.of()),time).wire();
      time.now=CrossQuarterNativeSettlementTest.NEW_NOW;gate.beginDrain();assertTrue(gate.beginExclusive());Book closed=backend.cleanCloseForQuarter();backend.retireClosedWriter();child(closed,time,late);
      var maintenance=new JournalQuarterProtocolTest.Memory(memory.b);var protocol=new JournalQuarterProtocol(JournaledNativeWriterTest.BINDING,maintenance,time,"restart-clean");protocol.start();assertEquals(JournalQuarterProtocol.Status.VERIFIED,protocol.status());child(maintenance.book,time,late);
      memory.b=maintenance.book;Book reopened=backend.reopenAfterVerifiedQuarter(memory.b,(b,owner)->{var t=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);return new NativeStoresBackend.Session(new Service(t,time,JournaledNativeWriterTest.BINDING,owner,Limits.defaults()),t);});child(reopened,time,late);
    }
  }
  @Test void unknownCloseBeforeAndLateSuccessStayUnownedInNewJvm()throws Exception {
    var time=new CrossQuarterNativeSettlementTest.Time();var memory=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);var service=new Service(tx,time,JournaledNativeWriterTest.BINDING,"restart-unknown",Limits.defaults());service.start();
    var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
    try(var backend=new NativeStoresBackend(service,tx,time,gate)){
      gate.beginDrain();assertTrue(gate.beginExclusive());memory.delay=true;assertThrows(RuntimeException.class,backend::cleanCloseForQuarter);int count=memory.writes;
      child(memory.b,time,null);memory.b=simulate(memory.b,memory.delayed);child(memory.b,time,memory.delayed);
      assertThrows(RuntimeException.class,backend::cleanCloseForQuarter);assertEquals(count,memory.writes);
    }
  }
}
