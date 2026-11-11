package com.bridge.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Pure, one-snapshot business checks. Never exposes tables or authorizes a write. */
final class ProductionReadOnlyBusiness {
  static final String CONTRACT = "legacy-record-contract-v1";
  static final int MAX_ROWS = 1000, MAX_CELL_CHARS = 20000, MAX_LOGICAL_CHARS = 12 * 1024 * 1024;
  private static final String ISSUER = "https://accounts.google.com";
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  enum State { VERIFIED, UNAVAILABLE, INVALID }
  enum Domain { COMMIT_CHUNK_LINKAGE, ENVELOPE_RULES, REVISION_POLICY, ACCOUNTS,
    AUTH_IDENTITIES, ERASURE_LEDGER, ERASURE_REFERENCES, ACCOUNT_REFERENCES,
    RECORD_TIMES, RETENTION_LEDGER, RETENTION_STATE, LIFETIME_CARRY,
    LIFETIME_CONSISTENCY, QUARTERLY_AGGREGATES, AGGREGATE_CONSISTENCY }
  enum Reason { INVALID_TABLE, BUDGET_EXCEEDED, PREREQUISITE_UNVERIFIED,
    INVALID_COMMIT, DUPLICATE_COMMIT, INVALID_CHUNK, DUPLICATE_CHUNK, ORPHAN_CHUNK,
    CHUNK_MARKER_MISMATCH, MISSING_CHUNKS, INVALID_ENVELOPE, UNSUPPORTED_SCHEMA,
    UNSUPPORTED_RULE, UNSUPPORTED_SCORE_VERSION, UNSUPPORTED_TEXT_ENCODING, LEGACY_RULE_SEMANTICS_UNAVAILABLE,
    LIFETIME_ELIGIBILITY_UNAVAILABLE, UNKNOWN_EXTENSION_SEMANTICS,
    MULTIPLE_MATCH_REVISIONS, REVISION_HISTORY_UNAVAILABLE,
    INVALID_ACCOUNT, DUPLICATE_ACCOUNT, UNSUPPORTED_ACCOUNT_SCHEMA,
    INVALID_IDENTITY, UNSUPPORTED_IDENTITY_SCHEMA, CONFLICTING_IDENTITY,
    DUPLICATE_IDENTITY_EQUIVALENT, MISSING_ACCOUNT_REFERENCE,
    INVALID_ERASURE_LEDGER, UNSUPPORTED_ERASURE_SCHEMA, DUPLICATE_TOMBSTONE_EQUIVALENT,
    ERASED_IDENTIFIER_PRESENT, ERASED_MARKER_PROVENANCE_UNAVAILABLE,
    INVALID_RETENTION_LEDGER, UNSUPPORTED_RETENTION_SCHEMA, DUPLICATE_RETENTION_QUARTER,
    RETENTION_MISSING, RETENTION_PENDING, RETENTION_STALE, HISTORICAL_PENDING,
    FUTURE_RETENTION, INVALID_RECORD_TIME, RECORD_BEFORE_RETENTION,
    INVALID_CARRY, UNSUPPORTED_CARRY_SCHEMA, UNSUPPORTED_CARRY_RULE,
    CARRY_HISTORY_UNAVAILABLE, CARRY_AFTER_RETENTION, CARRY_RAW_OVERLAP,
    CARRY_ARITHMETIC_INVALID, INVALID_AGGREGATE, UNSUPPORTED_AGGREGATE_SCHEMA,
    UNSUPPORTED_PUBLICATION, DUPLICATE_AGGREGATE_QUARTER, AGGREGATE_NOT_PURGED,
    AGGREGATE_RAW_OVERLAP, AGGREGATE_HISTORY_UNAVAILABLE }
  record Check(State state, Map<Reason,Integer> reasons) {
    Check { reasons = Collections.unmodifiableMap(new EnumMap<>(reasons)); }
  }
  record Summary(String contract, State state, Map<Domain,Check> checks, int decodedRecords,
      int expiredRecords, int lifetimeEligibleRecords) {
    Summary { checks = Collections.unmodifiableMap(new EnumMap<>(checks)); }
    boolean verified() { return state == State.VERIFIED; }
    public boolean mutationPermitted() { return false; }
    public boolean coherentSnapshotVerified() { return false; }
    public boolean writerExclusionVerified() { return false; }
    @Override public String toString() { return "ReadOnlyBusiness[" + state + ", mutationPermitted=false]"; }
  }
  private static final class Mutable {
    State state = State.VERIFIED;
    final EnumMap<Reason,Integer> reasons = new EnumMap<>(Reason.class);
    void add(State severity, Reason reason) { if (severity.ordinal() > state.ordinal()) state = severity;
      reasons.merge(reason,1,Integer::sum); }
    Check freeze() { return new Check(state,reasons); }
  }
  private record Marker(String id,String hash,int count,String match,int revision) {
    @Override public String toString() { return "ReadOnlyCommit[redacted]"; }
  }
  private record Retention(Instant cutoff, boolean complete, boolean valid) {}
  private final EnumMap<Domain,Mutable> checks = new EnumMap<>(Domain.class);
  private final Map<String,List<List<String>>> tables;
  private final Instant now, boundary;
  private final Set<String> badTables = new HashSet<>(), accounts = new HashSet<>(), erasures = new HashSet<>();
  private final Map<String,ProductionReadOnlyLifetime.Carry> carries = new HashMap<>();
  private final Map<String,ProductionReadOnlyLifetime.Counts[]> contributions = new HashMap<>();
  private final Set<String> aggregateQuarters = new HashSet<>();
  private int decoded, expired, eligible;
  private ProductionReadOnlyBusiness(Map<String,List<List<String>>> tables, Instant now) {
    this.tables=tables; this.now=now; this.boundary=quarterStart(now);
    for (var domain:Domain.values()) checks.put(domain,new Mutable());
  }
  static Summary inspect(Map<String,List<List<String>>> tables, Instant now) {
    ProductionReadOnlyBusiness scan;
    try { scan=new ProductionReadOnlyBusiness(tables,now); }
    catch (RuntimeException bad) { return unavailable(); }
    try {
      if (!scan.shapes()) return scan.summary();
      scan.accounts(); scan.erasures(); scan.identities(); scan.accountErasures();
      Retention retention=scan.retention(); scan.carryLedger(retention); scan.aggregates(retention);
      scan.records(retention); scan.carryArithmetic();
    } catch (RuntimeException | LinkageError unexpected) {
      // No raw exception or causes escape. Preserve completed independent observations.
      for (var value:scan.checks.values()) value.add(State.UNAVAILABLE,Reason.PREREQUISITE_UNVERIFIED);
    }
    return scan.summary();
  }
  private static Summary unavailable() {
    var checks=new EnumMap<Domain,Check>(Domain.class);
    for(var domain:Domain.values()) checks.put(domain,new Check(State.UNAVAILABLE,new EnumMap<>(Map.of(Reason.PREREQUISITE_UNVERIFIED,1))));
    return new Summary(CONTRACT,State.UNAVAILABLE,checks,0,0,0);
  }
  private Summary summary() {
    var result=new EnumMap<Domain,Check>(Domain.class); State state=State.VERIFIED;
    for(var entry:checks.entrySet()) { result.put(entry.getKey(),entry.getValue().freeze());
      if(entry.getValue().state.ordinal()>state.ordinal())state=entry.getValue().state; }
    return new Summary(CONTRACT,state,result,decoded,expired,eligible);
  }
  private void mark(Domain d,State s,Reason r) { checks.get(d).add(s,r); }
  private boolean good(Domain d) { return checks.get(d).state==State.VERIFIED; }
  private void invalid(Domain d,Reason r) { mark(d,State.INVALID,r); }
  private void unavailable(Domain d,Reason r) { mark(d,State.UNAVAILABLE,r); }
  private List<List<String>> rows(String tab) { var r=tables.get(tab); return badTables.contains(tab)?List.of():r.subList(1,r.size()); }
  private boolean shapes() {
    if(tables==null || !tables.keySet().equals(new HashSet<>(ProductionReadOnlyWorkbook.TABS))) {
      for(var d:Domain.values())invalid(d,Reason.INVALID_TABLE); return false;
    }
    long chars=0;
    for(String tab:ProductionReadOnlyWorkbook.TABS) {
      var rows=tables.get(tab); boolean bad=rows==null||rows.isEmpty();
      if(!bad && rows.size()>MAX_ROWS) { for(var d:Domain.values())unavailable(d,Reason.BUDGET_EXCEEDED);return false; }
      if(!bad) {
        var header=rows.get(0); int width=header==null?0:header.size();
        bad=header==null || !(header.equals(ProductionReadOnlyWorkbook.HEADERS.get(tab)) || tab.equals("accounts") &&
          (header.equals(List.of("account_id","schema_version")) || header.equals(List.of("account_id","schema_version","nickname","email","lifetime_statistics"))));
        for(var row:rows) {
          if(row==null || row.size()!=width) { bad=true;continue; }
          for(var value:row) {
            if(value==null) { bad=true;continue; }
            if(value.length()>MAX_CELL_CHARS || (chars+=value.length())>MAX_LOGICAL_CHARS) {
              for(var d:Domain.values())unavailable(d,Reason.BUDGET_EXCEEDED);return false;
            }
          }
        }
      }
      if(bad) { badTables.add(tab); invalid(domain(tab),Reason.INVALID_TABLE); }
    }
    return true;
  }
  private static Domain domain(String tab) { return switch(tab) {
    case "accounts"->Domain.ACCOUNTS;case "auth_identities"->Domain.AUTH_IDENTITIES;
    case "erasure_ledger"->Domain.ERASURE_LEDGER;case "retention_ledger"->Domain.RETENTION_LEDGER;
    case "quarterly_aggregates"->Domain.QUARTERLY_AGGREGATES;default->Domain.COMMIT_CHUNK_LINKAGE;}; }
  private void accounts() {
    if(badTables.contains("accounts")) { unavailable(Domain.LIFETIME_CARRY,Reason.PREREQUISITE_UNVERIFIED);return; }
    for(var r:rows("accounts")) {
      if(!Set.of("1","2").contains(r.get(1))) {unavailable(Domain.ACCOUNTS,Reason.UNSUPPORTED_ACCOUNT_SCHEMA);unavailable(Domain.LIFETIME_CARRY,Reason.PREREQUISITE_UNVERIFIED);continue;}
      boolean valid=uuid(r.get(0));
      if(!accounts.add(r.get(0)))invalid(Domain.ACCOUNTS,Reason.DUPLICATE_ACCOUNT);
      if(r.get(1).equals("1")) { for(int i=2;i<Math.min(4,r.size());i++)if(!r.get(i).isEmpty())valid=false; }
      else if(r.get(1).equals("2")) {
        valid &= r.size()>=3 && !r.get(2).isBlank() && r.get(2).length()<=256 && r.get(2).chars().noneMatch(Character::isISOControl);
        if(r.size()>=4)valid &= r.get(3).isEmpty() || r.get(3).length()<=254 && r.get(3).matches("[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+\\.[^\\s@\\p{Cntrl}]+");
      }
      if(!valid)invalid(Domain.ACCOUNTS,Reason.INVALID_ACCOUNT);
      try {
        var carry=ProductionReadOnlyLifetime.decode(r.size()==5?r.get(4):"");carries.put(r.get(0),carry);
        if(carry.through()!=null)unavailable(Domain.LIFETIME_CONSISTENCY,Reason.CARRY_HISTORY_UNAVAILABLE);
      } catch(ProductionReadOnlyLifetime.Failure failure) {
        var reason=Reason.valueOf(failure.reason().name());
        mark(Domain.LIFETIME_CARRY,reason==Reason.INVALID_CARRY?State.INVALID:State.UNAVAILABLE,reason);
      }
    }
  }
  private void erasures() {
    var r=rows("erasure_ledger");
    if(!r.isEmpty() && r.get(0).get(0).equals("META") && !r.get(0).equals(List.of("META","erasure-ledger-v1","1"))) {
      unavailable(Domain.ERASURE_LEDGER,Reason.UNSUPPORTED_ERASURE_SCHEMA);return;
    }
    if(r.isEmpty() || !r.get(0).equals(List.of("META","erasure-ledger-v1","1"))) {
      invalid(Domain.ERASURE_LEDGER,Reason.INVALID_ERASURE_LEDGER);return;
    }
    for(var row:r.subList(1,r.size())) {
      if(!row.get(2).equals("1")) {unavailable(Domain.ERASURE_LEDGER,Reason.UNSUPPORTED_ERASURE_SCHEMA);continue;}
      if(!Set.of("ACCOUNT","GUEST","IDENTITY").contains(row.get(0)) || !hashText(row.get(1))) {
        invalid(Domain.ERASURE_LEDGER,Reason.INVALID_ERASURE_LEDGER);continue;
      }
      if(!erasures.add(row.get(0)+":"+row.get(1)))mark(Domain.ERASURE_LEDGER,State.VERIFIED,Reason.DUPLICATE_TOMBSTONE_EQUIVALENT);
    }
  }
  private void identities() {
    var seen=new HashMap<String,String>();
    if(!good(Domain.ACCOUNTS))unavailable(Domain.AUTH_IDENTITIES,Reason.PREREQUISITE_UNVERIFIED);
    if(!good(Domain.ERASURE_LEDGER))unavailable(Domain.ERASURE_REFERENCES,Reason.PREREQUISITE_UNVERIFIED);
    for(var r:rows("auth_identities")) {
      if(!r.get(3).equals("1")) {unavailable(Domain.AUTH_IDENTITIES,Reason.UNSUPPORTED_IDENTITY_SCHEMA);continue;}
      if(!ISSUER.equals(r.get(0)) || r.get(1).isBlank() || r.get(1).length()>255 || !uuid(r.get(2)))invalid(Domain.AUTH_IDENTITIES,Reason.INVALID_IDENTITY);
      if(good(Domain.ACCOUNTS) && !accounts.contains(r.get(2)))invalid(Domain.AUTH_IDENTITIES,Reason.MISSING_ACCOUNT_REFERENCE);
      String key=r.get(0)+"\0"+r.get(1), previous=seen.putIfAbsent(key,r.get(2));
      if(previous!=null)mark(Domain.AUTH_IDENTITIES,previous.equals(r.get(2))?State.VERIFIED:State.INVALID,
        previous.equals(r.get(2))?Reason.DUPLICATE_IDENTITY_EQUIVALENT:Reason.CONFLICTING_IDENTITY);
      if(erased("IDENTITY",key))invalid(Domain.ERASURE_REFERENCES,Reason.ERASED_IDENTIFIER_PRESENT);
    }
  }
  private void accountErasures() {
    if(!good(Domain.ACCOUNTS)||!good(Domain.AUTH_IDENTITIES)||!good(Domain.ERASURE_LEDGER))unavailable(Domain.ERASURE_REFERENCES,Reason.PREREQUISITE_UNVERIFIED);
    for(String id:accounts)if(erased("ACCOUNT",id))invalid(Domain.ERASURE_REFERENCES,Reason.ERASED_IDENTIFIER_PRESENT);
  }
  private Retention retention() {
    Instant cutoff=Instant.MIN;boolean complete=false;Set<String> seen=new HashSet<>();int pending=0;
    for(var r:rows("retention_ledger")) {
      if(!r.get(4).equals("1")) {unavailable(Domain.RETENTION_LEDGER,Reason.UNSUPPORTED_RETENTION_SCHEMA);continue;}
      if(!seen.add(r.get(0)))invalid(Domain.RETENTION_LEDGER,Reason.DUPLICATE_RETENTION_QUARTER);
      try {
        Instant b=Instant.parse(r.get(1));boolean done=r.get(2).equals("COMPLETE");
        if(!quarterStart(b).equals(b)||!quarter(b).equals(r.get(0))||!done&&!r.get(2).equals("PENDING"))throw bad();
        if(done) { Instant at=Instant.parse(r.get(3));if(at.isBefore(b))throw bad();if(at.isAfter(now))invalid(Domain.RETENTION_LEDGER,Reason.FUTURE_RETENTION); }
        else { if(!r.get(3).isEmpty())throw bad();pending++; }
        if(b.isAfter(boundary))invalid(Domain.RETENTION_LEDGER,Reason.FUTURE_RETENTION);
        if(b.isAfter(cutoff)) { cutoff=b;complete=done; }
      } catch(RuntimeException invalid) { invalid(Domain.RETENTION_LEDGER,Reason.INVALID_RETENTION_LEDGER); }
    }
    boolean valid=good(Domain.RETENTION_LEDGER);
    if(!valid)unavailable(Domain.RETENTION_STATE,Reason.PREREQUISITE_UNVERIFIED);
    else if(cutoff.equals(Instant.MIN))unavailable(Domain.RETENTION_STATE,Reason.RETENTION_MISSING);
    else {
      if(!complete)unavailable(Domain.RETENTION_STATE,Reason.RETENTION_PENDING);
      if(cutoff.isBefore(boundary))unavailable(Domain.RETENTION_STATE,Reason.RETENTION_STALE);
      if(pending>(complete?0:1))mark(Domain.RETENTION_STATE,State.VERIFIED,Reason.HISTORICAL_PENDING);
    }
    return new Retention(cutoff,complete,valid);
  }
  private void carryLedger(Retention retention) {
    if(!retention.valid || !good(Domain.LIFETIME_CARRY)||!good(Domain.ACCOUNTS))unavailable(Domain.LIFETIME_CONSISTENCY,Reason.PREREQUISITE_UNVERIFIED);
    for(var carry:carries.values())if(carry.through()!=null && retention.valid && carry.through().isAfter(retention.cutoff))invalid(Domain.LIFETIME_CONSISTENCY,Reason.CARRY_AFTER_RETENTION);
  }
  private void aggregates(Retention retention) {
    for(var r:rows("quarterly_aggregates")) {
      if(!r.get(4).equals("1")) {unavailable(Domain.QUARTERLY_AGGREGATES,Reason.UNSUPPORTED_AGGREGATE_SCHEMA);continue;}
      if(!aggregateQuarters.add(r.get(0)))invalid(Domain.QUARTERLY_AGGREGATES,Reason.DUPLICATE_AGGREGATE_QUARTER);
      try {
        Instant q=quarterText(r.get(0));
        if(r.get(1).equals("SUPPRESSED")) { if(!r.get(2).isEmpty()||!r.get(3).isEmpty())throw bad(); }
        else if(r.get(1).equals("CANDIDATE_COUNTS")) {
          for(int i=2;i<4;i++)if(!r.get(i).matches("[0-9]+")||Long.parseLong(r.get(i))%10!=0)throw bad();
        } else unavailable(Domain.QUARTERLY_AGGREGATES,Reason.UNSUPPORTED_PUBLICATION);
        if(retention.valid && !q.isBefore(retention.cutoff))unavailable(Domain.AGGREGATE_CONSISTENCY,Reason.AGGREGATE_NOT_PURGED);
      } catch(RuntimeException invalid) { invalid(Domain.QUARTERLY_AGGREGATES,Reason.INVALID_AGGREGATE); }
      unavailable(Domain.AGGREGATE_CONSISTENCY,Reason.AGGREGATE_HISTORY_UNAVAILABLE);
    }
    if(!retention.valid||!good(Domain.QUARTERLY_AGGREGATES))unavailable(Domain.AGGREGATE_CONSISTENCY,Reason.PREREQUISITE_UNVERIFIED);
  }
  private void records(Retention retention) {
    var markers=new LinkedHashMap<String,Marker>(); var parts=new HashMap<String,TreeMap<Integer,String>>();
    Set<String> unusable=new HashSet<>();Map<String,Integer> matches=new HashMap<>();
    if(badTables.contains("commits")||badTables.contains("envelopes"))recordPrerequisite();
    for(var r:rows("commits")) {
      try {
        int count=number(r.get(2),1,10000),revision=number(r.get(4),1,Integer.MAX_VALUE);
        if(!uuid(r.get(3)) || !r.get(0).equals(r.get(3)+":"+revision)||!hashText(r.get(1)))throw bad();
        var marker=new Marker(r.get(0),r.get(1),count,r.get(3),revision);
        if(markers.putIfAbsent(marker.id,marker)!=null) { invalid(Domain.COMMIT_CHUNK_LINKAGE,Reason.DUPLICATE_COMMIT);unusable.add(marker.id); }
        matches.merge(marker.match,1,Integer::sum);
        if(revision>1)unavailable(Domain.REVISION_POLICY,Reason.REVISION_HISTORY_UNAVAILABLE);
      } catch(RuntimeException invalid) { invalid(Domain.COMMIT_CHUNK_LINKAGE,Reason.INVALID_COMMIT);recordPrerequisite(); }
    }
    for(int n:matches.values())if(n>1)unavailable(Domain.REVISION_POLICY,Reason.MULTIPLE_MATCH_REVISIONS);
    for(var r:rows("envelopes")) {
      Marker marker=markers.get(r.get(0));
      if(marker==null) { invalid(Domain.COMMIT_CHUNK_LINKAGE,Reason.ORPHAN_CHUNK);continue; }
      try {
        int count=number(r.get(3),1,10000),index=number(r.get(2),0,count-1);
        if(!marker.hash.equals(r.get(1)) || count!=marker.count) {
          invalid(Domain.COMMIT_CHUNK_LINKAGE,Reason.CHUNK_MARKER_MISMATCH);unusable.add(marker.id);continue;
        }
        if(parts.computeIfAbsent(marker.id,k->new TreeMap<>()).putIfAbsent(index,r.get(4))!=null) {
          invalid(Domain.COMMIT_CHUNK_LINKAGE,Reason.DUPLICATE_CHUNK);unusable.add(marker.id);
        }
      } catch(RuntimeException invalid) { invalid(Domain.COMMIT_CHUNK_LINKAGE,Reason.INVALID_CHUNK);unusable.add(marker.id); }
    }
    for(var marker:markers.values()) {
      var chunks=parts.get(marker.id);
      if(chunks==null||chunks.size()!=marker.count) { invalid(Domain.COMMIT_CHUNK_LINKAGE,Reason.MISSING_CHUNKS);unusable.add(marker.id); }
      if(unusable.contains(marker.id)) { recordPrerequisite();continue; }
      long chars=0;for(String p:chunks.values())chars+=p.length();
      if(chars>ProductionReadOnlyEnvelope.MAX_PAYLOAD_CHARS) { unavailable(Domain.ENVELOPE_RULES,Reason.BUDGET_EXCEEDED);recordPrerequisite();continue; }
      try {
        var envelope=ProductionReadOnlyEnvelope.decode(marker.id,marker.match,marker.revision,String.join("",chunks.values()),marker.hash);
        decoded++;JsonNode root=envelope.payload();
        if(!ProductionReadOnlyEnvelope.STATISTICS_RULE.equals(root.path("match").path("statistics_rule").textValue()))unavailable(Domain.ENVELOPE_RULES,Reason.LEGACY_RULE_SEMANTICS_UNAVAILABLE);
        if(envelope.hasUnknownFields())unavailable(Domain.ENVELOPE_RULES,Reason.UNKNOWN_EXTENSION_SEMANTICS);
        if(!envelope.hasLifetimeRule())unavailable(Domain.LIFETIME_CONSISTENCY,Reason.LIFETIME_ELIGIBILITY_UNAVAILABLE);
        inspectRecord(root,retention,envelope.lifetimeSupported(),matches.get(marker.match)==1);
      } catch(ProductionReadOnlyEnvelope.Failure failure) {
        Reason reason=Reason.valueOf(failure.reason().name());
        mark(Domain.ENVELOPE_RULES,reason==Reason.INVALID_ENVELOPE?State.INVALID:State.UNAVAILABLE,reason);recordPrerequisite();
      }
    }
    if(!good(Domain.COMMIT_CHUNK_LINKAGE))recordPrerequisite();
    if(!good(Domain.REVISION_POLICY))unavailable(Domain.LIFETIME_CONSISTENCY,Reason.PREREQUISITE_UNVERIFIED);
  }
  private void recordPrerequisite() {
    for(var d:List.of(Domain.ENVELOPE_RULES,Domain.REVISION_POLICY,Domain.RECORD_TIMES,Domain.ACCOUNT_REFERENCES,
      Domain.ERASURE_REFERENCES,Domain.LIFETIME_CONSISTENCY,Domain.AGGREGATE_CONSISTENCY))unavailable(d,Reason.PREREQUISITE_UNVERIFIED);
  }
  private void inspectRecord(JsonNode root,Retention retention,boolean lifetime,boolean unique) {
    var match=root.path("match");Instant finish=null;
    try {
      Instant start=Instant.parse(match.path("started_at").textValue());finish=Instant.parse(match.path("finished_at").textValue());
      if(start.isAfter(finish)||finish.isAfter(now))throw bad();
      if(finish.isBefore(boundary))expired++;
      if(retention.valid && finish.isBefore(retention.cutoff))invalid(Domain.RECORD_TIMES,Reason.RECORD_BEFORE_RETENTION);
      if(aggregateQuarters.contains(quarter(finish)))unavailable(Domain.AGGREGATE_CONSISTENCY,Reason.AGGREGATE_RAW_OVERLAP);
    } catch(RuntimeException invalid) { invalid(Domain.RECORD_TIMES,Reason.INVALID_RECORD_TIME); }
    if(!retention.valid)unavailable(Domain.RECORD_TIMES,Reason.PREREQUISITE_UNVERIFIED);
    if(!good(Domain.ACCOUNTS))unavailable(Domain.ACCOUNT_REFERENCES,Reason.PREREQUISITE_UNVERIFIED);
    if(!good(Domain.ERASURE_LEDGER))unavailable(Domain.ERASURE_REFERENCES,Reason.PREREQUISITE_UNVERIFIED);
    if(lifetime)eligible++;
    for(var p:root.path("participants")) {
      for(String field:List.of("account_id","guest_id")) {
        JsonNode id=p.path(field);
        if(id.isMissingNode()||id.isNull())continue;
        if(!id.isTextual()||!uuid(id.textValue())) { invalid(Domain.ACCOUNT_REFERENCES,Reason.INVALID_ACCOUNT);continue; }
        if(field.equals("account_id")&&good(Domain.ACCOUNTS)&&!accounts.contains(id.textValue()))invalid(Domain.ACCOUNT_REFERENCES,Reason.MISSING_ACCOUNT_REFERENCE);
        if(erased(field.equals("account_id")?"ACCOUNT":"GUEST",id.textValue()))invalid(Domain.ERASURE_REFERENCES,Reason.ERASED_IDENTIFIER_PRESENT);
      }
      if(p.has("erased_marker")) {
        if(!uuid(p.path("erased_marker").textValue())||!p.path("account_id").isNull()||!p.path("guest_id").isNull())invalid(Domain.ERASURE_REFERENCES,Reason.INVALID_ENVELOPE);
        unavailable(Domain.ERASURE_REFERENCES,Reason.ERASED_MARKER_PROVENANCE_UNAVAILABLE);
      }
      String account=p.path("account_id").textValue();
      if(account!=null&&lifetime&&unique) {
        var carry=carries.get(account);
        if(carry!=null&&carry.through()!=null&&finish!=null&&finish.isBefore(carry.through()))invalid(Domain.LIFETIME_CONSISTENCY,Reason.CARRY_RAW_OVERLAP);
        try {
          var next=ProductionReadOnlyLifetime.contribution(match,p.path("team_id").textValue());
          var sum=contributions.computeIfAbsent(account,k->new ProductionReadOnlyLifetime.Counts[]{ProductionReadOnlyLifetime.Counts.zero(),ProductionReadOnlyLifetime.Counts.zero()});
          int category=match.path("match_category").asText().equals("FORMAL")?0:1;sum[category]=sum[category].plus(next);
        } catch(ProductionReadOnlyLifetime.Failure invalid) { invalid(Domain.LIFETIME_CONSISTENCY,Reason.CARRY_ARITHMETIC_INVALID); }
      }
    }
  }
  private void carryArithmetic() {
    for(var entry:contributions.entrySet()) {
      var carry=carries.get(entry.getKey());if(carry==null)continue;
      try {
        var formal=carry.formal().plus(entry.getValue()[0]);var casual=carry.casual().plus(entry.getValue()[1]);
        if(Math.addExact(formal.matches(),casual.matches())>9_007_199_254_740_991L)invalid(Domain.LIFETIME_CONSISTENCY,Reason.CARRY_ARITHMETIC_INVALID);
      }
      catch(ProductionReadOnlyLifetime.Failure invalid) {invalid(Domain.LIFETIME_CONSISTENCY,Reason.CARRY_ARITHMETIC_INVALID);}
    }
  }
  private boolean erased(String kind,String id) { return erasures.contains(kind+":"+digest(kind,id)); }
  static String digest(String kind,String id) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("bridge-erasure-v1\0"+kind+"\0"+id).getBytes(StandardCharsets.UTF_8))); }
    catch(Exception bad) {throw bad();}
  }
  static Instant quarterStart(Instant instant) {var local=instant.atZone(TAIPEI);return LocalDate.of(local.getYear(),((local.getMonthValue()-1)/3)*3+1,1).atStartOfDay(TAIPEI).toInstant();}
  static String quarter(Instant instant) {var local=instant.atZone(TAIPEI);return local.getYear()+"-Q"+((local.getMonthValue()-1)/3+1);}
  private static Instant quarterText(String text) {if(!text.matches("[0-9]{4}-Q[1-4]"))throw bad();return LocalDate.of(Integer.parseInt(text.substring(0,4)),(text.charAt(6)-'1')*3+1,1).atStartOfDay(TAIPEI).toInstant();}
  private static int number(String s,int min,int max) {if(s==null||!s.matches("0|[1-9][0-9]{0,9}"))throw bad();int n=Integer.parseInt(s);if(n<min||n>max)throw bad();return n;}
  private static boolean hashText(String text) {return text!=null&&text.matches("[0-9a-f]{64}");}
  private static boolean uuid(String text) {try{return text!=null&&UUID.fromString(text).toString().equals(text);}catch(RuntimeException invalid){return false;}}
  private static IllegalArgumentException bad() {return new IllegalArgumentException("Read-only business data rejected");}
}
