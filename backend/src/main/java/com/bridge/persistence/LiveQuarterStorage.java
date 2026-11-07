package com.bridge.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Dedicated quarterly maintenance transport adapter. Never installed in the game Spring graph. */
final class LiveQuarterStorage implements ManualQuarterMaintenance.Storage, AutoCloseable {
  interface Transport extends AutoCloseable {
    byte[] readWorkbook();
    void replaceWorkbook(byte[] json);
    @Override default void close() {}
  }
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ManualQuarterMaintenance.Binding binding;
  private final Transport transport;
  private final Clock clock;
  private final BooleanSupplier stopped;
  private LiveQuarterWorkbook.Workbook observed;
  private String metadata;
  private ManualQuarterMaintenance.Preview authorization;
  private ManualQuarterMaintenance.Snapshot pending;
  private int writeAttempts;
  private boolean closed;

  LiveQuarterStorage(ManualQuarterMaintenance.Binding binding, Transport transport, Clock clock, BooleanSupplier stopped) {
    this.binding = Objects.requireNonNull(binding); this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock); this.stopped = Objects.requireNonNull(stopped);
  }
  @Override public synchronized ManualQuarterMaintenance.Snapshot readComplete(ManualQuarterMaintenance.Binding target) {
    try {
      checkTarget(target); requireStopped();
      var workbook = LiveQuarterWorkbook.parse(transport.readWorkbook(), binding, clock);
      requireStopped();
      if (metadata != null && !metadata.equals(workbook.metadataFingerprint())) reject();
      metadata = workbook.metadataFingerprint(); observed = workbook;
      return workbook.snapshot();
    } catch (RuntimeException failure) { throw safeFailure(); }
  }

  synchronized String fingerprint() { if (observed == null) throw safeFailure(); return observed.fingerprint(); }

  /** Called only after explicit operator confirmation and attempt marker creation by the CLI. */
  synchronized void authorize(ManualQuarterMaintenance.Preview plan, String expectedWorkbookFingerprint) {
    try {
      requireStopped();
      if (authorization != null || writeAttempts != 0 || observed == null || plan == null
          || !binding.spreadsheetId().equals(plan.spreadsheetId()) || !binding.datasetId().equals(plan.datasetId())
          || !ManualQuarterMaintenance.digest(observed.snapshot()).equals(plan.beforeDigest())
          || !observed.fingerprint().equals(expectedWorkbookFingerprint)
          || !plan.expiresAt().isAfter(clock.instant()) || plan.expiresAt().isAfter(clock.instant().plusSeconds(300))
          || !plan.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant()))) reject();
      authorization = plan;
    } catch (RuntimeException failure) { throw safeFailure(); }
  }

  @Override public synchronized void replaceAtomic(ManualQuarterMaintenance.Binding target, String beforeDigest,
      Map<String,List<List<String>>> sevenTables) {
    try {
      checkTarget(target); requireWindow();
      if (writeAttempts >= 2 || !sevenTables.keySet().equals(ManualQuarterMaintenance.MUTABLE)) reject();
      // Core already rereads; this extra read binds the actual request to fresh metadata/tab IDs too.
      ManualQuarterMaintenance.Snapshot fresh = readComplete(target);
      if (!ManualQuarterMaintenance.digest(fresh).equals(beforeDigest)) reject();
      Map<String,List<List<String>>> all = new TreeMap<>(fresh.tables()); all.putAll(sevenTables);
      var candidate = new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(), true, all);
      ManualQuarterMaintenance.validate(candidate, binding, clock);
      for (String tab : ManualQuarterMaintenance.PRESERVED)
        if (!fresh.tables().get(tab).equals(candidate.tables().get(tab))) reject();
      var ledger = new RetentionLedger(candidate.tables().get("retention_ledger"));
      if (!ledger.cutoff().equals(authorization.cutoff())) reject();
      if (writeAttempts == 0) {
        if (!beforeDigest.equals(authorization.beforeDigest()) || ledger.complete()) reject();
        // Independently verify only expired raw data is removed and current payloads stay exact.
        var prior = ManualQuarterMaintenance.decoded(fresh.tables());
        var after = ManualQuarterMaintenance.decoded(candidate.tables());
        Set<String> keep = new HashSet<>();
        prior.forEach((id, envelope) -> {
          if (!QuarterlyRetention.finishedAt(envelope).isBefore(authorization.cutoff())) {
            keep.add(id); if (!envelope.equals(after.get(id))) reject();
          }
        });
        if (!keep.equals(after.keySet())) reject();
        pending = candidate;
      } else {
        if (pending == null || !fresh.equals(pending) || !ledger.complete()) reject();
        for (String tab : ManualQuarterMaintenance.HEADERS.keySet())
          if (!tab.equals("retention_ledger") && !fresh.tables().get(tab).equals(candidate.tables().get(tab))) reject();
      }
      byte[] body = batch(candidate, observed.grids());
      requireWindow(); // include the final full read and serialization in the plan expiry window
      writeAttempts++;
      try { transport.replaceWorkbook(body); }
      finally { Arrays.fill(body, (byte)0); }
      // Response alone is never completion: ManualQuarterMaintenance performs complete readback.
    } catch (RuntimeException failure) { throw safeFailure(); }
  }

  private byte[] batch(ManualQuarterMaintenance.Snapshot candidate, Map<String,LiveQuarterWorkbook.Grid> grids) {
    try {
      List<Object> requests = new ArrayList<>();
      for (String tab : new TreeSet<>(ManualQuarterMaintenance.MUTABLE)) {
        var grid = grids.get(tab); var rows = candidate.tables().get(tab);
        int endColumn = Math.min(5, grid.columnCount());
        if (rows.size() > grid.rowCount() || endColumn < ManualQuarterMaintenance.HEADERS.get(tab).size()) reject();
        List<Object> data = new ArrayList<>();
        for (var row : rows) {
          List<Object> values = new ArrayList<>();
          for (String value : row)
            values.add(value.isEmpty() ? Map.of() : Map.of("userEnteredValue", Map.of("stringValue", value)));
          data.add(Map.of("values", values));
        }
        // Missing trailing rows/cells clear only userEnteredValue inside this explicit validated rectangle.
        requests.add(Map.of("updateCells", Map.of("range", Map.of("sheetId", grid.sheetId(),
            "startRowIndex", 0, "endRowIndex", grid.rowCount(), "startColumnIndex", 0, "endColumnIndex", endColumn),
            "rows", data, "fields", "userEnteredValue")));
      }
      byte[] result = JSON.writeValueAsBytes(Map.of("requests", requests));
      if (result.length > LiveQuarterWorkbook.MAX_RESPONSE_BYTES) reject();
      return result;
    } catch (Exception failure) { throw safeFailure(); }
  }
  private void checkTarget(ManualQuarterMaintenance.Binding target) { if (closed || !binding.equals(target)) reject(); }
  private void requireStopped() { if (closed || !stopped.getAsBoolean()) reject(); }
  private void requireWindow() {
    requireStopped();
    if (authorization == null || !authorization.expiresAt().isAfter(clock.instant())
        || !authorization.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant()))) reject();
  }
  @Override public synchronized void close() {
    closed = true;
    try { transport.close(); } catch (RuntimeException ignored) { /* never log transport/private details */ }
  }
  private static void reject() { throw safeFailure(); }
  private static IllegalStateException safeFailure() { return new IllegalStateException("Quarter storage rejected or unavailable"); }
}
