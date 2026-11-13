package com.bridge.quartercore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Separate JVM; only synthetic snapshot and rejected replay wire are passed. No live factory. */
public final class NativeLifecycleRestartProbe {
  public static void main(String[] args)throws Exception {
    if(args.length!=4)throw new IllegalArgumentException();
    Book book=JournalRestartProbe.load(Path.of(args[0]));Clock clock=Clock.fixed(Instant.parse(args[1]),ZoneOffset.UTC);
    var binding=JournaledNativeWriterTest.BINDING;NativeCommitJournal.verify(binding,book,clock);
    if(!JournalRestartProbe.fullDigest(book).equals(args[2]))throw new AssertionError("Snapshot changed");
    if(!args[3].equals("-")){
      boolean rejected=false;try{simulate(book,Files.readString(Path.of(args[3])));}catch(RuntimeException denied){rejected=true;}
      if(!rejected)throw new AssertionError("Late old writer accepted");
    }
    var memory=new JournaledNativeWriterTest.Memory(book);var tx=new JournaledNativeTransport(memory,binding,clock);
    try(var fresh=new Service(tx,clock,binding,"fresh-process",Limits.defaults())){
      // Fresh Service stays STARTING: a serialized CLOSED marker or valid journal is not a source-cutover grant.
      var writer=new NativeQuarterLifecycle.WriterLifecycle(){
        public Book lifecycleSnapshot(){return tx.snapshot();}
        public boolean lifecycleOpen(){return fresh.state()==State.OPEN;}
        public Book cleanCloseForQuarter(){throw new AssertionError("Unowned close");}
        public void retireClosedWriter(){throw new AssertionError("Unowned retire");}
        public Book reopenAfterVerifiedQuarter(Book b){throw new AssertionError("Unowned claim");}
      };
      var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);
      var target=new QuarterNativeControl.Target(binding.spreadsheetId(),binding.datasetId(),binding.purpose());
      var lifecycle=new NativeQuarterLifecycle(target,writer,gate,QuarterNativeControl.OwnershipVerifier.unavailable(),null,clock);
      var coordinator=new com.bridge.persistence.QuarterMaintenanceCoordinator(gate,lifecycle,clock,()->true,()->{throw new AssertionError("Unowned resume");},()->{});
      coordinator.tick();coordinator.tick();
      if(gate.state()!=com.bridge.persistence.QuarterMaintenanceGate.State.BLOCKED_MANUAL||memory.writes!=0||fresh.state()!=State.STARTING)
        throw new AssertionError("Restart granted ownership or dispatched");
      System.out.println("LIFECYCLE_CHILD_OK pid="+ProcessHandle.current().pid()+" writes=0 gate=BLOCKED_MANUAL service=STARTING");
    }
  }
}
