package com.bridge.quartercore;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable server-created canonical JSON, never a request DTO. */
public record FinalMatchEnvelope(String commitId, String matchId, int revision, String canonicalJson, String payloadHash) {
    public static final String LEGACY_STATISTICS_RULE = "four-humans-at-settlement-v1";
    public static final String SETTLEMENT_STATISTICS_RULE = "match-category-at-settlement-v2";
    public static final String STATISTICS_RULE = "fixed-room-type-v3";
    public static final String DEAL_TIMING_RULE = "elapsed-including-pauses-v1";
    /** Only verified rule markers establish historic eligibility, never HUMAN/MIXED alone. */
    public String statisticsCategory() {
        var match = payload().path("match");
        return switch (match.path("statistics_rule").asText()) {
            case LEGACY_STATISTICS_RULE -> "FORMAL";
            case SETTLEMENT_STATISTICS_RULE, STATISTICS_RULE -> match.path("match_category").textValue();
            default -> null;
        };
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    public FinalMatchEnvelope {
        if (revision < 1 || !UUID.fromString(matchId).toString().equals(matchId) || !commitId.equals(matchId + ":" + revision)) throw new IllegalArgumentException("Invalid match identity");
        try {
            JsonNode tree = JSON.readTree(canonicalJson);
            validate(tree, matchId, revision);
            if (!tree.isObject() || !canonicalJson.equals(JSON.writeValueAsString(canonical(tree))) || !payloadHash.equals(hash(canonicalJson))) throw new IllegalArgumentException("Non-canonical envelope");
        } catch (Exception e) { throw new IllegalArgumentException("Invalid envelope", e); }
    }
    public static FinalMatchEnvelope create(String matchId, int revision, Map<String,Object> payload) {
        try {
            String json = JSON.writeValueAsString(canonical(JSON.valueToTree(payload)));
            return new FinalMatchEnvelope(matchId + ":" + revision, matchId, revision, json, hash(json));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid snapshot", e); }
    }
    /** Fresh typed tree; callers cannot mutate this envelope. */
    public JsonNode payload() { try { return JSON.readTree(canonicalJson); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static void validate(JsonNode root,String matchId,int revision) {
        JsonNode match=root.path("match"),people=root.path("participants"),deals=root.path("completed_deals");
        if(!match.isObject() || !match.path("match_id").asText().equals(matchId)
            || !match.path("revision").isIntegralNumber() || match.path("revision").asInt()!=revision
            || !match.path("schema_version").isIntegralNumber() || match.path("schema_version").asInt()!=1
            || match.path("score_version").asText().isBlank() || match.path("app_version").asText().isBlank()
            || !people.isArray() || people.size()!=4 || !deals.isArray()
            || !match.path("completed_deals").isIntegralNumber() || match.path("completed_deals").asInt()!=deals.size())
            throw new IllegalArgumentException("Invalid match schema");
        boolean statistics = match.has("statistics_rule");
        boolean fixed = STATISTICS_RULE.equals(match.path("statistics_rule").textValue());
        boolean categorized = fixed || SETTLEMENT_STATISTICS_RULE.equals(match.path("statistics_rule").textValue());
        boolean legacyStatistics = LEGACY_STATISTICS_RULE.equals(match.path("statistics_rule").textValue());
        if (statistics && (!match.path("statistics_rule").isTextual()
            || !(categorized || legacyStatistics)
            || !(fixed ? Set.of("SCORED", "FORFEIT", "NO_CONTEST").contains(match.path("outcome").asText())
                : "SCORED".equals(match.path("outcome").textValue()))))
            throw new IllegalArgumentException("Invalid statistics rule");
        if (categorized ? !Set.of("FORMAL", "CASUAL").contains(match.path("match_category").asText())
            : match.has("match_category")) throw new IllegalArgumentException("Invalid statistics category");
        if (fixed ? !match.path("match_category").asText().equals(match.path("room_type").textValue())
            : match.has("room_type")) throw new IllegalArgumentException("Invalid fixed room type");
        if (match.has("lifetime_rule") && (!fixed || !LifetimeStatistics.RULE.equals(match.path("lifetime_rule").textValue())))
            throw new IllegalArgumentException("Invalid lifetime statistics marker");
        int settlementBots = 0, originalHumans = 0;
        Set<String> accounts = new HashSet<>();
        Set<Integer> seats=new HashSet<>();
        for(JsonNode person:people) {
            JsonNode seat=person.path("seat");
            if(!person.path("match_id").asText().equals(matchId) || !seat.isIntegralNumber() || seat.asInt()<0 || seat.asInt()>3 || !seats.add(seat.asInt()))throw new IllegalArgumentException("Invalid participant key");
            if(!person.path("account_id").isNull() && !person.path("account_id").isMissingNode() && !person.path("guest_id").isNull() && !person.path("guest_id").isMissingNode())throw new IllegalArgumentException("Ambiguous identity");
            if (statistics) {
                JsonNode account = person.path("account_id");
                boolean bot = "BOT".equals(person.path("player_type").textValue());
                boolean human = "HUMAN".equals(person.path("player_type").textValue());
                if (human) originalHumans++;
                String control = person.path("control_type").asText();
                if (!(human || categorized && bot)
                    || !(bot ? "BOT".equals(control) : Set.of("HUMAN", "MIXED").contains(control))
                    || !(seat.asInt() % 2 == 0 ? "NS" : "EW").equals(person.path("team_id").textValue())
                    || !person.path("guest_id").isNull()
                    || !(account.isNull() || !bot && canonicalUuid(account) && accounts.add(account.textValue())))
                    throw new IllegalArgumentException("Invalid statistics participant");
                if (fixed && "FORMAL".equals(match.path("room_type").asText())
                    && (!human || !"HUMAN".equals(control)))
                    throw new IllegalArgumentException("Formal room cannot contain automated control");
                if (categorized) {
                    String settled = person.path("settlement_control").asText();
                    if (!Set.of("HUMAN", "BOT").contains(settled)
                        || bot && !"BOT".equals(settled)
                        || "HUMAN".equals(control) && !"HUMAN".equals(settled))
                        throw new IllegalArgumentException("Invalid settlement controller");
                    if ("BOT".equals(settled)) settlementBots++;
                } else if (person.has("settlement_control")) throw new IllegalArgumentException("Unexpected settlement controller");
                if (person.has("erased_marker") && (bot || !canonicalUuid(person.path("erased_marker")) || !account.isNull()))
                    throw new IllegalArgumentException("Invalid erased statistics participant");
            }
        }
        if (fixed && originalHumans == 0) throw new IllegalArgumentException("Room must start with a human");
        if (categorized && (fixed
            ? "FORMAL".equals(match.path("room_type").asText()) && settlementBots != 0
              || settlementBots == 4 && !"NO_CONTEST".equals(match.path("outcome").asText())
            : settlementBots == 4 || !(settlementBots == 0 ? "FORMAL" : "CASUAL").equals(match.path("match_category").textValue())))
            throw new IllegalArgumentException("Category does not match settlement controllers");
        boolean timed = match.has("deal_timing_rule");
        if (fixed && !timed) throw new IllegalArgumentException("Fixed-room records require deal timing");
        if (timed && !DEAL_TIMING_RULE.equals(match.path("deal_timing_rule").textValue()))
            throw new IllegalArgumentException("Invalid deal timing rule");
        Set<String> ids=new HashSet<>();Set<Integer> indices=new HashSet<>();
        long nsTotal = 0, ewTotal = 0;
        for(JsonNode deal:deals) {
            var elapsed = deal.path("elapsed_ms");
            if (timed ? !elapsed.isIntegralNumber() || !elapsed.canConvertToLong()
                    || elapsed.longValue() < 0 || elapsed.longValue() > 9_007_199_254_740_991L
                : deal.has("elapsed_ms")) throw new IllegalArgumentException("Invalid deal elapsed time");
            String id=deal.path("deal_id").asText();
            if(!deal.path("match_id").asText().equals(matchId) || !UUID.fromString(id).toString().equals(id) || !ids.add(id)
                || !deal.path("deal_index").isIntegralNumber() || !indices.add(deal.path("deal_index").asInt()))throw new IllegalArgumentException("Invalid completed deal key");
            if (fixed) {
                int index = integer(deal, "deal_index");
                if (index < 1 || index > deals.size()) throw new IllegalArgumentException("Invalid completed deal sequence");
                nsTotal = Math.addExact(nsTotal, integer(deal, "ns_total"));
                ewTotal = Math.addExact(ewTotal, integer(deal, "ew_total"));
            }
        }
        if (fixed) {
            int planned = integer(match, "planned_deals"), completed = integer(match, "completed_deals");
            String outcome = match.path("outcome").asText();
            var winner = match.path("winner_team_id");
            if (!Set.of(1, 3, 5).contains(planned) || completed < 0 || completed > planned
                || "SCORED".equals(outcome) && completed != planned
                || !match.path("reason").isTextual() || match.path("reason").asText().isBlank()
                || integer(match, "ns_total") != nsTotal || integer(match, "ew_total") != ewTotal)
                throw new IllegalArgumentException("Invalid finalized match totals");
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
                throw new IllegalArgumentException("Outcome contradicts fixed-room lifecycle");
            String expected = nsTotal == ewTotal ? null : nsTotal > ewTotal ? "NS" : "EW";
            if ("SCORED".equals(outcome) ? expected == null ? !winner.isNull() : !expected.equals(winner.textValue())
                : "NO_CONTEST".equals(outcome) ? !winner.isNull() : !Set.of("NS", "EW").contains(winner.asText()))
                throw new IllegalArgumentException("Invalid finalized match winner");
        }
    }
    private static int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Invalid match number");
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
        throw new IllegalArgumentException("Only integer numeric values supported");
    }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
}
