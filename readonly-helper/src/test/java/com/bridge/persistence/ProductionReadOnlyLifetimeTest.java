package com.bridge.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyLifetime.*;

/** Synthetic values only; no assertion of provenance from deleted historical records. */
class ProductionReadOnlyLifetimeTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant Q4 = Instant.parse("2026-09-30T16:00:00Z");
  private static final long MAX = 9_007_199_254_740_991L;
  private static Counts win() { return new Counts(1,1,1,0,0,7,1,0,0); }
  private static ObjectNode fixture() {
    ObjectNode r = JSON.createObjectNode();
    r.put("schema_version",1).put("rule",RULE).put("score_version",SCORE_VERSION)
        .put("through",Q4.toString()).put("trackedFromQuarter","2026-Q3");
    r.set("formal",JSON.valueToTree(win())); r.set("casual",JSON.valueToTree(Counts.zero())); return r;
  }
  private static Failure invalid(String text, Reason reason) {
    Failure f = assertThrows(Failure.class, () -> decode(text));
    assertEquals(reason,f.reason()); assertEquals(reason.name(),f.getMessage()); assertNull(f.getCause()); return f;
  }
  @Test void emptySentinelAndNumericCarryPreserveContract() {
    assertEquals(Carry.empty(),decode("")); assertNull(decode("").through());
    Carry carry = decode(fixture().toString());
    assertEquals(Q4,carry.through()); assertEquals("2026-Q3",carry.trackedFromQuarter());
    assertEquals(win(),carry.formal()); assertEquals(win(),carry.counts());
    assertEquals(Counts.zero(),carry.casual());
    ObjectNode zero = fixture(); zero.putNull("trackedFromQuarter"); zero.set("formal",JSON.valueToTree(Counts.zero()));
    assertEquals(Counts.zero(),decode(zero.toString()).counts());
    assertEquals("Carry[redacted]",carry.toString()); assertEquals("Counts[redacted]",win().toString());
  }
  @Test void unknownVersionsHaveFixedDistinctFailures() {
    invalid(fixture().put("schema_version",2).toString(),Reason.UNSUPPORTED_CARRY_SCHEMA);
    invalid(fixture().put("schema_version",2).put("future-field","unknown").toString(),Reason.UNSUPPORTED_CARRY_SCHEMA);
    invalid(fixture().put("schema_version",new java.math.BigInteger("18446744073709551617")).toString(),Reason.UNSUPPORTED_CARRY_SCHEMA);
    invalid(fixture().put("rule","private-marker").toString(),Reason.UNSUPPORTED_CARRY_RULE);
    invalid(fixture().put("rule","private-marker").put("future-field","unknown").toString(),Reason.UNSUPPORTED_CARRY_RULE);
    invalid(fixture().put("score_version","private-marker").toString(),Reason.UNSUPPORTED_SCORE_VERSION);
    invalid(fixture().put("score_version","private-marker").put("future-field","unknown").toString(),Reason.UNSUPPORTED_SCORE_VERSION);
    invalid(fixture().put("schema_version","1").toString(),Reason.INVALID_CARRY);
    invalid(fixture().putNull("rule").toString(),Reason.INVALID_CARRY);
  }
  @Test void malformedMissingUnknownDuplicateTrailingAndWrongTypesFailClosed() {
    for (String bad : new String[]{null," ","null","[]","{}","{private-marker}",fixture()+" {}",
        fixture().toString().replace("\"schema_version\":1","\"schema_version\":1,\"schema_version\":1"),
        fixture().toString().replace("\"wins\":1","\"wins\":1,\"wins\":1")}) invalid(bad,Reason.INVALID_CARRY);
    List<Consumer<ObjectNode>> edits = List.of(r -> r.put("private-marker",1),r -> r.remove("through"),
        r -> r.putNull("through"),r -> r.put("through",42),r -> r.put("trackedFromQuarter",42),
        r -> ((ObjectNode)r.path("formal")).remove("wins"),r -> ((ObjectNode)r.path("formal")).put("private-marker",1),
        r -> ((ObjectNode)r.path("formal")).put("matches",true),r -> ((ObjectNode)r.path("formal")).put("totalScore","7"),
        r -> ((ObjectNode)r.path("formal")).put("totalScore",7.0),r -> r.putNull("casual"));
    for (var edit : edits) { var r=fixture(); edit.accept(r); invalid(r.toString(),Reason.INVALID_CARRY); }
  }
  @Test void taipeiCanonicalBoundaryAndTrackedQuarterAreEnforced() {
    for (String through : List.of("2026-10-01T00:00:00Z","2026-09-30T16:00:01Z","2026-10-01T00:00:00+08:00","2026-09-30T16:00:00.000Z"))
      invalid(fixture().put("through",through).toString(),Reason.INVALID_CARRY);
    for (String q : List.of("2026-Q4","2027-Q1","2026-Q9","0000-Q1","26-Q3","private-marker"))
      invalid(fixture().put("trackedFromQuarter",q).toString(),Reason.INVALID_CARRY);
    invalid(fixture().putNull("trackedFromQuarter").toString(),Reason.INVALID_CARRY);
    ObjectNode r=fixture(); r.set("formal",JSON.valueToTree(Counts.zero())); invalid(r.toString(),Reason.INVALID_CARRY);
    assertEquals(Instant.parse("2025-12-31T16:00:00Z"),currentQuarterStart(Instant.parse("2026-03-31T15:59:59Z")));
    assertEquals(Instant.parse("2026-03-31T16:00:00Z"),currentQuarterStart(Instant.parse("2026-03-31T16:00:00Z")));
  }
  @Test void numericInvariantsAndOverflowAreBounded() {
    for (String field : List.of("matches","completedDeals","wins","losses","draws","scoredMatches","forfeitMatches","interruptedMatches")) {
      for(long bad : new long[]{-1,MAX+1}) { var r=fixture(); ((ObjectNode)r.path("formal")).put(field,bad); invalid(r.toString(),Reason.INVALID_CARRY); }
    }
    for (String field : List.of("matches","wins","draws","scoredMatches","forfeitMatches","interruptedMatches")) {
      var r=fixture(); ((ObjectNode)r.path("formal")).put(field,2); invalid(r.toString(),Reason.INVALID_CARRY);
    }
    assertThrows(Failure.class,()->new Counts(1,0,0,0,0,1,0,0,1));
    assertThrows(Failure.class,()->new Counts(1,6,1,0,0,0,1,0,0));
    assertThrows(Failure.class,()->new Counts(1,0,1,0,0,0,1,0,0));
    assertThrows(Failure.class,()->new Counts(1,1,1,0,0,MAX+1,1,0,0));
    assertThrows(Failure.class,()->new Counts(1,1,1,0,0,-MAX-1,1,0,0));
    var max = new Counts(MAX,0,0,0,0,0,0,0,MAX);
    assertThrows(Failure.class,()->max.plus(new Counts(1,0,0,0,0,0,0,0,1)));
    assertThrows(Failure.class,()->win().plus(null));
    var high = new Counts(1,1,1,0,0,MAX,1,0,0);
    assertThrows(Failure.class,()->high.plus(win()));
    assertEquals(new Counts(2,2,2,0,0,14,2,0,0),win().plus(win()));
  }
  @Test void casualForfeitsAreInvalidAndCrossCategorySumIsBounded() {
    var forfeit = new Counts(1,0,1,0,0,0,0,1,0);
    assertThrows(Failure.class,()->new Carry(Q4,"2026-Q3",Counts.zero(),forfeit));
    var max = new Counts(MAX,0,0,0,0,0,0,0,MAX);
    assertThrows(Failure.class,()->new Carry(Q4,"2026-Q3",max,win()));
  }
  private static ObjectNode match(String outcome, String winner, int deals) {
    ObjectNode m=JSON.createObjectNode();m.put("outcome",outcome).put("completed_deals",deals).put("ns_total",7).put("ew_total",-3);
    if(winner==null)m.putNull("winner_team_id");else m.put("winner_team_id",winner);return m;
  }
  @Test void currentContributionArithmeticHandlesAllOutcomesWithoutHistoricalReplay() {
    assertEquals(win(),contribution(match("SCORED","NS",1),"NS"));
    assertEquals(new Counts(1,1,0,1,0,-3,1,0,0),contribution(match("SCORED","NS",1),"EW"));
    assertEquals(1,contribution(match("SCORED",null,1),"NS").draws());
    assertEquals(1,contribution(match("FORFEIT","EW",1),"NS").forfeitMatches());
    assertEquals(new Counts(1,1,0,0,0,7,0,0,1),contribution(match("NO_CONTEST",null,1),"NS"));
    for (ObjectNode bad : List.of(match("SCORED","NS",0),match("FORFEIT",null,1),match("NO_CONTEST","NS",1),match("UNKNOWN",null,1),match("SCORED","private-marker",1),match("SCORED","NS",6)))
      assertThrows(Failure.class,()->contribution(bad,"NS"));
    assertThrows(Failure.class,()->contribution(match("SCORED","NS",1),"private-marker"));
    assertThrows(Failure.class,()->contribution(null,"NS"));
  }
  @Test void parsingBudgetsAndSafeDiagnosticsNeverExposeInput() {
    invalid(" ".repeat(MAX_JSON_CHARS+1),Reason.BUDGET_EXCEEDED);
    invalid("[[[[[[[0]]]]]]]",Reason.BUDGET_EXCEEDED);
    invalid("{\"" + "n".repeat(129) + "\":0}",Reason.BUDGET_EXCEEDED);
    invalid("{\"schema_version\":" + "1".repeat(65) + "}",Reason.BUDGET_EXCEEDED);
    invalid("[" + "0,".repeat(256) + "0]",Reason.BUDGET_EXCEEDED);
    Failure f=invalid("{\"private-marker\":",Reason.INVALID_CARRY);
    assertFalse(f.toString().contains("private-marker"));
    invalid(" ".repeat(MAX_JSON_CHARS),Reason.INVALID_CARRY);
  }
}
