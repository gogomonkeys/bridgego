package com.bridge.quartercore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** Private account-scoped, numeric-only career carry. No network, identity invention or historical backfill. */
public final class LifetimeStatistics {
  public static final String RULE = "account-career-v1";
  public static final String SCORE_VERSION = "bridgego-custom-v0.11-1";
  private static final long MAX = 9_007_199_254_740_991L;
  private static final Set<String> COUNT_FIELDS = Set.of("matches", "completedDeals", "wins", "losses", "draws", "totalScore",
      "scoredMatches", "forfeitMatches", "interruptedMatches");
  private static final Set<String> CARRY_FIELDS = Set.of("schema_version", "rule", "score_version", "through",
      "trackedFromQuarter", "formal", "casual");
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(20000).build()).build())
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
    public static Counts zero() { return new Counts(0,0,0,0,0,0,0,0,0); }
    public Counts plus(Counts other) {
      Objects.requireNonNull(other);
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
        if (!QuarterlyRetention.currentQuarterStart(through).equals(through)) reject();
        if (trackedFromQuarter != null && !quarterStart(trackedFromQuarter).isBefore(through)) reject();
      }
    }
    public static Carry empty() { return new Carry(null, null, Counts.zero(), Counts.zero()); }
  }
  private LifetimeStatistics() {}
  public static Carry empty() { return Carry.empty(); }

  public static Carry decode(String json) {
    if (json == null) reject();
    if (json.isEmpty()) return empty();
    if (json.length() > 20000) reject();
    try {
      JsonNode root = JSON.readTree(json); fields(root, CARRY_FIELDS);
      if (!root.path("schema_version").isIntegralNumber() || !root.path("schema_version").canConvertToInt() || root.path("schema_version").intValue() != 1
          || !RULE.equals(root.path("rule").textValue()) || !SCORE_VERSION.equals(root.path("score_version").textValue())) reject();
      String throughText = root.path("through").textValue();
      if (throughText == null) reject();
      Instant through = Instant.parse(throughText);
      if (!through.toString().equals(throughText)) reject();
      JsonNode tracked = root.path("trackedFromQuarter");
      if (!tracked.isNull() && !tracked.isTextual()) reject();
      return new Carry(through, tracked.isNull() ? null : tracked.textValue(), counts(root.path("formal")), counts(root.path("casual")));
    } catch (RuntimeException failure) { throw new SheetsGateway.Conflict(); }
      catch (Exception failure) { throw new SheetsGateway.Conflict(); }
  }
  public static String encode(Carry carry) {
    if (carry == null) reject();
    if (carry.equals(empty())) return "";
    if (carry.through() == null) reject();
    try {
      var root = JSON.createObjectNode(); root.put("schema_version", 1).put("rule", RULE).put("score_version", SCORE_VERSION)
          .put("through", carry.through().toString());
      if (carry.trackedFromQuarter() == null) root.putNull("trackedFromQuarter"); else root.put("trackedFromQuarter", carry.trackedFromQuarter());
      root.set("formal", JSON.valueToTree(carry.formal())); root.set("casual", JSON.valueToTree(carry.casual()));
      return JSON.writeValueAsString(root);
    } catch (Exception failure) { throw new SheetsGateway.Conflict(); }
  }
  public static Carry readAccount(List<List<String>> accounts, String account) {
    AccountProfileRows.validateTable(accounts);
    return accounts.stream().skip(1).filter(row -> row.get(0).equals(account))
        .map(row -> row.size() == 5 ? decode(row.get(4)) : empty()).findFirst().orElse(empty());
  }

  /** Current raw contributions only; through=null is deliberately not persistable. */
  public static Carry fromEnvelopes(String account, Collection<FinalMatchEnvelope> envelopes) {
    uuid(account); Counts formal = Counts.zero(), casual = Counts.zero(); String tracked = null;
    for (var envelope : eligibleUnique(envelopes).values()) {
      var root = envelope.payload(); var match = root.path("match");
      String team = null;
      for (var person : root.path("participants")) if (account.equals(person.path("account_id").textValue())) {
        if (team != null) reject(); team = person.path("team_id").textValue();
      }
      if (team == null) continue;
      if (!Set.of("NS", "EW").contains(team)) reject();
      Counts count = contribution(match, team);
      if ("FORMAL".equals(envelope.statisticsCategory())) formal = formal.plus(count); else casual = casual.plus(count);
      tracked = earliest(tracked, QuarterlyRetention.quarter(QuarterlyRetention.finishedAt(envelope)));
    }
    return new Carry(null, tracked, formal, casual);
  }

  /** Pure replacement for accounts, folded in the same atomic batch as expired raw removal. */
  public static List<List<String>> fold(List<List<String>> accounts, Collection<FinalMatchEnvelope> expired, Instant cutoff) {
    AccountProfileRows.validateTable(accounts);
    if (cutoff == null || !QuarterlyRetention.currentQuarterStart(cutoff).equals(cutoff)) reject();
    var eligible = eligibleUnique(expired); Set<String> known = new HashSet<>();
    accounts.stream().skip(1).forEach(row -> known.add(row.get(0)));
    for (var envelope : eligible.values()) {
      if (!QuarterlyRetention.finishedAt(envelope).isBefore(cutoff)) reject();
      for (var person : envelope.payload().path("participants")) {
        String account = person.path("account_id").textValue();
        if (account != null && !known.contains(account)) reject(); // Never recreate an erased account.
      }
    }
    boolean changed = false; List<List<String>> result = new ArrayList<>(); result.add(accounts.get(0));
    for (var row : accounts.subList(1, accounts.size())) {
      Carry prior = row.size() == 5 ? decode(row.get(4)) : empty();
      if (prior.through() != null && prior.through().isAfter(cutoff)) reject();
      List<FinalMatchEnvelope> mine = new ArrayList<>();
      for (var envelope : eligible.values()) {
        boolean included = false;
        for (var person : envelope.payload().path("participants"))
          if (row.get(0).equals(person.path("account_id").textValue())) included = true;
        if (!included) continue;
        if (prior.through() != null && QuarterlyRetention.finishedAt(envelope).isBefore(prior.through())) reject();
        mine.add(envelope);
      }
      Carry contribution = fromEnvelopes(row.get(0), mine);
      if (prior.equals(empty()) && contribution.equals(empty())) { result.add(row); continue; }
      Carry next = new Carry(cutoff, earliest(prior.trackedFromQuarter(), contribution.trackedFromQuarter()),
          prior.formal().plus(contribution.formal()), prior.casual().plus(contribution.casual()));
      String encoded = encode(next);
      if (row.size() == 5 && row.get(4).equals(encoded)) { result.add(row); continue; }
      var updated = new ArrayList<>(row); while (updated.size() < 5) updated.add(""); updated.set(4, encoded);
      result.add(List.copyOf(updated)); changed = true;
    }
    if (changed) result.set(0, AccountProfileRows.LIFETIME_HEADER);
    AccountProfileRows.validateTable(result);
    return List.copyOf(result);
  }
  public static boolean eligibleForRetention(FinalMatchEnvelope e) {
    return RULE.equals(e.payload().path("match").path("lifetime_rule").asText())
        && SCORE_VERSION.equals(e.payload().path("match").path("score_version").asText())
        && Set.of("FORMAL", "CASUAL").contains(e.statisticsCategory());
  }
  private static Map<String,FinalMatchEnvelope> eligibleUnique(Collection<FinalMatchEnvelope> records) {
    if (records == null) reject(); var unique = new LinkedHashMap<String,FinalMatchEnvelope>();
    var all = new LinkedHashMap<String,FinalMatchEnvelope>();
    for (var envelope : records) {
      if (envelope == null) reject();
      var previous = all.putIfAbsent(envelope.matchId(), envelope);
      if (previous != null && !previous.equals(envelope)) reject();
    }
    for (var envelope : all.values()) {
      var match = envelope.payload().path("match");
      if (!match.has("lifetime_rule")) continue;
      if (!RULE.equals(match.path("lifetime_rule").textValue())) reject();
      if (!SCORE_VERSION.equals(match.path("score_version").textValue())) continue;
      if (!Set.of("FORMAL", "CASUAL").contains(envelope.statisticsCategory())) reject();
      try {
        if (Instant.parse(match.path("started_at").asText()).isAfter(QuarterlyRetention.finishedAt(envelope))) reject();
      } catch (RuntimeException invalid) { throw new SheetsGateway.Conflict(); }
      unique.put(envelope.matchId(), envelope);
    }
    return unique;
  }
  private static Counts contribution(JsonNode match, String team) {
    String outcome = match.path("outcome").textValue(), winner = match.path("winner_team_id").textValue();
    if (outcome == null || !Set.of("SCORED", "FORFEIT", "NO_CONTEST").contains(outcome)) reject();
    long completed = number(match.path("completed_deals"));
    if (completed < 0 || completed > 5) reject();
    boolean scored = "SCORED".equals(outcome), forfeited = "FORFEIT".equals(outcome), interrupted = "NO_CONTEST".equals(outcome);
    if (scored && completed == 0 || forfeited && !Set.of("NS", "EW").contains(winner)
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
    catch (ArithmeticException overflow) { throw new SheetsGateway.Conflict(); }
  }
  private static String earliest(String a, String b) { return a == null ? b : b == null || a.compareTo(b) <= 0 ? a : b; }
  private static boolean validQuarter(String quarter) { return quarter != null && quarter.matches("[0-9]{4}-Q[1-4]") && !quarter.startsWith("0000"); }
  private static Instant quarterStart(String quarter) {
    if (!validQuarter(quarter)) { reject(); }
    return LocalDate.of(Integer.parseInt(quarter.substring(0,4)), (quarter.charAt(6) - '1') * 3 + 1, 1)
        .atStartOfDay(QuarterlyRetention.TAIPEI).toInstant();
  }
  private static void uuid(String value) { try { if (!UUID.fromString(value).toString().equals(value)) reject(); } catch (RuntimeException invalid) { reject(); } }
  private static void reject() { throw new SheetsGateway.Conflict(); }
}
