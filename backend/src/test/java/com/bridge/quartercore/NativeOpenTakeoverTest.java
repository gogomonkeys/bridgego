package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class NativeOpenTakeoverTest {
  static NativeTakeoverAuthority.NativeSourceEvidence proof(Book before){String token=JournalSessionAuthority.fullToken(before);String old=root(new ArrayList<>(before.metadata.values())).owner();return (b,t,o,n)->{if(!t.equals(token)||!o.equals(old)||n.equals(o))throw new Rejected();};}
  static NativeStoresBackend.Session ordinary(Book owned,String owner,java.time.Clock clock,NativeTakeoverAuthority cap,java.util.concurrent.atomic.AtomicReference<NativeHandleBackendCompositionTest.Sink> sinkRef){
    var b=JournalSessionAuthorityTest.BINDING;var proof=cap.ownershipEvidence();
    var authority=JournalSessionAuthority.provision(b,owned,owner,"native-restart-source","synthetic-reconciled-handoff",(binding,o,source,approval,token)->{
      var actual=cap.ownershipEvidence();if(!actual.equals(proof)||!actual.binding().equals(binding)||!actual.owner().equals(o)||!actual.token().equals(token))throw new Rejected();
    },clock,Duration.ofMinutes(5));
    var sink=new NativeHandleBackendCompositionTest.Sink(owned,authority);sinkRef.set(sink);
    var handle=new JournalGoogleFactory.Handle(sink,()->{},authority,b);var tx=new JournaledNativeTransport(handle.transport(),b.manualBinding(),clock);
    return new NativeStoresBackend.Session(new Service(tx,clock,b.manualBinding(),owner,Limits.defaults()),tx,handle::close);
  }
  @Test void verifiedCasAdoptsFreshServiceWithoutSecondClaimThenWrites()throws Exception {
    var before=NativeTakeoverPlanTest.open();var memory=new JournaledNativeWriterTest.Memory(before);var binding=JournalSessionAuthorityTest.BINDING;var clock=JournaledNativeWriterTest.CLOCK;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);
    var capRef=new java.util.concurrent.atomic.AtomicReference<NativeTakeoverAuthority>();var sinkRef=new java.util.concurrent.atomic.AtomicReference<NativeHandleBackendCompositionTest.Sink>();
    var releases=new AtomicInteger();var takeover=new NativeOpenTakeover(binding,clock);
    try(var backend=takeover.start(memory::snapshot,proof(before),a->{capRef.set(a);return new NativeOpenTakeover.Session(memory,releases::incrementAndGet);},(owned,owner)->ordinary(owned,owner,clock,capRef.get(),sinkRef),gate)){
      assertEquals(1,memory.writes);assertEquals(1,releases.get());assertTrue(backend.lifecycleOpen());assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.STARTING,gate.state());
      gate.openVerified(QuarterlyRetention.currentQuarterStart(clock.instant()));var row=before.sheet("auth_identities").rows.get(1);
      assertEquals(row.get(2),backend.findOrCreateAccount(row.get(0),row.get(1),"Taken over","test@example.com"));assertEquals(1,memory.writes);assertEquals(1,sinkRef.get().writes.get());
    }
  }
  @Test void unknownAndOldWriterWinningNeverProvisionOrdinaryOrReplay()throws Exception {
    for(boolean oldWins:new boolean[]{false,true}){
      var before=NativeTakeoverPlanTest.open();var memory=new JournaledNativeWriterTest.Memory(before);var binding=JournalSessionAuthorityTest.BINDING;var clock=JournaledNativeWriterTest.CLOCK;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var provision=new AtomicInteger();var takeover=new NativeOpenTakeover(binding,clock);
      if(!oldWins)memory.delay=true;
      assertThrows(RuntimeException.class,()->takeover.start(memory::snapshot,proof(before),a->{
        if(oldWins){Root r=root(new ArrayList<>(memory.b.metadata.values()));Root next=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,r.owner(),"OPEN","OLD-WINS",hash("old"));var p=NativeCommitJournal.append(binding.manualBinding(),memory.b,QuarterPlan.phaseWire(r,next,List.of()),clock);p.dispatchOnce(memory::batch);}
        return new NativeOpenTakeover.Session(memory,()->{});
      },(b,o)->{provision.incrementAndGet();throw new AssertionError();},gate));
      assertEquals(0,provision.get());int writes=memory.writes;
      if(!oldWins)memory.b=simulate(memory.b,memory.delayed); // Late success is not adopted.
      assertThrows(RuntimeException.class,()->takeover.start(memory::snapshot,proof(before),a->{throw new AssertionError();},(b,o)->{throw new AssertionError();},gate));
      assertEquals(writes,memory.writes);assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.BLOCKED_MANUAL,gate.state());
    }
  }
}
