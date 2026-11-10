package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.junit.jupiter.api.Test;

class ManualQuarterMaintenanceTest {
  static class Storage implements ManualQuarterMaintenance.Storage {
    final LocalManualQuarterRehearsal.Fixture f;
    Storage() { f = new LocalManualQuarterRehearsal.Fixture(); }
    Storage(Instant now) { f = new LocalManualQuarterRehearsal.Fixture(now); }
    int reads, writes;
    int failBeforeWrite, timeoutAfterWrite;
    boolean readUnavailable;
    IntConsumer onRead = count -> {};
    IntConsumer afterWrite = count -> {};
    Function<ManualQuarterMaintenance.Snapshot, ManualQuarterMaintenance.Snapshot> transform = value -> value;
    public ManualQuarterMaintenance.Snapshot readComplete(ManualQuarterMaintenance.Binding binding) {
      onRead.accept(++reads);
      if (readUnavailable && writes > 0) throw new IllegalStateException("DO-NOT-ECHO-PRIVATE-CONTENT");
      return transform.apply(f.readComplete(binding));
    }
    public void replaceAtomic(ManualQuarterMaintenance.Binding binding, String digest, Map<String,List<List<String>>> rows) {
      int attempt = ++writes;
      if (failBeforeWrite == attempt) throw new SheetsGateway.Failure(403, 0);
      f.replaceAtomic(binding, digest, rows);
      afterWrite.accept(attempt);
      if (timeoutAfterWrite == attempt) throw new SheetsGateway.Failure(0, 0);
    }
    ManualQuarterMaintenance service() { return service(() -> true, f.clock, true); }
    ManualQuarterMaintenance service(BooleanSupplier stopped, Clock clock, boolean enabled) {
      return new ManualQuarterMaintenance(f.binding, this, clock, stopped, enabled);
    }
  }
  static ManualQuarterMaintenance.Preview preview(Storage s, ManualQuarterMaintenance service) {
    return service.preview(s.f.binding.stoppedConfirmation());
  }
  static ManualQuarterMaintenance.Result execute(ManualQuarterMaintenance service, ManualQuarterMaintenance.Preview p) {
    return service.execute(p.planId(), p.confirmationText());
  }

  @Test void quarterCleanupPreservesProfileAndLegacyAccountsExactly() {
    var s = new Storage();
    var rows = s.f.tables.get("accounts");
    rows.set(0, AccountProfileRows.HEADER);
    rows.set(1, List.of(rows.get(1).get(0), "2", "測試玩家", "player@example.test"));
    var expected = List.copyOf(rows);
    var service = s.service(); var p = preview(s, service);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(service, p).outcome());
    assertEquals(expected, s.f.tables.get("accounts"));
  }

  @Test void accountsIdentitiesTombstonesCurrentPayloadsAndScoresSurviveAndReplayOnlyReads() {
    var s = new Storage(); var service = s.service(); var p = preview(s, service);
    assertEquals(1, p.expiredCommits()); assertEquals(0, s.writes);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(service, p).outcome());
    s.f.verify(); assertEquals(2, s.writes);
    SheetsGateway noAppend = new SheetsGateway() {
      public List<List<String>> read(String tab) { return s.f.tables.get(tab); }
      public void appendAtomic(Map<String,List<List<Object>>> rows) { throw new AssertionError("Erased identity must not return"); }
    };
    assertThrows(SheetsGateway.Conflict.class, () -> new SheetsIdentityStore(noAppend, s.f.clock)
        .findOrCreateAccount("https://accounts.google.com", s.f.erasedSubject));
    for (String tab : ManualQuarterMaintenance.PRESERVED) assertEquals(s.f.before.get(tab), s.f.tables.get(tab));
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(service, p).outcome());
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, service.inspect(p.planId()).outcome());
    assertEquals(2, s.writes);
    assertThrows(IllegalStateException.class, () -> preview(s, service));
  }

  @Test void defaultDryRunAndBothExactConfirmationsRequired() {
    var s = new Storage(); var service = new ManualQuarterMaintenance(s.f.binding, s, s.f.clock, () -> true);
    assertThrows(IllegalStateException.class, () -> service.preview("yes")); assertEquals(0, s.reads);
    var p = preview(s, service);
    assertThrows(IllegalStateException.class, () -> service.execute(p.planId(), "yes"));
    assertThrows(IllegalStateException.class, () -> service.execute("other-plan", p.confirmationText()));
    assertEquals(ManualQuarterMaintenance.Outcome.DRY_RUN, execute(service, p).outcome()); assertEquals(0, s.writes);
  }

  @Test void allFourTaiwanBoundariesRemoveOnlyEarlierFinishesAndPreserveExistingAggregates() {
    for (String boundary : List.of("2025-12-31T16:00:00Z", "2026-03-31T16:00:00Z",
        "2026-06-30T16:00:00Z", "2026-09-30T16:00:00Z")) {
      var s = new Storage(Instant.parse(boundary));
      var oldAggregate = List.of("2024-Q4", "SUPPRESSED", "", "", "1");
      s.f.tables.get("quarterly_aggregates").add(oldAggregate);
      var service = s.service(); var p = preview(s, service);
      assertEquals(Instant.parse(boundary), p.cutoff()); assertEquals(1, p.expiredCommits());
      assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(service, p).outcome());
      s.f.verify(); assertEquals(2, s.writes);
      assertEquals(oldAggregate, s.f.tables.get("quarterly_aggregates").get(1));
      assertFalse(s.f.tables.get("envelopes").toString().contains(s.f.expired.matchId()));
      assertFalse(s.f.tables.get("commits").toString().contains(s.f.expired.matchId()));
      assertEquals(s.f.retained, ManualQuarterMaintenance.decoded(s.f.tables));
      assertDoesNotThrow(() -> QuarterlyRetention.requireCollectionReady(new SheetsGateway() {
        public List<List<String>> read(String tab) { return s.f.tables.get(tab); }
        public void appendAtomic(Map<String,List<List<Object>>> ignored) { fail("No append expected"); }
      }, s.f.clock));
    }
  }

  @Test void freshCompletedQuarterPlanOnlyRereadsAndPreservesOriginalCompletionTime() {
    var s = new Storage(); var first = s.service(); execute(first, preview(s, first));
    var before = ManualQuarterMaintenance.immutable(s.f.tables); int priorReads = s.reads;
    var clock = new MovingClock(); clock.now = s.f.clock.instant().plusSeconds(60);
    var again = s.service(() -> true, clock, true); var p = preview(s, again);
    assertEquals(0, p.expiredCommits()); assertTrue(again.alreadyComplete(p.planId()));
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(again, p).outcome());
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(again, p).outcome());
    assertEquals(2, s.writes); assertEquals(before, s.f.tables); assertTrue(s.reads >= priorReads + 4);
  }

  @Test void completedQuarterNoopStillRequiresFreshUnchangedSnapshotStoppedWritersAndUnexpiredPlan() {
    for (String fault : List.of("changed", "read-unavailable", "expired", "rollover", "writers-resumed")) {
      var s = new Storage(); var first = s.service(); execute(first, preview(s, first));
      var stopped = new AtomicBoolean(true); var clock = new MovingClock();
      var again = s.service(stopped::get, clock, true); var p = preview(s, again);
      switch (fault) {
        case "changed" -> s.f.tables.get("quarterly_aggregates").add(List.of("2025-Q1", "SUPPRESSED", "", "", "1"));
        case "read-unavailable" -> s.readUnavailable = true;
        case "expired" -> clock.now = clock.now.plusSeconds(300);
        case "rollover" -> clock.now = Instant.parse("2026-12-31T16:00:00Z");
        case "writers-resumed" -> stopped.set(false);
      }
      if (Set.of("changed", "read-unavailable").contains(fault))
        assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, execute(again, p).outcome());
      else assertThrows(IllegalStateException.class, () -> execute(again, p));
      assertEquals(2, s.writes);
    }
  }

  @Test void wrongTargetIncompleteUnknownTabMissingTabOrDatasetRejectsBeforeWrites() {
    for (int mode = 0; mode < 5; mode++) {
      var s = new Storage(); final int choice = mode;
      s.transform = original -> {
        var tabs = new TreeMap<>(original.tables());
        if (choice == 2) tabs.put("unknown", List.of(List.of("private")));
        if (choice == 3) tabs.remove("accounts");
        if (choice == 4) tabs.put("dataset", List.of(ManualQuarterMaintenance.HEADERS.get("dataset"),
            List.of("bridgego-restricted-test", UUID.randomUUID().toString())));
        return new ManualQuarterMaintenance.Snapshot(choice == 0 ? "OTHER_TARGET_NOT_APPROVED" : original.spreadsheetId(),
            choice != 1, tabs);
      };
      assertThrows(IllegalStateException.class, () -> preview(s, s.service())); assertEquals(0, s.writes);
    }
  }

  @Test void unknownColumnsDuplicatesCorruptChunksAndForbiddenPublicationRejectBeforeWrites() {
    for (int mode = 0; mode < 9; mode++) {
      var s = new Storage(); var tabs = s.f.tables;
      switch (mode) {
        case 0 -> tabs.get("accounts").set(0, List.of("account_id", "schema_version", "private"));
        case 1 -> tabs.get("accounts").add(tabs.get("accounts").get(1));
        case 2 -> tabs.get("erasure_ledger").add(tabs.get("erasure_ledger").get(2));
        case 3 -> tabs.get("commits").add(tabs.get("commits").get(1));
        case 4 -> tabs.get("envelopes").remove(1);
        case 5 -> tabs.get("quarterly_aggregates").add(List.of("2026-Q2", "CANDIDATE_COUNTS", "50", "50", "1"));
        case 6 -> tabs.get("accounts").add(List.of(s.f.erasedAccount, "1"));
        case 7 -> tabs.get("commits").add(ManualQuarterMaintenance.HEADERS.get("commits"));
        case 8 -> tabs.get("envelopes").add(List.of("commit_id", "unknown", "unknown", "unknown", "unknown"));
      }
      assertThrows(IllegalStateException.class, () -> preview(s, s.service())); assertEquals(0, s.writes);
    }
  }

  @Test void unknownCanonicalFieldsAndOversizeDatasetRejectBeforeWrites() {
    var s = new Storage();
    var payload = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(s.f.expired.payload(), Map.class);
    payload.put("unknown_private_data", "synthetic-reject");
    var e = FinalMatchEnvelope.create(s.f.expired.matchId(), 1, payload);
    s.f.tables.get("envelopes").set(1, List.of(e.commitId(), e.payloadHash(), "0", "1", e.canonicalJson()));
    s.f.tables.get("commits").set(1, List.of(e.commitId(), e.payloadHash(), "1", e.matchId(), "1"));
    assertThrows(IllegalStateException.class, () -> preview(s, s.service())); assertEquals(0, s.writes);
    var large = new Storage(); for (int i = 0; i < 1000; i++) large.f.tables.get("accounts").add(List.of(UUID.randomUUID().toString(), "1"));
    assertThrows(IllegalStateException.class, () -> preview(large, large.service())); assertEquals(0, large.writes);
  }

  @Test void headerOnlyLedgerBootstrapWorksWithAccountsAndTombstones() {
    var s = new Storage();
    for (String tab : List.of("envelopes", "commits")) s.f.tables.put(tab, new ArrayList<>(List.of(ManualQuarterMaintenance.HEADERS.get(tab))));
    var service = s.service(); var p = preview(s, service); assertEquals(0, p.expiredCommits());
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(service, p).outcome());
    assertEquals(2, s.writes);
    assertEquals(s.f.before.get("accounts"), s.f.tables.get("accounts"));
    assertEquals(s.f.before.get("erasure_ledger"), s.f.tables.get("erasure_ledger"));
  }

  @Test void nestedPrivateFieldsOrWrongScalarTypesCannotHideInsideKnownPayloadKeys() {
    for (Object invalid : List.of(Map.of("private", "not-an-approved-field"), "7")) {
      var s = new Storage();
      var payload = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(s.f.expired.payload(), Map.class);
      ((Map<String,Object>)payload.get("match")).put("ns_total", invalid);
      var e = FinalMatchEnvelope.create(s.f.expired.matchId(), 1, payload);
      s.f.tables.get("envelopes").set(1, List.of(e.commitId(), e.payloadHash(), "0", "1", e.canonicalJson()));
      s.f.tables.get("commits").set(1, List.of(e.commitId(), e.payloadHash(), "1", e.matchId(), "1"));
      assertThrows(IllegalStateException.class, () -> preview(s, s.service())); assertEquals(0, s.writes);
    }
  }

  @Test void firstWriteFailureIsNotAppliedAndRepeatedExecutionNeverRetries() {
    var s = new Storage(); s.failBeforeWrite = 1; var service = s.service(); var p = preview(s, service);
    assertEquals(ManualQuarterMaintenance.Outcome.NOT_APPLIED, execute(service, p).outcome());
    assertEquals(ManualQuarterMaintenance.Outcome.NOT_APPLIED, execute(service, p).outcome()); assertEquals(1, s.writes);
  }

  @Test void timeoutAfterEitherCommittedPhaseUsesReadbackNotReplay() {
    for (int phase : List.of(1, 2)) {
      var s = new Storage(); s.timeoutAfterWrite = phase; var service = s.service(); var p = preview(s, service);
      assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(service, p).outcome());
      assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(service, p).outcome());
      s.f.verify(); assertEquals(2, s.writes);
    }
  }

  @Test void secondPhaseFailureRemainsPendingUntilNewReviewedSessionAndFreshPlan() {
    var s = new Storage(); s.failBeforeWrite = 2; var service = s.service(); var p = preview(s, service);
    assertEquals(ManualQuarterMaintenance.Outcome.PENDING, execute(service, p).outcome());
    assertEquals(ManualQuarterMaintenance.Outcome.PENDING, execute(service, p).outcome()); assertEquals(2, s.writes);
    assertFalse(new RetentionLedger(s.f.tables.get("retention_ledger")).complete());
    s.failBeforeWrite = 0; var recovery = s.service(); var fresh = preview(s, recovery);
    assertEquals(0, fresh.expiredCommits());
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(recovery, fresh).outcome());
    s.f.verify(); assertEquals(4, s.writes);
  }

  @Test void unavailableReadbackStaysUncertainAndDoesNotWriteCompleteWatermark() {
    var s = new Storage(); s.readUnavailable = true; var service = s.service(); var p = preview(s, service);
    assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, execute(service, p).outcome());
    assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, execute(service, p).outcome()); assertEquals(1, s.writes);
    s.readUnavailable = false;
    assertEquals(ManualQuarterMaintenance.Outcome.PENDING, service.inspect(p.planId()).outcome()); assertEquals(1, s.writes);
  }

  @Test void stopConditionCheckedBeforeReadsBeforeExecutionAndAfterFirstWrite() {
    var stopped = new AtomicBoolean(false); var s = new Storage();
    var service = s.service(stopped::get, s.f.clock, true);
    assertThrows(IllegalStateException.class, () -> preview(s, service)); assertEquals(0, s.reads);
    stopped.set(true); var p = preview(s, service); stopped.set(false);
    assertThrows(IllegalStateException.class, () -> execute(service, p)); assertEquals(0, s.writes);
    stopped.set(true); s.afterWrite = n -> stopped.set(false);
    assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, execute(service, p).outcome()); assertEquals(1, s.writes);
    stopped.set(true); assertEquals(ManualQuarterMaintenance.Outcome.PENDING, service.inspect(p.planId()).outcome());
  }

  @Test void changedSnapshotBeforeEitherPhasePreventsThatWrite() {
    for (int changedRead : List.of(2, 3, 5)) {
      var s = new Storage(); var service = s.service(); var p = preview(s, service);
      s.onRead = n -> { if (n == changedRead) s.f.tables.get("erasure_ledger").add(List.of("GUEST",
          ErasureLedger.digest("GUEST", UUID.randomUUID().toString()), "1")); };
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, execute(service, p).outcome());
      assertEquals(changedRead == 5 ? 1 : 0, s.writes);
    }
  }

  static final class MovingClock extends Clock {
    Instant now = Instant.parse("2026-11-06T08:00:00Z");
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId ignored) { return this; }
    public Instant instant() { return now; }
  }
  @Test void expiredPlanOrInFlightQuarterBoundaryCannotFinalize() {
    var s = new Storage(); var clock = new MovingClock(); var service = s.service(() -> true, clock, true);
    var p = preview(s, service); clock.now = clock.now.plusSeconds(301);
    assertThrows(IllegalStateException.class, () -> execute(service, p)); assertEquals(0, s.writes);
    var other = new Storage(); var moving = new MovingClock(); var second = other.service(() -> true, moving, true);
    var q = preview(other, second); other.afterWrite = n -> moving.now = Instant.parse("2026-12-31T16:00:00Z");
    assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, execute(second, q).outcome()); assertEquals(1, other.writes);
    assertFalse(new RetentionLedger(other.f.tables.get("retention_ledger")).complete());
  }

  @Test void nextQuarterPurgesCurrentRecordsButPreservesAccountsAndTombstones() {
    var s = new Storage(); var first = s.service(); execute(first, preview(s, first));
    var clock = new MovingClock(); clock.now = Instant.parse("2026-12-31T16:00:00Z");
    var next = s.service(() -> true, clock, true); var p = preview(s, next); assertEquals(2, p.expiredCommits());
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, execute(next, p).outcome());
    assertTrue(ManualQuarterMaintenance.decoded(s.f.tables).isEmpty());
    for (String tab : ManualQuarterMaintenance.PRESERVED) assertEquals(s.f.before.get(tab), s.f.tables.get(tab));
    assertEquals(List.of("2026-Q4", "SUPPRESSED", "", "", "1"), s.f.tables.get("quarterly_aggregates").get(2));
  }

  @Test void storageErrorsDoNotExposePrivateDetails() {
    var s = new Storage(); s.onRead = n -> { throw new IllegalStateException("SECRET-TOKEN-RAW-BODY"); };
    var exception = assertThrows(IllegalStateException.class, () -> preview(s, s.service()));
    assertFalse(exception.toString().contains("SECRET")); assertNull(exception.getCause()); assertEquals(0, s.writes);
  }

  @Test void cliRejectsArgumentsAndOverridesWithoutEchoingThem() {
    for (var args : List.of(new String[]{"SECRET-ARG"}, new String[0])) {
      var bytes = new ByteArrayOutputStream();
      assertEquals(2, LocalManualQuarterRehearsal.run(args, Set.of("GOOGLE_FAKE_SECRET"), Set.of(),
          new StringReader(""), new PrintStream(bytes)));
      assertFalse(bytes.toString().contains("SECRET"));
    }
  }

  @Test void cliBothConfirmationsCancelOrRunMemoryOnly() {
    var bytes = new ByteArrayOutputStream();
    assertEquals(2, LocalManualQuarterRehearsal.run(new String[0], Set.of(), Set.of(), new StringReader("yes\n"), new PrintStream(bytes)));
    assertFalse(bytes.toString().contains("PREVIEW:"));
    bytes.reset();
    Reader answer = new Reader() {
      String remaining = "";
      public int read(char[] target, int offset, int length) {
        if (remaining.isEmpty()) {
          String[] lines = bytes.toString().stripTrailing().split("\\R");
          remaining = lines[lines.length-1] + "\n";
        }
        target[offset] = remaining.charAt(0); remaining = remaining.substring(1); return 1;
      }
      public void close() {}
    };
    assertEquals(0, LocalManualQuarterRehearsal.run(new String[0], Set.of(), Set.of(), answer, new PrintStream(bytes)));
    assertTrue(bytes.toString().contains("RESULT=COMPLETE (memory only)"));
  }
}
