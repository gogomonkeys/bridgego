package com.bridge.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Pure bounded adaptation of FinalMatchEnvelope validation. No storage or writer dependencies.
 * Missing historical markers establish no statistics/lifetime eligibility. Unknown versions fail closed.
 * Bounds are observation budgets, not additional game rules.
 */
final class ProductionReadOnlyEnvelope {
    static final String LEGACY_STATISTICS_RULE = "four-humans-at-settlement-v1";
    static final String SETTLEMENT_STATISTICS_RULE = "match-category-at-settlement-v2";
    static final String STATISTICS_RULE = "fixed-room-type-v3";
    static final String DEAL_TIMING_RULE = "elapsed-including-pauses-v1";
    static final String LIFETIME_RULE = "account-career-v1";
    static final String SCORE_VERSION = "bridgego-custom-v0.11-1";
    static final int MAX_PAYLOAD_CHARS = 1_000_000, MAX_DEALS = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
            .maxDocumentLength(MAX_PAYLOAD_CHARS).maxTokenCount(200_000).maxNameLength(128)
            .maxStringLength(MAX_PAYLOAD_CHARS).maxNumberLength(100).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    enum Reason { INVALID_ENVELOPE, UNSUPPORTED_SCHEMA, UNSUPPORTED_RULE, UNSUPPORTED_SCORE_VERSION, UNSUPPORTED_TEXT_ENCODING, BUDGET_EXCEEDED }
    static final class Failure extends IllegalArgumentException {
        private final Reason reason;
        private Failure(Reason reason) { super(reason.name(), null); this.reason = reason; }
        Reason reason() { return reason; }
    }
    record Decoded(JsonNode payload, String statisticsCategory, boolean hasStatisticsRule,
                   boolean hasLifetimeRule, boolean hasDealTimingRule, boolean hasUnknownFields) {
        boolean supported() { return hasStatisticsRule && !hasUnknownFields; }
        boolean lifetimeSupported() { return hasLifetimeRule && !hasUnknownFields; }
        @Override public String toString() { return "Decoded[REDACTED]"; }
    }
  private static final Set<String> MATCH_FIELDS = Set.of("lifetime_rule", "room_type", "deal_timing_rule", "match_category", "statistics_rule", "match_id", "revision", "schema_version", "score_version",
      "app_version", "started_at", "finished_at", "planned_deals", "completed_deals", "outcome", "winner_team_id",
      "reason", "ns_total", "ew_total");
  private static final Set<String> PERSON_FIELDS = Set.of("match_id", "seat", "team_id", "player_type",
      "settlement_control", "control_type", "account_id", "guest_id", "erased_marker");
  private static final Set<String> DEAL_FIELDS = Set.of("elapsed_ms", "match_id", "deal_id", "deal_index", "contract_type",
      "contract_level", "contract_strain", "attacking_team_id", "ns_tricks", "ew_tricks", "ns_privilege_used",
      "ns_base", "ns_exact_bonus", "ns_penalty", "ns_privilege_adjustment", "ns_total", "ew_privilege_used",
      "ew_base", "ew_exact_bonus", "ew_penalty", "ew_privilege_adjustment", "ew_total");
    private static boolean unknownFields(JsonNode root) {
        if (unknown(root, Set.of("match", "participants", "completed_deals")) || unknown(root.path("match"), MATCH_FIELDS)) return true;
        for (JsonNode p : root.path("participants")) if (unknown(p, PERSON_FIELDS)) return true;
        for (JsonNode d : root.path("completed_deals")) if (unknown(d, DEAL_FIELDS)) return true;
        return false;
    }
    private static boolean unknown(JsonNode node, Set<String> fields) {
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return true;
        return false;
    }
    // Same optional-nullable scalar contract as ManualQuarterMaintenance. Unknown
    // extension keys are preserved and reported separately, never silently validated.
    private static void validateKnownTypes(JsonNode root) {
        typedScalars(root.path("match"), MATCH_FIELDS,
            Set.of("revision", "schema_version", "planned_deals", "completed_deals", "ns_total", "ew_total"), Set.of());
        for (JsonNode person : root.path("participants"))
            typedScalars(person, PERSON_FIELDS, Set.of("seat"), Set.of());
        for (JsonNode deal : root.path("completed_deals"))
            typedScalars(deal, DEAL_FIELDS, Set.of("elapsed_ms", "deal_index", "contract_level", "ns_tricks", "ew_tricks",
                "ns_base", "ns_exact_bonus", "ns_penalty", "ns_privilege_adjustment", "ns_total", "ew_base",
                "ew_exact_bonus", "ew_penalty", "ew_privilege_adjustment", "ew_total"),
                Set.of("ns_privilege_used", "ew_privilege_used"));
    }
    private static void typedScalars(JsonNode node, Set<String> known, Set<String> integers, Set<String> booleans) {
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!known.contains(entry.getKey()) || value.isNull()) return;
            if (integers.contains(entry.getKey()) ? !value.isIntegralNumber()
                : booleans.contains(entry.getKey()) ? !value.isBoolean() : !value.isTextual())
                throw failure(Reason.INVALID_ENVELOPE);
        });
    }
    private ProductionReadOnlyEnvelope() {}
    private static Failure failure(Reason reason) { return new Failure(reason); }
    static Decoded decode(String commitId, String matchId, int revision, String canonicalJson, String payloadHash) {
        try {
            if (canonicalJson == null || matchId == null || commitId == null || payloadHash == null)
                throw failure(Reason.INVALID_ENVELOPE);
            if (canonicalJson.length() > MAX_PAYLOAD_CHARS) throw failure(Reason.BUDGET_EXCEEDED);
            if (revision < 1 || !UUID.fromString(matchId).toString().equals(matchId)
                || !commitId.equals(matchId + ":" + revision)) throw failure(Reason.INVALID_ENVELOPE);
            unicode(canonicalJson);
            JsonNode root = JSON.readTree(canonicalJson);
            unicodeTree(root);
            if (root == null || !root.isObject()
                || !canonicalJson.equals(JSON.writeValueAsString(canonical(root)))
                || !payloadHash.equals(hash(canonicalJson))) throw failure(Reason.INVALID_ENVELOPE);
            JsonNode match = root.path("match");
            if (!match.isObject()) throw failure(Reason.INVALID_ENVELOPE);
            if (root.path("completed_deals").size() > MAX_DEALS) throw failure(Reason.BUDGET_EXCEEDED);
            JsonNode schema = match.path("schema_version");
            if (!schema.isIntegralNumber() || !schema.canConvertToInt()) throw failure(Reason.INVALID_ENVELOPE);
            if (schema.intValue() != 1) throw failure(Reason.UNSUPPORTED_SCHEMA);
            marker(match, "statistics_rule", Set.of(LEGACY_STATISTICS_RULE, SETTLEMENT_STATISTICS_RULE, STATISTICS_RULE));
            marker(match, "deal_timing_rule", Set.of(DEAL_TIMING_RULE));
            marker(match, "lifetime_rule", Set.of(LIFETIME_RULE));
            validate(root, matchId, revision);
            validateKnownTypes(root);
            if (!SCORE_VERSION.equals(match.path("score_version").textValue())) throw failure(Reason.UNSUPPORTED_SCORE_VERSION);
            if (match.has("lifetime_rule")) {
                Instant started = Instant.parse(match.path("started_at").textValue());
                Instant finished = Instant.parse(match.path("finished_at").textValue());
                if (started.isAfter(finished)) throw failure(Reason.INVALID_ENVELOPE);
            }
            String category = switch (match.path("statistics_rule").asText()) {
                case LEGACY_STATISTICS_RULE -> "FORMAL";
                case SETTLEMENT_STATISTICS_RULE, STATISTICS_RULE -> match.path("match_category").textValue();
                default -> null;
            };
            return new Decoded(root, category, match.has("statistics_rule"), match.has("lifetime_rule"), match.has("deal_timing_rule"), unknownFields(root));
        } catch (Failure safe) { throw safe; }
          catch (StreamConstraintsException budget) { throw failure(Reason.BUDGET_EXCEEDED); }
          catch (Exception invalid) { throw failure(Reason.INVALID_ENVELOPE); }
    }
    // Java UTF-8 replacement of lone UTF-16 surrogates cannot establish an exact hash binding.
    // Inspect the joined payload and decoded strings/names, never reject a pair split across chunks.
    private static void unicode(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) throw failure(Reason.UNSUPPORTED_TEXT_ENCODING);
            } else if (Character.isLowSurrogate(c)) throw failure(Reason.UNSUPPORTED_TEXT_ENCODING);
        }
    }
    private static void unicodeTree(JsonNode node) {
        if (node==null) return;
        if (node.isTextual()) unicode(node.textValue());
        if (node.isObject()) node.fields().forEachRemaining(e->{unicode(e.getKey());unicodeTree(e.getValue());});
        else if (node.isArray()) node.forEach(ProductionReadOnlyEnvelope::unicodeTree);
    }
    private static void marker(JsonNode match, String field, Set<String> known) {
        if (!match.has(field)) return;
        JsonNode value = match.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw failure(Reason.INVALID_ENVELOPE);
        if (!known.contains(value.textValue())) throw failure(Reason.UNSUPPORTED_RULE);
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void validate(JsonNode root,String matchId,int revision) {
        JsonNode match=root.path("match"),people=root.path("participants"),deals=root.path("completed_deals");
        if(!match.isObject() || !match.path("match_id").textValue().equals(matchId)
            || !match.path("revision").isIntegralNumber() || !match.path("revision").canConvertToInt() || match.path("revision").asInt()!=revision
            || !match.path("schema_version").isIntegralNumber() || !match.path("schema_version").canConvertToInt() || match.path("schema_version").asInt()!=1
            || match.path("score_version").textValue().isBlank() || match.path("app_version").textValue().isBlank()
            || !people.isArray() || people.size()!=4 || !deals.isArray()
            || !match.path("completed_deals").isIntegralNumber() || !match.path("completed_deals").canConvertToInt() || match.path("completed_deals").asInt()!=deals.size())
            throw failure(Reason.INVALID_ENVELOPE);
        boolean statistics = match.has("statistics_rule");
        boolean fixed = STATISTICS_RULE.equals(match.path("statistics_rule").textValue());
        boolean categorized = fixed || SETTLEMENT_STATISTICS_RULE.equals(match.path("statistics_rule").textValue());
        boolean legacyStatistics = LEGACY_STATISTICS_RULE.equals(match.path("statistics_rule").textValue());
        if (statistics && (!match.path("statistics_rule").isTextual()
            || !(categorized || legacyStatistics)
            || !(fixed ? Set.of("SCORED", "FORFEIT", "NO_CONTEST").contains(match.path("outcome").textValue())
                : "SCORED".equals(match.path("outcome").textValue()))))
            throw failure(Reason.INVALID_ENVELOPE);
        if (categorized ? !Set.of("FORMAL", "CASUAL").contains(match.path("match_category").textValue())
            : match.has("match_category")) throw failure(Reason.INVALID_ENVELOPE);
        if (fixed ? !match.path("match_category").textValue().equals(match.path("room_type").textValue())
            : match.has("room_type")) throw failure(Reason.INVALID_ENVELOPE);
        if (match.has("lifetime_rule") && (!fixed || !LIFETIME_RULE.equals(match.path("lifetime_rule").textValue())))
            throw failure(Reason.INVALID_ENVELOPE);
        int settlementBots = 0, originalHumans = 0;
        Set<String> accounts = new HashSet<>();
        Set<Integer> seats=new HashSet<>();
        for(JsonNode person:people) {
            JsonNode seat=person.path("seat");
            if(!person.path("match_id").textValue().equals(matchId) || !seat.isIntegralNumber() || !seat.canConvertToInt() || seat.asInt()<0 || seat.asInt()>3 || !seats.add(seat.asInt()))throw failure(Reason.INVALID_ENVELOPE);
            if(!person.path("account_id").isNull() && !person.path("account_id").isMissingNode() && !person.path("guest_id").isNull() && !person.path("guest_id").isMissingNode())throw failure(Reason.INVALID_ENVELOPE);
            if (statistics) {
                JsonNode account = person.path("account_id");
                boolean bot = "BOT".equals(person.path("player_type").textValue());
                boolean human = "HUMAN".equals(person.path("player_type").textValue());
                if (human) originalHumans++;
                String control = person.path("control_type").textValue();
                if (!(human || categorized && bot)
                    || !(bot ? "BOT".equals(control) : Set.of("HUMAN", "MIXED").contains(control))
                    || !(seat.asInt() % 2 == 0 ? "NS" : "EW").equals(person.path("team_id").textValue())
                    || !person.path("guest_id").isNull()
                    || !(account.isNull() || !bot && canonicalUuid(account) && accounts.add(account.textValue())))
                    throw failure(Reason.INVALID_ENVELOPE);
                if (fixed && "FORMAL".equals(match.path("room_type").textValue())
                    && (!human || !"HUMAN".equals(control)))
                    throw failure(Reason.INVALID_ENVELOPE);
                if (categorized) {
                    String settled = person.path("settlement_control").textValue();
                    if (!Set.of("HUMAN", "BOT").contains(settled)
                        || bot && !"BOT".equals(settled)
                        || "HUMAN".equals(control) && !"HUMAN".equals(settled))
                        throw failure(Reason.INVALID_ENVELOPE);
                    if ("BOT".equals(settled)) settlementBots++;
                } else if (person.has("settlement_control")) throw failure(Reason.INVALID_ENVELOPE);
                if (person.has("erased_marker") && (bot || !canonicalUuid(person.path("erased_marker")) || !account.isNull()))
                    throw failure(Reason.INVALID_ENVELOPE);
            }
        }
        if (fixed && originalHumans == 0) throw failure(Reason.INVALID_ENVELOPE);
        if (categorized && (fixed
            ? "FORMAL".equals(match.path("room_type").textValue()) && settlementBots != 0
              || settlementBots == 4 && !"NO_CONTEST".equals(match.path("outcome").textValue())
            : settlementBots == 4 || !(settlementBots == 0 ? "FORMAL" : "CASUAL").equals(match.path("match_category").textValue())))
            throw failure(Reason.INVALID_ENVELOPE);
        boolean timed = match.has("deal_timing_rule");
        if (fixed && !timed) throw failure(Reason.INVALID_ENVELOPE);
        if (timed && !DEAL_TIMING_RULE.equals(match.path("deal_timing_rule").textValue()))
            throw failure(Reason.INVALID_ENVELOPE);
        Set<String> ids=new HashSet<>();Set<Integer> indices=new HashSet<>();
        long nsTotal = 0, ewTotal = 0;
        for(JsonNode deal:deals) {
            var elapsed = deal.path("elapsed_ms");
            if (timed ? !elapsed.isIntegralNumber() || !elapsed.canConvertToLong()
                    || elapsed.longValue() < 0 || elapsed.longValue() > 9_007_199_254_740_991L
                : deal.has("elapsed_ms")) throw failure(Reason.INVALID_ENVELOPE);
            String id=deal.path("deal_id").textValue();
            if(!deal.path("match_id").textValue().equals(matchId) || !UUID.fromString(id).toString().equals(id) || !ids.add(id)
                || !deal.path("deal_index").isIntegralNumber() || !deal.path("deal_index").canConvertToInt() || !indices.add(deal.path("deal_index").asInt()))throw failure(Reason.INVALID_ENVELOPE);
            if (fixed) {
                int index = integer(deal, "deal_index");
                if (index < 1 || index > deals.size()) throw failure(Reason.INVALID_ENVELOPE);
                nsTotal = Math.addExact(nsTotal, integer(deal, "ns_total"));
                ewTotal = Math.addExact(ewTotal, integer(deal, "ew_total"));
            }
        }
        if (fixed) {
            int planned = integer(match, "planned_deals"), completed = integer(match, "completed_deals");
            String outcome = match.path("outcome").textValue();
            var winner = match.path("winner_team_id");
            if (!Set.of(1, 3, 5).contains(planned) || completed < 0 || completed > planned
                || "SCORED".equals(outcome) && completed != planned
                || !match.path("reason").isTextual() || match.path("reason").textValue().isBlank()
                || integer(match, "ns_total") != nsTotal || integer(match, "ew_total") != ewTotal)
                throw failure(Reason.INVALID_ENVELOPE);
            String reason = match.path("reason").textValue();
            String roomType = match.path("room_type").textValue();
            boolean validReason = switch (outcome) {
                case "SCORED" -> (planned == 3 ? "THREE_DEALS_COMPLETE" : "PLANNED_DEALS_COMPLETE").equals(reason);
                case "FORFEIT" -> "FORMAL".equals(roomType)
                    && Set.of("MANUAL_EXIT", "RECONNECT_TIMEOUT").contains(reason);
                case "NO_CONTEST" -> "FORMAL".equals(roomType) ? "BOTH_TEAMS_FAILED".equals(reason)
                    : Set.of("NO_HUMANS_CONNECTED", "NO_HUMANS_REMAIN").contains(reason);
                default -> false;
            };
            if (!validReason || !"SCORED".equals(outcome) && completed >= planned)
                throw failure(Reason.INVALID_ENVELOPE);
            String expected = nsTotal == ewTotal ? null : nsTotal > ewTotal ? "NS" : "EW";
            if ("SCORED".equals(outcome) ? expected == null ? !winner.isNull() : !expected.equals(winner.textValue())
                : "NO_CONTEST".equals(outcome) ? !winner.isNull() : !Set.of("NS", "EW").contains(winner.textValue()))
                throw failure(Reason.INVALID_ENVELOPE);
        }
    }
    private static int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw failure(Reason.INVALID_ENVELOPE);
        return value.intValue();
    }
    private static boolean canonicalUuid(JsonNode node) {
        if (!node.isTextual()) return false;
        try { return UUID.fromString(node.textValue()).toString().equals(node.textValue()); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static Object canonical(JsonNode n) {
        if(n.isObject()) { Map<String,Object> m=new TreeMap<>(); n.fields().forEachRemaining(e->m.put(e.getKey(),canonical(e.getValue()))); return m; }
        if(n.isArray()) { List<Object> a=new ArrayList<>(); n.forEach(v->a.add(canonical(v))); return a; }
        if(n.isNull()) return null;
        if(n.isTextual()) return n.textValue();
        if(n.isBoolean()) return n.booleanValue();
        if(n.isIntegralNumber()) return n.bigIntegerValue();
        throw failure(Reason.INVALID_ENVELOPE);
    }
}
