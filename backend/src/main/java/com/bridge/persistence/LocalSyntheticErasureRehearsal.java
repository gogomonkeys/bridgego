package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated memory-only rehearsal entry, excluded from normal application/operator JARs.
 * No Spring, configuration loading, external fixtures, file IO, credentials or HTTP gateway.
 * The private synthetic proof below is not Google ownership verification.
 */
public final class LocalSyntheticErasureRehearsal {
  private static final String ISSUER = "https://accounts.google.com";
  private static final String AUDIENCE = "local-synthetic-rehearsal-only";
  private static final String OWNER = "synthetic-erasure-owner";
  private static final String OTHER = "synthetic-other-owner";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> BLOCKED_PREFIXES = List.of(
      "BRIDGE_", "GOOGLE_", "SPRING_", "SERVER_", "MANAGEMENT_", "LOADER_", "JAVAX_NET_SSL_");
  private static final Set<String> BLOCKED_KEYS = Set.of(
      "PUBLIC_ORIGIN", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "CLASSPATH",
      "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "HTTP_PROXYHOST", "HTTP_PROXYPORT",
      "HTTPS_PROXYHOST", "HTTPS_PROXYPORT", "SOCKSPROXYHOST", "SOCKSPROXYPORT",
      "JAVA_NET_USESYSTEMPROXIES", "JAVA_SECURITY_MANAGER", "JAVA_SECURITY_POLICY");

  private LocalSyntheticErasureRehearsal() {}

  public static void main(String[] args) {
    // Inspect names only. Never read or print configuration/credential values.
    int exit = run(args, System.getenv().keySet(), System.getProperties().stringPropertyNames(),
        new InputStreamReader(System.in, StandardCharsets.UTF_8), System.out);
    if (exit != 0) System.exit(exit);
  }

  static int run(String[] args, Set<String> environmentKeys, Set<String> propertyKeys,
      Reader input, PrintStream output) {
    try {
      validateLaunch(args, environmentKeys, propertyKeys);
    } catch (IllegalArgumentException rejected) {
      output.println("REJECTED: no arguments or application/external-resource overrides are allowed.");
      output.println("No fixture was created. No values are displayed.");
      return 2;
    }
    try {
      Session session = new Session(Clock.systemUTC(), Fault.NONE);
      var preview = session.preview();
      output.println("LOCAL SYNTHETIC ERASURE REHEARSAL — MEMORY ONLY");
      output.println("No Google login, files, credentials, network gateway or persistent storage is used.");
      output.printf("PREVIEW: affected matches=%d, target seats=%d; unrelated seats and scores must remain unchanged.%n",
          preview.affectedMatches(), preview.affectedSeats());
      output.println("To change only this generated in-memory fixture, enter this exact line:");
      output.println(preview.confirmationText());
      output.flush();
      String confirmation = readConfirmation(input);
      if (!preview.confirmationText().equals(confirmation)) {
        output.println("CANCELLED: exact confirmation was not received; fixture is unchanged.");
        return 2;
      }
      var result = session.execute(confirmation);
      require(result.outcome() == OperatorAccountErasure.Outcome.VERIFIED);
      int scenarios = checkSafetyScenarios();
      output.printf("RESULT=VERIFIED (memory only): target seats cleared=%d, hashes recomputed=%d, unchanged matches=%d.%n",
          result.clearedSeats(), result.rehashedMatches(), result.unchangedMatches());
      output.printf("Other seats and scores preserved; identity and old-match tombstone replay rejected. Safety scenarios=%d PASS.%n", scenarios);
      output.println("NOT_APPLIED and UNCERTAIN simulations did not retry a replacement.");
      output.println("Exit discards all fixtures. This does not verify real Google ownership, Sheets atomicity, revision history or backups.");
      return 0;
    } catch (Exception | LinkageError failed) {
      // Never print exception bodies, submitted input, plan data, identifiers or credentials.
      output.println("STOPPED: a rehearsal check did not complete. No external data was accessed.");
      return 1;
    }
  }

  static void validateLaunch(String[] args, Set<String> environmentKeys, Set<String> propertyKeys) {
    if (args == null || args.length != 0) throw new IllegalArgumentException("Arguments are disabled");
    for (Set<String> keys : List.of(environmentKeys, propertyKeys)) {
      for (String key : keys) {
        String normalized = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        if (BLOCKED_KEYS.contains(normalized) || BLOCKED_PREFIXES.stream().anyMatch(normalized::startsWith))
          throw new IllegalArgumentException("External overrides are disabled");
      }
    }
  }

  private static String readConfirmation(Reader input) throws IOException {
    StringBuilder line = new StringBuilder();
    int next;
    while ((next = input.read()) != -1 && next != '\n') {
      if (line.length() >= 256) return null;
      line.append((char) next);
    }
    if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
    return line.toString(); // no trim: spaces or extra text are not exact confirmation
  }

  record Result(OperatorAccountErasure.Outcome outcome, int clearedSeats, int rehashedMatches, int unchangedMatches) {}

  // Package-private interfaces exist solely for offline tests; the CLI accepts no fixture/proof inputs.
  static final class Session {
    private final Fixture fixture;
    private final OperatorAccountErasure service;
    private final Map<String,List<List<String>>> before;
    private final OperatorAccountErasure.Preview preview;

    Session(Clock clock, Fault fault) {
      fixture = new Fixture(clock);
      service = fixture.service(true);
      before = fixture.memory.snapshot();
      preview = fixture.preview(service);
      require(fixture.memory.replacements == 0 && before.equals(fixture.memory.snapshot()));
      fixture.memory.fault = fault;
    }

    OperatorAccountErasure.Preview preview() { return preview; }
    Map<String,List<List<String>>> snapshot() { return fixture.memory.snapshot(); }
    int replacements() { return fixture.memory.replacements; }

    Result execute(String confirmation) throws Exception {
      var outcome = fixture.execute(service, preview, OWNER, confirmation);
      if (outcome != OperatorAccountErasure.Outcome.VERIFIED) return new Result(outcome, 0, 0, 0);
      var verified = verifyErasure(fixture, before);
      verifyReplayRejected(fixture);
      return verified;
    }
  }

  enum Fault { NONE, BEFORE_WRITE, TIMEOUT_AFTER_WRITE, READBACK_UNAVAILABLE }

  /** These are deliberately separate generated fixtures, never a live/application gateway. */
  static int checkSafetyScenarios() throws Exception {
    Clock clock = Clock.systemUTC();
    int checks = 0;
    Fixture wrong = new Fixture(clock);
    var service = wrong.service(true);
    var plan = wrong.preview(service);
    expectRejected(() -> wrong.execute(service, plan, OTHER, plan.confirmationText()));
    require(wrong.memory.replacements == 0); checks++;
    expectRejected(() -> wrong.execute(service, plan, OWNER, "yes"));
    require(wrong.memory.replacements == 0); checks++;

    Fixture stale = new Fixture(clock);
    var staleService = stale.service(true);
    var stalePlan = stale.preview(staleService);
    stale.memory.tabs.get("accounts").add(List.of(UUID.randomUUID().toString(), "1"));
    var changed = stale.memory.snapshot();
    expectRejected(() -> stale.execute(staleService, stalePlan, OWNER, stalePlan.confirmationText()));
    require(stale.memory.replacements == 0 && changed.equals(stale.memory.snapshot())); checks++;

    MutableClock moving = new MutableClock(clock.instant());
    Fixture expired = new Fixture(moving);
    var expiredService = expired.service(true);
    var expiredPlan = expired.preview(expiredService);
    moving.now = moving.now.plusSeconds(301);
    expectRejected(() -> expired.execute(expiredService, expiredPlan, OWNER, expiredPlan.confirmationText()));
    require(expired.memory.replacements == 0); checks++;

    Fixture dry = new Fixture(clock);
    var dryService = dry.service(false);
    var dryPlan = dry.preview(dryService);
    expectRejected(() -> dry.execute(dryService, dryPlan, OWNER, dryPlan.confirmationText()));
    require(dry.memory.replacements == 0); checks++;

    Session notApplied = new Session(clock, Fault.BEFORE_WRITE);
    var original = notApplied.snapshot();
    require(notApplied.execute(notApplied.preview().confirmationText()).outcome() == OperatorAccountErasure.Outcome.NOT_APPLIED);
    require(notApplied.replacements() == 1 && original.equals(notApplied.snapshot())); checks++;

    Session timeout = new Session(clock, Fault.TIMEOUT_AFTER_WRITE);
    require(timeout.execute(timeout.preview().confirmationText()).outcome() == OperatorAccountErasure.Outcome.VERIFIED);
    require(timeout.replacements() == 1); checks++;

    Session uncertain = new Session(clock, Fault.READBACK_UNAVAILABLE);
    require(uncertain.execute(uncertain.preview().confirmationText()).outcome() == OperatorAccountErasure.Outcome.UNCERTAIN);
    require(uncertain.replacements() == 1); checks++;
    return checks;
  }

  private static Result verifyErasure(Fixture fixture, Map<String,List<List<String>>> before) throws Exception {
    var after = fixture.memory.snapshot();
    require(before.get("dataset").equals(after.get("dataset")));
    require(before.get("retention_ledger").equals(after.get("retention_ledger")));
    require(before.get("accounts").stream().filter(row -> !row.get(0).equals(fixture.targetAccount)).toList().equals(after.get("accounts")));
    require(before.get("auth_identities").stream().filter(row -> !row.get(2).equals(fixture.targetAccount)).toList().equals(after.get("auth_identities")));
    require(!after.toString().contains(fixture.targetAccount) && !after.toString().contains(OWNER));
    Set<List<String>> expectedLedger = new HashSet<>(before.get("erasure_ledger"));
    expectedLedger.add(List.of("ACCOUNT", sha256("bridge-erasure-v1\u0000ACCOUNT\u0000" + fixture.targetAccount), "1"));
    expectedLedger.add(List.of("IDENTITY", sha256("bridge-erasure-v1\u0000IDENTITY\u0000" + ISSUER + "\u0000" + OWNER), "1"));
    require(expectedLedger.equals(new HashSet<>(after.get("erasure_ledger"))) && expectedLedger.size() == after.get("erasure_ledger").size());

    var observed = SheetsStatsStore.readVerified(fixture.memory);
    require(observed.size() == fixture.originals.size());
    Set<String> markers = new HashSet<>();
    int changed = 0, unchanged = 0, seats = 0;
    for (FinalMatchEnvelope old : fixture.originals) {
      var current = observed.get(old.commitId());
      require(current != null && current.matchId().equals(old.matchId()) && current.revision() == old.revision());
      require(sha256(current.canonicalJson()).equals(current.payloadHash()));
      JsonNode original = old.payload(), erased = current.payload();
      require(original.path("match").equals(erased.path("match")));
      require(original.path("completed_deals").equals(erased.path("completed_deals")));
      boolean touched = false;
      for (int seat = 0; seat < 4; seat++) {
        JsonNode was = original.path("participants").get(seat), now = erased.path("participants").get(seat);
        if (fixture.targetAccount.equals(was.path("account_id").asText())) {
          String marker = now.path("erased_marker").asText();
          require(UUID.fromString(marker).toString().equals(marker) && markers.add(marker));
          ObjectNode expected = (ObjectNode) was.deepCopy();
          expected.putNull("account_id"); expected.putNull("guest_id"); expected.put("erased_marker", marker);
          require(expected.equals(now)); seats++; touched = true;
        } else require(was.equals(now));
      }
      if (touched) { require(!old.payloadHash().equals(current.payloadHash())); changed++; }
      else { require(old.equals(current)); unchanged++; }
    }
    require(changed == 2 && unchanged == 1 && seats == 2);
    return new Result(OperatorAccountErasure.Outcome.VERIFIED, seats, changed, unchanged);
  }

  private static void verifyReplayRejected(Fixture fixture) throws Exception {
    int writes = fixture.memory.appends;
    boolean rejected = false;
    try { new SheetsIdentityStore(fixture.memory, fixture.clock).findOrCreateAccount(ISSUER, OWNER); }
    catch (SheetsGateway.Conflict expected) { rejected = true; }
    require(rejected && writes == fixture.memory.appends);
    var old = fixture.originals.get(0);
    ObjectNode renamed = (ObjectNode) old.payload();
    String newId = UUID.randomUUID().toString();
    ((ObjectNode) renamed.path("match")).put("match_id", newId);
    for (JsonNode person : renamed.path("participants")) ((ObjectNode) person).put("match_id", newId);
    for (JsonNode deal : renamed.path("completed_deals")) ((ObjectNode) deal).put("match_id", newId);
    var replay = FinalMatchEnvelope.create(newId, 1, JSON.convertValue(renamed, Map.class));
    try (var store = new SheetsStatsStore(fixture.memory, 2, fixture.clock)) {
      for (var envelope : List.of(old, replay)) {
        store.submit(envelope);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (store.status(envelope.commitId()) == StatsStore.SaveStatus.PENDING && System.nanoTime() < end) Thread.sleep(5);
        require(store.status(envelope.commitId()) == StatsStore.SaveStatus.CONFLICT);
      }
    }
    require(writes == fixture.memory.appends && SheetsStatsStore.readVerified(fixture.memory).size() == 3);
  }

  private static final class Fixture {
    final Clock clock;
    final MemorySheets memory = new MemorySheets();
    final String targetAccount = UUID.randomUUID().toString();
    final String otherAccount = UUID.randomUUID().toString();
    final List<FinalMatchEnvelope> originals = new ArrayList<>();
    private final String proofSecret = UUID.randomUUID().toString();
    private final GoogleCredentialVerifier proof = (credential, audience, nonce) -> {
      if (!AUDIENCE.equals(audience)) throw new IllegalArgumentException();
      for (String subject : List.of(OWNER, OTHER))
        if (Objects.equals(credential, proof(subject, nonce))) return new GoogleCredentialVerifier.Principal(ISSUER, subject);
      throw new IllegalArgumentException();
    };

    Fixture(Clock clock) {
      this.clock = clock;
      Instant now = clock.instant(), quarter = QuarterlyRetention.currentQuarterStart(now);
      memory.tabs.put("dataset", new ArrayList<>(List.of(List.of("purpose", "dataset_id"),
          List.of("bridgego-local-synthetic-rehearsal", UUID.randomUUID().toString()))));
      memory.tabs.put("retention_ledger", new ArrayList<>(List.of(RetentionLedger.HEADER,
          List.of(QuarterlyRetention.quarter(quarter), quarter.toString(), "COMPLETE", now.toString(), "1"))));
      memory.tabs.put("accounts", new ArrayList<>(List.of(List.of("account_id", "schema_version"),
          List.of(targetAccount, "1"), List.of(otherAccount, "1"))));
      memory.tabs.put("auth_identities", new ArrayList<>(List.of(List.of("issuer", "subject", "account_id", "schema_version"),
          List.of(ISSUER, OWNER, targetAccount, "1"), List.of(ISSUER, OTHER, otherAccount, "1"))));
      memory.tabs.put("erasure_ledger", new ArrayList<>(List.of(ErasureLedger.HEADER, ErasureLedger.SENTINEL)));
      memory.tabs.put("envelopes", new ArrayList<>(List.of(List.of("commit_id", "payload_hash", "chunk_index", "chunk_count", "canonical_json_chunk"))));
      memory.tabs.put("commits", new ArrayList<>(List.of(List.of("commit_id", "payload_hash", "chunk_count", "match_id", "revision"))));
      for (int index = 0; index < 3; index++) {
        String matchId = UUID.randomUUID().toString();
        List<Map<String,Object>> people = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
          boolean target = index < 2 && seat == index * 2;
          String account = target ? targetAccount : seat == 1 ? otherAccount : null;
          var person = new LinkedHashMap<String,Object>();
          person.put("match_id", matchId); person.put("seat", seat); person.put("team_id", seat % 2 == 0 ? "NS" : "EW");
          person.put("player_type", "HUMAN"); person.put("control_type", "HUMAN");
          person.put("account_id", account); person.put("guest_id", account == null ? UUID.randomUUID().toString() : null);
          people.add(person);
        }
        var match = new LinkedHashMap<String,Object>();
        match.put("match_id", matchId); match.put("revision", 1); match.put("schema_version", 1);
        match.put("score_version", "synthetic-rehearsal-1"); match.put("app_version", "synthetic-rehearsal");
        match.put("started_at", now.minusSeconds(30).toString()); match.put("finished_at", now.toString());
        match.put("planned_deals", 1); match.put("completed_deals", 1); match.put("ns_total", 7); match.put("ew_total", -3);
        var deal = Map.<String,Object>of("match_id", matchId, "deal_id", UUID.randomUUID().toString(),
            "deal_index", 1, "ns_total", 7, "ew_total", -3);
        var envelope = FinalMatchEnvelope.create(matchId, 1, Map.of("match", match, "participants", people, "completed_deals", List.of(deal)));
        originals.add(envelope);
        memory.tabs.get("envelopes").add(List.of(envelope.commitId(), envelope.payloadHash(), "0", "1", envelope.canonicalJson()));
        memory.tabs.get("commits").add(List.of(envelope.commitId(), envelope.payloadHash(), "1", matchId, "1"));
      }
    }

    private String proof(String subject, String nonce) { return proofSecret + ":" + subject + ":" + nonce; }
    OperatorAccountErasure service(boolean enabled) {
      return new OperatorAccountErasure(memory, proof, AUDIENCE, clock, () -> true, enabled);
    }
    OperatorAccountErasure.Preview preview(OperatorAccountErasure service) {
      var challenge = service.challenge();
      return service.preview(proof(OWNER, challenge.nonce()), challenge.nonce());
    }
    OperatorAccountErasure.Outcome execute(OperatorAccountErasure service, OperatorAccountErasure.Preview plan,
        String owner, String confirmation) {
      var challenge = service.challenge();
      return service.execute(plan.planId(), plan.beforeDigest(), confirmation, proof(owner, challenge.nonce()), challenge.nonce());
    }
  }

  private static final class MemorySheets implements SheetsGateway {
    private final Map<String,List<List<String>>> tabs = new TreeMap<>();
    private int replacements, appends;
    private Fault fault = Fault.NONE;
    private boolean unavailable;
    @Override public synchronized List<List<String>> read(String tab) {
      if (unavailable) throw new Failure(503, 0);
      if (!tabs.containsKey(tab)) throw new Conflict();
      return tabs.get(tab).stream().map(List::copyOf).toList();
    }
    synchronized Map<String,List<List<String>>> snapshot() {
      Map<String,List<List<String>>> copy = new TreeMap<>();
      tabs.forEach((tab, rows) -> copy.put(tab, rows.stream().map(List::copyOf).toList()));
      return Collections.unmodifiableMap(copy);
    }
    @Override public synchronized void appendAtomic(Map<String,List<List<Object>>> rows) {
      // Only erased-identity/match replay checks use this path, and must reject before any append.
      appends++;
      throw new IllegalStateException("Unexpected synthetic append");
    }
    @Override public synchronized void replaceAtomic(Map<String,List<List<Object>>> rows) {
      replacements++;
      if (!rows.keySet().equals(Set.of("accounts", "auth_identities", "envelopes", "commits", "erasure_ledger"))) throw new Conflict();
      if (fault == Fault.BEFORE_WRITE) throw new Failure(503, 0);
      Map<String,List<List<String>>> replacement = new TreeMap<>();
      rows.forEach((tab, values) -> replacement.put(tab, new ArrayList<>(values.stream()
          .map(row -> row.stream().map(Object::toString).toList()).toList())));
      tabs.putAll(replacement);
      if (fault == Fault.READBACK_UNAVAILABLE) unavailable = true;
      if (fault == Fault.TIMEOUT_AFTER_WRITE) throw new Failure(0, 0);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;
    MutableClock(Instant now) { this.now = now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
  private static void require(boolean valid) { if (!valid) throw new IllegalStateException("Synthetic rehearsal check failed"); }
  private static void expectRejected(Runnable action) {
    boolean rejected = false;
    try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
    require(rejected);
  }
}
