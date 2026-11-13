package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
class NativeTakeoverProcessTest {
  @TempDir Path dir;
  void run(Book book,String mode)throws Exception {
    Path snapshot=dir.resolve(UUID.randomUUID()+".json"),out=dir.resolve(UUID.randomUUID()+".out");JournalRestartProbe.save(snapshot,book);
    var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),NativeTakeoverProcessProbe.class.getName(),snapshot.toString(),JournaledNativeWriterTest.CLOCK.instant().toString(),JournalRestartProbe.fullDigest(book),mode).redirectErrorStream(true).redirectOutput(out.toFile()).start();
    if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();fail("Child timeout");}var text=Files.readString(out);assertEquals(0,p.exitValue(),text);assertNotEquals(ProcessHandle.current().pid(),p.pid());assertTrue(text.contains("TAKEOVER_CHILD_OK pid="+p.pid()),text);
  }
  @Test void cleanAcknowledgedCloseReopensWithFreshOwnerInNewJvm()throws Exception {
    var memory=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
    try(var s=JournaledNativeWriterTest.service(tx)){s.start();s.cleanClose();assertEquals(State.CLOSED,s.state());run(memory.b,"ACK");}
  }
  @Test void nativeOnlyReconciledOpenCasSucceedsInDifferentJvm()throws Exception {run(NativeTakeoverPlanTest.open(),"NATIVE_ACK");}
  @Test void openAndUnacknowledgedLateClosedNeverAcquireNewSession()throws Exception {
    var memory=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);
    try(var s=JournaledNativeWriterTest.service(tx)){
      s.start();run(memory.b,"UNPROVEN");memory.delay=true;s.cleanClose();assertEquals(State.UNKNOWN,s.state());run(memory.b,"UNKNOWN");
      memory.b=simulate(memory.b,memory.delayed);assertEquals("CLOSED",root(new ArrayList<>(memory.b.metadata.values())).state());run(memory.b,"UNKNOWN");
    }
  }
}
