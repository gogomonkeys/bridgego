package com.bridge.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit operator core. Not a Spring bean, credential reader, transport or public endpoint. */
public final class ManualQuarterMaintenance {
  private static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String,List<String>> HEADERS = Map.of(
      "dataset", List.of("purpose", "dataset_id"),
      "accounts", List.of("account_id", "schema_version"),
      "auth_identities", List.of("issuer", "subject", "account_id", "schema_version"),
      "envelopes", List.of("commit_id", "payload_hash", "chunk_index", "chunk_count", "canonical_json_chunk"),
      "commits", List.of("commit_id", "payload_hash", "chunk_count", "match_id", "revision"),
      "erasure_ledger", ErasureLedger.HEADER, "retention_ledger", RetentionLedger.HEADER,
      "quarterly_aggregates", QuarterlyRetention.AGGREGATE_HEADER);
  static final Set<String> MUTABLE = Set.of("accounts", "auth_identities", "envelopes", "commits",
      "erasure_ledger", "retention_ledger", "quarterly_aggregates");
  static final Set<String> PRESERVED = Set.of("dataset", "accounts", "auth_identities", "erasure_ledger");
  private static final Set<String> MATCH_FIELDS = Set.of("statistics_rule", "match_id", "revision", "schema_version", "score_version",
      "app_version", "started_at", "finished_at", "planned_deals", "completed_deals", "outcome", "winner_team_id",
      "reason", "ns_total", "ew_total");
  private static final Set<String> PERSON_FIELDS = Set.of("match_id", "seat", "team_id", "player_type",
      "control_type", "account_id", "guest_id", "erased_marker");
  private static final Set<String> DEAL_FIELDS = Set.of("match_id", "deal_id", "deal_index", "contract_type",
      "contract_level", "contract_strain", "attacking_team_id", "ns_tricks", "ew_tricks", "ns_privilege_used",
      "ns_base", "ns_exact_bonus", "ns_penalty", "ns_privilege_adjustment", "ns_total", "ew_privilege_used",
      "ew_base", "ew_exact_bonus", "ew_penalty", "ew_privilege_adjustment", "ew_total");

  /** Binding is an explicit input from an authorized operator host, never inferred from observed content. */
  public record Binding(String spreadsheetId, String datasetId, String purpose) {
    public Binding(String spreadsheetId, String datasetId) { this(spreadsheetId,datasetId,"bridgego-restricted-test"); }
    public Binding {
      if (spreadsheetId == null || !spreadsheetId.matches("[A-Za-z0-9_-]{10,160}")) reject();
      uuid(datasetId);
      if (purpose == null || !Set.of("bridgego-restricted-test","bridgego-managed").contains(purpose)) reject();
    }
    public String stoppedConfirmation() {
      return "STOPPED ALL WRITERS SHEET " + spreadsheetId + " DATASET " + datasetId;
    }
  }

  /**
   * Trusted adapter contract: complete enumerated bounded tables, literal strings only, known layout,
   * no extra populated ranges/tabs or unsupported metadata. A boolean alone is NOT Google verification.
   * Callers must not adapt an A:E-only read as complete; a separate adapter must enforce this contract.
   */
  public record Snapshot(String spreadsheetId, boolean completeKnownLayout, Map<String,List<List<String>>> tables) {
    public Snapshot { tables = immutable(tables); }
  }
  public interface Storage {
    Snapshot readComplete(Binding binding);
    /** One atomic seven-tab rewrite, no automatic retry. Digest is NOT a server-side lock or CAS. */
    void replaceAtomic(Binding binding, String expectedBeforeDigest, Map<String,List<List<String>>> sevenTables);
  }
  public record Preview(String planId, String spreadsheetId, String datasetId, String beforeDigest,
      Instant cutoff, Instant expiresAt, int expiredCommits, String confirmationText) {}
  public enum Outcome { DRY_RUN, COMPLETE, PENDING, NOT_APPLIED, UNCERTAIN }
  public record Result(Outcome outcome, Instant cutoff) {}

  private final Binding binding;
  private final Storage storage;
  private final Clock clock;
  private final BooleanSupplier writersStopped;
  private final boolean executionAuthorized;
  private Plan plan;
  private static final class Plan {
    final Preview view;
    final QuarterlyRetention engine;
    final QuarterlyRetention.Preview inner;
    final Guard guard;
    boolean attempted;
    Plan(Preview view, QuarterlyRetention engine, QuarterlyRetention.Preview inner, Guard guard) {
      this.view = view; this.engine = engine; this.inner = inner; this.guard = guard;
    }
  }

  /** Default is read-only preview, even if the operator supplies both exact confirmations. */
  public ManualQuarterMaintenance(Binding binding, Storage storage, Clock clock, BooleanSupplier writersStopped) {
    this(binding, storage, clock, writersStopped, false);
  }
  public ManualQuarterMaintenance(Binding binding, Storage storage, Clock clock, BooleanSupplier writersStopped,
      boolean executionAuthorized) {
    this.binding = Objects.requireNonNull(binding); this.storage = Objects.requireNonNull(storage);
    this.clock = Objects.requireNonNull(clock); this.writersStopped = Objects.requireNonNull(writersStopped);
    this.executionAuthorized = executionAuthorized;
  }

  public synchronized Preview preview(String exactStoppedConfirmation) {
    try {
      if (plan != null && plan.attempted) reject(); // investigate/reconcile before an explicitly new operator session
      if (!binding.stoppedConfirmation().equals(exactStoppedConfirmation)) reject();
      stopped();
      Guard guard = new Guard();
      var engine = new QuarterlyRetention(guard, clock, this::stillStopped, true,
          QuarterlyRetention.AggregatePolicy.suppressAll());
      var inner = engine.preview();
      String id = UUID.randomUUID().toString(), before = digest(guard.observed);
      Instant expiry = clock.instant().plusSeconds(300);
      String confirmation = "PURGE QUARTER " + QuarterlyRetention.quarter(inner.cutoff()) + " SHEET "
          + binding.spreadsheetId + " DATASET " + binding.datasetId + " PLAN " + id + " BEFORE " + before;
      Preview view = new Preview(id, binding.spreadsheetId, binding.datasetId, before, inner.cutoff(), expiry,
          inner.expiredCommits(), confirmation);
      guard.before = guard.observed; guard.cutoff = inner.cutoff(); guard.expiry = expiry;
      var retention = new RetentionLedger(guard.before.tables.get("retention_ledger"));
      if (retention.complete() && retention.cutoff().equals(inner.cutoff()) && inner.expiredCommits() == 0)
        guard.complete = guard.before; // The engine still rereads and checks the fresh no-op plan before returning.
      plan = new Plan(view, engine, inner, guard);
      return view;
    } catch (RuntimeException failure) { throw safeFailure(); }
  }

  public synchronized Result execute(String planId, String exactConfirmation) {
    Plan current = requirePlan(planId);
    if (!current.view.confirmationText.equals(exactConfirmation)) throw safeFailure();
    if (current.attempted) return inspect(planId); // never replay either phase of a prior attempt
    if (!executionAuthorized) return new Result(Outcome.DRY_RUN, current.view.cutoff);
    try {
      current.guard.window();
      current.attempted = true; // before storage reads/writes with potentially uncertain outcomes
      current.engine.execute(current.inner.planId(), current.inner.confirmationText());
      return inspect(planId);
    } catch (RuntimeException failure) {
      if (current.attempted) return inspect(planId);
      throw safeFailure();
    }
  }

  /** A preview hint only. Callers must still perform a fresh read-only inspection and honor expiry. */
  synchronized boolean alreadyComplete(String planId) {
    Plan current = requirePlan(planId);
    current.guard.window();
    return current.guard.complete != null && current.guard.complete.equals(current.guard.before);
  }

  /** Read-only reconciliation, including after expiry. Cannot finalize PENDING or retry a rewrite. */
  public synchronized Result inspect(String planId) {
    Plan current = requirePlan(planId);
    try {
      stopped(); Snapshot actual = read(); stopped();
      if (!QuarterlyRetention.currentQuarterStart(clock.instant()).equals(current.view.cutoff))
        return new Result(Outcome.UNCERTAIN, current.view.cutoff);
      if (actual.equals(current.guard.complete)) return new Result(Outcome.COMPLETE, current.view.cutoff);
      if (actual.equals(current.guard.pending)) return new Result(Outcome.PENDING, current.view.cutoff);
      if (actual.equals(current.guard.before)) return new Result(Outcome.NOT_APPLIED, current.view.cutoff);
      return new Result(Outcome.UNCERTAIN, current.view.cutoff);
    } catch (RuntimeException failure) { return new Result(Outcome.UNCERTAIN, current.view.cutoff); }
  }
  private Plan requirePlan(String id) {
    if (plan == null || !plan.view.planId.equals(id)) throw safeFailure();
    return plan;
  }
  private boolean stillStopped() { return writersStopped.getAsBoolean(); }
  private void stopped() { if (!stillStopped()) reject(); }
  private Snapshot read() {
    stopped(); Snapshot snapshot = storage.readComplete(binding); stopped();
    validate(snapshot, binding, clock); return snapshot;
  }

  /** Each core snapshot is one coherent whole-dataset read, never eight independent live tab reads. */
  private final class Guard implements SheetsGateway {
    Snapshot observed, before, pending, complete;
    final Set<String> cycle = new HashSet<>();
    Instant cutoff, expiry;
    int writes;
    public List<List<String>> read(String tab) {
      if (!HEADERS.containsKey(tab)) reject();
      if (cycle.isEmpty()) observed = ManualQuarterMaintenance.this.read();
      if (!cycle.add(tab)) reject();
      List<List<String>> rows = observed.tables.get(tab);
      if (cycle.size() == HEADERS.size()) cycle.clear();
      return rows;
    }
    public void appendAtomic(Map<String,List<List<Object>>> ignored) { throw safeFailure(); }
    void window() {
      stopped();
      if (!expiry.isAfter(clock.instant()) || !cutoff.equals(QuarterlyRetention.currentQuarterStart(clock.instant()))) reject();
    }
    public void replaceAtomic(Map<String,List<List<Object>>> replacement) {
      window();
      if (writes >= 2 || !replacement.keySet().equals(MUTABLE)) reject();
      Snapshot fresh = ManualQuarterMaintenance.this.read();
      if (!fresh.equals(observed)) reject();
      Map<String,List<List<String>>> tables = new TreeMap<>(fresh.tables);
      replacement.forEach((name, rows) -> tables.put(name, rows.stream().map(row -> row.stream().map(value -> {
        if (!(value instanceof String)) reject(); return (String)value;
      }).toList()).toList()));
      Snapshot candidate = new Snapshot(binding.spreadsheetId, true, tables);
      validate(candidate, binding, clock);
      for (String tab : PRESERVED) if (!before.tables.get(tab).equals(candidate.tables.get(tab))) reject();
      // Raw current-quarter payloads, scores and canonical hashes must remain byte-for-byte identical.
      Map<String,FinalMatchEnvelope> prior = decoded(before.tables), after = decoded(candidate.tables);
      Set<String> retained = new HashSet<>();
      prior.forEach((id, envelope) -> {
        if (!QuarterlyRetention.finishedAt(envelope).isBefore(cutoff)) {
          retained.add(id); if (!envelope.equals(after.get(id))) reject();
        }
      });
      if (!after.keySet().equals(retained)) reject();
      if (writes == 0) {
        RetentionLedger ledger = new RetentionLedger(candidate.tables.get("retention_ledger"));
        if (ledger.complete() || !ledger.cutoff().equals(cutoff)) reject();
        pending = candidate;
      } else {
        if (pending == null || !fresh.equals(pending)) reject();
        for (String tab : HEADERS.keySet()) if (!tab.equals("retention_ledger")
            && !pending.tables.get(tab).equals(candidate.tables.get(tab))) reject();
        RetentionLedger ledger = new RetentionLedger(candidate.tables.get("retention_ledger"));
        if (!ledger.complete() || !ledger.cutoff().equals(cutoff)) reject();
        complete = candidate;
      }
      window();
      writes++; // count the attempt before invoking a potentially non-returning/unknown external action
      Map<String,List<List<String>>> seven = new TreeMap<>();
      MUTABLE.forEach(tab -> seven.put(tab, candidate.tables.get(tab)));
      storage.replaceAtomic(binding, digest(fresh), Collections.unmodifiableMap(seven));
    }
  }

  static void validate(Snapshot snapshot, Binding binding, Clock clock) {
    if (snapshot == null || !snapshot.completeKnownLayout || !binding.spreadsheetId.equals(snapshot.spreadsheetId)
        || !snapshot.tables.keySet().equals(HEADERS.keySet())) reject();
    int total = 0;
    for (var header : HEADERS.entrySet()) {
      var rows = snapshot.tables.get(header.getKey());
      if (rows.isEmpty() || rows.size() > 1000 || !rows.get(0).equals(header.getValue())) reject();
      for (var row : rows.subList(1, rows.size()))
        if (!row.isEmpty() && row.get(0).equals(header.getValue().get(0))) reject();
      for (var row : rows) {
        if (row.size() != header.getValue().size()) reject();
        for (String value : row) {
          if (value == null || value.length() > 20000 || (total += value.length()) > 1_048_576) reject();
        }
      }
    }
    if (!snapshot.tables.get("dataset").equals(List.of(HEADERS.get("dataset"),
        List.of(binding.purpose, binding.datasetId)))) reject();
    var tombstones = snapshot.tables.get("erasure_ledger");
    if (new HashSet<>(tombstones).size() != tombstones.size()) reject();
    ErasureLedger erasures = new ErasureLedger(tombstones);
    Set<String> accounts = new HashSet<>(), identities = new HashSet<>();
    for (var row : data(snapshot, "accounts")) {
      uuid(row.get(0));
      if (!row.get(1).equals("1") || !accounts.add(row.get(0)) || erasures.contains("ACCOUNT", row.get(0))) reject();
    }
    for (var row : data(snapshot, "auth_identities")) {
      String key = ErasureLedger.identityKey(row.get(0), row.get(1));
      if (!row.get(0).equals("https://accounts.google.com") || row.get(1).isBlank() || row.get(1).length() > 255
          || !row.get(1).equals(row.get(1).trim()) || row.get(1).chars().anyMatch(Character::isISOControl)
          || !accounts.contains(row.get(2)) || !row.get(3).equals("1") || !identities.add(key)
          || erasures.contains("IDENTITY", key)) reject();
    }
    Set<String> markers = new HashSet<>(), chunks = new HashSet<>();
    for (var row : data(snapshot, "commits")) if (!markers.add(row.get(0))) reject();
    for (var row : data(snapshot, "envelopes")) if (!chunks.add(row.get(0) + ":" + row.get(2))) reject();
    RetentionLedger retention = new RetentionLedger(snapshot.tables.get("retention_ledger"));
    if (retention.cutoff().isAfter(QuarterlyRetention.currentQuarterStart(clock.instant()))) reject();
    for (var row : data(snapshot, "retention_ledger"))
      if (row.get(2).equals("COMPLETE") && Instant.parse(row.get(3)).isAfter(clock.instant())) reject();
    Set<String> quarters = new HashSet<>();
    for (var row : data(snapshot, "quarterly_aggregates")) {
      if (!row.get(0).matches("[0-9]{4}-Q[1-4]") || !quarters.add(row.get(0)) || !row.get(1).equals("SUPPRESSED")
          || !row.get(2).isEmpty() || !row.get(3).isEmpty() || !row.get(4).equals("1")) reject();
    }
    for (var envelope : decoded(snapshot.tables).values()) {
      retention.requireAllowed(envelope);
      var root = envelope.payload();
      Set<String> rootNames = new HashSet<>(); root.fieldNames().forEachRemaining(rootNames::add);
      if (!rootNames.equals(Set.of("match", "participants", "completed_deals"))) reject();
      var match = root.path("match"); fields(match, MATCH_FIELDS);
      typedScalars(match, Set.of("revision", "schema_version", "planned_deals", "completed_deals", "ns_total", "ew_total"), Set.of());
      if (!match.path("app_version").asText().equals("0.12.1")
          || !match.path("score_version").asText().equals("bridgego-custom-v0.11-1")) reject();
      Instant started = Instant.parse(match.path("started_at").asText()), finished = QuarterlyRetention.finishedAt(envelope);
      if (started.isAfter(finished) || finished.isAfter(clock.instant())) reject();
      for (JsonNode person : root.path("participants")) {
        fields(person, PERSON_FIELDS);
        typedScalars(person, Set.of("seat"), Set.of());
        if (!Set.of("HUMAN", "BOT").contains(person.path("player_type").asText())
            || !Set.of("HUMAN", "BOT", "MIXED").contains(person.path("control_type").asText())
            || !Set.of("NS", "EW").contains(person.path("team_id").asText())) reject();
        for (String key : List.of("account_id", "guest_id")) {
          var id = person.get(key);
          if (id == null || !id.isNull() && !id.isTextual()) reject();
          if (id.isTextual()) {
            uuid(id.asText());
            if (person.path("player_type").asText().equals("BOT")) reject();
            if (key.equals("account_id") && !accounts.contains(id.asText())) reject();
          }
        }
        if (person.has("erased_marker")) {
          uuid(person.path("erased_marker").asText());
          if (!person.path("account_id").isNull() || !person.path("guest_id").isNull()) reject();
        }
      }
      for (var deal : root.path("completed_deals")) {
        fields(deal, DEAL_FIELDS);
        typedScalars(deal, Set.of("deal_index", "contract_level", "ns_tricks", "ew_tricks", "ns_base", "ns_exact_bonus",
            "ns_penalty", "ns_privilege_adjustment", "ns_total", "ew_base", "ew_exact_bonus", "ew_penalty",
            "ew_privilege_adjustment", "ew_total"), Set.of("ns_privilege_used", "ew_privilege_used"));
      }
    }
  }
  private static void fields(JsonNode node, Set<String> allowed) {
    if (!node.isObject()) reject();
    node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) reject(); });
  }
  private static void typedScalars(JsonNode node, Set<String> integers, Set<String> booleans) {
    node.fields().forEachRemaining(entry -> {
      JsonNode value = entry.getValue();
      if (value.isNull()) return; // optional nullable fields remain nullable; required keys are checked by the core
      if (integers.contains(entry.getKey()) ? !value.isIntegralNumber()
          : booleans.contains(entry.getKey()) ? !value.isBoolean() : !value.isTextual()) reject();
    });
  }
  private static void uuid(String id) {
    try { if (id == null || !UUID.fromString(id).toString().equals(id)) reject(); }
    catch (RuntimeException bad) { throw safeFailure(); }
  }
  static Map<String,FinalMatchEnvelope> decoded(Map<String,List<List<String>>> tables) {
    return SheetsStatsStore.readVerified(new SheetsGateway() {
      public List<List<String>> read(String tab) { return tables.get(tab); }
      public void appendAtomic(Map<String,List<List<Object>>> ignored) { throw safeFailure(); }
    });
  }
  private static List<List<String>> data(Snapshot snapshot, String tab) {
    return snapshot.tables.get(tab).subList(1, snapshot.tables.get(tab).size());
  }
  static Map<String,List<List<String>>> immutable(Map<String,List<List<String>>> source) {
    var result = new TreeMap<String,List<List<String>>>();
    source.forEach((name, rows) -> result.put(name, rows.stream().map(List::copyOf).toList()));
    return Collections.unmodifiableMap(result);
  }
  static String digest(Snapshot snapshot) {
    try { return FinalMatchEnvelope.hash(JSON.writeValueAsString(snapshot)); }
    catch (Exception ignored) { throw safeFailure(); }
  }
  private static void reject() { throw safeFailure(); }
  private static IllegalStateException safeFailure() { return new IllegalStateException("Quarter maintenance input, authorization or storage rejected"); }
}
