package com.bridge.persistence;

import com.bridge.persistence.SyntheticSheetsErasurePreparation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

/**
 * OFFLINE execution protocol. No entry point, Spring wiring, HTTP, credentials or live adapter.
 * Snapshot is a trusted adapter contract, NOT a replacement for validating raw Sheets JSON.
 * The generated eight-grid projection and every append-only control grid are fingerprinted together.
 */
final class SyntheticCleanupExecutor {
  enum Phase { SEED_ERASURE, ERASE_ACCOUNT, SEED_QUARTER, PURGE_QUARTER }
  enum Outcome { VERIFIED, STOPPED_UNCERTAIN, REJECTED }
  static final int CONTROL_ID_BASE = 1_900_000_000;
  static final String PURPOSE = "SYNTHETIC-ONLY-CLEANUP-ATTEMPT-v1";
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Control is a proposed dedicated 2x8 literal-only sheet, never an ignored extra row/tab. */
  record Control(int sheetId, String title, List<List<String>> cells) {
    Control { cells = cells.stream().map(List::copyOf).toList(); }
  }
  record Snapshot(Workbook data, Map<Phase,Control> controls, boolean commentsIncluded,
      boolean exhaustiveMetadata, boolean unsupportedMetadata, String anchorDigest) {
    Snapshot(Workbook data, Map<Phase,Control> controls, boolean commentsIncluded, boolean exhaustiveMetadata, boolean unsupportedMetadata) {
      this(data,controls,commentsIncluded,exhaustiveMetadata,unsupportedMetadata,SyntheticCleanupWorkbook.sha256("OFFLINE_MODEL_ONLY"));
    }
    Snapshot { controls = Collections.unmodifiableMap(new TreeMap<>(controls)); }
  }
  record Preview(Phase phase, String planId, String targetHash, String beforeHash, String afterHash,
      Instant expiresAt, String confirmation) {}
  /** All values are fixed statuses, not underlying exceptions, targets, identifiers or payloads. */
  record Result(Outcome outcome, boolean cleanupAcceptance) {}

  interface Gateway {
    /** One coherent complete read: control tabs, data tabs, typed cells and ALL metadata/comments. */
    Snapshot readComplete(String fixedTarget);
    /** Whole raw JSON digest for bound raw adapters; model digest only for offline model gateways. */
    default String fullFingerprint(Snapshot snapshot) { return fingerprint(snapshot); }
    /** One atomic fixed-ID addSheet + literal marker + all bounded data updates. No retries. */
    void applyPhaseOnce(String fixedTarget, Control control, Workbook expectedBefore, Workbook after);

  }
  interface ExclusiveWriterLease {
    /** Procedural operator freeze prerequisite, NOT technical fencing. Never infer it from a digest/marker. */
    boolean held();
  }
  private static final class Plan {
    final Preview view; final Snapshot before, after; final Control control; boolean attempted;
    Plan(Preview view, Snapshot before, Snapshot after, Control control) {
      this.view = view; this.before = before; this.after = after; this.control = control;
    }
  }
  /** Created only by the local generator, not by parsing an uploaded manifest or arbitrary workbook. */
  static final class FixtureSet {
    final String target; final Instant generatedAt, cutoff;
    final List<Workbook> states;
    private String anchorDigest = SyntheticCleanupWorkbook.sha256("OFFLINE_MODEL_ONLY");
    private boolean rawBound;
    void bindRawAnchor(String digest) { if(rawBound || !digest.matches("[0-9a-f]{64}")) reject(); anchorDigest=digest; rawBound=true; }
    String anchorDigest() { return anchorDigest; }
    private FixtureSet(String target, Instant at, List<Workbook> states) {
      this.target = target; generatedAt = at; cutoff = QuarterlyRetention.currentQuarterStart(at);
      this.states = List.copyOf(states);
    }
    Snapshot initial() { return new Snapshot(states.get(0), Map.of(), true, true, false, anchorDigest); }
  }

  private final FixtureSet fixtures;
  private final Gateway gateway;
  private final Clock clock;
  private final ExclusiveWriterLease exclusive;
  private Plan plan;
  private boolean stopped;
  private int verifiedPhases; // A new host cannot continue an existing run, even after an exact readback.
  SyntheticCleanupExecutor(FixtureSet fixtures, Gateway gateway, Clock clock, ExclusiveWriterLease exclusive) {
    this.fixtures = Objects.requireNonNull(fixtures); this.gateway = Objects.requireNonNull(gateway);
    this.clock = Objects.requireNonNull(clock); this.exclusive = Objects.requireNonNull(exclusive);
    SyntheticCleanupWorkbook.requireApprovedTarget(fixtures.target);
  }

  synchronized Preview preview(Phase phase) {
    requireActiveWindow();
    if (stopped || phase.ordinal() != verifiedPhases || plan != null && plan.attempted
        && verifiedPhases <= plan.view.phase.ordinal()) reject();
    Snapshot before = read();
    int ordinal = phase.ordinal();
    if (!before.data.equals(fixtures.states.get(ordinal)) || before.controls.size() != ordinal) { stopped=true;reject(); }
    for (Phase p : Phase.values()) if (before.controls.containsKey(p) != (p.ordinal() < ordinal)) {stopped=true;reject();}
    String id = UUID.randomUUID().toString();
    String beforeHash = gateway.fullFingerprint(before);
    Instant expiry = clock.instant().plusSeconds(300);
    // Bind only data's proposed hash in the marker to avoid a self-referential whole-snapshot hash.
    String dataAfterHash = SyntheticSheetsErasurePreparation.fingerprint(fixtures.states.get(ordinal + 1));
    Control control = control(phase, id, fingerprint(before), dataAfterHash, expiry);
    var controls = new TreeMap<>(before.controls); controls.put(phase, control);
    Snapshot after = new Snapshot(fixtures.states.get(ordinal + 1), controls, true, true, false, fixtures.anchorDigest);
    validate(after, fixtures); String afterHash = gateway.fullFingerprint(after);
    String targetHash = SyntheticCleanupWorkbook.sha256(fixtures.target);
    String confirmation = "SYNTHETIC " + phase + " TARGET " + targetHash + " PLAN " + id
        + " BEFORE " + beforeHash + " AFTER " + afterHash + " PROCEDURAL WRITER FREEZE ACKNOWLEDGED";
    Preview view = new Preview(phase, id, targetHash, beforeHash, afterHash, expiry, confirmation);
    plan = new Plan(view, before, after, control); return view;
  }

  synchronized Result execute(String planId, String exactConfirmation) {
    Plan current = plan;
    if (stopped || current == null || current.attempted || !current.view.planId.equals(planId)
        || !current.view.confirmation.equals(exactConfirmation)) return rejected();
    try {
      requireWindow(current);
      if (!read().equals(current.before)) {stopped=true;return rejected();}
      requireWindow(current);
      // Consume before any remote call. A lost response is never interpreted as permission to replay.
      current.attempted = true;
      gateway.applyPhaseOnce(fixtures.target, current.control, current.before.data, current.after.data);
      requireWindow(current);
      Snapshot observed = read();
      requireWindow(current);
      if (!observed.equals(current.after) || !gateway.fullFingerprint(observed).equals(current.view.afterHash)) return uncertain();
      // No COMPLETE write, cleanup of markers, or continuation after a failed/unknown write.
      verifiedPhases++;
      return new Result(Outcome.VERIFIED, false);
    } catch (Exception | LinkageError failure) { return current.attempted ? uncertain() : rejected(); }
  }
  /** Reconciliation is deliberately read-only and cannot unlock/retry or turn uncertainty into a new write. */
  synchronized Result inspect(String planId) {
    if (plan == null || !plan.view.planId.equals(planId) || !plan.attempted) return rejected();
    try {
      requireActiveWindow();
      Snapshot observed = read();
      return observed.equals(plan.after) ? new Result(Outcome.VERIFIED,false) : uncertain();
    } catch (Exception | LinkageError failure) { return uncertain(); }
  }
  private Result rejected() { return new Result(Outcome.REJECTED, false); }
  private Result uncertain() { stopped = true; return new Result(Outcome.STOPPED_UNCERTAIN, false); }
  private Snapshot read() {
    try { requireActiveWindow(); Snapshot s = gateway.readComplete(fixtures.target); requireActiveWindow(); validate(s, fixtures); return s; }
    catch(RuntimeException | LinkageError unknown){stopped=true;throw unknown;}
  }
  private void requireWindow(Plan p) {
    requireActiveWindow(); if (!p.view.expiresAt.isAfter(clock.instant())) reject();
  }
  private void requireActiveWindow() {
    if (!exclusive.held() || !fixtures.cutoff.equals(QuarterlyRetention.currentQuarterStart(clock.instant()))
        || clock.instant().isBefore(fixtures.generatedAt) || clock.instant().isAfter(fixtures.generatedAt.plusSeconds(3600))) reject();
  }
  static void validate(Snapshot s, FixtureSet fixtures) {
    if (s == null || !fixtures.anchorDigest.equals(s.anchorDigest) || !s.commentsIncluded || !s.exhaustiveMetadata || s.unsupportedMetadata || s.data == null
        || !s.data.complete() || s.data.unsupportedMetadata() || !fixtures.target.equals(s.data.spreadsheetId())
        || !fixtures.states.contains(s.data) || s.controls.size() > Phase.values().length) reject();
    var prior = new TreeMap<Phase,Control>();
    for (var entry : s.controls.entrySet()) {
      Phase phase = entry.getKey(); Control c = entry.getValue();
      if (phase.ordinal() != prior.size()) reject();
      if (c == null || c.sheetId != CONTROL_ID_BASE + phase.ordinal() || !c.title.equals("_synthetic_once_" + phase)
          || c.cells.size() != 2 || !c.cells.get(0).equals(List.of("purpose","phase","plan","target_hash","before_hash","data_after_hash","expires_at","state"))) reject();
      var row = c.cells.get(1);
      if (row.size() != 8 || !row.get(0).equals(PURPOSE) || !row.get(1).equals(phase.name())
          || !UUID.fromString(row.get(2)).toString().equals(row.get(2))
          || !row.get(3).equals(SyntheticCleanupWorkbook.APPROVED_TARGET_SHA256)
          || !row.get(4).equals(fingerprint(new Snapshot(fixtures.states.get(phase.ordinal()), prior, true, true, false, fixtures.anchorDigest)))
          || !row.get(5).equals(SyntheticSheetsErasurePreparation.fingerprint(fixtures.states.get(phase.ordinal() + 1)))
          || !Instant.parse(row.get(6)).toString().equals(row.get(6)) || !row.get(7).equals("ATTEMPTED_NEVER_RESET")) reject();
      prior.put(phase,c);
    }
  }
  private Control control(Phase phase, String id, String before, String after, Instant expiry) {
    return new Control(CONTROL_ID_BASE + phase.ordinal(), "_synthetic_once_" + phase,
        List.of(List.of("purpose","phase","plan","target_hash","before_hash","data_after_hash","expires_at","state"),
            List.of(PURPOSE, phase.name(), id, SyntheticCleanupWorkbook.APPROVED_TARGET_SHA256, before, after,
                expiry.toString(), "ATTEMPTED_NEVER_RESET")));
  }
  static String fingerprint(Snapshot s) {
    try { return SyntheticCleanupWorkbook.sha256(JSON.writeValueAsString(s)); }
    catch (Exception failure) { throw new IllegalStateException("Synthetic execution rejected"); }
  }
  static FixtureSet generate(String approvedTarget, Instant at) {
    SyntheticCleanupWorkbook.requireApprovedTarget(approvedTarget);
    Clock fixed = Clock.fixed(at, ZoneOffset.UTC);
    Manifest erasure = SyntheticSheetsErasurePreparation.generate(approvedTarget, UUID.randomUUID().toString(), Set.of(), at);
    Workbook eraseBefore = erasure.baseline();
    Workbook eraseAfter = SyntheticSheetsErasurePreparation.computeInMemoryCandidate(erasure, fixed);
    SyntheticSheetsErasurePreparation.verifyTransform(erasure, eraseAfter);
    var quarter = new LocalManualQuarterRehearsal.Fixture(at);
    String earlierQuarter=QuarterlyRetention.quarter(QuarterlyRetention.currentQuarterStart(at).minusSeconds(120L*86400));
    quarter.tables.get("quarterly_aggregates").add(List.of(earlierQuarter,"SUPPRESSED","","","1"));
    var before = ManualQuarterMaintenance.immutable(quarter.tables);
    var engine = new ManualQuarterMaintenance(quarter.binding, quarter, fixed, () -> true, true);
    var p = engine.preview(quarter.binding.stoppedConfirmation());
    if (engine.execute(p.planId(), p.confirmationText()).outcome() != ManualQuarterMaintenance.Outcome.COMPLETE) reject();
    quarter.verify();
    Workbook quarterBefore = tables(approvedTarget, before), quarterAfter = tables(approvedTarget, quarter.tables);
    Map<String,Grid> scaffold = new TreeMap<>();
    for (var e : eraseBefore.sheets().entrySet()) scaffold.put(e.getKey(), SyntheticSheetsErasurePreparation.grid(e.getValue().sheetId(),
        List.of(ManualQuarterMaintenance.HEADERS.get(e.getKey()))));
    Grid identities = scaffold.get("auth_identities");
    scaffold.put("auth_identities", SyntheticSheetsErasurePreparation.grid(identities.sheetId(), List.of(
        ManualQuarterMaintenance.HEADERS.get("auth_identities"), List.of("", "SYNTHETIC-ONLY-FORMAT-TEST"))));
    return new FixtureSet(approvedTarget, at, List.of(new Workbook(approvedTarget,true,false,scaffold),
        eraseBefore, eraseAfter, quarterBefore, quarterAfter));
  }
  private static Workbook tables(String target, Map<String,List<List<String>>> tables) {
    Map<String,Grid> grids = new TreeMap<>(); int id = 100;
    for (var e : new TreeMap<>(tables).entrySet()) grids.put(e.getKey(), SyntheticSheetsErasurePreparation.grid(id++, e.getValue()));
    return new Workbook(target,true,false,grids);
  }
  private static void reject() { throw new IllegalStateException("Synthetic execution rejected"); }
}
