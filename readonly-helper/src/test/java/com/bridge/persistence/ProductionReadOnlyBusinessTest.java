package com.bridge.persistence;

import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyBusiness.*;

/** Synthetic in-memory tables only. No external reads, identities or storage adapters. */
class ProductionReadOnlyBusinessTest {
  static final Instant NOW=Instant.parse("2026-11-11T12:00:00Z"),Q4=Instant.parse("2026-09-30T16:00:00Z");
  static final String ACCOUNT="00000000-0000-0000-0000-000000000010";
  static Map<String,List<List<String>>> tables() {
    var out=new LinkedHashMap<String,List<List<String>>>();
    ProductionReadOnlyWorkbook.HEADERS.forEach((k,v)->out.put(k,new ArrayList<>(List.of(v))));
    out.get("dataset").add(List.of("bridgego-managed",ProductionReadOnlyWorkbookTest.DATASET));
    out.get("erasure_ledger").add(List.of("META","erasure-ledger-v1","1"));
    out.get("retention_ledger").add(List.of("2026-Q4",Q4.toString(),"COMPLETE",Q4.toString(),"1"));
    out.get("accounts").add(List.of(ACCOUNT,"2","SYNTHETIC_PRIVATE_NAME","synthetic@example.invalid"));
    out.get("auth_identities").add(List.of("https://accounts.google.com","SYNTHETIC_PRIVATE_SUBJECT",ACCOUNT,"1"));
    return out;
  }
  static ObjectNode payload() {
    var root=ProductionReadOnlyEnvelopeTest.fixed();
    ProductionReadOnlyEnvelopeTest.match(root).put("lifetime_rule",ProductionReadOnlyLifetime.RULE)
      .put("started_at","2026-10-01T00:00:00Z").put("finished_at","2026-10-01T00:01:00Z");
    ProductionReadOnlyEnvelopeTest.person(root,0).put("account_id",ACCOUNT);return root;
  }
  static void append(Map<String,List<List<String>>> tables,ObjectNode payload,int chunkSize) throws Exception {
    var m=payload.path("match");String json=ProductionReadOnlyEnvelopeTest.json(payload),hash=ProductionReadOnlyEnvelopeTest.hash(json);
    String id=m.path("match_id").asText()+":"+m.path("revision").intValue();int count=(json.length()+chunkSize-1)/chunkSize;
    tables.get("commits").add(List.of(id,hash,Integer.toString(count),m.path("match_id").asText(),m.path("revision").asText()));
    for(int i=0;i<count;i++)tables.get("envelopes").add(List.of(id,hash,Integer.toString(i),Integer.toString(count),json.substring(i*chunkSize,Math.min(json.length(),(i+1)*chunkSize))));
  }
  static void append(Map<String,List<List<String>>> tables,ObjectNode payload) throws Exception {append(tables,payload,20000);}
  static void set(Map<String,List<List<String>>> t,String tab,int row,int col,String value) {var next=new ArrayList<>(t.get(tab).get(row));next.set(col,value);t.get(tab).set(row,List.copyOf(next));}
  static Summary inspect(Map<String,List<List<String>>> tables) {return ProductionReadOnlyBusiness.inspect(tables,NOW);}
  static void reason(Summary s,Domain d,State state,Reason reason) {assertEquals(state,s.checks().get(d).state(),d.toString());assertTrue(s.checks().get(d).reasons().getOrDefault(reason,0)>0,reason.toString());assertFalse(s.verified());}
  static String carry(Instant through) {
    var json=ProductionReadOnlyEnvelopeTest.JSON.createObjectNode();
    json.put("schema_version",1).put("rule",ProductionReadOnlyLifetime.RULE).put("score_version",ProductionReadOnlyLifetime.SCORE_VERSION)
      .put("through",through.toString()).put("trackedFromQuarter","2026-Q2");
    json.set("formal",ProductionReadOnlyEnvelopeTest.JSON.valueToTree(new ProductionReadOnlyLifetime.Counts(1,1,1,0,0,7,1,0,0)));
    json.set("casual",ProductionReadOnlyEnvelopeTest.JSON.valueToTree(ProductionReadOnlyLifetime.Counts.zero()));return json.toString();
  }
  static void addCarry(Map<String,List<List<String>>> t,String json) {t.get("accounts").set(0,List.of("account_id","schema_version","nickname","email","lifetime_statistics"));t.get("accounts").set(1,List.of(ACCOUNT,"2","SYNTHETIC_PRIVATE_NAME","synthetic@example.invalid",json));}
  @Test void completeNarrowContractVerifiesWithoutMutationAuthority() throws Exception {
    var t=tables();append(t,payload(),128);Collections.reverse(t.get("envelopes").subList(1,t.get("envelopes").size()));
    var s=inspect(t);assertEquals(State.VERIFIED,s.state(),s.checks().toString());assertEquals(1,s.decodedRecords());assertEquals(1,s.lifetimeEligibleRecords());
    assertTrue(s.verified());assertFalse(s.mutationPermitted());assertFalse(s.writerExclusionVerified());assertFalse(s.coherentSnapshotVerified());
    assertThrows(UnsupportedOperationException.class,()->s.checks().clear());
    assertThrows(UnsupportedOperationException.class,()->s.checks().get(Domain.ACCOUNTS).reasons().clear());
  }
  @Test void structuralFixtureIsNotUpgradedToBusinessValid() {
    var report=ProductionReadOnlyWorkbookTest.inspect(ProductionReadOnlyWorkbookTest.fixture());
    assertEquals(State.INVALID,report.businessSemantics().state());assertFalse(report.businessSemanticsVerified());
  }
  @Test void headerOnlyRecordsNeedNoInventedIdentityOrAggregateHistory() {
    var t=tables();t.get("auth_identities").subList(1,2).clear();assertTrue(inspect(t).verified());
    t.get("accounts").subList(1,2).clear();assertTrue(inspect(t).verified());
  }
  @Test void allAccountHeadersAndBlankCarryAreSupported() {
    for(int width:List.of(2,4,5)) {var t=tables();var h=List.of("account_id","schema_version","nickname","email","lifetime_statistics").subList(0,width);
      t.get("accounts").set(0,h);t.get("accounts").set(1,List.of(ACCOUNT,"1","","","").subList(0,width));assertTrue(inspect(t).verified());}
  }
  @Test void independentFailuresAreAggregatedRatherThanStoppingAtFirst() throws Exception {
    var t=tables();append(t,payload());set(t,"envelopes",1,4,"SYNTHETIC_PRIVATE_BAD_JSON");set(t,"accounts",1,2,"");set(t,"retention_ledger",1,1,"2026-10-01T00:00:00Z");
    var s=inspect(t);reason(s,Domain.ENVELOPE_RULES,State.INVALID,Reason.INVALID_ENVELOPE);
    reason(s,Domain.ACCOUNTS,State.INVALID,Reason.INVALID_ACCOUNT);reason(s,Domain.RETENTION_LEDGER,State.INVALID,Reason.INVALID_RETENTION_LEDGER);
    reason(s,Domain.RECORD_TIMES,State.UNAVAILABLE,Reason.PREREQUISITE_UNVERIFIED);
    assertEquals(State.VERIFIED,s.checks().get(Domain.ERASURE_LEDGER).state());
  }
  @Test void malformedCommitChunkReferencesAndMissingPartsFailClosed() throws Exception {
    for(int mode=0;mode<6;mode++) {var t=tables();append(t,payload(),128);
      switch(mode) {case 0->set(t,"envelopes",1,0,"orphan");case 1->set(t,"envelopes",1,1,"0".repeat(64));case 2->t.get("envelopes").remove(1);
        case 3->t.get("envelopes").add(t.get("envelopes").get(1));case 4->set(t,"envelopes",1,2,"9999");case 5->set(t,"commits",1,2,"10000");}
      var s=inspect(t);assertEquals(State.INVALID,s.checks().get(Domain.COMMIT_CHUNK_LINKAGE).state());assertEquals(0,s.decodedRecords());assertFalse(s.verified());}
  }
  @Test void invalidRecordDoesNotHideValidLaterRecordOrIndependentLedgers() throws Exception {
    var t=tables();append(t,payload());set(t,"envelopes",1,4,"invalid");var other=payload();replaceMatch(other,"00000000-0000-0000-0000-000000000090",1);append(t,other);
    var s=inspect(t);assertEquals(1,s.decodedRecords());reason(s,Domain.ENVELOPE_RULES,State.INVALID,Reason.INVALID_ENVELOPE);assertEquals(State.VERIFIED,s.checks().get(Domain.ACCOUNTS).state());
  }
  static void replaceMatch(ObjectNode p,String id,int revision) {ProductionReadOnlyEnvelopeTest.match(p).put("match_id",id).put("revision",revision);for(var person:p.path("participants"))((ObjectNode)person).put("match_id",id);for(var deal:p.path("completed_deals"))((ObjectNode)deal).put("match_id",id);}
  @Test void duplicatesAndHistoricalRevisionsAreNeverSilentlyLatestCollapsed() throws Exception {
    var t=tables();append(t,payload());t.get("commits").add(t.get("commits").get(1));reason(inspect(t),Domain.COMMIT_CHUNK_LINKAGE,State.INVALID,Reason.DUPLICATE_COMMIT);
    t=tables();append(t,payload());var p=payload();replaceMatch(p,p.path("match").path("match_id").asText(),2);append(t,p);
    var s=inspect(t);reason(s,Domain.REVISION_POLICY,State.UNAVAILABLE,Reason.MULTIPLE_MATCH_REVISIONS);assertEquals(2,s.decodedRecords());
    t=tables();append(t,p);reason(inspect(t),Domain.REVISION_POLICY,State.UNAVAILABLE,Reason.REVISION_HISTORY_UNAVAILABLE);
  }
  @Test void unknownSchemaRuleAndScoreAreUnavailableNotMalformedData() throws Exception {
    for(String field:List.of("schema_version","statistics_rule","score_version")) {var t=tables();var p=payload();if(field.equals("schema_version"))ProductionReadOnlyEnvelopeTest.match(p).put(field,2);else ProductionReadOnlyEnvelopeTest.match(p).put(field,"unknown-private-value");append(t,p);
      assertEquals(State.UNAVAILABLE,inspect(t).checks().get(Domain.ENVELOPE_RULES).state());}
  }
  @Test void legacyRulesRemainReadableButDoNotGainModernSemantics() throws Exception {
    for(String rule:List.of("",ProductionReadOnlyEnvelope.LEGACY_STATISTICS_RULE,ProductionReadOnlyEnvelope.SETTLEMENT_STATISTICS_RULE)) {
      var t=tables();var p=rule.isEmpty()?ProductionReadOnlyEnvelopeTest.legacy():ProductionReadOnlyEnvelopeTest.marked(rule);
      ProductionReadOnlyEnvelopeTest.match(p).put("started_at","2026-10-01T00:00:00Z").put("finished_at","2026-10-01T00:01:00Z");append(t,p);
      var s=inspect(t);assertEquals(1,s.decodedRecords());reason(s,Domain.ENVELOPE_RULES,State.UNAVAILABLE,Reason.LEGACY_RULE_SEMANTICS_UNAVAILABLE);
      reason(s,Domain.LIFETIME_CONSISTENCY,State.UNAVAILABLE,Reason.LIFETIME_ELIGIBILITY_UNAVAILABLE);
    }
  }
  @Test void unknownExtensionIsVisibleUnavailable() throws Exception {var t=tables();var p=payload();p.put("private-future-field",true);append(t,p);reason(inspect(t),Domain.ENVELOPE_RULES,State.UNAVAILABLE,Reason.UNKNOWN_EXTENSION_SEMANTICS);}
  @Test void identityDuplicatePolicyIsExplicitAndConflictsRejected() {
    var t=tables();t.get("auth_identities").add(t.get("auth_identities").get(1));var s=inspect(t);assertTrue(s.verified());assertEquals(1,s.checks().get(Domain.AUTH_IDENTITIES).reasons().get(Reason.DUPLICATE_IDENTITY_EQUIVALENT));
    set(t,"auth_identities",2,2,"00000000-0000-0000-0000-000000000099");reason(inspect(t),Domain.AUTH_IDENTITIES,State.INVALID,Reason.CONFLICTING_IDENTITY);
  }
  @Test void missingAccountAndInvalidIdentitySchemaDetected() throws Exception {
    var t=tables();t.get("accounts").subList(1,2).clear();append(t,payload());var s=inspect(t);
    reason(s,Domain.AUTH_IDENTITIES,State.INVALID,Reason.MISSING_ACCOUNT_REFERENCE);reason(s,Domain.ACCOUNT_REFERENCES,State.INVALID,Reason.MISSING_ACCOUNT_REFERENCE);
    t=tables();set(t,"auth_identities",1,3,"2");reason(inspect(t),Domain.AUTH_IDENTITIES,State.UNAVAILABLE,Reason.UNSUPPORTED_IDENTITY_SCHEMA);
  }
  @Test void noReverseAccountIdentityRequirementAndSubjectWhitespaceNotInvented() {var t=tables();set(t,"auth_identities",1,1," synthetic ");assertTrue(inspect(t).verified());}
  @Test void erasureTombstonesBlockRestoredAccountsAndIdentities() throws Exception {
    var t=tables();append(t,payload());t.get("erasure_ledger").add(List.of("ACCOUNT",digest("ACCOUNT",ACCOUNT),"1"));
    t.get("erasure_ledger").add(List.of("IDENTITY",digest("IDENTITY","https://accounts.google.com\0SYNTHETIC_PRIVATE_SUBJECT"),"1"));
    var s=inspect(t);reason(s,Domain.ERASURE_REFERENCES,State.INVALID,Reason.ERASED_IDENTIFIER_PRESENT);assertTrue(s.checks().get(Domain.ERASURE_REFERENCES).reasons().get(Reason.ERASED_IDENTIFIER_PRESENT)>=3);
  }
  @Test void guestErasureAndRandomErasedMarkerProvenanceAreDistinct() throws Exception {
    var t=tables();var p=ProductionReadOnlyEnvelopeTest.legacy();ProductionReadOnlyEnvelopeTest.person(p,0).put("guest_id",ACCOUNT);
    ProductionReadOnlyEnvelopeTest.match(p).put("started_at","2026-10-01T00:00:00Z").put("finished_at","2026-10-01T00:01:00Z");append(t,p);t.get("erasure_ledger").add(List.of("GUEST",digest("GUEST",ACCOUNT),"1"));reason(inspect(t),Domain.ERASURE_REFERENCES,State.INVALID,Reason.ERASED_IDENTIFIER_PRESENT);
    t=tables();p=payload();ProductionReadOnlyEnvelopeTest.person(p,0).putNull("account_id").put("erased_marker",ACCOUNT);append(t,p);
    reason(inspect(t),Domain.ERASURE_REFERENCES,State.UNAVAILABLE,Reason.ERASED_MARKER_PROVENANCE_UNAVAILABLE);
  }
  @Test void sentinelAndTombstoneGrammarAreCheckedAndEqualDuplicatesDocumented() {
    var t=tables();t.get("erasure_ledger").clear();t.get("erasure_ledger").add(ProductionReadOnlyWorkbook.HEADERS.get("erasure_ledger"));reason(inspect(t),Domain.ERASURE_LEDGER,State.INVALID,Reason.INVALID_ERASURE_LEDGER);
    t=tables();var row=List.of("ACCOUNT","a".repeat(64),"1");t.get("erasure_ledger").add(row);t.get("erasure_ledger").add(row);assertTrue(inspect(t).verified());assertEquals(1,inspect(t).checks().get(Domain.ERASURE_LEDGER).reasons().get(Reason.DUPLICATE_TOMBSTONE_EQUIVALENT));
    set(t,"erasure_ledger",2,1,"not-a-digest");reason(inspect(t),Domain.ERASURE_LEDGER,State.INVALID,Reason.INVALID_ERASURE_LEDGER);
  }
  @Test void retentionStatesBoundariesAndHistoricalPendingAreDifferent() {
    var t=tables();set(t,"retention_ledger",1,2,"PENDING");set(t,"retention_ledger",1,3,"");reason(inspect(t),Domain.RETENTION_STATE,State.UNAVAILABLE,Reason.RETENTION_PENDING);
    t=tables();t.get("retention_ledger").add(List.of("2026-Q3","2026-06-30T16:00:00Z","PENDING","","1"));assertTrue(inspect(t).verified());
    t=tables();t.get("retention_ledger").set(1,List.of("2026-Q3","2026-06-30T16:00:00Z","COMPLETE","2026-07-01T00:00:00Z","1"));reason(inspect(t),Domain.RETENTION_STATE,State.UNAVAILABLE,Reason.RETENTION_STALE);
    t=tables();set(t,"retention_ledger",1,1,"2026-10-01T00:00:00Z");reason(inspect(t),Domain.RETENTION_LEDGER,State.INVALID,Reason.INVALID_RETENTION_LEDGER);
  }
  @Test void duplicateFutureAndIncompleteRetentionRowsAreRejected() {
    var t=tables();t.get("retention_ledger").add(t.get("retention_ledger").get(1));reason(inspect(t),Domain.RETENTION_LEDGER,State.INVALID,Reason.DUPLICATE_RETENTION_QUARTER);
    t=tables();set(t,"retention_ledger",1,3,"2027-01-01T00:00:00Z");reason(inspect(t),Domain.RETENTION_LEDGER,State.INVALID,Reason.FUTURE_RETENTION);
    t=tables();set(t,"retention_ledger",1,2,"PENDING");reason(inspect(t),Domain.RETENTION_LEDGER,State.INVALID,Reason.INVALID_RETENTION_LEDGER);
  }
  @Test void timestampCutoffExclusiveAndStartMayPrecedeQuarter() throws Exception {
    var t=tables();var p=payload();ProductionReadOnlyEnvelopeTest.match(p).put("started_at","2026-09-29T00:00:00Z").put("finished_at",Q4.toString());append(t,p);assertTrue(inspect(t).verified());
    t=tables();ProductionReadOnlyEnvelopeTest.match(p).put("finished_at",Q4.minusSeconds(1).toString());append(t,p);reason(inspect(t),Domain.RECORD_TIMES,State.INVALID,Reason.RECORD_BEFORE_RETENTION);
  }
  @Test void futureTimestampAndMalformedTimeAreInvalid() throws Exception {for(String end:List.of("bad-time","2027-01-01T00:00:00Z")) {var t=tables();var p=payload();ProductionReadOnlyEnvelopeTest.match(p).put("finished_at",end);append(t,p);assertEquals(State.INVALID,inspect(t).state());}}
  @Test void carryLocalEquationsDoNotProveHistoricalDerivation() throws Exception {
    var t=tables();addCarry(t,carry(Q4));append(t,payload());var s=inspect(t);assertEquals(State.VERIFIED,s.checks().get(Domain.LIFETIME_CARRY).state());reason(s,Domain.LIFETIME_CONSISTENCY,State.UNAVAILABLE,Reason.CARRY_HISTORY_UNAVAILABLE);
    addCarry(t,carry(Instant.parse("2026-12-31T16:00:00Z")));reason(inspect(t),Domain.LIFETIME_CONSISTENCY,State.INVALID,Reason.CARRY_AFTER_RETENTION);
  }
  @Test void carryOverlapUnknownVersionAndMalformedJsonFailClosed() throws Exception {
    var t=tables();addCarry(t,carry(Q4));var p=payload();ProductionReadOnlyEnvelopeTest.match(p).put("started_at","2026-09-01T00:00:00Z").put("finished_at","2026-09-01T01:00:00Z");append(t,p);reason(inspect(t),Domain.LIFETIME_CONSISTENCY,State.INVALID,Reason.CARRY_RAW_OVERLAP);
    t=tables();addCarry(t,carry(Q4).replace("account-career-v1","unknown-private-rule"));reason(inspect(t),Domain.LIFETIME_CARRY,State.UNAVAILABLE,Reason.UNSUPPORTED_CARRY_RULE);
    for(String field:List.of("rule","score_version")) {
      var future=(ObjectNode)ProductionReadOnlyEnvelopeTest.JSON.readTree(carry(Q4));
      future.put(field,"unknown-private-value").put("future-field","unknown");addCarry(t,future.toString());
      reason(inspect(t),Domain.LIFETIME_CARRY,State.UNAVAILABLE,field.equals("rule")?Reason.UNSUPPORTED_CARRY_RULE:Reason.UNSUPPORTED_SCORE_VERSION);
    }
    addCarry(t,"SYNTHETIC_PRIVATE_BAD_JSON");reason(inspect(t),Domain.LIFETIME_CARRY,State.INVALID,Reason.INVALID_CARRY);
  }
  @Test void aggregatePolicyCannotBeReconstructedAndNoFalseConservationRule() {
    for(var row:List.of(List.of("2026-Q3","SUPPRESSED","","","1"),List.of("2026-Q3","CANDIDATE_COUNTS","0","9999990","1"))) {
      var t=tables();t.get("quarterly_aggregates").add(row);var s=inspect(t);assertEquals(State.VERIFIED,s.checks().get(Domain.QUARTERLY_AGGREGATES).state());reason(s,Domain.AGGREGATE_CONSISTENCY,State.UNAVAILABLE,Reason.AGGREGATE_HISTORY_UNAVAILABLE);}
  }
  @Test void invalidAggregateAndUnknownPublicationAreDistinct() {
    var t=tables();t.get("quarterly_aggregates").add(List.of("2026-Q3","CANDIDATE_COUNTS","51","20","1"));reason(inspect(t),Domain.QUARTERLY_AGGREGATES,State.INVALID,Reason.INVALID_AGGREGATE);
    t=tables();t.get("quarterly_aggregates").add(List.of("2026-Q3","future-publication","","","1"));reason(inspect(t),Domain.QUARTERLY_AGGREGATES,State.UNAVAILABLE,Reason.UNSUPPORTED_PUBLICATION);
  }
  @Test void aggregateCurrentQuarterAndRawOverlapAreUnavailableNotInventedCorruption() throws Exception {
    var t=tables();append(t,payload());t.get("quarterly_aggregates").add(List.of("2026-Q4","SUPPRESSED","","","1"));var s=inspect(t);
    reason(s,Domain.AGGREGATE_CONSISTENCY,State.UNAVAILABLE,Reason.AGGREGATE_NOT_PURGED);reason(s,Domain.AGGREGATE_CONSISTENCY,State.UNAVAILABLE,Reason.AGGREGATE_RAW_OVERLAP);
  }
  @Test void reportSerializationHasOnlyFixedStatusesCountsAndNoRawValues() throws Exception {
    var t=tables();append(t,payload());set(t,"envelopes",1,4,"SYNTHETIC_PRIVATE_BAD_JSON");var s=inspect(t);
    String output=ProductionReadOnlyEnvelopeTest.JSON.writeValueAsString(s)+s;
    for(String secret:List.of(ACCOUNT,"SYNTHETIC_PRIVATE","synthetic@example.invalid","https://accounts.google.com","2026-"))assertFalse(output.contains(secret));
    assertTrue(output.contains("INVALID_ENVELOPE"));
    for(var method:ProductionReadOnlyWorkbook.class.getMethods())assertFalse(method.getName().toLowerCase().contains("table"));
  }
  @Test void rowCellAndPayloadBudgetsAreUnavailableNotDataCorruption() throws Exception {
    var t=tables();for(int i=t.get("accounts").size();i<=MAX_ROWS;i++)t.get("accounts").add(List.of(ACCOUNT,"1","",""));reason(inspect(t),Domain.ACCOUNTS,State.UNAVAILABLE,Reason.BUDGET_EXCEEDED);
    t=tables();set(t,"accounts",1,2,"x".repeat(MAX_CELL_CHARS+1));reason(inspect(t),Domain.ACCOUNTS,State.UNAVAILABLE,Reason.BUDGET_EXCEEDED);
    t=tables();String id=ProductionReadOnlyEnvelopeTest.ID+":1",hash="a".repeat(64);int count=51;
    t.get("commits").add(List.of(id,hash,Integer.toString(count),ProductionReadOnlyEnvelopeTest.ID,"1"));
    for(int i=0;i<count;i++)t.get("envelopes").add(List.of(id,hash,Integer.toString(i),Integer.toString(count),"x".repeat(20000)));
    reason(inspect(t),Domain.ENVELOPE_RULES,State.UNAVAILABLE,Reason.BUDGET_EXCEEDED);
  }
  @Test void malformedTableDoesNotPreventIndependentAccountsCheck() {var t=tables();t.get("commits").add(List.of("bad"));var s=inspect(t);reason(s,Domain.COMMIT_CHUNK_LINKAGE,State.INVALID,Reason.INVALID_TABLE);assertEquals(State.VERIFIED,s.checks().get(Domain.ACCOUNTS).state());}

  @Test void cumulativeLogicalBudgetIsUnavailableBeforeValueValidation() {
    var t=tables();String repeated="x".repeat(13000);
    for(int i=t.get("accounts").size();i<MAX_ROWS;i++)t.get("accounts").add(List.of(ACCOUNT,"2",repeated,""));
    reason(inspect(t),Domain.ACCOUNTS,State.UNAVAILABLE,Reason.BUDGET_EXCEEDED);
  }
  @Test void futureTombstoneSchemaDoesNotAcquireVersionOneMeaning() {
    var t=tables();t.get("erasure_ledger").add(List.of("ACCOUNT",digest("ACCOUNT",ACCOUNT),"2"));
    var s=inspect(t);reason(s,Domain.ERASURE_LEDGER,State.UNAVAILABLE,Reason.UNSUPPORTED_ERASURE_SCHEMA);
    reason(s,Domain.ERASURE_REFERENCES,State.UNAVAILABLE,Reason.PREREQUISITE_UNVERIFIED);
    assertFalse(s.checks().get(Domain.ERASURE_REFERENCES).reasons().containsKey(Reason.ERASED_IDENTIFIER_PRESENT));
  }
  @Test void combinedCategoryMatchOverflowRejectsEvenWhenEachCategoryFits() throws Exception {
    var t=tables();var c=(ObjectNode)ProductionReadOnlyEnvelopeTest.JSON.readTree(carry(Q4));
    long n=9_007_199_254_740_990L;
    c.set("formal",ProductionReadOnlyEnvelopeTest.JSON.valueToTree(new ProductionReadOnlyLifetime.Counts(n,0,0,0,0,0,0,0,n)));
    addCarry(t,c.toString());
    for(int i=1;i<=2;i++) {var p=payload();replaceMatch(p,String.format("00000000-0000-0000-0000-%012d",i),1);
      ProductionReadOnlyEnvelopeTest.match(p).put("room_type","CASUAL").put("match_category","CASUAL").put("reason","NO_HUMANS_CONNECTED");append(t,p);}
    reason(inspect(t),Domain.LIFETIME_CONSISTENCY,State.INVALID,Reason.CARRY_ARITHMETIC_INVALID);
  }

  @Test void unknownRowVersionsAreNotInterpretedAsVersionOneConflicts() {
    var t=tables();t.get("auth_identities").add(List.of("https://accounts.google.com","SYNTHETIC_PRIVATE_SUBJECT","future-identity-value","2"));
    var s=inspect(t);reason(s,Domain.AUTH_IDENTITIES,State.UNAVAILABLE,Reason.UNSUPPORTED_IDENTITY_SCHEMA);
    assertFalse(s.checks().get(Domain.AUTH_IDENTITIES).reasons().containsKey(Reason.CONFLICTING_IDENTITY));
    t=tables();t.get("retention_ledger").add(List.of("future-quarter","future-boundary","future-state","future-value","2"));
    reason(inspect(t),Domain.RETENTION_LEDGER,State.UNAVAILABLE,Reason.UNSUPPORTED_RETENTION_SCHEMA);
    t=tables();t.get("quarterly_aggregates").add(List.of("future-quarter","future-publication","future-count","future-count","2"));
    reason(inspect(t),Domain.QUARTERLY_AGGREGATES,State.UNAVAILABLE,Reason.UNSUPPORTED_AGGREGATE_SCHEMA);
  }

  @Test void supplementaryPairMayCrossChunkBoundaryWithoutLossOfHashBinding() throws Exception {
    var t=tables();var p=payload();ProductionReadOnlyEnvelopeTest.match(p).put("app_version","synthetic "+new String(Character.toChars(0x1f600)));
    String json=ProductionReadOnlyEnvelopeTest.json(p);int split=json.indexOf((char)0xd83d)+1;assertTrue(split>0);append(t,p,split);
    assertTrue(inspect(t).verified());
    t=tables();p=payload();ProductionReadOnlyEnvelopeTest.match(p).put("app_version",String.valueOf((char)0xd800));append(t,p);
    reason(inspect(t),Domain.ENVELOPE_RULES,State.UNAVAILABLE,Reason.UNSUPPORTED_TEXT_ENCODING);
  }
}
