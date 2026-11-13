package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import static com.bridge.persistence.QuarterMaintenanceCoordinator.*;
import java.time.*;
import java.util.*;

/** Native model + original permit/Service protocol tests, not real Google transport acceptance. */
class QuarterNativeControlTest {
  static final Clock CLOCK=Clock.systemUTC();
  static final Instant QUARTER=QuarterlyRetention.currentQuarterStart(CLOCK.instant());
  static final QuarterNativeControl.Target TARGET=new QuarterNativeControl.Target(RecoveredFixture.ID,RecoveredFixture.DATASET,"bridgego-restricted-test");
  static final QuarterNativeControl.OwnershipVerifier MODEL_EXCLUSIVITY=(t,s)->{}; // Test harness only, not a production lease.
  static class Memory implements NarrowSheetsTransport {
    final Set<String> committed=new HashSet<>();Book book;WritePermit permit;String path;int writes,failAt;boolean afterApply;
    Memory()throws Exception{book=RecoveredFixture.book();
      book.sheet("retention_ledger").rows.add(new ArrayList<>(List.of("2026-Q3","2026-06-30T16:00:00Z","COMPLETE","2026-06-30T16:00:01Z","1")));
      committed.add(QuarterPlan.bookDigest(book));}
    public Book snapshot(){return book.copy();}public List<Metadata> control(){return new ArrayList<>(book.metadata.values());}
    public List<List<String>> related(String t){return copyRows(book.sheet(t).rows);}
    public boolean unsupportedStructure(){return false;}
    public void batch(String wire){
      var call=permit.beforeDispatch(path,wire);writes++;
      if(writes==failAt&&!afterApply){call.unknown();throw new Unknown();}
      book=simulate(book,wire);
      if(writes==failAt){call.unknown();throw new Unknown();}
      call.acknowledged();
      if(root(control()).state().equals("CLOSED"))committed.add(QuarterPlan.bookDigest(book));
    }
    QuarterNativeControl.Session arm(QuarterNativeControl.Target target,Book baseline,String op,Clock clock){
      try {
        var constructor=BootArm.class.getDeclaredConstructor();constructor.setAccessible(true);var boot=constructor.newInstance();
        var now=Instant.now();String owner="q-"+op;
        var plan=BootArm.Plan.quarter(target.binding(),op,BootArm.Stage.A,baseline,owner,clock,Limits.defaults(),now,now.plusSeconds(360),Duration.ofSeconds(60),Duration.ofSeconds(300),16,null);
        var arm=plan.expectedArm(boot.nonce());book.metadata.put(arm.id(),arm);
        permit=boot.consume(plan,book).orElseThrow();path=plan.mutationPath();
        return new QuarterNativeControl.Session(this,permit,owner,()->{if(permit.status()!=WritePermit.Status.FINISHED)permit.closeUnknown();});
      }catch(Exception fail){throw new IllegalStateException(fail);}
    }
    QuarterNativeControl adapter(boolean writable,QuarterNativeControl.OwnershipVerifier ownership){
      return new QuarterNativeControl(TARGET,()->book.copy(),ownership,(t,s)->{if(!committed.contains(s))throw new Rejected();},writable?this::arm:null,CLOCK);
    }
  }
  @Test void noOwnershipOrWriteCapabilityFailsClosed()throws Exception {
    var m=new Memory();var denied=m.adapter(true,QuarterNativeControl.OwnershipVerifier.unavailable());
    assertEquals(Phase.UNKNOWN,denied.inspect(QUARTER).phase());assertEquals(0,m.writes);
    var ro=m.adapter(false,MODEL_EXCLUSIVITY);var report=ro.inspect(QUARTER);assertEquals(Phase.DUE,report.phase());
    assertThrows(RuntimeException.class,()->ro.executeOnce(QUARTER,report,UUID.randomUUID().toString()));assertEquals(0,m.writes);
  }
  @Test void fiveRealProtocolBatchesAndFreshCompletionIdentity()throws Exception {
    var m=new Memory();var before=m.book.tables();var adapter=m.adapter(true,MODEL_EXCLUSIVITY);
    var preview=adapter.inspect(QUARTER);assertEquals(Phase.DUE,preview.phase());String op=UUID.randomUUID().toString();
    adapter.executeOnce(QUARTER,preview,op);
    assertEquals(5,m.writes);var after=adapter.inspect(QUARTER);assertEquals(Phase.COMPLETE,after.phase());assertEquals(op,after.completedOperation());
    assertEquals(before.get("auth_identities"),m.book.tables().get("auth_identities"));
    assertEquals(2,m.book.sheet("commits").rows.size());
    assertThrows(RuntimeException.class,()->adapter.executeOnce(QUARTER,preview,op));assertEquals(5,m.writes);
    var restart=m.adapter(true,MODEL_EXCLUSIVITY);assertEquals(Phase.COMPLETE,restart.inspect(QUARTER).phase());
    assertEquals("",restart.inspect(QUARTER).completedOperation());assertEquals(5,m.writes);
  }
  @Test void unknownEveryPhaseNeverReplaysAndRestartDoesNotAdoptOpenState()throws Exception {
    for(int at=1;at<=5;at++)for(boolean applied:new boolean[]{false,true}) {
      var m=new Memory();m.failAt=at;m.afterApply=applied;var adapter=m.adapter(true,MODEL_EXCLUSIVITY);
      var observed=adapter.inspect(QUARTER);String op=UUID.randomUUID().toString();
      assertThrows(RuntimeException.class,()->adapter.executeOnce(QUARTER,observed,op));assertEquals(at,m.writes);
      assertThrows(RuntimeException.class,()->adapter.executeOnce(QUARTER,observed,op));assertEquals(at,m.writes);
      // Restart must obtain a new *external* ownership proof that accounts for late unknown
      // requests. With no such proof it refuses even an apparently unchanged CLOSED root.
      var restart=m.adapter(true,QuarterNativeControl.OwnershipVerifier.unavailable());
      assertEquals(Phase.UNKNOWN,restart.inspect(QUARTER).phase());assertEquals(at,m.writes);
    }
  }
  @Test void changedSnapshotsAndHistoricalMarkersPreventWrites()throws Exception {
    var m=new Memory();var adapter=m.adapter(true,MODEL_EXCLUSIVITY);var observed=adapter.inspect(QUARTER);
    m.book.sheet("accounts").rows.get(1).set(2,"Changed nickname");
    assertThrows(RuntimeException.class,()->adapter.executeOnce(QUARTER,observed,UUID.randomUUID().toString()));assertEquals(0,m.writes);
    // A later validated read is not forever pinned to the old digest. Still no write lease is inferred.
    assertEquals(Phase.UNKNOWN,adapter.inspect(QUARTER).phase());
    // Model an independently persisted legitimate profile commit proof (test-only ledger).
    m.committed.add(QuarterPlan.bookDigest(m.book));
    assertEquals(Phase.DUE,adapter.inspect(QUARTER).phase());assertNotEquals(observed.token(),adapter.inspect(QUARTER).token());
  }
  @Test void closedLedgerAndReceiptAreInsufficientWhenCommittedSnapshotChanges()throws Exception {
    var m=new Memory();var adapter=m.adapter(true,MODEL_EXCLUSIVITY);var preview=adapter.inspect(QUARTER);
    adapter.executeOnce(QUARTER,preview,UUID.randomUUID().toString());Book clean=m.book.copy();
    // Ordinary-looking profile mutation still needs proof from the sole committed writer.
    m.book.sheet("accounts").rows.get(1).set(2,"Uncommitted profile");
    assertEquals(Phase.UNKNOWN,m.adapter(false,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());
    m.book=clean.copy();var carry=(com.fasterxml.jackson.databind.node.ObjectNode)parse(m.book.sheet("accounts").rows.get(1).get(4));
    ((com.fasterxml.jackson.databind.node.ObjectNode)carry.path("formal")).put("totalScore",8);
    m.book.sheet("accounts").rows.get(1).set(4,carry.toString());
    assertEquals(Phase.UNKNOWN,m.adapter(false,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());
    m.book=clean.copy();var raw=m.book.sheet("envelopes").rows.get(1);
    var envelope=(com.fasterxml.jackson.databind.node.ObjectNode)parse(raw.get(4));
    ((com.fasterxml.jackson.databind.node.ObjectNode)envelope.path("match")).put("app_version","uncommitted-version");
    String changed=envelope.toString();raw.set(4,changed);raw.set(1,FinalMatchEnvelope.hash(changed));
    m.book.sheet("commits").rows.get(1).set(1,FinalMatchEnvelope.hash(changed));
    assertEquals(Phase.UNKNOWN,m.adapter(false,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());
    m.book=clean.copy();var receipt=m.book.metadata.get(3);var body=(com.fasterxml.jackson.databind.node.ObjectNode)parse(receipt.value());body.put("extra",true);
    m.book.metadata.put(3,new Metadata(3,receipt.key(),body.toString(),null));
    // Even a purported proof cannot bypass structural receipt validation.
    m.committed.add(QuarterPlan.bookDigest(m.book));
    assertEquals(Phase.UNKNOWN,m.adapter(false,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());
    m.book=clean.copy();assertEquals(Phase.COMPLETE,m.adapter(false,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());
    m.book.sheet("accounts").rows.get(1).set(2,"Legitimate profile commit");
    assertEquals(Phase.UNKNOWN,m.adapter(false,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());
    // The test harness now acts as the trusted sole writer's independent commit ledger.
    // Production has no implementation of this evidence path yet.
    m.committed.add(QuarterPlan.bookDigest(m.book));
    assertEquals(Phase.COMPLETE,m.adapter(false,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());
  }
  @Test void missingPriorLedgerNeverBecomesImplicitBootstrapPermission()throws Exception {
    var m=new Memory();m.book.sheet("retention_ledger").rows.remove(1);m.committed.add(QuarterPlan.bookDigest(m.book));
    assertEquals(Phase.UNKNOWN,m.adapter(true,MODEL_EXCLUSIVITY).inspect(QUARTER).phase());assertEquals(0,m.writes);
  }
  @Test void malformedNativeResponseAndWrongConfiguredDatasetAreUnknown()throws Exception {
    var raw=new QuarterNativeControl(TARGET,t->"{}".getBytes(),MODEL_EXCLUSIVITY,CLOCK);
    assertEquals(Phase.UNKNOWN,raw.inspect(QUARTER).phase());
    var m=new Memory();var other=new QuarterNativeControl.Target(TARGET.spreadsheetId(),UUID.randomUUID().toString(),TARGET.purpose());
    var mismatch=new QuarterNativeControl(other,()->m.book.copy(),MODEL_EXCLUSIVITY,null,CLOCK);
    assertEquals(Phase.UNKNOWN,mismatch.inspect(QUARTER).phase());
  }
}
