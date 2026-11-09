package com.bridge.persistence;

import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Manually supervised single-process trial on one rebuildable synthetic sheet only.
 * No web route, credential loading, scheduler or deployment wiring. An authenticated host must
 * display View and bind a fresh explicit confirmation to its id. Operators must stop all writers,
 * supervise one process, and investigate any interruption; never restart/reseed to retry an unknown
 * result. This is NOT cross-restart, concurrency, crash-recovery or production erasure certification.
 */
final class SyntheticCleanupManualQuarterTrial implements AutoCloseable {
  record Fixture(ManualQuarterMaintenance.Binding binding, Map<String,List<List<String>>> tables) {
    Fixture { tables = ManualQuarterMaintenance.immutable(tables); }
  }
  record View(String planId, Instant cutoff, Instant expiresAt, int removeMatches,
      int keepMatches, String summary) {}
  static final Instant FIXTURE_EPOCH = Instant.parse("2026-11-09T00:00:00Z");
  /** Stable versioned input for separately authorized fixture preparation. Never uses runtime randomness. */
  static Fixture defaultFixture(String target) { return fixture(target,FIXTURE_EPOCH); }
  static Fixture fixture(String target,Instant now) {
    SyntheticCleanupWorkbook.requireApprovedTarget(target);
    Objects.requireNonNull(now);
    var binding = new ManualQuarterMaintenance.Binding(target, id("dataset-v1"));
    Map<String,List<List<String>>> tables = new TreeMap<>();
    ManualQuarterMaintenance.HEADERS.forEach((tab,header)->tables.put(tab,new ArrayList<>(List.of(header))));
    tables.get("dataset").add(List.of("bridgego-restricted-test",binding.datasetId()));
    String first=id("account-first-v1"),second=id("account-second-v1");
    tables.get("accounts").add(List.of(first,"1")); tables.get("accounts").add(List.of(second,"1"));
    tables.get("auth_identities").add(List.of("https://accounts.google.com","SYNTHETIC-FIRST-ONLY",first,"1"));
    tables.get("auth_identities").add(List.of("https://accounts.google.com","SYNTHETIC-SECOND-ONLY",second,"1"));
    tables.get("erasure_ledger").add(ErasureLedger.SENTINEL);
    tables.get("erasure_ledger").add(List.of("ACCOUNT",ErasureLedger.digest("ACCOUNT",id("erased-account-v1")),"1"));
    tables.get("erasure_ledger").add(List.of("IDENTITY",ErasureLedger.digest("IDENTITY",
        ErasureLedger.identityKey("https://accounts.google.com","SYNTHETIC-ERASED-ONLY")),"1"));
    Instant boundary=QuarterlyRetention.currentQuarterStart(now);
    add(tables,envelope("expired",boundary.minusSeconds(60),boundary.minusNanos(1),first,second));
    add(tables,envelope("boundary",boundary.minusSeconds(60),boundary,first,second));
    add(tables,envelope("current",now.minusSeconds(30),now,first,second));
    return new Fixture(binding,tables);
  }
  private static String id(String label) {
    return UUID.nameUUIDFromBytes(("bridgego-manual-quarter-trial-v1/"+label)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
  }
  private static void add(Map<String,List<List<String>>> tables,FinalMatchEnvelope envelope) {
    tables.get("envelopes").add(List.of(envelope.commitId(),envelope.payloadHash(),"0","1",envelope.canonicalJson()));
    tables.get("commits").add(List.of(envelope.commitId(),envelope.payloadHash(),"1",envelope.matchId(),"1"));
  }
  private static FinalMatchEnvelope envelope(String label,Instant start,Instant finish,String first,String second) {
    String matchId=id(label+"-match-v1");
    Map<String,Object> match=new LinkedHashMap<>();
    match.put("match_id",matchId);match.put("revision",1);match.put("schema_version",1);
    match.put("score_version","bridgego-custom-v0.11-1");match.put("app_version","0.12.1");
    match.put("started_at",start.toString());match.put("finished_at",finish.toString());
    match.put("completed_deals",1);match.put("ns_total",7);match.put("ew_total",-3);
    List<Map<String,Object>> participants=new ArrayList<>();
    for(int seat=0;seat<4;seat++){
      Map<String,Object> person=new LinkedHashMap<>();
      person.put("match_id",matchId);person.put("seat",seat);person.put("team_id",seat%2==0?"NS":"EW");
      person.put("player_type",seat==3?"BOT":"HUMAN");person.put("control_type",seat==3?"BOT":"HUMAN");
      person.put("account_id",seat==0?first:seat==1?second:null);person.put("guest_id",null);
      if(seat==2)person.put("erased_marker",id(label+"-erased-marker-v1"));participants.add(person);
    }
    return FinalMatchEnvelope.create(matchId,1,Map.of("match",match,"participants",participants,
        "completed_deals",List.of(Map.of("match_id",matchId,"deal_id",id(label+"-deal-v1"),
            "deal_index",1,"ns_total",7,"ew_total",-3))));
  }
  private final Fixture fixture;
  private final Clock clock;
  private final BooleanSupplier frozen;
  private final LiveQuarterStorage storage;
  private final ManualQuarterMaintenance engine;
  private ManualQuarterMaintenance.Preview plan;
  private String fingerprint;
  private boolean stopped;

  SyntheticCleanupManualQuarterTrial(Fixture fixture, LiveQuarterStorage.Transport transport,
      Clock clock, BooleanSupplier frozen) {
    this.fixture = Objects.requireNonNull(fixture);
    SyntheticCleanupWorkbook.requireApprovedTarget(fixture.binding().spreadsheetId());
    this.clock = Objects.requireNonNull(clock);
    this.frozen = Objects.requireNonNull(frozen);
    storage = new LiveQuarterStorage(fixture.binding(), transport, clock, frozen);
    engine = new ManualQuarterMaintenance(fixture.binding(), storage, clock, frozen, true);
  }
  /** Service-side integration point. Caller must already have authorization for this key and
   * isolated target; bytes are never loaded from an environment/file/browser by this class.
   * No network request occurs until preview. Only the existing service may supply its key.
   */
  static SyntheticCleanupManualQuarterTrial open(Fixture fixture, byte[] authorizedKey,
      Clock clock, BooleanSupplier frozen) {
    SyntheticCleanupWorkbook.requireApprovedTarget(fixture.binding().spreadsheetId());
    if (!frozen.getAsBoolean())
      throw new IllegalArgumentException("Fixed synthetic target and stopped writers required");
    var http = LiveQuarterHttp.open(fixture.binding(), authorizedKey);
    try { return new SyntheticCleanupManualQuarterTrial(fixture, http, clock, frozen); }
    catch (RuntimeException failure) { http.close(); throw failure; }
  }
  synchronized View preview() {
    requireActive();
    try {
      // Require exactly the locally generated fixture, never merely a target label or row count.
      var observed = storage.readComplete(fixture.binding());
      if (!fixture.tables().equals(observed.tables())) throw new IllegalStateException();
      plan = engine.preview(fixture.binding().stoppedConfirmation());
      if (!plan.beforeDigest().equals(ManualQuarterMaintenance.digest(observed))) throw new IllegalStateException();
      fingerprint = storage.fingerprint();
      int total = ManualQuarterMaintenance.decoded(fixture.tables()).size();
      if (plan.expiredCommits()!=1 || total!=3) throw new IllegalStateException();
      return new View(plan.planId(), plan.cutoff(), plan.expiresAt(), plan.expiredCommits(),
          total - plan.expiredCommits(), "刪除過期假戰績；保留本季與邊界假戰績、假帳號及身分。兩次原子寫入；不清除版本歷史或備份。");
    } catch (RuntimeException failure) { close(); throw new IllegalStateException("Preview unavailable; no writes"); }
  }
  /** UI submits the preview id + explicit affirmative action, not a copied long digest string.
   * Authentication/CSRF/nonce validation belongs to the existing host and is not supplied here. */
  synchronized String confirm(String previewId, boolean explicitlyConfirmed) {
    requireActive();
    if (!explicitlyConfirmed || plan == null || !plan.planId().equals(previewId)) return "REJECTED_NO_WRITE";
    // Expiry is checked before any authorization/execution attempt. Refreshing this untouched
    // preview is safe within this same process; the previous plan is explicitly invalidated.
    if (!plan.expiresAt().isAfter(clock.instant())) {
      plan=null; fingerprint=null; return "PREVIEW_EXPIRED_NO_WRITE";
    }
    stopped = true; // Single-process attempt latch, NOT a durable marker.
    try {
      if (!frozen.getAsBoolean())
        return "STOPPED_NO_WRITE";
      storage.authorize(plan, fingerprint);
      var result = engine.execute(plan.planId(), plan.confirmationText());
      return result.outcome() == ManualQuarterMaintenance.Outcome.COMPLETE
          ? "SYNTHETIC_QUARTER_VERIFIED" : "STOPPED_RESULT_REQUIRES_REVIEW";
    } catch (RuntimeException failure) { return "STOPPED_RESULT_REQUIRES_REVIEW"; }
    finally { storage.close(); }
  }
  private void requireActive() {
    try {
      if (stopped || !frozen.getAsBoolean()) throw new IllegalStateException();
    } catch (RuntimeException failure) {
      close(); throw new IllegalStateException("Session stopped; do not retry");
    }
  }
  @Override public synchronized void close() { stopped = true; storage.close(); }
}
