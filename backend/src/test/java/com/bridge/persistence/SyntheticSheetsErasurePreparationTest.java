package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.SyntheticSheetsErasurePreparation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class SyntheticSheetsErasurePreparationTest {
  static final String SHEET = "OFFLINE_NEW_SYNTHETIC_SHEET";
  static final String PRODUCTION = "FORBIDDEN_PRODUCTION_SHEET";
  static final String DATASET = "ee9479e1-c502-4ea3-afd0-adf6feb79245";
  static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
  Manifest manifest; Fake gateway; MutableClock clock; AtomicBoolean stopped; Operator operator;
  @BeforeEach void setup() {
    manifest = SyntheticSheetsErasurePreparation.generate(SHEET, DATASET, Set.of(PRODUCTION), NOW);
    gateway = new Fake(manifest.baseline()); clock = new MutableClock(); stopped = new AtomicBoolean(true);
    operator = new Operator(manifest, gateway, clock, stopped::get);
  }
  static final class MutableClock extends Clock {
    Instant now = NOW;
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
  }
  static final class Fake implements WholeWorkbookGateway {
    Workbook current; int reads, writes; boolean beforeFailure, timeoutAfter, failReadback, failReads, partial;
    Replacement last;
    Fake(Workbook current) { this.current = current; }
    public Workbook readComplete() { reads++; if (failReads) throw new IllegalStateException("PRIVATE failed body"); return current; }
    public void replaceAtomic(Replacement replacement) {
      writes++; last = replacement;
      assertEquals(current.spreadsheetId(), replacement.spreadsheetId());
      assertEquals(SyntheticSheetsErasurePreparation.REPLACED, replacement.grids().keySet());
      if (beforeFailure) throw new IllegalStateException("PRIVATE before commit");
      Map<String,Grid> grids = new TreeMap<>(current.sheets());
      if (partial) grids.put("accounts", replacement.grids().get("accounts"));
      else grids.putAll(replacement.grids());
      current = new Workbook(current.spreadsheetId(), true, false, grids);
      if (failReadback) failReads = true;
      if (timeoutAfter) throw new IllegalStateException("PRIVATE timeout");
    }
  }
  static Workbook withGrid(Workbook base, String tab, Grid value) {
    Map<String,Grid> changed = new TreeMap<>(base.sheets()); changed.put(tab, value);
    return new Workbook(base.spreadsheetId(), base.complete(), base.unsupportedMetadata(), changed);
  }
  static Workbook cell(Workbook base, String tab, int row, int col, Cell value) {
    Grid old = base.sheets().get(tab);
    List<List<Cell>> rows = new ArrayList<>(old.cells().stream().map(ArrayList::new).toList());
    rows.get(row).set(col, value);
    return withGrid(base, tab, new Grid(old.sheetId(), old.rowCount(), old.columnCount(), old.hidden(), old.unsupportedMetadata(), rows));
  }
  void rejectPreview(Workbook value) {
    gateway.current = value;
    assertThrows(RuntimeException.class, operator::preview);
    assertEquals(0, gateway.writes);
  }

  @Test void fixtureIsExactDeterministicAndExportHasNoLiveBinding() throws Exception {
    var again = SyntheticSheetsErasurePreparation.generate(SHEET, DATASET, Set.of(PRODUCTION), NOW);
    assertEquals(manifest.baseline(), again.baseline());
    var json = new ObjectMapper().readTree(SyntheticSheetsErasurePreparation.exportFixture(manifest));
    assertEquals("OFFLINE_PREPARATION_ONLY_NOT_AUTHORIZATION", json.path("status").asText());
    assertEquals(SyntheticSheetsErasurePreparation.fingerprint(manifest.baseline()), json.path("expected_whole_workbook_sha256").asText());
    assertEquals(8, json.path("workbook").path("sheets").size());
    assertEquals(32, json.path("limits").path("rows_per_sheet").asInt());
    assertEquals(0, gateway.reads); assertEquals(0, gateway.writes);
  }

  @Test void productionBindingAndWrongWorkbookOrDatasetAreRejected() {
    assertThrows(RuntimeException.class, () -> SyntheticSheetsErasurePreparation.generate(PRODUCTION, DATASET, Set.of(PRODUCTION), NOW));
    assertThrows(RuntimeException.class, () -> SyntheticSheetsErasurePreparation.generate(SHEET, "not-a-dataset", Set.of(), NOW));
    rejectPreview(new Workbook(PRODUCTION, true, false, manifest.baseline().sheets()));
    rejectPreview(new Workbook("DIFFERENT_TEST_WORKBOOK", true, false, manifest.baseline().sheets()));
    rejectPreview(cell(manifest.baseline(), "dataset", 2, 1, new Cell("TEXT", UUID.randomUUID().toString(), "")));
    assertEquals(0, gateway.writes);
  }

  @Test void realOrUnknownIdentityDataIsRejectedEvenWithOriginalSyntheticLabels() {
    rejectPreview(cell(manifest.baseline(), "auth_identities", 1, 1, new Cell("TEXT", "123456789012345678901", "")));
    rejectPreview(cell(manifest.baseline(), "auth_identities", 1, 1, new Cell("TEXT", "person@example.test", "")));
    rejectPreview(cell(manifest.baseline(), "accounts", 1, 0, new Cell("TEXT", UUID.randomUUID().toString(), "")));
    rejectPreview(cell(manifest.baseline(), "auth_identities", 1, 1, new Cell("TEXT", "SYNTHETIC-ONLY:arbitrary-target", "")));
    rejectPreview(cell(manifest.baseline(), "envelopes", 1, 4,
        new Cell("TEXT", manifest.baseline().sheets().get("envelopes").cells().get(1).get(4).value().replace("\"ns_total\":7", "\"ns_total\":8"), "")));
  }

  @Test void unknownTabsColumnsRowsHiddenDataNotesAndFormulasAreRejected() {
    var base = manifest.baseline(); var grid = base.sheets().get("accounts");
    rejectPreview(withGrid(base, "unknown-private-tab", grid));
    Map<String,Grid> missing = new TreeMap<>(base.sheets()); missing.remove("quarterly_aggregates");
    rejectPreview(new Workbook(SHEET, true, false, missing));
    rejectPreview(withGrid(base, "accounts", new Grid(grid.sheetId(), 32, 6, false, false, grid.cells())));
    rejectPreview(withGrid(base, "accounts", new Grid(grid.sheetId(), 1000, 5, false, false, grid.cells())));
    rejectPreview(withGrid(base, "accounts", new Grid(grid.sheetId(), 32, 5, true, false, grid.cells())));
    rejectPreview(withGrid(base, "accounts", new Grid(grid.sheetId(), 32, 5, false, true, grid.cells())));
    rejectPreview(cell(base, "accounts", 31, 4, new Cell("TEXT", "private data beyond ordinary populated rows", "")));
    rejectPreview(cell(base, "accounts", 1, 0, new Cell("TEXT", grid.cells().get(1).get(0).value(), "private note")));
    rejectPreview(cell(base, "accounts", 1, 0, new Cell("FORMULA", "=A1", "")));
    rejectPreview(cell(base, "accounts", 1, 0, new Cell("NUMBER", "123", "")));
  }

  @Test void incompleteOrOversizedAttestationsNeverReachWrite() {
    var base = manifest.baseline();
    rejectPreview(new Workbook(SHEET, false, false, base.sheets()));
    rejectPreview(new Workbook(SHEET, true, true, base.sheets()));
    rejectPreview(cell(base, "accounts", 1, 0, new Cell("TEXT", "x".repeat(8193), "")));
    Workbook oversized = base;
    for (int row = 10; row < 20; row++) oversized = cell(oversized, "accounts", row, 0, new Cell("TEXT", "x".repeat(8192), ""));
    rejectPreview(oversized);
  }

  @Test void previewIsReadOnlyAndExactConfirmationBindsBothDigestsAndDestination() {
    var before = gateway.current; var p = operator.preview();
    assertEquals(1, gateway.reads); assertEquals(0, gateway.writes); assertEquals(before, gateway.current);
    for (String required : List.of(SHEET, DATASET, p.planId(), p.beforeDigest(), p.afterDigest(), "EXCLUSIVE WRITERS STOPPED"))
      assertTrue(p.confirmationText().contains(required));
    for (String wrong : List.of("yes", "", p.confirmationText() + " ", p.confirmationText().replace(SHEET, PRODUCTION))) {
      assertThrows(RuntimeException.class, () -> operator.execute(p.planId(), wrong));
      assertEquals(0, gateway.writes); assertEquals(1, gateway.reads);
    }
  }

  @Test void exactExecuteAndPoststateInspectionPreserveUnrelatedData() {
    var before = gateway.current; var p = operator.preview();
    assertEquals(Outcome.VERIFIED, operator.execute(p.planId(), p.confirmationText()));
    assertEquals(1, gateway.writes); assertEquals(3, gateway.reads);
    assertEquals(p.afterDigest(), SyntheticSheetsErasurePreparation.fingerprint(gateway.current));
    for (String tab : List.of("dataset", "retention_ledger", "quarterly_aggregates"))
      assertEquals(before.sheets().get(tab), gateway.current.sheets().get(tab));
    assertEquals(Outcome.VERIFIED, operator.inspect(p.planId()));
    assertEquals(Outcome.VERIFIED, operator.execute(p.planId(), p.confirmationText()));
    assertEquals(1, gateway.writes);
    assertThrows(RuntimeException.class, operator::preview); // a poststate is never treated as a new baseline
    assertEquals(1, gateway.writes);
  }

  @Test void freshWholeSnapshotAndWriterAcknowledgementAreRechecked() {
    var p = operator.preview();
    gateway.current = cell(gateway.current, "accounts", 31, 4, new Cell("TEXT", "changed after preview", ""));
    assertThrows(RuntimeException.class, () -> operator.execute(p.planId(), p.confirmationText()));
    assertEquals(0, gateway.writes);
    gateway.current = manifest.baseline(); stopped.set(false);
    assertThrows(RuntimeException.class, () -> operator.execute(p.planId(), p.confirmationText()));
    assertEquals(0, gateway.writes);
    int reads = gateway.reads; assertThrows(RuntimeException.class, operator::preview); assertEquals(reads, gateway.reads);
  }

  @Test void expiredOrReplacedPlanCannotWrite() {
    var first = operator.preview(); var second = operator.preview();
    assertThrows(RuntimeException.class, () -> operator.execute(first.planId(), first.confirmationText()));
    clock.now = NOW.plusSeconds(300);
    assertThrows(RuntimeException.class, () -> operator.execute(second.planId(), second.confirmationText()));
    assertEquals(0, gateway.writes);
  }

  @Test void timeoutAfterCommitReadsBackWithoutBlindReplacementRetry() {
    var p = operator.preview(); gateway.timeoutAfter = true;
    assertEquals(Outcome.VERIFIED, operator.execute(p.planId(), p.confirmationText()));
    assertEquals(Outcome.VERIFIED, operator.execute(p.planId(), p.confirmationText()));
    assertEquals(1, gateway.writes);
  }

  @Test void notAppliedAndUnknownOutcomesNeverRetrySamePlan() {
    var p = operator.preview(); gateway.beforeFailure = true;
    assertEquals(Outcome.NOT_APPLIED, operator.execute(p.planId(), p.confirmationText()));
    gateway.beforeFailure = false;
    assertEquals(Outcome.NOT_APPLIED, operator.execute(p.planId(), p.confirmationText()));
    assertEquals(1, gateway.writes);
    var q = operator.preview(); gateway.failReadback = true;
    assertEquals(Outcome.UNCERTAIN, operator.execute(q.planId(), q.confirmationText()));
    assertEquals(Outcome.UNCERTAIN, operator.execute(q.planId(), q.confirmationText()));
    assertEquals(2, gateway.writes);
    gateway.failReads = false;
    assertEquals(Outcome.VERIFIED, operator.inspect(q.planId()));
    assertEquals(2, gateway.writes);
  }

  @Test void mixedReadbackIsUncertainAndIsNotRewritten() {
    var p = operator.preview(); gateway.partial = true;
    assertEquals(Outcome.UNCERTAIN, operator.execute(p.planId(), p.confirmationText()));
    assertEquals(Outcome.UNCERTAIN, operator.execute(p.planId(), p.confirmationText()));
    assertEquals(1, gateway.writes);
  }

  @Test void mainOnlySummarizesOrExportsAndRejectsCleanupArgumentsAndConfiguration() throws Exception {
    var out = new ByteArrayOutputStream(); var print = new PrintStream(out, true, StandardCharsets.UTF_8);
    assertEquals(0, SyntheticSheetsErasurePreparation.run(new String[0], Set.of(), Set.of(), print));
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("cannot access or erase a real spreadsheet"));
    out.reset();
    assertEquals(0, SyntheticSheetsErasurePreparation.run(new String[]{"--export-fixture"}, Set.of(), Set.of(), print));
    var json = new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
    assertEquals("OFFLINE_SYNTHETIC_WORKBOOK_NOT_APPROVED", json.path("spreadsheet_id").asText());
    for (String arg : List.of("--execute", "--sheet=live", "--credential=file", "--fixture=input.json")) {
      out.reset(); assertEquals(2, SyntheticSheetsErasurePreparation.run(new String[]{arg}, Set.of(), Set.of(), print));
      assertFalse(out.toString(StandardCharsets.UTF_8).contains(arg));
    }
    for (String key : List.of("GOOGLE_APPLICATION_CREDENTIALS", "BRIDGE_SHEETS_SPREADSHEET_ID", "SPRING_CONFIG_IMPORT", "loader.main")) {
      assertEquals(2, SyntheticSheetsErasurePreparation.run(new String[0], Set.of(key), Set.of(), print));
      assertEquals(2, SyntheticSheetsErasurePreparation.run(new String[0], Set.of(), Set.of(key), print));
    }
  }
}
