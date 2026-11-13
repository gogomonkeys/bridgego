package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.time.*;
import java.util.*;

class NativeCommitJournalTest {
  static final Clock CLOCK=Clock.systemUTC();
  static final ManualQuarterMaintenance.Binding BINDING=new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,RecoveredFixture.DATASET);
  static Book initialize()throws Exception {
    Book before=RecoveredFixture.book();return NativeCommitJournal.bootstrap(BINDING,before,NativeCommitJournal.digest(BINDING,before),CLOCK).expected();
  }
  static String operation(Book before,String op,List<Object> mutations) {
    Root old=root(new ArrayList<>(before.metadata.values()));
    Root next=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,old.owner(),"CLOSED",op,hash(op));
    return QuarterPlan.phaseWire(old,next,mutations);
  }
  static JournalPlan profile(Book before,String op,String nickname) {
    int id=before.sheet("accounts").id;
    return NativeCommitJournal.append(BINDING,before,operation(before,op,List.of(update(id,1,2,2,3,List.of(List.of(nickname))))),CLOCK);
  }
  @Test void explicitBootstrapAndRestartVerificationUseStoredProof()throws Exception {
    Book before=RecoveredFixture.book();assertThrows(RuntimeException.class,()->NativeCommitJournal.verify(BINDING,before,CLOCK));
    assertThrows(RuntimeException.class,()->NativeCommitJournal.bootstrap(BINDING,before,"0".repeat(64),CLOCK));
    JournalPlan bootstrap=NativeCommitJournal.bootstrap(BINDING,before,NativeCommitJournal.digest(BINDING,before),CLOCK);
    Book committed=simulate(before,bootstrap.wire());assertTrue(bootstrap.matchesAfter(committed));
    assertEquals(1,NativeCommitJournal.verify(BINDING,committed.copy(),CLOCK).sequence());
    assertEquals(before.tables(),committed.tables());
    assertThrows(RuntimeException.class,()->NativeCommitJournal.bootstrap(BINDING,committed,NativeCommitJournal.digest(BINDING,committed),CLOCK));
  }
  @Test void dataRootReceiptAndJournalAreOneAtomicFinalWire()throws Exception {
    Book before=initialize();JournalPlan plan=profile(before,"PROFILE-1","New name");Book committed=simulate(before,plan.wire());
    assertTrue(plan.matchesAfter(committed));assertEquals("New name",committed.sheet("accounts").rows.get(1).get(2));
    var evidence=NativeCommitJournal.verify(BINDING,committed,CLOCK);assertEquals(2,evidence.sequence());assertEquals("PROFILE-1",evidence.operation());
    var proof=BoundedSheetsProjection.MAPPER.readValue(committed.metadata.get(NativeCommitJournal.ID).value(),NativeCommitJournal.Proof.class);
    assertEquals(NativeCommitJournal.verify(BINDING,before,CLOCK).proofHash(),proof.predecessorProofHash());
    assertEquals(NativeCommitJournal.digest(BINDING,before),proof.beforeDigest());
    assertEquals(NativeCommitJournal.digest(BINDING,committed),proof.afterDigest());
    assertEquals(1,committed.metadata.values().stream().filter(m->m.key().equals(NativeCommitJournal.KEY)).count());
  }
  @Test void competingLateAndDuplicateRequestsCannotApplyTwice()throws Exception {
    Book before=initialize();JournalPlan a=profile(before,"PROFILE-A","A"),b=profile(before,"PROFILE-B","B");
    Book committed=simulate(before,a.wire());String stable=NativeCommitJournal.canonical(committed.nativeValue());
    assertThrows(RuntimeException.class,()->simulate(committed,b.wire()));
    assertThrows(RuntimeException.class,()->simulate(committed,a.wire()));
    assertEquals(stable,NativeCommitJournal.canonical(committed.nativeValue()));
    assertEquals("A",committed.sheet("accounts").rows.get(1).get(2));
    assertThrows(RuntimeException.class,()->profile(committed,"PROFILE-A","Again"));
  }
  @Test void unknownBeforeDispatchOrAfterApplyIsReadOnlyReconciledNeverReplayed()throws Exception {
    for(boolean apply:new boolean[]{false,true}) {
      Book before=initialize();Book[] actual={before};JournalPlan plan=profile(before,"UNKNOWN","U");
      assertThrows(RuntimeException.class,()->plan.dispatchOnce(wire->{if(apply)actual[0]=simulate(actual[0],wire);throw new Unknown();}));
      assertEquals(apply,plan.matchesAfter(actual[0]));
      assertThrows(RuntimeException.class,()->plan.dispatchOnce(wire->{throw new AssertionError("No second dispatch");}));
      // A new process can verify the stored exact current snapshot, but an unchanged
      // predecessor does NOT prove an old request cannot still arrive. Ownership is separate.
      assertEquals(apply?2:1,NativeCommitJournal.verify(BINDING,actual[0].copy(),CLOCK).sequence());
    }
  }
  @Test void alteredDataCarryReceiptAndProofOrWrongTargetReject()throws Exception {
    Book clean=profile(initialize(),"GOOD","Good").expected();
    Book data=clean.copy();data.sheet("accounts").rows.get(1).set(2,"Tampered");
    assertThrows(RuntimeException.class,()->NativeCommitJournal.verify(BINDING,data,CLOCK));
    Book receipt=clean.copy();var old=receipt.metadata.get(2);receipt.metadata.put(2,new Metadata(2,old.key(),old.value().replace("JOURNAL-BOOTSTRAP","WRONG-BOOTSTRAP"),null));
    assertThrows(RuntimeException.class,()->NativeCommitJournal.verify(BINDING,receipt,CLOCK));
    Book proof=clean.copy();var marker=proof.metadata.get(NativeCommitJournal.ID);proof.metadata.put(marker.id(),new Metadata(marker.id(),marker.key(),marker.value().replace("\"afterDigest\":\"","\"afterDigest\":\"0"),null));
    assertThrows(RuntimeException.class,()->NativeCommitJournal.verify(BINDING,proof,CLOCK));
    var wrong=new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,UUID.randomUUID().toString());
    assertThrows(RuntimeException.class,()->NativeCommitJournal.verify(wrong,clean,CLOCK));
    Book removed=clean.copy();removed.metadata.remove(NativeCommitJournal.ID);
    assertThrows(RuntimeException.class,()->NativeCommitJournal.verify(BINDING,removed,CLOCK));
  }
  @Test void journalCannotBeModifiedInsideBusinessWireOrDigestExcludedByLookalike()throws Exception {
    Book before=initialize();String malicious=operation(before,"BAD",List.of(deleteMetadata(NativeCommitJournal.ID)));
    assertThrows(RuntimeException.class,()->NativeCommitJournal.append(BINDING,before,malicious,CLOCK));
    Root control=root(new ArrayList<>(before.metadata.values()));
    var honest=parse(operation(before,"FUTURE-RECEIPT",List.of())).path("requests");
    var requests=new ArrayList<Object>();requests.add(deleteMetadata(control.next()));
    honest.forEach(r->requests.add(JSON.convertValue(r,Object.class)));
    // Deleting a currently absent future slot is a no-op in a naive end-state simulation,
    // but would destroy the winning competitor receipt when this request arrives late.
    assertThrows(RuntimeException.class,()->NativeCommitJournal.append(BINDING,before,wire(requests),CLOCK));
    Book duplicate=before.copy();duplicate.metadata.put(20002,new Metadata(20002,NativeCommitJournal.KEY,"{}",null));
    assertThrows(RuntimeException.class,()->NativeCommitJournal.digest(BINDING,duplicate));
    Book collision=before.copy();collision.metadata.put(NativeCommitJournal.ID,new Metadata(NativeCommitJournal.ID,"other","{}",null));
    assertThrows(RuntimeException.class,()->NativeCommitJournal.digest(BINDING,collision));
  }
  @Test void persistedVerifierReadsFreshAndDoesNotDependOnMemoryTokenSet()throws Exception {
    Book[] actual={initialize()};var target=new QuarterNativeControl.Target(RecoveredFixture.ID,RecoveredFixture.DATASET,"bridgego-restricted-test");
    var verifier=NativeCommitJournal.verifier(target,()->actual[0].copy(),CLOCK);
    String token=QuarterPlan.bookDigest(actual[0]);assertDoesNotThrow(()->verifier.verify(target,token));
    actual[0]=profile(actual[0],"PROFILE-NEW","New").expected();
    assertThrows(RuntimeException.class,()->verifier.verify(target,token));
    assertDoesNotThrow(()->verifier.verify(target,QuarterPlan.bookDigest(actual[0])));
    actual[0].sheet("accounts").rows.get(1).set(2,"Unjournaled");
    assertThrows(RuntimeException.class,()->verifier.verify(target,QuarterPlan.bookDigest(actual[0])));
  }
  @Test void missingExtraAndNoncanonicalProofFieldsAndCapacityFailClosed()throws Exception {
    Book clean=initialize();var marker=clean.metadata.get(NativeCommitJournal.ID);
    for(int mode=0;mode<3;mode++) {
      var value=(com.fasterxml.jackson.databind.node.ObjectNode)parse(marker.value());
      if(mode==0)value.remove("beforeDigest");if(mode==1)value.put("extra",true);
      String encoded=mode==2?" "+marker.value():value.toString();Book bad=clean.copy();
      bad.metadata.put(marker.id(),new Metadata(marker.id(),marker.key(),encoded,null));
      assertThrows(RuntimeException.class,()->NativeCommitJournal.verify(BINDING,bad,CLOCK));
    }
    Book large=RecoveredFixture.book();large.metadata.put(30001,new Metadata(30001,"foreign.padding","x".repeat(29500),null));
    assertThrows(RuntimeException.class,()->NativeCommitJournal.bootstrap(BINDING,large,NativeCommitJournal.digest(BINDING,large),CLOCK));
  }
  @Test void latestProofSizeIsBoundedAndEachOperationUpdatesSingleRecord()throws Exception {
    Book current=initialize();for(int i=0;i<12;i++)current=profile(current,"P-"+i,"Name "+i).expected();
    assertEquals(13,NativeCommitJournal.verify(BINDING,current,CLOCK).sequence());
    assertEquals(1,current.metadata.values().stream().filter(m->m.key().equals(NativeCommitJournal.KEY)).count());
    assertTrue(current.metadata.get(NativeCommitJournal.ID).characters()<2048);
  }
}
