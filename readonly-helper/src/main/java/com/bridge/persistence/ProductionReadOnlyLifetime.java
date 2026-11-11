package com.bridge.persistence;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import java.time.*;
import java.util.*;

/** Pure read-only extraction of LifetimeStatistics counts/carry contract.
 * Validates stored numeric carry, not its historical derivation from removed raw records.
 * No fold, account replacement, encoding, gateway, or production dependencies. */
final class ProductionReadOnlyLifetime {
  public static final String RULE = "account-career-v1";
  public static final String SCORE_VERSION = "bridgego-custom-v0.11-1";
  static final int MAX_JSON_CHARS = 20_000;
  enum Reason { INVALID_CARRY, UNSUPPORTED_CARRY_SCHEMA, UNSUPPORTED_CARRY_RULE, UNSUPPORTED_SCORE_VERSION, BUDGET_EXCEEDED }
  static final class Failure extends IllegalArgumentException {
    private final Reason reason;
    Failure(Reason reason) { super(reason.name()); this.reason = reason; }
    Reason reason() { return reason; }
  }
  private static final long MAX = 9_007_199_254_740_991L;
  private static final Set<String> COUNT_FIELDS = Set.of("matches", "completedDeals", "wins", "losses", "draws", "totalScore",
      "scoredMatches", "forfeitMatches", "interruptedMatches");
  private static final Set<String> CARRY_FIELDS = Set.of("schema_version", "rule", "score_version", "through",
      "trackedFromQuarter", "formal", "casual");
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder()
          .maxNestingDepth(6).maxDocumentLength(MAX_JSON_CHARS).maxTokenCount(256)
          .maxNameLength(128).maxNumberLength(64).maxStringLength(MAX_JSON_CHARS).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public record Counts(long matches, long completedDeals, long wins, long losses, long draws, long totalScore,
      long scoredMatches, long forfeitMatches, long interruptedMatches) {
    public Counts {
      for (long value : new long[]{matches, completedDeals, wins, losses, draws, scoredMatches, forfeitMatches, interruptedMatches})
        if (value < 0 || value > MAX) reject();
      if (totalScore < -MAX || totalScore > MAX || matches != sum(scoredMatches, forfeitMatches, interruptedMatches)
          || sum(wins, losses, draws) != sum(scoredMatches, forfeitMatches) || draws > scoredMatches
          || completedDeals < scoredMatches || completedDeals > Math.min(MAX, matches * 5)
          || completedDeals == 0 && totalScore != 0) reject();
    }
    @Override public String toString() { return "Counts[redacted]"; }
    public static Counts zero() { return new Counts(0,0,0,0,0,0,0,0,0); }
    public Counts plus(Counts other) {
      if (other == null) reject();
      return new Counts(sum(matches, other.matches), sum(completedDeals, other.completedDeals), sum(wins, other.wins),
          sum(losses, other.losses), sum(draws, other.draws), sum(totalScore, other.totalScore),
          sum(scoredMatches, other.scoredMatches), sum(forfeitMatches, other.forfeitMatches), sum(interruptedMatches, other.interruptedMatches));
    }
  }
  public record Carry(Instant through, String trackedFromQuarter, Counts formal, Counts casual) {
    public Carry {
      if (formal == null || casual == null || casual.forfeitMatches() != 0) reject();
      long matches = sum(formal.matches(), casual.matches());
      if (matches == 0 ? trackedFromQuarter != null : !validQuarter(trackedFromQuarter)) reject();
      if (through != null) {
        if (!currentQuarterStart(through).equals(through)) reject();
        if (trackedFromQuarter != null && !quarterStart(trackedFromQuarter).isBefore(through)) reject();
      }
    }
    @Override public String toString() { return "Carry[redacted]"; }
    public Counts counts() { return formal.plus(casual); }
    public static Carry empty() { return new Carry(null, null, Counts.zero(), Counts.zero()); }
  }
  private ProductionReadOnlyLifetime() {}
  public static Carry empty() { return Carry.empty(); }

  public static Carry decode(String json) {
    if (json == null) reject();
    if (json.isEmpty()) return empty();
    if (json.length() > MAX_JSON_CHARS) throw new Failure(Reason.BUDGET_EXCEEDED);
    try {
      JsonNode root = JSON.readTree(json);
      if(root==null || !root.isObject()) reject();
      JsonNode schema = root.path("schema_version");
      if (!schema.isIntegralNumber()) reject();
      if (!schema.canConvertToInt() || schema.intValue() != 1) throw new Failure(Reason.UNSUPPORTED_CARRY_SCHEMA);
      if (!root.path("rule").isTextual() || !root.path("score_version").isTextual()) reject();
      if (!RULE.equals(root.path("rule").textValue())) throw new Failure(Reason.UNSUPPORTED_CARRY_RULE);
      if (!SCORE_VERSION.equals(root.path("score_version").textValue())) throw new Failure(Reason.UNSUPPORTED_SCORE_VERSION);
      fields(root, CARRY_FIELDS);
      String throughText = root.path("through").textValue();
      if (throughText == null) reject();
      Instant through = Instant.parse(throughText);
      if (!through.toString().equals(throughText)) reject();
      JsonNode tracked = root.path("trackedFromQuarter");
      if (!tracked.isNull() && !tracked.isTextual()) reject();
      return new Carry(through, tracked.isNull() ? null : tracked.textValue(), counts(root.path("formal")), counts(root.path("casual")));
    } catch (Failure failure) { throw failure; }
      catch (StreamConstraintsException failure) { throw new Failure(Reason.BUDGET_EXCEEDED); }
      catch (Exception failure) { throw new Failure(Reason.INVALID_CARRY); }
  }
  static Counts contribution(JsonNode match, String team) {
    if (match == null || !match.isObject() || !("NS".equals(team) || "EW".equals(team))) reject();
    String outcome = match.path("outcome").textValue(), winner = match.path("winner_team_id").textValue();
    if (outcome == null || !Set.of("SCORED", "FORFEIT", "NO_CONTEST").contains(outcome)) reject();
    if (!match.path("winner_team_id").isNull() && !("NS".equals(winner) || "EW".equals(winner))) reject();
    long completed = number(match.path("completed_deals"));
    if (completed < 0 || completed > 5) reject();
    boolean scored = "SCORED".equals(outcome), forfeited = "FORFEIT".equals(outcome), interrupted = "NO_CONTEST".equals(outcome);
    if (scored && completed == 0 || forfeited && !("NS".equals(winner) || "EW".equals(winner))
        || interrupted && !match.path("winner_team_id").isNull()) reject();
    boolean draw = scored && match.path("winner_team_id").isNull();
    boolean won = !interrupted && team.equals(winner), lost = !interrupted && !draw && !won;
    return new Counts(1, completed, won ? 1 : 0, lost ? 1 : 0, draw ? 1 : 0,
        number(match.path(team.equals("NS") ? "ns_total" : "ew_total")), scored ? 1 : 0, forfeited ? 1 : 0, interrupted ? 1 : 0);
  }
  private static Counts counts(JsonNode n) {
    fields(n, COUNT_FIELDS);
    return new Counts(number(n.path("matches")), number(n.path("completedDeals")), number(n.path("wins")), number(n.path("losses")),
        number(n.path("draws")), number(n.path("totalScore")), number(n.path("scoredMatches")), number(n.path("forfeitMatches")), number(n.path("interruptedMatches")));
  }
  private static void fields(JsonNode n, Set<String> required) {
    if (n == null || !n.isObject()) reject(); Set<String> actual = new HashSet<>(); n.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(required)) reject();
  }
  private static long number(JsonNode n) {
    if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < -MAX || n.longValue() > MAX) reject();
    return n.longValue();
  }
  private static long sum(long... values) {
    try { long total = 0; for (long value : values) total = Math.addExact(total, value); if (total < -MAX || total > MAX) reject(); return total; }
    catch (ArithmeticException overflow) { throw new Failure(Reason.INVALID_CARRY); }
  }
  private static boolean validQuarter(String quarter) { return quarter != null && quarter.matches("[0-9]{4}-Q[1-4]") && !quarter.startsWith("0000"); }
  private static Instant quarterStart(String quarter) {
    if (!validQuarter(quarter)) { reject(); }
    return LocalDate.of(Integer.parseInt(quarter.substring(0,4)), (quarter.charAt(6) - '1') * 3 + 1, 1)
        .atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant();
  }
  static Instant currentQuarterStart(Instant now) {
    try {
      ZonedDateTime local = now.atZone(ZoneId.of("Asia/Taipei"));
      return LocalDate.of(local.getYear(), ((local.getMonthValue()-1)/3)*3+1, 1)
          .atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant();
    } catch (RuntimeException failure) { throw new Failure(Reason.INVALID_CARRY); }
  }
  private static void reject() { throw new Failure(Reason.INVALID_CARRY); }
}
