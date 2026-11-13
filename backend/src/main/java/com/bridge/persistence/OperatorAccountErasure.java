package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Offline operator service, deliberately NOT a Spring bean, controller, or public endpoint.
 * Execution is disabled unless explicitly enabled by an offline harness. The harness must
 * prove every app/identity writer is stopped; a digest is NOT a distributed lock or CAS.
 */
public final class OperatorAccountErasure {
    public record Challenge(String nonce, Instant expiresAt) {}
    public record Preview(String planId,String beforeDigest,String afterDigest,int affectedMatches,
                          int affectedSeats,boolean alreadyErased,Instant expiresAt,String confirmationText) {}
    public enum Outcome { VERIFIED, ALREADY_ERASED, NOT_APPLIED, UNCERTAIN }
    private record Plan(Preview publicView,String identityHash,Optional<String> snapshotFingerprint,
                        Map<String,List<List<String>>> replacement) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Duration TTL=Duration.ofMinutes(5);
    private static final Map<String,List<String>> HEADERS=Map.of(
        "envelopes",List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk"),
        "commits",List.of("commit_id","payload_hash","chunk_count","match_id","revision"),
        "accounts",List.of("account_id","schema_version"),
        "auth_identities",List.of("issuer","subject","account_id","schema_version"),
        "erasure_ledger",ErasureLedger.HEADER);
    private static final Set<String> PERSON_FIELDS=Set.of("match_id","seat","team_id","player_type","settlement_control", "control_type","account_id","guest_id","erased_marker");
    private static final Set<String> MATCH_FIELDS=Set.of("lifetime_rule", "room_type", "deal_timing_rule", "match_category", "statistics_rule","match_id","revision","schema_version","score_version","app_version","started_at","finished_at","planned_deals","completed_deals","outcome","winner_team_id","reason","ns_total","ew_total");
    private static final Set<String> DEAL_FIELDS=Set.of("elapsed_ms","match_id","deal_id","deal_index","contract_type","contract_level","contract_strain","attacking_team_id","ns_tricks","ew_tricks","ns_privilege_used","ns_base","ns_exact_bonus","ns_penalty","ns_privilege_adjustment","ns_total","ew_privilege_used","ew_base","ew_exact_bonus","ew_penalty","ew_privilege_adjustment","ew_total");
    private final SheetsGateway gateway;
    private final GoogleCredentialVerifier verifier;
    private final String audience;
    private final Clock clock;
    private final BooleanSupplier exclusiveOffline;
    private final boolean executionEnabled;
    private final Map<String,Instant> challenges=new LinkedHashMap<>();
    private final Map<String,Plan> plans=new LinkedHashMap<>();

    /** Default constructor is dry-run only, even with valid operator confirmation. */
    public OperatorAccountErasure(SheetsGateway gateway,GoogleCredentialVerifier verifier,String audience,
                                  Clock clock,BooleanSupplier exclusiveOffline) {
        this(gateway,verifier,audience,clock,exclusiveOffline,false);
    }
    public OperatorAccountErasure(SheetsGateway gateway,GoogleCredentialVerifier verifier,String audience,
                                  Clock clock,BooleanSupplier exclusiveOffline,boolean executionEnabled) {
        this.gateway=Objects.requireNonNull(gateway);this.verifier=Objects.requireNonNull(verifier);
        if(audience==null||audience.isBlank())throw new IllegalArgumentException("Verified OAuth audience required");
        this.audience=audience;this.clock=Objects.requireNonNull(clock);this.exclusiveOffline=Objects.requireNonNull(exclusiveOffline);this.executionEnabled=executionEnabled;
    }
    public synchronized Challenge challenge() {
        prune();if(challenges.size()>=16)throw new IllegalStateException("Too many ownership challenges");
        String nonce=UUID.randomUUID().toString()+UUID.randomUUID();Instant expires=clock.instant().plus(TTL);challenges.put(nonce,expires);return new Challenge(nonce,expires);
    }
    private GoogleCredentialVerifier.Principal verify(String credential,String nonce) {
        Instant expires=challenges.remove(nonce);
        if(expires==null||!expires.isAfter(clock.instant()))throw new IllegalArgumentException("Fresh ownership challenge required");
        GoogleCredentialVerifier.Principal principal;
        try { principal=verifier.verify(credential,audience,nonce); }catch(RuntimeException invalid){throw new IllegalArgumentException("Ownership verification failed");}
        String issuer=principal.issuer();if("accounts.google.com".equals(issuer))issuer="https://accounts.google.com";
        if(!"https://accounts.google.com".equals(issuer)||principal.subject()==null||principal.subject().isBlank())throw new IllegalArgumentException("Ownership verification failed");
        return new GoogleCredentialVerifier.Principal(issuer,principal.subject());
    }
    private void requireOffline() {if(!exclusiveOffline.getAsBoolean())throw new IllegalStateException("Exclusive offline maintenance is required");}
    public synchronized Preview preview(String credential,String challengeNonce) {
        requireOffline();prune();if(plans.size()>=16)throw new IllegalStateException("Too many erasure plans");
        var owner=verify(credential,challengeNonce);
        Map<String,List<List<String>>> before=readSnapshot();
        Optional<String> snapshotFingerprint=gateway.snapshotFingerprint();
        String identityHash=ErasureLedger.digest("IDENTITY",ErasureLedger.identityKey(owner.issuer(),owner.subject()));
        ErasureLedger ledger=new ErasureLedger(before.get("erasure_ledger"));
        String account=null;
        for(List<String> row:data(before,"auth_identities"))if(row.get(0).equals(owner.issuer())&&row.get(1).equals(owner.subject())) {
            if(account!=null&&!account.equals(row.get(2)))throw new SheetsGateway.Conflict();account=row.get(2);
        }
        boolean already=ledger.contains("IDENTITY",ErasureLedger.identityKey(owner.issuer(),owner.subject()));
        if(already&&account!=null)throw new SheetsGateway.Conflict();
        if(account==null&&!already)throw new IllegalArgumentException("Verified identity has no account in this dataset");
        Map<String,List<List<String>>> after=deepCopy(before);
        int affectedMatches=0,affectedSeats=0;
        if(!already) {
            final String accountId=account;
            var ledgerRows=after.get("erasure_ledger");
            addTombstone(ledgerRows,"ACCOUNT",accountId);
            for(List<String> row:data(before,"auth_identities"))if(row.get(2).equals(accountId))addTombstone(ledgerRows,"IDENTITY",ErasureLedger.identityKey(row.get(0),row.get(1)));
            after.get("accounts").removeIf(row->row.get(0).equals(accountId));
            after.get("auth_identities").removeIf(row->row.size()==4&&row.get(2).equals(accountId));
            List<FinalMatchEnvelope> envelopes=verifiedEnvelopes(before);
            after.put("envelopes",new ArrayList<>(List.of(HEADERS.get("envelopes"))));after.put("commits",new ArrayList<>(List.of(HEADERS.get("commits"))));
            for(FinalMatchEnvelope envelope:envelopes) {
                ObjectNode payload=(ObjectNode)envelope.payload();
                Set<String> rootFields=new HashSet<>();payload.fieldNames().forEachRemaining(rootFields::add);
                if(!rootFields.equals(HistoricalCasualAdmission.rootFields(payload)))throw new SheetsGateway.Conflict();
                requireFields(payload.path("match"),MATCH_FIELDS);
                for(JsonNode deal:payload.path("completed_deals"))requireFields(deal,DEAL_FIELDS);
                boolean changed=false;
                for(JsonNode person:payload.path("participants")) {
                    Set<String> fields=new HashSet<>();person.fieldNames().forEachRemaining(fields::add);
                    if(!PERSON_FIELDS.containsAll(fields))throw new SheetsGateway.Conflict();
                    if(person.path("account_id").asText().equals(accountId)) {
                        ObjectNode target=(ObjectNode)person;
                        // The production schema prohibits simultaneous account_id and guest_id.
                        // Never infer ownership of guest-only history from a Google account.
                        target.putNull("account_id");target.putNull("guest_id");
                        target.put("erased_marker",UUID.randomUUID().toString());changed=true;affectedSeats++;
                    }
                }
                if(changed){HistoricalCasualAdmission.afterAccountErasure(payload);affectedMatches++;envelope=FinalMatchEnvelope.create(envelope.matchId(),envelope.revision(),JSON.convertValue(payload,Map.class));}
                if(envelope.canonicalJson().contains(accountId))throw new SheetsGateway.Conflict();
                appendEnvelope(after,envelope);
            }
        }
        validateSnapshot(after);String beforeDigest=digest(before),afterDigest=digest(after);
        String id=UUID.randomUUID().toString();Instant expiry=clock.instant().plus(TTL);
        String confirmation="ERASE "+id+" "+beforeDigest+" EXCLUSIVE OFFLINE CONFIRMED";
        Preview view=new Preview(id,beforeDigest,afterDigest,affectedMatches,affectedSeats,already,expiry,confirmation);
        plans.put(id,new Plan(view,identityHash,snapshotFingerprint,deepCopy(after)));return view;
    }
    /** Fresh proof plus exact confirmation required. Never retries a destructive write blindly. */
    public synchronized Outcome execute(String planId,String expectedWholeDatasetDigest,String explicitConfirmation,
                                        String freshCredential,String freshChallengeNonce) {
        if(!executionEnabled)throw new IllegalStateException("Execution disabled: dry-run only");requireOffline();
        Plan plan=plans.get(planId);if(plan==null||!plan.publicView.expiresAt().isAfter(clock.instant()))throw new IllegalArgumentException("Fresh preview required");
        if(!plan.publicView.beforeDigest().equals(expectedWholeDatasetDigest)||!plan.publicView.confirmationText().equals(explicitConfirmation))throw new IllegalArgumentException("Explicit operator confirmation required");
        var owner=verify(freshCredential,freshChallengeNonce);
        if(!plan.identityHash.equals(ErasureLedger.digest("IDENTITY",ErasureLedger.identityKey(owner.issuer(),owner.subject()))))throw new IllegalArgumentException("Ownership does not match preview");
        Map<String,List<List<String>>> current=readSnapshot();String actual=digest(current);
        if(actual.equals(plan.publicView.afterDigest())&&!actual.equals(plan.publicView.beforeDigest()))return Outcome.VERIFIED;
        if(!actual.equals(plan.publicView.beforeDigest()))throw new IllegalStateException("Dataset changed; fresh preview required");
        // Bind metadata-capable adapters to the preview too, not merely the final pre-write read.
        if(!plan.snapshotFingerprint.equals(gateway.snapshotFingerprint()))throw new IllegalStateException("Dataset changed; fresh preview required");
        if(plan.publicView.alreadyErased())return Outcome.ALREADY_ERASED;
        requireOffline();
        Map<String,List<List<Object>>> replacement=new TreeMap<>();
        for(String tab:HEADERS.keySet())replacement.put(tab,plan.replacement.get(tab).stream().map(r->new ArrayList<Object>(r)).map(r->(List<Object>)r).toList());
        try { gateway.replaceAtomic(replacement); }
        catch(SheetsGateway.Failure uncertain) { return verifyOutcome(plan); }
        return verifyOutcome(plan);
    }
    private Outcome verifyOutcome(Plan plan) {
        try {requireOffline();String actual=digest(readSnapshot());if(actual.equals(plan.publicView.afterDigest()))return Outcome.VERIFIED;if(actual.equals(plan.publicView.beforeDigest()))return Outcome.NOT_APPLIED;return Outcome.UNCERTAIN;}
        catch(RuntimeException unavailable){return Outcome.UNCERTAIN;}
    }
    private Map<String,List<List<String>>> readSnapshot() {
        Map<String,List<List<String>>> snapshot=new TreeMap<>();for(String tab:HEADERS.keySet())snapshot.put(tab,gateway.read(tab));snapshot.put("dataset",gateway.read("dataset"));validateSnapshot(snapshot);return snapshot;
    }
    private static void validateSnapshot(Map<String,List<List<String>>> snapshot) {
        for(var entry:HEADERS.entrySet()) {
            List<List<String>> rows=snapshot.get(entry.getKey());
            if(entry.getKey().equals("accounts")){AccountProfileRows.validateTable(rows);continue;}
            if(rows==null||rows.isEmpty()||!rows.get(0).equals(entry.getValue()))throw new SheetsGateway.Conflict();
            for(List<String> row:rows)if(row.size()!=entry.getValue().size())throw new SheetsGateway.Conflict();
        }
        if(snapshot.get("dataset")==null||snapshot.get("dataset").isEmpty())throw new SheetsGateway.Conflict();
        ErasureLedger ledger=new ErasureLedger(snapshot.get("erasure_ledger"));Set<String> accounts=new HashSet<>();Map<String,String> identities=new HashMap<>();
        for(List<String> row:data(snapshot,"accounts")) {if(ledger.contains("ACCOUNT",row.get(0)))throw new SheetsGateway.Conflict();try {UUID.fromString(row.get(0));}catch(Exception e){throw new SheetsGateway.Conflict();}accounts.add(row.get(0));}
        for(List<String> row:data(snapshot,"auth_identities")) {
            if(ledger.contains("IDENTITY",ErasureLedger.identityKey(row.get(0),row.get(1)))||!row.get(0).equals("https://accounts.google.com")||row.get(1).isBlank()||!row.get(3).equals("1")||!accounts.contains(row.get(2)))throw new SheetsGateway.Conflict();
            String previous=identities.putIfAbsent(ErasureLedger.identityKey(row.get(0),row.get(1)),row.get(2));if(previous!=null&&!previous.equals(row.get(2)))throw new SheetsGateway.Conflict();
        }
        verifiedEnvelopes(snapshot);
    }
    private static List<FinalMatchEnvelope> verifiedEnvelopes(Map<String,List<List<String>>> snapshot) {
        SheetsGateway memory=new SheetsGateway(){public List<List<String>> read(String tab){return snapshot.get(tab);}public void appendAtomic(Map<String,List<List<Object>>> ignored){throw new UnsupportedOperationException();}};
        return List.copyOf(SheetsStatsStore.readVerified(memory).values());
    }
    private static void requireFields(JsonNode node,Set<String> allowed) {Set<String> fields=new HashSet<>();node.fieldNames().forEachRemaining(fields::add);if(!node.isObject()||!allowed.containsAll(fields))throw new SheetsGateway.Conflict();}
    private static List<List<String>> data(Map<String,List<List<String>>> snapshot,String tab){return snapshot.get(tab).subList(1,snapshot.get(tab).size());}
    private static void addTombstone(List<List<String>> rows,String kind,String id){List<String> row=List.of(kind,ErasureLedger.digest(kind,id),"1");if(!rows.contains(row))rows.add(row);}
    private static void appendEnvelope(Map<String,List<List<String>>> target,FinalMatchEnvelope e) {
        List<String> parts=new ArrayList<>();String json=e.canonicalJson();for(int start=0;start<json.length();) {int end=Math.min(start+20000,json.length());if(end<json.length()&&Character.isHighSurrogate(json.charAt(end-1)))end--;parts.add(json.substring(start,end));start=end;}
        for(int i=0;i<parts.size();i++)target.get("envelopes").add(List.of(e.commitId(),e.payloadHash(),Integer.toString(i),Integer.toString(parts.size()),parts.get(i)));
        target.get("commits").add(List.of(e.commitId(),e.payloadHash(),Integer.toString(parts.size()),e.matchId(),Integer.toString(e.revision())));
    }
    private static Map<String,List<List<String>>> deepCopy(Map<String,List<List<String>>> source){Map<String,List<List<String>>> copy=new TreeMap<>();source.forEach((k,v)->copy.put(k,new ArrayList<>(v.stream().map(List::copyOf).toList())));return copy;}
    private static String digest(Map<String,List<List<String>>> snapshot){try{return FinalMatchEnvelope.hash(JSON.writeValueAsString(new TreeMap<>(snapshot)));}catch(Exception e){throw new IllegalStateException("Cannot fingerprint dataset");}}
    private void prune(){Instant now=clock.instant();challenges.values().removeIf(t->!t.isAfter(now));plans.values().removeIf(p->!p.publicView.expiresAt().isAfter(now));}
}
