package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** No Google calls, keys, filesystem permissions or external writes. */
class SyntheticCleanupManualQuarterTrialTest {
  static class Wire extends LiveQuarterStorageTest.FakeTransport {
    @Override public byte[] readWorkbook() {
      byte[] ignored = super.readWorkbook();
      last.put("spreadsheetId", SyntheticCleanupExecutorTest.target());
      last.put("spreadsheetUrl", "https://docs.google.com/spreadsheets/d/" + SyntheticCleanupExecutorTest.target() + "/edit");
      return LiveQuarterWorkbookTest.bytes(last);
    }
    SyntheticCleanupManualQuarterTrial.Fixture input() {
      return new SyntheticCleanupManualQuarterTrial.Fixture(new ManualQuarterMaintenance.Binding(
          SyntheticCleanupExecutorTest.target(), fixture.binding.datasetId()), fixture.tables);
    }
  }
  static SyntheticCleanupManualQuarterTrial session(Wire w) {
    return new SyntheticCleanupManualQuarterTrial(w.input(), w, w.fixture.clock, () -> true);
  }
  @Test void directRealCoreAndAdapterRemoveExactlyExpiredMatchWithShortConfirmation() {
    var wire = new Wire();
    var session = session(wire);
    var view = session.preview();
    assertEquals(1, view.removeMatches()); assertEquals(2, view.keepMatches()); assertEquals(0, wire.posts);
    assertEquals("REJECTED_NO_WRITE", session.confirm(view.planId(), false));
    assertEquals("REJECTED_NO_WRITE", session.confirm("wrong", true));
    assertEquals("SYNTHETIC_QUARTER_VERIFIED", session.confirm(view.planId(), true));
    assertEquals(2, wire.posts); wire.fixture.verify();
    assertThrows(IllegalStateException.class, () -> session.confirm(view.planId(), true));
    assertEquals(2, wire.posts);
  }
  @Test void ambiguousGoogleWriteClosesSessionWithoutAutomaticReplay() {
    var w = new Wire(); w.unavailableAfterWrite = true;
    var s = session(w); var v = s.preview();
    assertEquals("STOPPED_RESULT_REQUIRES_REVIEW", s.confirm(v.planId(), true));
    assertEquals(1, w.posts); assertThrows(IllegalStateException.class, s::preview);
    assertEquals(1, w.posts);
  }
  @Test void changedFixtureCannotBeConfirmed() {
    var w = new Wire(); var s = session(w); var v = s.preview();
    w.fixture.tables.get("accounts").add(List.of(UUID.randomUUID().toString(), "1"));
    assertEquals("STOPPED_RESULT_REQUIRES_REVIEW", s.confirm(v.planId(), true)); assertEquals(0, w.posts);
  }
  @Test void fixtureGeneratorReusesThreeMatchesAndOnlyBoundTarget() {
    var f = SyntheticCleanupManualQuarterTrial.fixture(SyntheticCleanupExecutorTest.target(),Instant.parse("2026-11-09T00:00:00Z"));
    assertEquals(SyntheticCleanupExecutorTest.target(),f.binding().spreadsheetId());
    assertEquals(3,ManualQuarterMaintenance.decoded(f.tables()).size());
    assertEquals(8,f.tables().size()); assertEquals(f.binding().datasetId(),f.tables().get("dataset").get(1).get(1));
  }
  @Test void expirationDoesNotSendWrite() {
    var w = new Wire(); var now = new AtomicReference<>(w.fixture.clock.instant());
    Clock clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return now.get(); }
    };
    var s = new SyntheticCleanupManualQuarterTrial(w.input(),w,clock,()->true);
    var v = s.preview(); now.set(v.expiresAt());
    assertEquals("PREVIEW_EXPIRED_NO_WRITE",s.confirm(v.planId(),true));
    assertEquals("REJECTED_NO_WRITE",s.confirm(v.planId(),true));
    var fresh=s.preview();
    assertNotEquals(v.planId(),fresh.planId());
    assertEquals("REJECTED_NO_WRITE",s.confirm(v.planId(),true));
    assertEquals(0,w.posts);
  }
  @Test void refreshedPreviewInvalidatesOldButtonWithoutExtraWrite() {
    var w=new Wire();var s=session(w);var old=s.preview();var fresh=s.preview();
    assertNotEquals(old.planId(),fresh.planId());
    assertEquals("REJECTED_NO_WRITE",s.confirm(old.planId(),true));assertEquals(0,w.posts);
    assertEquals("SYNTHETIC_QUARTER_VERIFIED",s.confirm(fresh.planId(),true));
  }
  @Test void driftDuringEnginePreviewRejectsBeforeShowingConfirmablePlan() {
    var w = new Wire(); var s = session(w);
    w.beforeRead = n -> { if (n == 2) w.fixture.tables.get("accounts").add(List.of(UUID.randomUUID().toString(),"1")); };
    assertThrows(IllegalStateException.class,s::preview);assertEquals(0,w.posts);
    assertThrows(IllegalStateException.class,s::preview);
  }
  @Test void losingWriterFreezePermanentlyClosesSessionEvenIfRestored() {
    var w = new Wire(); var frozen = new AtomicBoolean(true);
    var s=new SyntheticCleanupManualQuarterTrial(w.input(),w,w.fixture.clock,frozen::get);
    var v=s.preview();frozen.set(false);
    assertThrows(IllegalStateException.class,()->s.confirm(v.planId(),true));
    frozen.set(true);assertThrows(IllegalStateException.class,()->s.confirm(v.planId(),true));
    assertEquals(0,w.posts);assertTrue(w.closed);
  }
  @Test void secondPhaseUncertaintyStopsWithoutReplay() {
    var w=new Wire();w.afterWrite=n->{if(n==2)w.unavailableAfterWrite=true;};
    var s=session(w);var v=s.preview();
    assertEquals("STOPPED_RESULT_REQUIRES_REVIEW",s.confirm(v.planId(),true));
    assertEquals(2,w.posts);assertThrows(IllegalStateException.class,s::preview);assertEquals(2,w.posts);
  }
  @Test void throwingFreezeCheckClosesSession() {
    var w=new Wire();var fail=new AtomicBoolean(false);
    var s=new SyntheticCleanupManualQuarterTrial(w.input(),w,w.fixture.clock,()->{
      if(fail.get())throw new IllegalStateException();return true;
    });
    var v=s.preview();fail.set(true);assertThrows(IllegalStateException.class,()->s.confirm(v.planId(),true));
    fail.set(false);assertThrows(IllegalStateException.class,s::preview);assertEquals(0,w.posts);
  }
  @Test void alternateTargetIsRejectedBeforeTransportRead() {
    var w=new Wire();var f=new SyntheticCleanupManualQuarterTrial.Fixture(
        new ManualQuarterMaintenance.Binding("UNAPPROVED_TARGET",w.fixture.binding.datasetId()),w.fixture.tables);
    assertThrows(IllegalArgumentException.class,()->new SyntheticCleanupManualQuarterTrial(f,w,w.fixture.clock,()->true));
    assertEquals(0,w.reads);assertEquals(0,w.posts);
  }
  @Test void fixedFixtureCanBePreparedSeparatelyAndMatchesAcrossProcesses() {
    var first=SyntheticCleanupManualQuarterTrial.defaultFixture(SyntheticCleanupExecutorTest.target());
    var second=SyntheticCleanupManualQuarterTrial.defaultFixture(SyntheticCleanupExecutorTest.target());
    assertEquals(first,second);
    assertEquals(first,SyntheticCleanupManualQuarterTrial.fixture(SyntheticCleanupExecutorTest.target(),SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH));
    assertEquals("2026-11-09T00:00:00Z",SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH.toString());
    assertEquals(3,ManualQuarterMaintenance.decoded(first.tables()).size());
    assertEquals(3,first.tables().get("accounts").size());
    assertEquals(3,first.tables().get("auth_identities").size());
    var cutoff=QuarterlyRetention.currentQuarterStart(SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH);
    assertEquals(1,ManualQuarterMaintenance.decoded(first.tables()).values().stream()
        .filter(envelope->QuarterlyRetention.finishedAt(envelope).isBefore(cutoff)).count());
  }
  @Test void separatelyPreparedDefaultTablesRunThroughRealCoreAndAdapter() {
    var fixture=SyntheticCleanupManualQuarterTrial.defaultFixture(SyntheticCleanupExecutorTest.target());
    var w=new Wire();
    w.fixture.tables=new TreeMap<>();
    fixture.tables().forEach((name,rows)->w.fixture.tables.put(name,new ArrayList<>(rows)));
    var s=new SyntheticCleanupManualQuarterTrial(fixture,w,
        Clock.fixed(SyntheticCleanupManualQuarterTrial.FIXTURE_EPOCH,ZoneOffset.UTC),()->true);
    var view=s.preview();assertEquals(1,view.removeMatches());assertEquals(2,view.keepMatches());
    assertEquals("SYNTHETIC_QUARTER_VERIFIED",s.confirm(view.planId(),true));assertEquals(2,w.posts);
    for(String table:ManualQuarterMaintenance.PRESERVED)assertEquals(fixture.tables().get(table),w.fixture.tables.get(table));
    assertEquals(2,ManualQuarterMaintenance.decoded(w.fixture.tables).size());
  }
  @Test void fixedFixtureStopsInAnotherQuarterRatherThanChangingRemovalScope() {
    var fixture=SyntheticCleanupManualQuarterTrial.defaultFixture(SyntheticCleanupExecutorTest.target());
    var w=new Wire();w.fixture.tables=new TreeMap<>();
    fixture.tables().forEach((name,rows)->w.fixture.tables.put(name,new ArrayList<>(rows)));
    var s=new SyntheticCleanupManualQuarterTrial(fixture,w,
        Clock.fixed(Instant.parse("2027-02-01T08:00:00Z"),ZoneOffset.UTC),()->true);
    assertThrows(IllegalStateException.class,s::preview);assertEquals(0,w.posts);
  }
}
