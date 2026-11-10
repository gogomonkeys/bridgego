package com.bridge.persistence;

import com.bridge.room.IdentityStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** One-use experiment admission. Production readiness is neither consulted nor asserted here. */
public final class ExperimentStorage implements IdentityStore, StatsStore, AutoCloseable {
  public static final String PURPOSE="bridgego-single-match-experiment-v1";
  static final String BOOT="_bridge_experiment_bootstrap", IDENTITY="_bridge_experiment_identity", RESULT="_bridge_experiment_result", RECOVERY="_bridge_experiment_recovery";
  static final Set<String> GUARDS=Set.of(BOOT,IDENTITY,RESULT,RECOVERY);
  static final List<String> GUARD_HEADER=List.of("dataset_id","expires_at","claim_nonce");
  static final Map<String,List<String>> HEADERS=Map.of(
      "dataset",List.of("purpose","dataset_id"), "accounts",List.of("account_id","schema_version"),
      "auth_identities",List.of("issuer","subject","account_id","schema_version"),
      "envelopes",List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk"),
      "commits",List.of("commit_id","payload_hash","chunk_count","match_id","revision"),
      "erasure_ledger",ErasureLedger.HEADER,"retention_ledger",RetentionLedger.HEADER,
      "quarterly_aggregates",QuarterlyRetention.AGGREGATE_HEADER);
  private static final Set<String> MATCH_FIELDS = Set.of("match_id", "revision", "schema_version", "score_version",
      "app_version", "started_at", "finished_at", "planned_deals", "completed_deals", "outcome", "winner_team_id",
      "reason", "ns_total", "ew_total");
  private static final Set<String> PERSON_FIELDS = Set.of("match_id", "seat", "team_id", "player_type",
      "control_type", "account_id", "guest_id", "erased_marker");
  private static final Set<String> DEAL_FIELDS = Set.of("match_id", "deal_id", "deal_index", "contract_type",
      "contract_level", "contract_strain", "attacking_team_id", "ns_tricks", "ew_tricks", "ns_privilege_used",
      "ns_base", "ns_exact_bonus", "ns_penalty", "ns_privilege_adjustment", "ns_total", "ew_privilege_used",
      "ew_base", "ew_exact_bonus", "ew_penalty", "ew_privilege_adjustment", "ew_total");
  public record Settings(String spreadsheetId,String datasetId,String issuer,String subject,Instant expiresAt) {
    public Settings {
      if(spreadsheetId==null||!spreadsheetId.matches("[A-Za-z0-9_-]{10,160}")||!uuid(datasetId)
          ||!"https://accounts.google.com".equals(issuer)||subject==null||subject.isBlank()
          ||subject.length()>255||!subject.equals(subject.trim())||subject.contains("@")||subject.contains("*")
          ||subject.chars().anyMatch(Character::isISOControl)||expiresAt==null)throw rejected();
    }
    public String sheetId(){return spreadsheetId;}
    @Override public String toString(){return "Experiment settings [redacted]";}
  }
  private final Settings settings;
  private final ExperimentGateway gateway;
  private final Clock clock;
  private final ExecutorService writer;
  private boolean identityAttempted, finalAttempted, bootstrapAttempted, closed;
  private String account, attemptedCommit, attemptedHash;
  private volatile SaveStatus saveStatus=SaveStatus.UNKNOWN;
  public ExperimentStorage(Settings settings,ExperimentGateway gateway,Clock clock) {
    this.settings=Objects.requireNonNull(settings);this.gateway=Objects.requireNonNull(gateway);this.clock=Objects.requireNonNull(clock);
    writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"single-experiment-writer");t.setDaemon(true);return t;});
  }
  public Settings settings(){return settings;}
  public synchronized boolean enabled(){return !closed&&clock.instant().isBefore(settings.expiresAt());}
  public synchronized boolean admissionAvailable(){return enabled()&&!identityAttempted;}
  public synchronized String previewEmptyDigest(){return previewEmpty();}
  public synchronized String initializeEmpty(String expectedDigest,boolean stopped,boolean authorized) {
    try {initializeEmpty(settings.datasetId(),expectedDigest,stopped,authorized);return "VERIFIED";}
    catch(RuntimeException failure){return bootstrapAttempted?"UNCERTAIN":"NOT_APPLIED";}
  }
  public synchronized String previewEmpty() { active();var s=read();requireEmpty(s);return digest(s); }
  public static String digest(ExperimentGateway.Snapshot s) {
    try { return FinalMatchEnvelope.hash(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new TreeMap<>(s.tables()))); }
    catch(Exception e){throw rejected();}
  }
  private void requireEmpty(ExperimentGateway.Snapshot before) {
    if(!before.tables().keySet().equals(HEADERS.keySet()))throw rejected();
    for(String tab:HEADERS.keySet()) {
      var expected=tab.equals("dataset")?List.of(HEADERS.get(tab),List.of(PURPOSE,settings.datasetId())):
          tab.equals("erasure_ledger")?List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL):List.of(HEADERS.get(tab));
      if(!before.tables().get(tab).equals(expected))throw rejected();
    }
  }
  /** No implicit startup initializer. An operator must separately authorize this exact empty dataset operation. */
  public synchronized void initializeEmpty(String exactDataset,String expectedDigest,boolean writersStopped,boolean authorized) {
    active(); if(bootstrapAttempted||!authorized||!writersStopped||!settings.datasetId().equals(exactDataset))throw rejected();
    var before=read();
    requireEmpty(before);if(!digest(before).equals(expectedDigest))throw rejected();
    Instant now=clock.instant(),cutoff=QuarterlyRetention.currentQuarterStart(now);
    if(!settings.expiresAt().isBefore(nextQuarter(cutoff))||settings.expiresAt().isAfter(now.plus(Duration.ofHours(24))))throw rejected();
    var guard=guard();
    var ledger=List.<List<Object>>of(List.of(QuarterlyRetention.quarter(cutoff),cutoff.toString(),"COMPLETE",now.toString(),"1"));
    active(); bootstrapAttempted=true; gateway.claimAtomic(BOOT,guard,Map.of("retention_ledger",ledger));
    var after=read();
    var expected=new TreeMap<>(before.tables());expected.put(BOOT,guard);
    expected.put("retention_ledger",List.of(RetentionLedger.HEADER,strings(ledger.get(0))));
    if(!after.tables().equals(expected))throw rejected();
  }
  @Override public synchronized String findOrCreateAccount(String issuer,String subject) {
    active(); if(identityAttempted||!settings.issuer().equals(issuer)||!settings.subject().equals(subject))throw rejected();
    identityAttempted=true; // Any rejected/uncertain attempt consumes this process; never retry automatic admission.
    var before=read();ready(before);
    if(before.tables().containsKey(IDENTITY)||before.tables().containsKey(RESULT)
        ||before.tables().get("accounts").size()!=1||before.tables().get("auth_identities").size()!=1
        ||before.tables().get("envelopes").size()!=1||before.tables().get("commits").size()!=1)throw rejected();
    new ErasureLedger(before.tables().get("erasure_ledger"));
    if(before.tables().get("erasure_ledger").size()!=2)throw rejected();
    String proposed=UUID.randomUUID().toString();var guard=guard();
    var accounts=List.<List<Object>>of(List.of(proposed,"1"));
    var identities=List.<List<Object>>of(List.of(issuer,subject,proposed,"1"));
    active(); gateway.claimAtomic(IDENTITY,guard,Map.of("accounts",accounts,"auth_identities",identities));
    var after=read();var expected=new TreeMap<>(before.tables());expected.put(IDENTITY,guard);
    expected.put("accounts",List.of(HEADERS.get("accounts"),strings(accounts.get(0))));
    expected.put("auth_identities",List.of(HEADERS.get("auth_identities"),strings(identities.get(0))));
    if(!after.tables().equals(expected))throw rejected();
    active(); account=proposed;return proposed;
  }
  /**
   * Separately enabled, operator-bound recovery after a verified Google principal. This does not
   * create an account or reset admission. A permanent singleton claim fences retries and restarts.
   */
  public synchronized String recoverAccount(String issuer,String subject,String expectedIdentityClaimNonce) {
    active();
    if(identityAttempted||!settings.issuer().equals(issuer)||!settings.subject().equals(subject)
        ||!uuid(expectedIdentityClaimNonce))throw rejected();
    identityAttempted=true; // No retry even when the read/write outcome cannot be established.
    var before=read();ready(before);
    if(!before.tables().containsKey(IDENTITY)||before.tables().containsKey(RECOVERY)
        ||before.tables().containsKey(RESULT)
        ||!before.tables().get(IDENTITY).get(1).get(2).equals(expectedIdentityClaimNonce)
        ||before.tables().get("accounts").size()!=2||before.tables().get("auth_identities").size()!=2
        ||before.tables().get("envelopes").size()!=1||before.tables().get("commits").size()!=1
        ||!before.tables().get("erasure_ledger").equals(List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL)))throw rejected();
    String existing=before.tables().get("accounts").get(1).get(0);
    if(!before.tables().get("auth_identities").get(1).equals(List.of(issuer,subject,existing,"1")))throw rejected();
    var guard=guard();active();gateway.claimAtomic(RECOVERY,guard,Map.of());
    var after=read();var expected=new TreeMap<>(before.tables());expected.put(RECOVERY,guard);
    if(!after.tables().equals(expected))throw rejected();
    active();account=existing;return existing;
  }
  @Override public synchronized SaveStatus submit(FinalMatchEnvelope e) {
    if(finalAttempted) return Objects.equals(attemptedCommit,e.commitId())&&Objects.equals(attemptedHash,e.payloadHash())?saveStatus:SaveStatus.REJECTED;
    try {active(); validateOwner(e);}catch(RuntimeException rejected){return SaveStatus.REJECTED;}
    finalAttempted=true;attemptedCommit=e.commitId();attemptedHash=e.payloadHash();saveStatus=SaveStatus.PENDING;
    try{writer.execute(()->write(e));}catch(RejectedExecutionException stopped){saveStatus=SaveStatus.FAILED;}
    return saveStatus;
  }
  private void write(FinalMatchEnvelope e) {
    try {
      active();validateOwner(e);var before=read();ready(before);validateEnvelopeFields(e,before,clock);
      if(before.tables().containsKey(RESULT)||!before.tables().containsKey(IDENTITY)
          ||before.tables().get("commits").size()!=1||before.tables().get("envelopes").size()!=1)throw rejected();
      if(!before.tables().get("accounts").equals(List.of(HEADERS.get("accounts"),List.of(account,"1")))
          ||!before.tables().get("auth_identities").equals(List.of(HEADERS.get("auth_identities"),List.of(settings.issuer(),settings.subject(),account,"1"))))throw rejected();
      new ErasureLedger(before.tables().get("erasure_ledger")).requireAllowed(e);
      var chunks=new ArrayList<List<Object>>();String raw=e.canonicalJson();var parts=new ArrayList<String>();
      for(int start=0;start<raw.length();) {int end=Math.min(raw.length(),start+20000);if(end<raw.length()&&Character.isHighSurrogate(raw.charAt(end-1)))end--;parts.add(raw.substring(start,end));start=end;}
      for(int i=0;i<parts.size();i++)chunks.add(List.of(e.commitId(),e.payloadHash(),Integer.toString(i),Integer.toString(parts.size()),parts.get(i)));
      var marker=List.<List<Object>>of(List.of(e.commitId(),e.payloadHash(),Integer.toString(parts.size()),e.matchId(),Integer.toString(e.revision())));
      var guard=guard();active();gateway.claimAtomic(RESULT,guard,Map.of("envelopes",chunks,"commits",marker));
      var after=read();var expected=new TreeMap<>(before.tables());expected.put(RESULT,guard);
      var erows=new ArrayList<List<String>>();erows.add(HEADERS.get("envelopes"));chunks.forEach(x->erows.add(strings(x)));expected.put("envelopes",erows);
      expected.put("commits",List.of(HEADERS.get("commits"),strings(marker.get(0))));
      if(!after.tables().equals(expected)||!decode(after).get(e.commitId()).equals(e))throw rejected();
      saveStatus=SaveStatus.CONFIRMED;
    }catch(RuntimeException unknown){saveStatus=SaveStatus.UNKNOWN;} // No network retry or release; operator investigates.
  }
  public synchronized SaveStatus status(String commitId){return Objects.equals(attemptedCommit,commitId)?saveStatus:SaveStatus.UNKNOWN;}
  public synchronized List<FinalMatchEnvelope> exportCommitted(){active();var s=read();ready(s);return List.copyOf(decode(s).values());}
  private void validateOwner(FinalMatchEnvelope e){
    if(account==null||e==null)throw rejected();
    for(String field:List.of("planned_deals","completed_deals")) {
      var value=e.payload().path("match").path(field);
      if(!value.isIntegralNumber()||!value.bigIntegerValue().equals(java.math.BigInteger.ONE))throw rejected();
    }
    if(e.payload().path("completed_deals").size()!=1)throw rejected();
    int humans=0,bots=0;
    for(var p:e.payload().path("participants")) {
      if(!p.path("guest_id").isNull()||p.has("erased_marker"))throw rejected();
      if(p.path("player_type").asText().equals("HUMAN")&&p.path("control_type").asText().equals("HUMAN")&&p.path("account_id").asText().equals(account))humans++;
      else if(p.path("player_type").asText().equals("BOT")&&p.path("control_type").asText().equals("BOT")&&p.path("account_id").isNull())bots++;
      else throw rejected();
    }
    Instant now=clock.instant(),finish=QuarterlyRetention.finishedAt(e),start=Instant.parse(e.payload().path("match").path("started_at").asText());
    if(humans!=1||bots!=3||finish.isAfter(now)||start.isAfter(finish)||!finish.isBefore(settings.expiresAt())
        ||finish.isBefore(QuarterlyRetention.currentQuarterStart(now)))throw rejected();
  }
  private ExperimentGateway.Snapshot read(){var s=gateway.readComplete();validate(s,settings,clock);return s;}
  private void ready(ExperimentGateway.Snapshot s){
    if(!s.tables().containsKey(BOOT))throw rejected();var l=new RetentionLedger(s.tables().get("retention_ledger"));
    if(!l.complete()||!l.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant())))throw rejected();
  }
  private synchronized void active(){if(!enabled())throw rejected();}
  private List<List<String>> guard(){return List.of(GUARD_HEADER,List.of(settings.datasetId(),settings.expiresAt().toString(),UUID.randomUUID().toString()));}
  static List<String> headers(String title){return GUARDS.contains(title)?GUARD_HEADER:HEADERS.get(title);}
  static void validate(ExperimentGateway.Snapshot s,Settings settings,Clock clock){
    if(s==null||!s.completeKnownLayout()||!settings.spreadsheetId().equals(s.spreadsheetId())||!s.tables().keySet().containsAll(HEADERS.keySet()))throw rejected();
    int total=0;
    for(var entry:s.tables().entrySet()) {
      var header=headers(entry.getKey());var rows=entry.getValue();
      if(header==null||rows.isEmpty()||rows.size()>1000||!rows.get(0).equals(header))throw rejected();
      for(var row:rows){if(row.size()!=header.size())throw rejected();for(String v:row)if(v==null||v.length()>20000||(total+=v.length())>1048576)throw rejected();}
      if(GUARDS.contains(entry.getKey())&&(rows.size()!=2||!rows.get(1).get(0).equals(settings.datasetId())||!rows.get(1).get(1).equals(settings.expiresAt().toString())||!uuid(rows.get(1).get(2))))throw rejected();
    }
    if(!s.tables().get("dataset").equals(List.of(HEADERS.get("dataset"),List.of(PURPOSE,settings.datasetId()))))throw rejected();
    if((s.tables().containsKey(RESULT)||s.tables().containsKey(RECOVERY))&&!s.tables().containsKey(IDENTITY)||s.tables().containsKey(IDENTITY)&&!s.tables().containsKey(BOOT))throw rejected();
    var a=s.tables().get("accounts");var i=s.tables().get("auth_identities");
    if(a.size()>2||i.size()>2||a.size()!=i.size())throw rejected();
    if(a.size()==2&&(!uuid(a.get(1).get(0))||!a.get(1).get(1).equals("1")||!i.get(1).equals(List.of(settings.issuer(),settings.subject(),a.get(1).get(0),"1"))||!s.tables().containsKey(IDENTITY)))throw rejected();
    if(new HashSet<>(s.tables().get("erasure_ledger")).size()!=s.tables().get("erasure_ledger").size())throw rejected();
    var erasures=new ErasureLedger(s.tables().get("erasure_ledger"));
    if(s.tables().containsKey(IDENTITY)) {
      boolean erased=erasures.contains("IDENTITY",ErasureLedger.identityKey(settings.issuer(),settings.subject()));
      if(a.size()==1&&!erased || a.size()==2&&(erased||erasures.contains("ACCOUNT",a.get(1).get(0))))throw rejected();
    }
    var l=new RetentionLedger(s.tables().get("retention_ledger"));
    if(s.tables().get("quarterly_aggregates").size()!=1||s.tables().get("retention_ledger").size()>2)throw rejected();
    if(s.tables().containsKey(BOOT)&&!l.complete())throw rejected();
    if(s.tables().get("commits").size()>2||new HashSet<>(s.tables().get("envelopes")).size()!=s.tables().get("envelopes").size())throw rejected();
    var decoded=decode(s);for(var e:decoded.values())validateEnvelopeFields(e,s,clock);if(decoded.size()>1||(!decoded.isEmpty()&&!s.tables().containsKey(RESULT))||(s.tables().containsKey(RESULT)&&decoded.size()!=1))throw rejected();
  }
  private static void validateEnvelopeFields(FinalMatchEnvelope envelope,ExperimentGateway.Snapshot snapshot,Clock clock){
    var root=envelope.payload();fields(root,Set.of("match","participants","completed_deals"));
    var match=root.path("match");fields(match,MATCH_FIELDS);
    for(String key:List.of("schema_version","planned_deals","completed_deals"))if(!match.path(key).isIntegralNumber()||!match.path(key).bigIntegerValue().equals(java.math.BigInteger.ONE))throw rejected();
    if(!match.path("revision").isIntegralNumber()||!match.path("revision").bigIntegerValue().equals(java.math.BigInteger.valueOf(envelope.revision()))
        ||!Set.of("0.12.1", "0.12.2", "0.13.0", "0.13.1").contains(match.path("app_version").asText())||!match.path("score_version").asText().equals("bridgego-custom-v0.11-1"))throw rejected();
    typed(match,Set.of("revision","schema_version","planned_deals","completed_deals","ns_total","ew_total"),Set.of());
    Instant start=Instant.parse(match.path("started_at").asText()),finish=QuarterlyRetention.finishedAt(envelope);
    if(start.isAfter(finish)||finish.isAfter(clock.instant()))throw rejected();
    var accounts=snapshot.tables().get("accounts");int humans=0,bots=0;
    for(var person:root.path("participants")){
      fields(person,PERSON_FIELDS);typed(person,Set.of("seat"),Set.of());
      if(!person.path("seat").canConvertToInt()||!Set.of("NS","EW").contains(person.path("team_id").asText())||!person.path("guest_id").isNull())throw rejected();
      String type=person.path("player_type").asText(),control=person.path("control_type").asText();
      if(type.equals("BOT")&&control.equals("BOT")&&person.path("account_id").isNull()&&!person.has("erased_marker"))bots++;
      else if(type.equals("HUMAN")&&control.equals("HUMAN")){
        humans++;
        if(person.has("erased_marker")){if(!uuid(person.path("erased_marker").asText())||!person.path("account_id").isNull())throw rejected();}
        else if(accounts.size()!=2||!person.path("account_id").isTextual()||!person.path("account_id").asText().equals(accounts.get(1).get(0)))throw rejected();
      }else throw rejected();
    }
    if(humans!=1||bots!=3)throw rejected();
    for(var deal:root.path("completed_deals")){fields(deal,DEAL_FIELDS);typed(deal,Set.of("deal_index","contract_level","ns_tricks","ew_tricks","ns_base","ns_exact_bonus","ns_penalty","ns_privilege_adjustment","ns_total","ew_base","ew_exact_bonus","ew_penalty","ew_privilege_adjustment","ew_total"),Set.of("ns_privilege_used","ew_privilege_used"));}
  }
  private static void fields(com.fasterxml.jackson.databind.JsonNode node,Set<String> allowed){if(!node.isObject())throw rejected();node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw rejected();});}
  private static void typed(com.fasterxml.jackson.databind.JsonNode node,Set<String> integers,Set<String> bools){node.fields().forEachRemaining(e->{var v=e.getValue();if(integers.contains(e.getKey())){if(!v.isIntegralNumber())throw rejected();}else if(bools.contains(e.getKey())){if(!v.isBoolean())throw rejected();}else if(!v.isNull()&&!v.isTextual())throw rejected();});}
  private static Map<String,FinalMatchEnvelope> decode(ExperimentGateway.Snapshot s){return SheetsStatsStore.readVerified(new SheetsGateway(){public List<List<String>> read(String t){return s.tables().get(t);}public void appendAtomic(Map<String,List<List<Object>>> rows){throw rejected();}});}
  private static List<String> strings(List<?> row){return row.stream().map(Object::toString).toList();}
  private static boolean uuid(String v){try{return UUID.fromString(v).toString().equals(v);}catch(Exception e){return false;}}
  private static Instant nextQuarter(Instant start){return start.atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant();}
  private static IllegalStateException rejected(){return new IllegalStateException("Experiment stopped or unavailable; do not retry an uncertain operation");}
  public synchronized void close(){closed=true;writer.shutdownNow();}
}
