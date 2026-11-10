package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class LifetimeStatisticsTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant Q4 = Instant.parse("2026-09-30T16:00:00Z"), Q1 = Instant.parse("2026-12-31T16:00:00Z");
  static FinalMatchEnvelope marked(FinalMatchEnvelope original, String type, String outcome) {
    var root = FixedRoomEnvelopeTest.record(original, type, outcome);
    var match = (ObjectNode)root.path("match"); match.put("lifetime_rule", LifetimeStatistics.RULE);
    if (outcome.equals("SCORED")) match.put("planned_deals", root.path("completed_deals").size())
        .put("reason", "PLANNED_DEALS_COMPLETE").put("winner_team_id", "NS");
    return MatchCategoryEnvelopeTest.envelope(root);
  }
  static FinalMatchEnvelope changed(FinalMatchEnvelope original, Consumer<ObjectNode> change) {
    var root = (ObjectNode)original.payload(); change.accept(root); return MatchCategoryEnvelopeTest.envelope(root);
  }
  static String account(LocalManualQuarterRehearsal.Fixture f) { return f.tables.get("accounts").get(1).get(0); }
  static LifetimeStatistics.Carry carry() {
    return new LifetimeStatistics.Carry(Q4, "2026-Q3", new LifetimeStatistics.Counts(1,1,1,0,0,7,1,0,0), LifetimeStatistics.Counts.zero());
  }

  @Test void strictNumericCarryRoundTripsAndEmptyIsNotInventedHistory() {
    var value = carry(); String encoded = LifetimeStatistics.encode(value);
    assertEquals(value, LifetimeStatistics.decode(encoded)); assertEquals("", LifetimeStatistics.encode(LifetimeStatistics.empty()));
    assertEquals(LifetimeStatistics.empty(), LifetimeStatistics.decode(""));
    assertFalse(encoded.contains("account_id")); assertFalse(encoded.contains("email")); assertFalse(encoded.contains("nickname"));
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.encode(new LifetimeStatistics.Carry(null,
        value.trackedFromQuarter(), value.formal(), value.casual())));
  }

  @Test void rejectsExtraFieldsDuplicatesWrongTypesUnsafeCountersAndInvalidBoundaries() throws Exception {
    String original = LifetimeStatistics.encode(carry());
    List<Consumer<ObjectNode>> changes = List.of(
        r -> r.put("account_id", UUID.randomUUID().toString()), r -> r.put("rule", "unknown"),
        r -> r.put("score_version", "old"), r -> r.put("schema_version", "1"),
        r -> r.put("schema_version", new java.math.BigInteger("18446744073709551617")),
        r -> r.put("through", Q4.plusSeconds(1).toString()), r -> r.putNull("through"),
        r -> r.put("trackedFromQuarter", "2026-Q4"), r -> r.put("trackedFromQuarter", "2026-Q9"),
        r -> r.putNull("trackedFromQuarter"), r -> ((ObjectNode)r.path("formal")).put("matches", -1),
        r -> ((ObjectNode)r.path("formal")).put("matches", 9007199254740992L),
        r -> ((ObjectNode)r.path("formal")).put("wins", 2), r -> ((ObjectNode)r.path("formal")).put("totalScore", "7"),
        r -> ((ObjectNode)r.path("formal")).put("completedDeals", 6), r -> ((ObjectNode)r.path("formal")).put("matches", true));
    for (var change : changes) {
      var root = (ObjectNode)JSON.readTree(original); change.accept(root);
      assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.decode(root.toString()));
    }
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.decode(original.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1")));
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.decode(original + " {}"));
    assertThrows(SheetsGateway.Conflict.class, () -> new LifetimeStatistics.Counts(1,0,0,0,0,5,0,0,1));
    var max = new LifetimeStatistics.Counts(9007199254740991L,0,0,0,0,0,0,0,9007199254740991L);
    assertThrows(SheetsGateway.Conflict.class, () -> max.plus(new LifetimeStatistics.Counts(1,0,0,0,0,0,0,0,1)));
  }

  @Test void onlyNewMarkerCountsAndDuplicatesOrConflictingSnapshotsCannotDoubleCount() {
    var f = new LocalManualQuarterRehearsal.Fixture(); var saved = marked(f.expired, "FORMAL", "FORFEIT");
    var unmarked = changed(saved, r -> ((ObjectNode)r.path("match")).remove("lifetime_rule"));
    assertEquals(LifetimeStatistics.empty(), LifetimeStatistics.fromEnvelopes(account(f), List.of(unmarked)));
    var result = LifetimeStatistics.fromEnvelopes(account(f), List.of(saved, saved));
    assertNull(result.through()); assertEquals("2026-Q3", result.trackedFromQuarter());
    assertEquals(1, result.formal().matches()); assertEquals(1, result.formal().losses()); assertEquals(7, result.formal().totalScore());
    assertEquals(1, result.formal().forfeitMatches());
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.fromEnvelopes(account(f), List.of(saved, unmarked)));
    var otherScore = changed(saved, r -> ((ObjectNode)r.path("match")).put("score_version", "other"));
    assertEquals(LifetimeStatistics.empty(), LifetimeStatistics.fromEnvelopes(account(f), List.of(otherScore)));
  }

  @Test void foldIsAccountScopedPreservesProfileAndRollsForwardWithoutReplayingPriorQuarters() {
    var f = new LocalManualQuarterRehearsal.Fixture(); String who = account(f), other = f.tables.get("accounts").get(2).get(0);
    var rows = List.of(AccountProfileRows.HEADER, List.of(who,"2","玩家","p@example.test"), List.of(other,"1"));
    var old = marked(f.expired, "FORMAL", "FORFEIT");
    var folded = LifetimeStatistics.fold(rows, List.of(old, old), Q4);
    assertEquals(AccountProfileRows.LIFETIME_HEADER, folded.get(0)); assertEquals(rows.get(1), folded.get(1).subList(0,4));
    var mine = LifetimeStatistics.readAccount(folded, who); var theirs = LifetimeStatistics.readAccount(folded, other);
    assertEquals(Q4, mine.through()); assertEquals(1, mine.formal().losses()); assertEquals(1, theirs.formal().wins());
    assertEquals(-3, theirs.formal().totalScore());
    assertEquals(folded, LifetimeStatistics.fold(folded, List.of(), Q4));
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.fold(folded, List.of(old), Q1));
    var current = marked(f.retained.values().stream().filter(e -> QuarterlyRetention.finishedAt(e).isAfter(Q4)).findFirst().orElseThrow(), "CASUAL", "NO_CONTEST");
    var next = LifetimeStatistics.fold(folded, List.of(current), Q1); var career = LifetimeStatistics.readAccount(next, who);
    assertEquals(Q1, career.through()); assertEquals("2026-Q3", career.trackedFromQuarter());
    assertEquals(1, career.formal().matches()); assertEquals(1, career.casual().matches());
    assertEquals(1, career.casual().interruptedMatches()); assertEquals(0, career.casual().draws());
  }

  @Test void cutoffOverlapUnknownAccountsAndFutureCarryFailClosed() {
    var f = new LocalManualQuarterRehearsal.Fixture(); var old = marked(f.expired, "FORMAL", "FORFEIT");
    var atBoundary = marked(f.retained.values().stream().filter(e -> QuarterlyRetention.finishedAt(e).equals(Q4)).findFirst().orElseThrow(), "FORMAL", "FORFEIT");
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.fold(f.tables.get("accounts"), List.of(atBoundary), Q4));
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.fold(f.tables.get("accounts"), List.of(old), Q4.plusMillis(1)));
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.fold(List.of(AccountProfileRows.LEGACY_HEADER), List.of(old), Q4));
    var rows = LifetimeStatistics.fold(f.tables.get("accounts"), List.of(old), Q1);
    assertThrows(SheetsGateway.Conflict.class, () -> LifetimeStatistics.fold(rows, List.of(), Q4));
  }

  @Test void erasedAccountsAndGuestsCannotAcquireOrReviveCarry() {
    var f = new LocalManualQuarterRehearsal.Fixture(); var old = marked(f.expired, "FORMAL", "FORFEIT");
    var erased = changed(old, r -> r.path("participants").forEach(n -> {
      ((ObjectNode)n).putNull("account_id").putNull("guest_id");
    }));
    assertEquals(LifetimeStatistics.empty(), LifetimeStatistics.fromEnvelopes(account(f), List.of(erased)));
    assertEquals(List.of(AccountProfileRows.LEGACY_HEADER), LifetimeStatistics.fold(List.of(AccountProfileRows.LEGACY_HEADER), List.of(erased), Q4));
  }
}
