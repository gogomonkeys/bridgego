package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Offline preparation only. There is deliberately no live gateway, credential reader or Spring entry. */
public final class SyntheticSheetsErasurePreparation {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PURPOSE = "bridgego-synthetic-account-erasure-v1";
  private static final String ISSUER = "https://accounts.google.com";
  private static final String AUDIENCE = "offline-synthetic-proof-not-google-login";
  private static final String OFFLINE_SHEET = "OFFLINE_SYNTHETIC_WORKBOOK_NOT_APPROVED";
  static final int ROWS = 32, COLUMNS = 5, MAX_CELL_CHARS = 8192, MAX_TOTAL_CHARS = 65536;
  static final Set<String> TABLES = Set.of("dataset", "accounts", "auth_identities", "envelopes", "commits",
      "erasure_ledger", "retention_ledger", "quarterly_aggregates");
  static final Set<String> REPLACED = Set.of("accounts", "auth_identities", "envelopes", "commits", "erasure_ledger");
  private static final Cell EMPTY = new Cell("EMPTY", "", "");
  private SyntheticSheetsErasurePreparation() {}

  public static void main(String[] args) {
    int status = run(args, System.getenv().keySet(), System.getProperties().stringPropertyNames(), System.out);
    if (status != 0) System.exit(status);
  }

  static int run(String[] args, Set<String> environmentNames, Set<String> propertyNames, PrintStream out) {
    try {
      if (args == null || args.length > 1 || args.length == 1 && !args[0].equals("--export-fixture")) reject();
      for (Set<String> names : List.of(environmentNames, propertyNames)) for (String name : names) {
        String key = name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        if (List.of("BRIDGE_", "GOOGLE_", "SPRING_", "SERVER_", "MANAGEMENT_", "LOADER_", "JAVAX_NET_SSL_").stream().anyMatch(key::startsWith)
            || Set.of("PUBLIC_ORIGIN", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "CLASSPATH",
                "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "HTTP_PROXYHOST", "HTTPS_PROXYHOST", "SOCKSPROXYHOST",
                "JAVA_NET_USESYSTEMPROXIES", "JAVA_SECURITY_MANAGER", "JAVA_SECURITY_POLICY").contains(key)) reject();
      }
      Manifest manifest = generate(OFFLINE_SHEET, UUID.randomUUID().toString(), Set.of(), Instant.now());
      if (args.length == 1) out.println(exportFixture(manifest));
      else {
        out.println("OFFLINE PREPARATION ONLY: exact synthetic fixture and guarded erasure core are available.");
        out.println("Contract: 8 known grids, 32 rows x 5 columns each, 3 synthetic matches, 2 accounts and 2 identity mappings.");
        out.println("Use --export-fixture to print a newly generated fixture/manifest to stdout. No input fixtures or credentials are opened.");
        out.println("No token provider or approved real Sheet binding exists. This entry point cannot access or erase a real spreadsheet.");
      }
      return 0;
    } catch (Exception | LinkageError failure) {
      out.println("REJECTED: preparation input/configuration was not accepted. No external access; no values displayed.");
      return 2;
    }
  }

  // The following contracts are package-private for fake-gateway tests and a separately reviewed future adapter.
  // No CLI/environment setting can supply a gateway, ownership proof, subject, account or real Sheet binding.
  record Cell(String type, String value, String note) {}
  record Grid(int sheetId, int rowCount, int columnCount, boolean hidden, boolean unsupportedMetadata,
      List<List<Cell>> cells) {
    Grid { cells = cells.stream().map(List::copyOf).toList(); }
  }
  record Workbook(String spreadsheetId, boolean complete, boolean unsupportedMetadata, Map<String,Grid> sheets) {
    Workbook { sheets = Collections.unmodifiableMap(new TreeMap<>(sheets)); }
  }
  record Replacement(String spreadsheetId, String datasetId, String beforeDigest, String afterDigest, Map<String,Grid> grids) {
    Replacement { grids = Collections.unmodifiableMap(new TreeMap<>(grids)); }
  }
  interface WholeWorkbookGateway {
    /** Must enumerate the entire bounded workbook, including grid shape, typed cells and unsupported metadata. */
    Workbook readComplete();
    /** One bounded atomic batch, no automatic retries. Digest is an audit binding, NOT Sheets CAS. */
    void replaceAtomic(Replacement replacement);
  }
  record Preview(String planId, String spreadsheetId, String datasetId, String beforeDigest, String afterDigest,
      Instant expiresAt, int affectedMatches, int affectedSeats, String confirmationText) {}
  enum Outcome { VERIFIED, NOT_APPLIED, UNCERTAIN }

  static final class Manifest {
    private final String sheetId, datasetId, targetAccount, targetSubject;
    private final Set<String> forbiddenSheetIds;
    private final Instant createdAt;
    private final Workbook baseline;
    private Manifest(String sheetId, String datasetId, Set<String> forbiddenSheetIds, Instant createdAt) {
      this.sheetId = sheetId; this.datasetId = datasetId; this.forbiddenSheetIds = Set.copyOf(forbiddenSheetIds);
      this.createdAt = createdAt;
      targetAccount = id(datasetId, "account-target");
      targetSubject = "SYNTHETIC-ONLY:" + datasetId + ":target";
      baseline = generateWorkbook(this, createdAt);
    }
    Workbook baseline() { return baseline; }
    String datasetId() { return datasetId; }
    String sheetId() { return sheetId; }
  }

  /** This does not grant permission or prove a Sheet is newly created. Future binding needs independent evidence. */
  static Manifest generate(String approvedNewSheetId, String datasetId, Set<String> forbiddenSheetIds, Instant createdAt) {
    if (approvedNewSheetId == null || !approvedNewSheetId.matches("[A-Za-z0-9_-]{10,160}")
        || forbiddenSheetIds.contains(approvedNewSheetId) || !UUID.fromString(datasetId).toString().equals(datasetId)) reject();
    return new Manifest(approvedNewSheetId, datasetId, forbiddenSheetIds, Objects.requireNonNull(createdAt));
  }

  static String exportFixture(Manifest manifest) throws Exception {
    return JSON.writeValueAsString(Map.of("status", "OFFLINE_PREPARATION_ONLY_NOT_AUTHORIZATION", "fixture_version", 1,
        "dataset_id", manifest.datasetId, "spreadsheet_id", manifest.sheetId, "created_at", manifest.createdAt.toString(),
        "expected_whole_workbook_sha256", fingerprint(manifest.baseline), "workbook", manifest.baseline,
        "limits", Map.of("sheets", 8, "rows_per_sheet", ROWS, "columns_per_sheet", COLUMNS,
            "max_cell_chars", MAX_CELL_CHARS, "max_total_chars", MAX_TOTAL_CHARS)));
  }

  static final class Operator {
    private final Manifest manifest;
    private final WholeWorkbookGateway gateway;
    private final Clock clock;
    private final BooleanSupplier writersStopped;
    private Plan plan;
    private static final class Plan {
      final Preview view;
      final Workbook after;
      boolean attempted;
      Plan(Preview view, Workbook after) { this.view = view; this.after = after; }
    }
    Operator(Manifest manifest, WholeWorkbookGateway gateway, Clock clock, BooleanSupplier writersStopped) {
      this.manifest = Objects.requireNonNull(manifest); this.gateway = Objects.requireNonNull(gateway);
      this.clock = Objects.requireNonNull(clock); this.writersStopped = Objects.requireNonNull(writersStopped);
    }
    synchronized Preview preview() {
      requireStopped();
      Workbook before = gateway.readComplete();
      validateWholeWorkbook(before, manifest);
      if (!manifest.baseline.equals(before)) reject(); // exact generated bytes, not a label/prefix heuristic
      Workbook after = computeInMemoryCandidate(manifest, clock);
      validateWholeWorkbook(after, manifest);
      verifyTransform(manifest, after);
      String id = UUID.randomUUID().toString(), beforeHash = fingerprint(before), afterHash = fingerprint(after);
      Instant expiry = clock.instant().plusSeconds(300);
      String confirmation = "ERASE SYNTHETIC SHEET " + manifest.sheetId + " DATASET " + manifest.datasetId
          + " PLAN " + id + " BEFORE " + beforeHash + " AFTER " + afterHash + " EXCLUSIVE WRITERS STOPPED";
      Preview view = new Preview(id, manifest.sheetId, manifest.datasetId, beforeHash, afterHash, expiry, 2, 2, confirmation);
      plan = new Plan(view, after);
      return view;
    }
    synchronized Outcome execute(String planId, String exactConfirmation) {
      Plan current = requirePlan(planId);
      if (!current.view.confirmationText().equals(exactConfirmation)) reject();
      // After the first attempt this operation only reconciles; it never replays a replacement.
      if (current.attempted) return inspect(planId);
      if (!current.view.expiresAt().isAfter(clock.instant())) reject();
      requireStopped();
      Workbook observed = gateway.readComplete();
      validateWholeWorkbook(observed, manifest);
      if (!manifest.baseline.equals(observed) || !fingerprint(observed).equals(current.view.beforeDigest())) reject();
      requireStopped();
      Map<String,Grid> replacement = new TreeMap<>();
      REPLACED.forEach(tab -> replacement.put(tab, current.after.sheets().get(tab)));
      current.attempted = true; // set BEFORE entering an operation with a potentially unknown outcome
      try { gateway.replaceAtomic(new Replacement(manifest.sheetId, manifest.datasetId,
          current.view.beforeDigest(), current.view.afterDigest(), replacement)); }
      catch (RuntimeException unknown) { /* Read back only; never retry this batch. */ }
      return inspect(planId);
    }
    synchronized Outcome inspect(String planId) {
      Plan current = requirePlan(planId);
      try {
        Workbook observed = gateway.readComplete();
        validateWholeWorkbook(observed, manifest);
        if (observed.equals(current.after) && fingerprint(observed).equals(current.view.afterDigest())) return Outcome.VERIFIED;
        if (observed.equals(manifest.baseline)) return Outcome.NOT_APPLIED;
        return Outcome.UNCERTAIN;
      } catch (RuntimeException unavailable) { return Outcome.UNCERTAIN; }
    }
    private Plan requirePlan(String id) {
      if (plan == null || !plan.view.planId().equals(id)) reject();
      return plan;
    }
    private void requireStopped() { if (!writersStopped.getAsBoolean()) reject(); }
  }

  static void validateWholeWorkbook(Workbook workbook, Manifest manifest) {
    if (workbook == null || !workbook.complete() || workbook.unsupportedMetadata()
        || !manifest.sheetId.equals(workbook.spreadsheetId()) || manifest.forbiddenSheetIds.contains(workbook.spreadsheetId())
        || !workbook.sheets().keySet().equals(TABLES)) reject();
    long characters = 0;
    for (String tab : TABLES) {
      Grid grid = workbook.sheets().get(tab), expected = manifest.baseline.sheets().get(tab);
      if (grid == null || grid.sheetId() != expected.sheetId() || grid.rowCount() != ROWS || grid.columnCount() != COLUMNS
          || grid.hidden() || grid.unsupportedMetadata() || grid.cells().size() != ROWS) reject();
      for (List<Cell> row : grid.cells()) {
        if (row.size() != COLUMNS) reject();
        for (Cell cell : row) {
          if (cell == null || cell.value() == null || cell.note() == null || !cell.note().isEmpty()
              || !Set.of("EMPTY", "TEXT").contains(cell.type()) || cell.value().length() > MAX_CELL_CHARS
              || cell.type().equals("EMPTY") && !cell.value().isEmpty()) reject();
          characters += cell.value().length();
          if (characters > MAX_TOTAL_CHARS) reject();
        }
      }
    }
  }

  static Workbook computeInMemoryCandidate(Manifest manifest, Clock clock) {
    MemoryTables memory = new MemoryTables(manifest.baseline);
    String localProof = UUID.randomUUID().toString();
    GoogleCredentialVerifier syntheticProof = (credential, audience, nonce) -> {
      if (!AUDIENCE.equals(audience) || !(localProof + ":" + nonce).equals(credential)) reject();
      return new GoogleCredentialVerifier.Principal(ISSUER, manifest.targetSubject);
    };
    // This enabled core only receives a private in-memory clone, never the whole-workbook gateway.
    var engine = new OperatorAccountErasure(memory, syntheticProof, AUDIENCE, clock, () -> true, true);
    var challenge = engine.challenge();
    var preview = engine.preview(localProof + ":" + challenge.nonce(), challenge.nonce());
    if (preview.affectedMatches() != 2 || preview.affectedSeats() != 2) reject();
    challenge = engine.challenge();
    if (engine.execute(preview.planId(), preview.beforeDigest(), preview.confirmationText(),
        localProof + ":" + challenge.nonce(), challenge.nonce()) != OperatorAccountErasure.Outcome.VERIFIED) reject();
    Map<String,Grid> result = new TreeMap<>(manifest.baseline.sheets());
    REPLACED.forEach(tab -> result.put(tab, grid(result.get(tab).sheetId(), memory.tables.get(tab))));
    return new Workbook(manifest.sheetId, true, false, result);
  }

  static void verifyTransform(Manifest manifest, Workbook after) {
    MemoryTables beforeRows = new MemoryTables(manifest.baseline), afterRows = new MemoryTables(after);
    for (String tab : TABLES) if (!REPLACED.contains(tab))
      if (!manifest.baseline.sheets().get(tab).equals(after.sheets().get(tab))) reject();
    if (!beforeRows.read("accounts").stream().filter(row -> !row.get(0).equals(manifest.targetAccount)).toList().equals(afterRows.read("accounts"))) reject();
    if (!beforeRows.read("auth_identities").stream().filter(row -> !row.get(2).equals(manifest.targetAccount)).toList().equals(afterRows.read("auth_identities"))) reject();
    Set<List<String>> ledger = new HashSet<>(beforeRows.read("erasure_ledger"));
    ledger.add(List.of("ACCOUNT", ErasureLedger.digest("ACCOUNT", manifest.targetAccount), "1"));
    ledger.add(List.of("IDENTITY", ErasureLedger.digest("IDENTITY", ErasureLedger.identityKey(ISSUER, manifest.targetSubject)), "1"));
    if (!ledger.equals(new HashSet<>(afterRows.read("erasure_ledger"))) || ledger.size() != afterRows.read("erasure_ledger").size()) reject();
    var old = SheetsStatsStore.readVerified(beforeRows);
    var now = SheetsStatsStore.readVerified(afterRows);
    if (!old.keySet().equals(now.keySet()) || old.size() != 3) reject();
    Set<String> markers = new HashSet<>(); int changed = 0;
    for (String commit : old.keySet()) {
      var original = old.get(commit); var replacement = now.get(commit);
      JsonNode a = original.payload(), b = replacement.payload();
      if (!a.path("match").equals(b.path("match")) || !a.path("completed_deals").equals(b.path("completed_deals"))) reject();
      boolean affected = false;
      for (int seat = 0; seat < 4; seat++) {
        JsonNode prior = a.path("participants").get(seat), current = b.path("participants").get(seat);
        if (manifest.targetAccount.equals(prior.path("account_id").asText())) {
          String marker = current.path("erased_marker").asText();
          if (!UUID.fromString(marker).toString().equals(marker) || !markers.add(marker)) reject();
          ObjectNode expected = (ObjectNode) prior.deepCopy();
          expected.putNull("account_id"); expected.putNull("guest_id"); expected.put("erased_marker", marker);
          if (!expected.equals(current)) reject();
          affected = true;
        } else if (!prior.equals(current)) reject();
      }
      if (affected) { if (original.payloadHash().equals(replacement.payloadHash())) reject(); changed++; }
      else if (!original.equals(replacement)) reject();
      if (!FinalMatchEnvelope.hash(replacement.canonicalJson()).equals(replacement.payloadHash())) reject();
    }
    if (changed != 2 || markers.size() != 2 || afterRows.tables.toString().contains(manifest.targetAccount)
        || afterRows.tables.toString().contains(manifest.targetSubject)) reject();
    // Validate residual tombstones against the exact old synthetic identities/envelopes.
    ErasureLedger blocked = new ErasureLedger(afterRows.read("erasure_ledger"));
    if (!blocked.contains("ACCOUNT", manifest.targetAccount)
        || !blocked.contains("IDENTITY", ErasureLedger.identityKey(ISSUER, manifest.targetSubject))) reject();
    int rejected = 0;
    for (var prior : old.values()) try { blocked.requireAllowed(prior); } catch (SheetsGateway.Conflict expected) { rejected++; }
    if (rejected != 2) reject();
  }

  private static Workbook generateWorkbook(Manifest manifest, Instant now) {
    Map<String,List<List<String>>> tables = new TreeMap<>();
    String dataset = manifest.datasetId, other = id(dataset, "account-other");
    tables.put("dataset", List.of(List.of("key", "value"), List.of("purpose", PURPOSE), List.of("dataset_id", dataset),
        List.of("fixture_version", "1"), List.of("fixture_origin", "GENERATED_ONLY_NO_REAL_IDENTITIES"), List.of("spreadsheet_id", manifest.sheetId)));
    tables.put("accounts", List.of(List.of("account_id", "schema_version"), List.of(manifest.targetAccount, "1"), List.of(other, "1")));
    tables.put("auth_identities", List.of(List.of("issuer", "subject", "account_id", "schema_version"),
        List.of(ISSUER, manifest.targetSubject, manifest.targetAccount, "1"),
        List.of(ISSUER, "SYNTHETIC-ONLY:" + dataset + ":other", other, "1")));
    tables.put("erasure_ledger", List.of(ErasureLedger.HEADER, ErasureLedger.SENTINEL));
    Instant boundary = QuarterlyRetention.currentQuarterStart(now);
    tables.put("retention_ledger", List.of(RetentionLedger.HEADER,
        List.of(QuarterlyRetention.quarter(boundary), boundary.toString(), "COMPLETE", now.toString(), "1")));
    tables.put("quarterly_aggregates", List.of(QuarterlyRetention.AGGREGATE_HEADER));
    tables.put("envelopes", new ArrayList<>(List.of(List.of("commit_id", "payload_hash", "chunk_index", "chunk_count", "canonical_json_chunk"))));
    tables.put("commits", new ArrayList<>(List.of(List.of("commit_id", "payload_hash", "chunk_count", "match_id", "revision"))));
    for (int n = 0; n < 3; n++) {
      String matchId = id(dataset, "match-" + n);
      List<Map<String,Object>> people = new ArrayList<>();
      for (int seat = 0; seat < 4; seat++) {
        String account = n < 2 && seat == 0 ? manifest.targetAccount : seat == 1 ? other : null;
        var person = new LinkedHashMap<String,Object>();
        person.put("match_id", matchId); person.put("seat", seat); person.put("team_id", seat % 2 == 0 ? "NS" : "EW");
        person.put("player_type", "HUMAN"); person.put("control_type", "HUMAN"); person.put("account_id", account);
        person.put("guest_id", account == null ? id(dataset, "guest-" + n + "-" + seat) : null); people.add(person);
      }
      var match = new LinkedHashMap<String,Object>();
      match.put("match_id", matchId); match.put("revision", 1); match.put("schema_version", 1);
      match.put("score_version", PURPOSE); match.put("app_version", PURPOSE); match.put("started_at", now.minusSeconds(30).toString());
      match.put("finished_at", now.toString()); match.put("completed_deals", 1); match.put("ns_total", 7); match.put("ew_total", -3);
      var deal = Map.<String,Object>of("match_id", matchId, "deal_id", id(dataset, "deal-" + n), "deal_index", 1, "ns_total", 7, "ew_total", -3);
      var envelope = FinalMatchEnvelope.create(matchId, 1, Map.of("match", match, "participants", people, "completed_deals", List.of(deal)));
      tables.get("envelopes").add(List.of(envelope.commitId(), envelope.payloadHash(), "0", "1", envelope.canonicalJson()));
      tables.get("commits").add(List.of(envelope.commitId(), envelope.payloadHash(), "1", matchId, "1"));
    }
    Map<String,Grid> grids = new TreeMap<>(); int sheetId = 100;
    for (var entry : tables.entrySet()) grids.put(entry.getKey(), grid(sheetId++, entry.getValue()));
    return new Workbook(manifest.sheetId, true, false, grids);
  }

  static Grid grid(int sheetId, List<List<String>> data) {
    if (data.size() > ROWS) reject();
    List<List<Cell>> cells = new ArrayList<>();
    for (int row = 0; row < ROWS; row++) {
      List<String> values = row < data.size() ? data.get(row) : List.of();
      if (values.size() > COLUMNS) reject();
      List<Cell> line = new ArrayList<>();
      for (int col = 0; col < COLUMNS; col++) {
        String value = col < values.size() ? values.get(col) : "";
        line.add(value.isEmpty() ? EMPTY : new Cell("TEXT", value, ""));
      }
      cells.add(line);
    }
    return new Grid(sheetId, ROWS, COLUMNS, false, false, cells);
  }
  private static final class MemoryTables implements SheetsGateway {
    final Map<String,List<List<String>>> tables = new TreeMap<>();
    MemoryTables(Workbook workbook) {
      workbook.sheets().forEach((name, grid) -> {
        List<List<String>> rows = new ArrayList<>();
        for (List<Cell> cells : grid.cells()) {
          List<String> row = new ArrayList<>(cells.stream().map(Cell::value).toList());
          while (!row.isEmpty() && row.get(row.size() - 1).isEmpty()) row.remove(row.size() - 1);
          rows.add(row);
        }
        while (!rows.isEmpty() && rows.get(rows.size() - 1).isEmpty()) rows.remove(rows.size() - 1);
        tables.put(name, rows);
      });
    }
    public List<List<String>> read(String tab) { return tables.get(tab).stream().map(List::copyOf).toList(); }
    public void appendAtomic(Map<String,List<List<Object>>> ignored) { throw new UnsupportedOperationException(); }
    public void replaceAtomic(Map<String,List<List<Object>>> rows) {
      if (!rows.keySet().equals(REPLACED)) reject();
      rows.forEach((tab, values) -> tables.put(tab, values.stream().map(row -> row.stream().map(Object::toString).toList()).toList()));
    }
  }
  static String fingerprint(Workbook workbook) {
    try { return FinalMatchEnvelope.hash(JSON.writeValueAsString(workbook)); }
    catch (Exception invalid) { throw new IllegalStateException("Synthetic workbook rejected"); }
  }
  private static String id(String dataset, String kind) {
    return UUID.nameUUIDFromBytes((PURPOSE + ":" + dataset + ":" + kind).getBytes(StandardCharsets.UTF_8)).toString();
  }
  private static void reject() { throw new IllegalStateException("Synthetic workbook or authorization binding rejected"); }
}
