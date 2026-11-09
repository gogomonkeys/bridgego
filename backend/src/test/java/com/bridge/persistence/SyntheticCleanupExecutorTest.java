package com.bridge.persistence;

import com.bridge.persistence.SyntheticCleanupExecutor.*;
import com.bridge.persistence.SyntheticSheetsErasurePreparation.Workbook;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupExecutorTest {
  static final Instant NOW = Instant.parse("2026-11-08T08:00:00Z");
  static String target() { String s = System.getProperty("bridge.synthetic.test.sheet"); Assumptions.assumeTrue(s != null); return s; }
  static class Time extends Clock {
    Instant now = NOW;
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
  }
  enum Fault { NONE, CLAIM_BEFORE, CLAIM_AFTER, REPLACE_BEFORE, REPLACE_AFTER, PARTIAL, READBACK, METADATA, CONTROL_TAMPER }
  static final class Memory implements Gateway {
    final String target; Snapshot state; int claims, replacements, reads; Fault fault = Fault.NONE;
    Runnable afterClaim = () -> {}; Runnable afterReplace = () -> {};
    Memory(FixtureSet fixtures) { target = fixtures.target; state = fixtures.initial(); }
    public synchronized Snapshot readComplete(String target) {
      assertEquals(this.target,target); reads++;
      if (fault == Fault.READBACK && replacements > 0) throw new IllegalStateException("sensitive-must-not-escape");
      return state;
    }
    public synchronized void applyPhaseOnce(String target, Control control, Workbook before, Workbook after) {
      assertEquals(this.target,target); assertEquals(state.data(),before); claims++;
      if (fault == Fault.CLAIM_BEFORE || fault == Fault.REPLACE_BEFORE) throw new IllegalStateException();
      for (Control old:state.controls().values()) if(old.sheetId()==control.sheetId() || old.title().equals(control.title()))throw new IllegalStateException();
      var controls=new TreeMap<>(state.controls());controls.put(Phase.valueOf(control.cells().get(1).get(1)),control);
      replacements++;
      state=new Snapshot(after,controls,true,true,false);
      if(fault==Fault.PARTIAL)state=new Snapshot(before,controls,true,true,false);
      if(fault==Fault.METADATA)state=new Snapshot(after,controls,true,false,true);
      if(fault==Fault.CONTROL_TAMPER)state=new Snapshot(after,Map.of(),true,true,false);
      afterClaim.run();afterReplace.run();
      if(fault==Fault.CLAIM_AFTER || fault==Fault.REPLACE_AFTER)throw new IllegalStateException();
    }

  }
  record Setup(FixtureSet fixtures, Memory memory, Time clock, AtomicBoolean lease, SyntheticCleanupExecutor executor) {}
  Setup setup() {
    var f = SyntheticCleanupExecutor.generate(target(),NOW); var m = new Memory(f); var c = new Time(); var lease = new AtomicBoolean(true);
    return new Setup(f,m,c,lease,new SyntheticCleanupExecutor(f,m,c,lease::get));
  }
  Preview preview(Setup s,Phase p) { return s.executor.preview(p); }
  Result execute(Setup s,Preview p) { return s.executor.execute(p.planId(),p.confirmation()); }
  @Test void allFourPhasesPreserveControlsAndUseRealEnginesWithoutAnyLiveAdapter() {
    var s=setup();
    for (Phase phase:Phase.values()) {
      var e = s.executor;
      var p=e.preview(phase); var previous = s.memory.state.controls();
      assertEquals(Outcome.VERIFIED,e.execute(p.planId(),p.confirmation()).outcome());
      previous.forEach((key,value)->assertEquals(value,s.memory.state.controls().get(key)));
      assertEquals(phase.ordinal()+1,s.memory.state.controls().size());
      assertEquals(Outcome.REJECTED,e.execute(p.planId(),p.confirmation()).outcome());
      assertFalse(e.inspect(p.planId()).cleanupAcceptance());
      var restart=new SyntheticCleanupExecutor(s.fixtures,s.memory,s.clock,s.lease::get);
      assertThrows(IllegalStateException.class,()->restart.preview(phase));
    }
    assertEquals(4,s.memory.claims); assertEquals(4,s.memory.replacements);
    assertEquals(s.fixtures.states.get(4),s.memory.state.data());
  }
  @Test void wrongTargetCannotGenerateAndWrongConfirmationCannotClaim() {
    assertThrows(IllegalArgumentException.class,()->SyntheticCleanupExecutor.generate("OTHER_SHEET_12345",NOW));
    var s=setup();var p=preview(s,Phase.SEED_ERASURE);
    assertEquals(Outcome.REJECTED,s.executor.execute(p.planId(),p.confirmation()+" ").outcome());
    assertEquals(0,s.memory.claims);assertEquals(0,s.memory.replacements);
  }
  @Test void prematurePhaseAndMissingExclusivityRejectBeforeAnyMutation() {
    var s=setup(); assertThrows(IllegalStateException.class,()->preview(s,Phase.ERASE_ACCOUNT));
    s.lease.set(false);assertThrows(IllegalStateException.class,()->preview(s,Phase.SEED_ERASURE));
    assertEquals(0,s.memory.claims);
  }
  @Test void changedOrIncompleteWholeSnapshotRejects() {
    for (int mode=0;mode<3;mode++) {
      var s=setup();var p=preview(s,Phase.SEED_ERASURE);
      s.memory.state=new Snapshot(s.memory.state.data(),Map.of(),mode!=0,mode!=1,mode==2);
      assertEquals(Outcome.REJECTED,execute(s,p).outcome());assertEquals(0,s.memory.claims);
    }
  }
  @Test void expiredPreviewAndQuarterBoundaryStopBeforeClaim() {
    for (Instant time:List.of(NOW.plusSeconds(301),Instant.parse("2026-12-31T16:00:00Z"))) {
      var s=setup();var p=preview(s,Phase.SEED_ERASURE);s.clock.now=time;
      assertEquals(Outcome.REJECTED,execute(s,p).outcome());assertEquals(0,s.memory.claims);
    }
  }
  @Test void noMutationAfterUnknownClaimIncludingLostAcknowledgement() {
    for (Fault fault:List.of(Fault.CLAIM_BEFORE,Fault.CLAIM_AFTER)) {
      var s=setup();var p=preview(s,Phase.SEED_ERASURE);s.memory.fault=fault;
      assertEquals(Outcome.STOPPED_UNCERTAIN,execute(s,p).outcome());
      assertEquals(Outcome.REJECTED,execute(s,p).outcome());
      assertThrows(IllegalStateException.class,()->preview(s,Phase.SEED_ERASURE));
      assertEquals(1,s.memory.claims);assertEquals(fault==Fault.CLAIM_AFTER?1:0,s.memory.replacements);
      if (fault==Fault.CLAIM_AFTER) assertThrows(IllegalStateException.class,()->new SyntheticCleanupExecutor(s.fixtures,s.memory,s.clock,s.lease::get).preview(Phase.SEED_ERASURE));
    }
  }
  @Test void mutationFailuresNeverReplayAndDurableClaimBlocksFreshExecutor() {
    for (Fault fault:List.of(Fault.REPLACE_BEFORE,Fault.REPLACE_AFTER,Fault.PARTIAL,Fault.READBACK,Fault.METADATA,Fault.CONTROL_TAMPER)) {
      var s=setup();var p=preview(s,Phase.SEED_ERASURE);s.memory.fault=fault;
      assertEquals(Outcome.STOPPED_UNCERTAIN,execute(s,p).outcome(),fault.name());
      assertEquals(Outcome.REJECTED,execute(s,p).outcome());assertEquals(fault==Fault.REPLACE_BEFORE?0:1,s.memory.replacements);
      var next=new SyntheticCleanupExecutor(s.fixtures,s.memory,s.clock,s.lease::get);
      if(fault!=Fault.REPLACE_BEFORE)assertThrows(IllegalStateException.class,()->next.preview(Phase.SEED_ERASURE));
    }
  }
  @Test void leaseLossOrExpiryAfterAtomicBatchNeverAssertsCompletion() {
    for (boolean leaseLoss:List.of(true,false)) {
      var s=setup();var p=preview(s,Phase.SEED_ERASURE);
      s.memory.afterClaim=()->{if(leaseLoss)s.lease.set(false);else s.clock.now=NOW.plusSeconds(301);};
      assertEquals(Outcome.STOPPED_UNCERTAIN,execute(s,p).outcome());assertEquals(1,s.memory.replacements);
    }
  }
  @Test void expiryOrLeaseLossDuringDataWriteCannotAssertVerified() {
    var s=setup();var p=preview(s,Phase.SEED_ERASURE);s.memory.afterReplace=()->s.lease.set(false);
    assertEquals(Outcome.STOPPED_UNCERTAIN,execute(s,p).outcome());assertEquals(1,s.memory.replacements);
  }
  @Test void staleConcurrentPlanCannotOverwriteExistingFixedClaim() {
    var s=setup();var other=new SyntheticCleanupExecutor(s.fixtures,s.memory,s.clock,s.lease::get);
    var a=preview(s,Phase.SEED_ERASURE);var b=other.preview(Phase.SEED_ERASURE);
    assertEquals(Outcome.VERIFIED,execute(s,a).outcome());
    assertEquals(Outcome.REJECTED,other.execute(b.planId(),b.confirmation()).outcome());
    assertEquals(1,s.memory.claims);assertEquals(1,s.memory.replacements);
  }
  @Test void forgedPriorControlChainAndRestartedNextPhaseBothReject() {
    var s=setup();var p=preview(s,Phase.SEED_ERASURE);assertEquals(Outcome.VERIFIED,execute(s,p).outcome());
    var original=s.memory.state;var c=original.controls().get(Phase.SEED_ERASURE);var bad=new ArrayList<>(c.cells().get(1));bad.set(4,"0".repeat(64));
    s.memory.state=new Snapshot(original.data(),Map.of(Phase.SEED_ERASURE,new Control(c.sheetId(),c.title(),List.of(c.cells().get(0),bad))),true,true,false);
    assertThrows(IllegalStateException.class,()->preview(s,Phase.ERASE_ACCOUNT));
    s.memory.state=original;
    var restart=new SyntheticCleanupExecutor(s.fixtures,s.memory,s.clock,s.lease::get);
    assertThrows(IllegalStateException.class,()->restart.preview(Phase.ERASE_ACCOUNT));
  }

  @Test void fourTaiwanQuarterBoundariesKeepExistingSuppressedAggregateAndCurrentMatches() {
    for(String time:List.of("2025-12-31T17:00:00Z","2026-03-31T17:00:00Z","2026-06-30T17:00:00Z","2026-09-30T17:00:00Z")) {
      var f=SyntheticCleanupExecutor.generate(target(),Instant.parse(time));
      var before=f.states.get(3);var after=f.states.get(4);
      var aggregateBefore=before.sheets().get("quarterly_aggregates").cells().get(1);
      assertTrue(after.sheets().get("quarterly_aggregates").cells().contains(aggregateBefore));
      assertEquals("SUPPRESSED",aggregateBefore.get(1).value());
      for(String tab:List.of("accounts","auth_identities","erasure_ledger"))assertEquals(before.sheets().get(tab),after.sheets().get(tab));
      long old=before.sheets().get("commits").cells().stream().filter(r->!r.get(0).value().isEmpty()).count();
      long retained=after.sheets().get("commits").cells().stream().filter(r->!r.get(0).value().isEmpty()).count();
      assertEquals(4,old);assertEquals(3,retained);
    }
  }

}
