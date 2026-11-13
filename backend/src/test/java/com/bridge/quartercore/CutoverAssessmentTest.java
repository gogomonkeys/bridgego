package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.CutoverAssessment.*;
class CutoverAssessmentTest {
  static final Clock CLOCK=Clock.systemUTC();
  static final ManualQuarterMaintenance.Binding TARGET=NativeCommitJournalTest.BINDING;
  static FreezeEvidence evidence(){return new FreezeEvidence("test-only-approval","test-only-source",CLOCK.instant().plusSeconds(60));}
  static Plan plan(Book b){return CutoverAssessment.plan(TARGET,b,token(TARGET,b),evidence(),(t,s,e)->{},CLOCK);}
  @Test void legacyMissingNativeRootDoesNotInventMigrationOrModifyData()throws Exception {
    Book b=RecoveredFixture.book();b.metadata.clear();b.sheet("accounts").rows.get(0).set(0,"unsupported-schema");String original=NativeCommitJournal.canonical(b.nativeValue());
    assertEquals(Status.BLOCKED_SCHEMA,inspect(TARGET,b,CLOCK).status());
    assertThrows(RuntimeException.class,()->plan(b));assertEquals(original,NativeCommitJournal.canonical(b.nativeValue()));
  }
  @Test void knownLegacyMetadataOnlyInitializationAndUnknownAreOneAtomicBatch()throws Exception {
    for(boolean apply:new boolean[]{false,true}) {
      Book b=RecoveredFixture.book();b.metadata.clear();
      assertEquals(Status.LEGACY_BOOTSTRAP_READY,inspect(TARGET,b,CLOCK).status());
      Plan p=plan(b);Book[] actual={b};String[] sent={null};
      assertThrows(RuntimeException.class,()->p.dispatchOnce(b,w->{sent[0]=w;if(apply)actual[0]=simulate(actual[0],w);throw new Unknown();}));
      assertEquals(apply?Plan.Readback.EXACT_APPLIED:Plan.Readback.UNCHANGED_UNCERTAIN,p.reconcile(actual[0]));
      assertThrows(RuntimeException.class,()->p.dispatchOnce(actual[0],w->fail()));
      if(!apply)actual[0]=simulate(actual[0],sent[0]);
      assertEquals(b.tables(),actual[0].tables());
      assertEquals(b.sheets.keySet(),actual[0].sheets.keySet());
      assertThrows(RuntimeException.class,()->simulate(actual[0],sent[0]));
    }
  }
  @Test void explicitEvidenceAndExactBaselineRequired()throws Exception {
    Book b=RecoveredFixture.book();assertEquals(Status.NATIVE_BOOTSTRAP_READY,inspect(TARGET,b,CLOCK).status());
    assertThrows(RuntimeException.class,()->CutoverAssessment.plan(TARGET,b,token(TARGET,b),evidence(),FreezeVerifier.unavailable(),CLOCK));
    assertThrows(RuntimeException.class,()->CutoverAssessment.plan(TARGET,b,"0".repeat(64),evidence(),(t,s,e)->{},CLOCK));
    assertThrows(RuntimeException.class,()->CutoverAssessment.plan(TARGET,b,token(TARGET,b),new FreezeEvidence("ref","source",Instant.EPOCH),(t,s,e)->{},CLOCK));
  }
  @Test void atomicBootstrapPreservesEveryDataCellAndIsNotAutomaticClaim()throws Exception {
    Book b=RecoveredFixture.book();Plan p=plan(b);Book[] actual={b};
    p.dispatchOnce(b,w->actual[0]=simulate(actual[0],w));
    assertEquals(b.tables(),actual[0].tables());assertEquals(Plan.Readback.EXACT_APPLIED,p.reconcile(actual[0]));
    assertEquals(Status.ALREADY_JOURNALED,inspect(TARGET,actual[0],CLOCK).status());
    assertThrows(RuntimeException.class,()->plan(actual[0]));assertThrows(RuntimeException.class,()->p.dispatchOnce(actual[0],w->fail()));
  }
  @Test void unknownBeforeOrAfterRemainsReadOnlyAndLateDuplicateRejected()throws Exception {
    for(boolean apply:new boolean[]{false,true}) {
      Book b=RecoveredFixture.book();Plan p=plan(b);Book[] actual={b};String[] sent={null};
      assertThrows(RuntimeException.class,()->p.dispatchOnce(b,w->{sent[0]=w;if(apply)actual[0]=simulate(actual[0],w);throw new Unknown();}));
      assertEquals(apply?Plan.Readback.EXACT_APPLIED:Plan.Readback.UNCHANGED_UNCERTAIN,p.reconcile(actual[0]));
      assertThrows(RuntimeException.class,()->p.dispatchOnce(actual[0],w->fail()));
      if(!apply)actual[0]=simulate(actual[0],sent[0]); // delayed original, not retry
      assertEquals(Plan.Readback.EXACT_APPLIED,p.reconcile(actual[0]));
      assertThrows(RuntimeException.class,()->simulate(actual[0],sent[0]));
    }
  }
  @Test void changedBaselineOrRevokedFreezeNeverDispatches()throws Exception {
    Book b=RecoveredFixture.book();Plan p=plan(b);Book changed=b.copy();changed.sheet("accounts").rows.get(1).set(2,"different");
    assertEquals(Plan.Readback.DIFFERENT_UNCERTAIN,p.reconcile(changed));
    assertThrows(RuntimeException.class,()->p.dispatchOnce(changed,w->fail()));
    AtomicInteger calls=new AtomicInteger();Plan revoked=CutoverAssessment.plan(TARGET,b,token(TARGET,b),evidence(),(t,s,e)->{if(calls.incrementAndGet()>1)throw new Rejected();},CLOCK);
    assertThrows(RuntimeException.class,()->revoked.dispatchOnce(b,w->fail()));
  }
}
