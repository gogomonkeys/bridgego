package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class DurableCleanHandoffAssessmentTest {
  @TempDir Path dir;
  static Book closed() throws Exception {
    var m=JournaledNativeWriterTest.initial();
    var tx=new JournaledNativeTransport(m,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
    try(var s=JournaledNativeWriterTest.service(tx)){s.start();s.cleanClose();assertEquals(State.CLOSED,s.state());}
    return m.b;
  }
  static DurableCleanHandoffAssessment.Result assess(Book b,Clock c){return DurableCleanHandoffAssessment.inspect(JournaledNativeWriterTest.BINDING,b,c);}
  @Test void cleanCurrentAndDueAndSerializationInAnotherJvm() throws Exception {
    Book b=closed();Clock now=JournaledNativeWriterTest.CLOCK;
    assertEquals(DurableCleanHandoffAssessment.State.CLEAN_CURRENT_RESUMABLE,assess(b,now).state());
    Clock next=Clock.fixed(QuarterlyRetention.currentQuarterStart(now.instant()).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1),ZoneOffset.UTC);
    assertEquals(DurableCleanHandoffAssessment.State.CLEAN_DUE_REQUIRES_MAINTENANCE,assess(b,next).state());
    for(Clock c:List.of(now,next)) {
      Path file=dir.resolve(UUID.randomUUID()+".json"),out=dir.resolve(UUID.randomUUID()+".out");JournalRestartProbe.save(file,b);
      var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),DurableCleanHandoffProbe.class.getName(),file.toString(),c.instant().toString(),assess(b,c).state().name(),JournalSessionAuthority.fullToken(b)).redirectErrorStream(true).redirectOutput(out.toFile()).start();
      if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();fail("child timeout");}
      assertEquals(0,p.exitValue(),Files.readString(out));assertNotEquals(ProcessHandle.current().pid(),p.pid());
    }
  }
  @Test void openMissingJournalTamperAndNonCloseAreBlocked()throws Exception {
    var clock=JournaledNativeWriterTest.CLOCK;Book b=closed(),bad=b.copy();bad.metadata.remove(NativeCommitJournal.ID);assertBlocked(bad);
    bad=b.copy();bad.sheet("accounts").rows.get(1).set(2,"tamper");assertBlocked(bad);
    bad=b.copy();bad.sheet("retention_ledger").rows.get(1).set(2,"PENDING");assertBlocked(bad);
    bad=b.copy();Root r=root(new ArrayList<>(bad.metadata.values()));bad.metadata.remove(r.next()-1);assertBlocked(bad);
    assertBlocked(JournaledNativeWriterTest.initial().b); // valid journal bootstrap is not clean-close proof
    var m=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(m,JournaledNativeWriterTest.BINDING,clock);
    try(var s=JournaledNativeWriterTest.service(tx)){s.start();assertBlocked(m.b);}
    assertEquals(DurableCleanHandoffAssessment.State.BLOCKED,DurableCleanHandoffAssessment.inspect(new ManualQuarterMaintenance.Binding("wrong-target",RecoveredFixture.DATASET),b,clock).state());
  }
  @Test void pendingMaintenanceAndWrongCloseHashAreBlockedEvenWithValidJournal()throws Exception {
    var m=JournaledNativeWriterTest.initial();
    var tx=new JournaledNativeTransport(m,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
    try(var service=JournaledNativeWriterTest.service(tx)) { service.start(); }
    Book b=m.b;Root r=root(new ArrayList<>(b.metadata.values()));
    for(String phase:List.of("PENDING","CLOSED")) {
      Root n=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,r.owner(),phase,"CLOSE-"+r.owner(),hash("not-owner"));
      var plan=NativeCommitJournal.append(JournaledNativeWriterTest.BINDING,b,QuarterPlan.phaseWire(r,n,List.of()),JournaledNativeWriterTest.CLOCK);
      assertBlocked(simulate(b,plan.wire()));
    }
  }
  static void assertBlocked(Book b){assertEquals(DurableCleanHandoffAssessment.State.BLOCKED,assess(b,JournaledNativeWriterTest.CLOCK).state());}
}
