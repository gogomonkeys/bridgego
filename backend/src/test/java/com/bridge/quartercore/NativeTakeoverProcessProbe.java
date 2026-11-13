package com.bridge.quartercore;
import java.nio.file.*;
import java.time.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Test-only child JVM: ACK is a synthetic evidence fixture, never production permission. */
public final class NativeTakeoverProcessProbe {
  public static void main(String[] args)throws Exception {
    if(args.length!=4)throw new IllegalArgumentException();
    var book=JournalRestartProbe.load(Path.of(args[0]));var clock=Clock.fixed(Instant.parse(args[1]),ZoneOffset.UTC);
    if(!JournalRestartProbe.fullDigest(book).equals(args[2]))throw new AssertionError();
    var binding=JournalSessionAuthorityTest.BINDING;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);
    var takeover=new NativeClosedTakeover(binding.manualBinding(),clock);var calls=new java.util.concurrent.atomic.AtomicInteger();
    var reference=new java.util.concurrent.atomic.AtomicReference<NativeHandleBackendCompositionTest.Sink>();
    NativeClosedTakeover.RestartEvidence evidence=(b,t,prior,owner)->{
      if(!args[3].equals("ACK")||!b.equals(binding.manualBinding())||!t.equals(JournalSessionAuthority.fullToken(book)))throw new NativeSheetsModel.Rejected();
    };
    NativeStoresBackend.ReopenFactory factory=(baseline,owner)->{
      calls.incrementAndGet();
      var authority=JournalSessionAuthority.provision(binding,baseline,owner,"synthetic-restarted-source","synthetic-clean-handoff",(b,o,s,a,t)->{
        if(!o.equals(owner))throw new NativeSheetsModel.Rejected();evidence.requireCleanHandoff(b.manualBinding(),t,root(new java.util.ArrayList<>(book.metadata.values())).owner(),o);
      },clock,Duration.ofMinutes(5));
      var sink=new NativeHandleBackendCompositionTest.Sink(baseline,authority);reference.set(sink);
      var handle=new JournalGoogleFactory.Handle(sink,()->{},authority,binding);var tx=new JournaledNativeTransport(handle.transport(),binding.manualBinding(),clock);
      return new NativeStoresBackend.Session(new Service(tx,clock,binding.manualBinding(),owner,Limits.defaults()),tx,handle::close);
    };
    if(args[3].equals("NATIVE_ACK")){
      var memory=new JournaledNativeWriterTest.Memory(book);
      var open=new NativeOpenTakeover(binding,clock);var capRef=new java.util.concurrent.atomic.AtomicReference<NativeTakeoverAuthority>();var sinkRef=new java.util.concurrent.atomic.AtomicReference<NativeHandleBackendCompositionTest.Sink>();
      try(var backend=open.start(memory::snapshot,NativeOpenTakeoverTest.proof(book),a->{capRef.set(a);return new NativeOpenTakeover.Session(memory,()->{});},(owned,owner)->NativeOpenTakeoverTest.ordinary(owned,owner,clock,capRef.get(),sinkRef),gate)){
        if(!backend.lifecycleOpen()||memory.writes!=1)throw new AssertionError("Native CAS not adopted");
        gate.openVerified(QuarterlyRetention.currentQuarterStart(clock.instant()));var row=book.sheet("auth_identities").rows.get(1);
        backend.findOrCreateAccount(row.get(0),row.get(1),"Native restart","test@example.com");if(memory.writes!=1||sinkRef.get().writes.get()!=1)throw new AssertionError();
      }
      System.out.println("TAKEOVER_CHILD_OK pid="+ProcessHandle.current().pid()+" mode=NATIVE_ACK");return;
    }
    if(args[3].equals("ACK")){
      try(var backend=takeover.start(()->book.copy(),evidence,factory,gate)){
        if(!backend.lifecycleOpen()||gate.state()!=com.bridge.persistence.QuarterMaintenanceGate.State.STARTING||reference.get().writes.get()!=1)throw new AssertionError("Claim missing");
        gate.openVerified(QuarterlyRetention.currentQuarterStart(clock.instant()));
        var row=book.sheet("auth_identities").rows.get(1);
        if(!row.get(2).equals(backend.findOrCreateAccount(row.get(0),row.get(1),"Fresh JVM","test@example.com")))throw new AssertionError();
        if(reference.get().writes.get()!=2)throw new AssertionError();
      }
    }else{
      boolean denied=false;try{takeover.start(()->book.copy(),evidence,factory,gate);}catch(RuntimeException expected){denied=true;}
      if(!denied||calls.get()!=0||gate.state()!=com.bridge.persistence.QuarterMaintenanceGate.State.BLOCKED_MANUAL)throw new AssertionError("Unproved takeover");
    }
    boolean denied=false;try{takeover.start(()->book.copy(),evidence,factory,gate);}catch(RuntimeException expected){denied=true;}if(!denied)throw new AssertionError("Restart replay");
    System.out.println("TAKEOVER_CHILD_OK pid="+ProcessHandle.current().pid()+" mode="+args[3]);
  }
}
