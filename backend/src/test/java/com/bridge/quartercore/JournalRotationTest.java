package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.util.*;
class JournalRotationTest {
  static final String OWNER="rotation-owner";
  static final java.time.Clock CLOCK=ExtractedCoreParityTest.CLOCK;
  static final ManualQuarterMaintenance.Binding BINDING=NativeCommitJournalTest.BINDING;
  static Book open()throws Exception {
    Book initial=RecoveredFixture.book();Book journal=NativeCommitJournal.bootstrap(BINDING,initial,NativeCommitJournal.digest(BINDING,initial),CLOCK).expected();
    Root old=root(new ArrayList<>(journal.metadata.values()));Root next=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,OWNER,"OPEN","CLAIM-"+OWNER,hash(OWNER));
    return NativeCommitJournal.append(BINDING,journal,QuarterPlan.phaseWire(old,next,List.of()),CLOCK).expected();
  }
  @Test void atomicRotationPreservesTablesLifetimeAndProofChain()throws Exception {
    Book before=open();Root old=root(new ArrayList<>(before.metadata.values()));var prior=NativeCommitJournal.verify(BINDING,before,CLOCK);
    var plan=JournalRotation.plan(BINDING,before,OWNER,CLOCK);Book after=simulate(before,plan.wire());assertTrue(plan.matchesAfter(after));
    Root actual=root(new ArrayList<>(after.metadata.values()));assertEquals(old.epoch()+1,actual.epoch());assertEquals(old.nextSheet(),actual.anchor());assertEquals(3,actual.next());
    assertEquals(before.tables(),after.tables());assertFalse(after.sheets.containsKey(old.anchor()));assertEquals(prior.sequence()+1,NativeCommitJournal.verify(BINDING,after,CLOCK).sequence());
    var proof=parse(after.metadata.get(NativeCommitJournal.ID).value());assertEquals(prior.proofHash(),proof.path("predecessorProofHash").asText());
    assertEquals(1,after.metadata.values().stream().filter(m->m.key().equals(RECEIPT_KEY)).count());
  }
  @Test void lateOldBatchAndDuplicateRotationCannotApplyAfterRotation()throws Exception {
    Book before=open();Root old=root(new ArrayList<>(before.metadata.values()));
    Root ordinary=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,old.owner(),"OPEN","LATE-PROFILE",hash("LATE-PROFILE"));
    String delayed=NativeCommitJournal.append(BINDING,before,QuarterPlan.phaseWire(old,ordinary,List.of(update(before.sheet("accounts").id,1,2,2,3,List.of(List.of("late"))))),CLOCK).wire();
    var plan=JournalRotation.plan(BINDING,before,OWNER,CLOCK);Book rotated=simulate(before,plan.wire());
    assertThrows(RuntimeException.class,()->simulate(rotated,delayed));assertThrows(RuntimeException.class,()->simulate(rotated,plan.wire()));
    Book competitor=simulate(before,delayed);assertThrows(RuntimeException.class,()->simulate(competitor,plan.wire()));
  }
  @Test void unknownDispatchRetainsExactPlanWithoutResend()throws Exception {
    Book before=open();var plan=JournalRotation.plan(BINDING,before,OWNER,CLOCK);var calls=new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(Unknown.class,()->plan.dispatchOnce(w->{calls.incrementAndGet();throw new Unknown();}));
    assertFalse(plan.matchesAfter(before));assertThrows(Rejected.class,()->plan.dispatchOnce(w->calls.incrementAndGet()));
    assertTrue(plan.matchesAfter(simulate(before,plan.wire())));assertEquals(1,calls.get());
  }
  @Test void serializedNativeReadSurvivesNewInstanceButDoesNotAcquireOwnership()throws Exception {
    Book before=open();var plan=JournalRotation.plan(BINDING,before,OWNER,CLOCK);Book after=simulate(before,plan.wire());
    var file=java.nio.file.Files.createTempFile("journal-rotation-native-",".json");
    Book recovered;
    try {JournalRestartProbe.save(file,after);recovered=JournalRestartProbe.load(file);}
    finally {java.nio.file.Files.deleteIfExists(file);}
    assertEquals(NativeCommitJournal.verify(BINDING,after,CLOCK),NativeCommitJournal.verify(BINDING,recovered,CLOCK));
    assertTrue(plan.matchesAfter(recovered));assertThrows(Rejected.class,()->JournalRotation.plan(BINDING,recovered,"wrong-owner",CLOCK));
    var transport=new ExtractedCoreParityTest.Memory(recovered);try(var service=new Service(transport,CLOCK,BINDING,"new-process",Limits.defaults())){service.start();assertNotEquals(State.OPEN,service.state());assertEquals(0,transport.writes);}
  }
  @Test void retiredWriterCannotUseStaleAnchorAfterExternalRotation()throws Exception {
    var memory=JournaledNativeWriterTest.initial();var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,CLOCK);
    try(var service=JournaledNativeWriterTest.service(transport)){
      service.start();assertEquals(State.OPEN,service.state());var writer=new NativeWriterAdapter(service);
      Book before=transport.snapshot();Root old=root(new ArrayList<>(before.metadata.values()));
      writer.retireForRotation();var plan=JournalRotation.plan(BINDING,before,old.owner(),CLOCK);
      plan.dispatchOnce(memory::batch);assertTrue(plan.matchesAfter(memory.b));int count=memory.writes;
      var identity=memory.b.sheet("auth_identities").rows.get(1);
      assertThrows(IllegalStateException.class,()->writer.identity("STALE-WRITER",identity.get(0),identity.get(1),"No","no@example.com"));
      assertEquals(count,memory.writes);assertNotEquals(State.OPEN,service.state());
    }
  }
  @Test void verifiedCheckpointAdoptsNewAnchorBeforeProfileAndMatch()throws Exception {
    var memory=JournaledNativeWriterTest.initial();var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,CLOCK);
    try(var service=JournaledNativeWriterTest.service(transport)){
      service.start();var writer=new NativeWriterAdapter(service);int oldAnchor=service.known.anchor();int count=memory.writes;
      writer.checkpoint();assertEquals(count+1,memory.writes);assertEquals(State.OPEN,service.state());assertNotEquals(oldAnchor,service.known.anchor());
      assertEquals(service.known.anchor(),service.ids.get("retention_ledger"));
      var identity=memory.b.sheet("auth_identities").rows.get(1);String lifetime=memory.b.sheet("accounts").rows.get(1).get(4);
      assertEquals(NativeWriterAdapter.Outcome.VERIFIED,writer.identity("AFTER-ROTATE-PROFILE",identity.get(0),identity.get(1),"After rotation","after@example.com").outcome());
      assertEquals(lifetime,memory.b.sheet("accounts").rows.get(1).get(4));
      var old=memory.b.sheet("commits").rows.get(1);String id=UUID.randomUUID().toString();
      String json=memory.b.sheet("envelopes").rows.get(1).get(4).replace(old.get(3),id);
      var envelope=new com.bridge.persistence.FinalMatchEnvelope(id+":1",id,1,json,FinalMatchEnvelope.hash(json));
      assertEquals(NativeWriterAdapter.Outcome.VERIFIED,writer.match("AFTER-ROTATE-MATCH",envelope).outcome());
      var last=parse(memory.wires.get(memory.wires.size()-1));
      assertEquals(service.known.anchor(),last.path("requests").path(0).path("updateCells").path("range").path("sheetId").asInt());
      NativeCommitJournal.verify(BINDING,memory.b,CLOCK);
    }
  }
  @Test void checkpointUnknownRetiresWriterWhetherBeforeOrAfterApply()throws Exception {
    for(boolean applied:new boolean[]{false,true}){
      var memory=JournaledNativeWriterTest.initial();var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,CLOCK);
      try(var service=JournaledNativeWriterTest.service(transport)){
        service.start();var writer=new NativeWriterAdapter(service);int count=memory.writes;
        if(applied)memory.timeoutAfter=true;else memory.delay=true;
        assertThrows(RuntimeException.class,writer::checkpoint);assertNotEquals(State.OPEN,service.state());assertEquals(count+1,memory.writes);
        assertThrows(IllegalStateException.class,writer::checkpoint);assertEquals(count+1,memory.writes);
        if(!applied)memory.b=simulate(memory.b,memory.delayed);
        NativeCommitJournal.verify(BINDING,memory.b,CLOCK);
        assertThrows(IllegalStateException.class,writer::checkpoint);assertEquals(count+1,memory.writes);
      }
    }
  }
}
