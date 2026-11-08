package com.bridge.persistence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Fixed in-memory demonstration of the reusable operator API; no live binding/credentials/network. */
public final class LocalManualQuarterRehearsal {
  private LocalManualQuarterRehearsal() {}
  public static void main(String[] args) {
    int result = run(args, System.getenv().keySet(), System.getProperties().stringPropertyNames(),
        new InputStreamReader(System.in, StandardCharsets.UTF_8), System.out);
    if (result != 0) System.exit(result);
  }
  static int run(String[] args, Set<String> envNames, Set<String> propertyNames, Reader input, PrintStream output) {
    try {
      if (args == null || args.length != 0) throw new IllegalArgumentException();
      for (Set<String> names : List.of(envNames, propertyNames)) for (String name : names) {
        String key = name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        if (List.of("BRIDGE_", "GOOGLE_", "SPRING_", "SERVER_", "MANAGEMENT_", "LOADER_", "JAVAX_NET_SSL_")
            .stream().anyMatch(key::startsWith) || Set.of("PUBLIC_ORIGIN", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS", "JAVA_OPTS", "CLASSPATH", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "HTTP_PROXYHOST",
            "HTTPS_PROXYHOST", "SOCKSPROXYHOST", "JAVA_NET_USESYSTEMPROXIES", "JAVA_SECURITY_MANAGER",
            "JAVA_SECURITY_POLICY").contains(key)) throw new IllegalArgumentException();
      }
    } catch (RuntimeException bad) {
      output.println("REJECTED: no arguments or external configuration overrides accepted. No values displayed."); return 2;
    }
    try {
      Fixture fixture = new Fixture();
      output.println("MEMORY-ONLY MANUAL QUARTER REHEARSAL. No Google, key files, network or persistent changes.");
      output.println("Fixed virtual clock: 2026-11-06T08:00:00Z. Two active accounts, two identities, two erasure tombstones.");
      output.println("First confirmation (only this fixed in-memory target):");
      output.println(fixture.binding.stoppedConfirmation()); output.flush();
      String first = line(input);
      if (!fixture.binding.stoppedConfirmation().equals(first)) { output.println("CANCELLED: no storage read or rewrite."); return 2; }
      var service = new ManualQuarterMaintenance(fixture.binding, fixture, fixture.clock, () -> true, true);
      var plan = service.preview(first);
      output.println("PREVIEW: expired matches=" + plan.expiredCommits() + "; cutoff=" + plan.cutoff()
          + "; retain all accounts, identities, tombstones and current matches; aggregates SUPPRESSED.");
      output.println("Second confirmation (exact fresh plan, memory changes only):");
      output.println(plan.confirmationText()); output.flush();
      String second = line(input);
      if (!plan.confirmationText().equals(second)) { output.println("CANCELLED: preview only, no rewrite."); return 2; }
      var result = service.execute(plan.planId(), second);
      if (result.outcome() != ManualQuarterMaintenance.Outcome.COMPLETE) throw new IllegalStateException();
      fixture.verify();
      if (service.execute(plan.planId(), second).outcome() != ManualQuarterMaintenance.Outcome.COMPLETE
          || fixture.writes != 2) throw new IllegalStateException();
      output.println("RESULT=COMPLETE (memory only): one expired match removed; two current matches unchanged.");
      output.println("Accounts/identities/tombstones preserved byte-for-byte; old-match replay blocked and erasure hashes retained.");
      output.println("Two atomic phases verified; repeated execute performed read-only reconciliation, no additional write.");
      output.println("No live adapter/binding supplied. This does not verify real Sheets, writer exclusivity, backups or revision history.");
      return 0;
    } catch (Exception | LinkageError failure) {
      output.println("STOPPED: local rehearsal did not verify completion. No external data accessed; no failure details displayed."); return 1;
    }
  }
  private static String line(Reader input) throws IOException {
    StringBuilder value = new StringBuilder(); int next;
    while ((next = input.read()) != -1 && next != '\n') {
      if (value.length() >= 1024) return null; value.append((char)next);
    }
    if (!value.isEmpty() && value.charAt(value.length()-1) == '\r') value.setLength(value.length()-1);
    return value.toString();
  }

  // Fixed generated data only; never exposed as application auth or a real storage adapter.
  static final class Fixture implements ManualQuarterMaintenance.Storage {
    final Clock clock;
    final ManualQuarterMaintenance.Binding binding = new ManualQuarterMaintenance.Binding(
        "OFFLINE_MANUAL_QUARTER_MEMORY_ONLY", UUID.randomUUID().toString());
    Map<String,List<List<String>>> tables = new TreeMap<>();
    final Map<String,List<List<String>>> before;
    final String erasedAccount = UUID.randomUUID().toString(), erasedSubject = "SYNTHETIC-ERASED-ONLY";
    final FinalMatchEnvelope expired;
    final Map<String,FinalMatchEnvelope> retained = new TreeMap<>();
    int reads, writes;
    Fixture() { this(Instant.parse("2026-11-06T08:00:00Z")); }
    Fixture(Instant now) {
      clock = Clock.fixed(now, ZoneOffset.UTC);
      ManualQuarterMaintenance.HEADERS.forEach((tab, header) -> tables.put(tab, new ArrayList<>(List.of(header))));
      tables.get("dataset").add(List.of("bridgego-restricted-test", binding.datasetId()));
      String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
      tables.get("accounts").add(List.of(first, "1")); tables.get("accounts").add(List.of(second, "1"));
      tables.get("auth_identities").add(List.of("https://accounts.google.com", "SYNTHETIC-FIRST-ONLY", first, "1"));
      tables.get("auth_identities").add(List.of("https://accounts.google.com", "SYNTHETIC-SECOND-ONLY", second, "1"));
      tables.get("erasure_ledger").add(ErasureLedger.SENTINEL);
      tables.get("erasure_ledger").add(List.of("ACCOUNT", ErasureLedger.digest("ACCOUNT", erasedAccount), "1"));
      tables.get("erasure_ledger").add(List.of("IDENTITY", ErasureLedger.digest("IDENTITY",
          ErasureLedger.identityKey("https://accounts.google.com", erasedSubject)), "1"));
      Instant boundary = QuarterlyRetention.currentQuarterStart(clock.instant());
      expired = envelope(boundary.minusSeconds(60), boundary.minusNanos(1), first, second); add(expired);
      var crossing = envelope(boundary.minusSeconds(60), boundary, first, second); add(crossing);
      var current = envelope(clock.instant().minusSeconds(30), clock.instant(), first, second); add(current);
      retained.put(crossing.commitId(), crossing); retained.put(current.commitId(), current);
      before = ManualQuarterMaintenance.immutable(tables);
    }
    void add(FinalMatchEnvelope envelope) {
      tables.get("envelopes").add(List.of(envelope.commitId(), envelope.payloadHash(), "0", "1", envelope.canonicalJson()));
      tables.get("commits").add(List.of(envelope.commitId(), envelope.payloadHash(), "1", envelope.matchId(), "1"));
    }
    private FinalMatchEnvelope envelope(Instant start, Instant finish, String first, String second) {
      String matchId = UUID.randomUUID().toString();
      Map<String,Object> match = new LinkedHashMap<>();
      match.put("match_id", matchId); match.put("revision", 1); match.put("schema_version", 1);
      match.put("score_version", "bridgego-custom-v0.11-1"); match.put("app_version", "0.12.1");
      match.put("started_at", start.toString()); match.put("finished_at", finish.toString());
      match.put("completed_deals", 1); match.put("ns_total", 7); match.put("ew_total", -3);
      List<Map<String,Object>> participants = new ArrayList<>();
      for (int seat = 0; seat < 4; seat++) {
        Map<String,Object> person = new LinkedHashMap<>();
        person.put("match_id", matchId); person.put("seat", seat); person.put("team_id", seat % 2 == 0 ? "NS" : "EW");
        person.put("player_type", seat == 3 ? "BOT" : "HUMAN");
        person.put("control_type", seat == 3 ? "BOT" : "HUMAN");
        person.put("account_id", seat == 0 ? first : seat == 1 ? second : null); person.put("guest_id", null);
        if (seat == 2) person.put("erased_marker", UUID.randomUUID().toString());
        participants.add(person);
      }
      return FinalMatchEnvelope.create(matchId, 1, Map.of("match", match, "participants", participants,
          "completed_deals", List.of(Map.of("match_id", matchId, "deal_id", UUID.randomUUID().toString(),
              "deal_index", 1, "ns_total", 7, "ew_total", -3))));
    }
    public ManualQuarterMaintenance.Snapshot readComplete(ManualQuarterMaintenance.Binding target) {
      if (!binding.equals(target)) throw new IllegalStateException(); reads++;
      return new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(), true, tables);
    }
    public void replaceAtomic(ManualQuarterMaintenance.Binding target, String beforeDigest,
        Map<String,List<List<String>>> replacement) {
      if (!binding.equals(target) || !replacement.keySet().equals(ManualQuarterMaintenance.MUTABLE)
          || !ManualQuarterMaintenance.digest(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(), true, tables)).equals(beforeDigest))
        throw new IllegalStateException();
      writes++; replacement.forEach((tab, rows) -> tables.put(tab, new ArrayList<>(rows)));
    }
    void verify() {
      for (String tab : ManualQuarterMaintenance.PRESERVED)
        if (!before.get(tab).equals(tables.get(tab))) throw new IllegalStateException();
      if (!retained.equals(ManualQuarterMaintenance.decoded(tables))) throw new IllegalStateException();
      RetentionLedger retention = new RetentionLedger(tables.get("retention_ledger"));
      if (!retention.complete() || !retention.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant()))) throw new IllegalStateException();
      try { retention.requireAllowed(expired); throw new IllegalStateException(); }
      catch (SheetsGateway.Conflict expected) { /* stale raw data cannot return */ }
      ErasureLedger erasures = new ErasureLedger(tables.get("erasure_ledger"));
      if (!erasures.contains("ACCOUNT", erasedAccount) || !erasures.contains("IDENTITY",
          ErasureLedger.identityKey("https://accounts.google.com", erasedSubject))) throw new IllegalStateException();
      if (!tables.get("quarterly_aggregates").contains(List.of(QuarterlyRetention.quarter(
          QuarterlyRetention.finishedAt(expired)), "SUPPRESSED", "", "", "1"))) throw new IllegalStateException();
    }
  }
}
