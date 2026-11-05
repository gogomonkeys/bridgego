package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable server-created canonical JSON, never a request DTO. */
public record FinalMatchEnvelope(String commitId, String matchId, int revision, String canonicalJson, String payloadHash) {
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
        Set<Integer> seats=new HashSet<>();
        for(JsonNode person:people) {
            JsonNode seat=person.path("seat");
            if(!person.path("match_id").asText().equals(matchId) || !seat.isIntegralNumber() || seat.asInt()<0 || seat.asInt()>3 || !seats.add(seat.asInt()))throw new IllegalArgumentException("Invalid participant key");
            if(!person.path("account_id").isNull() && !person.path("account_id").isMissingNode() && !person.path("guest_id").isNull() && !person.path("guest_id").isMissingNode())throw new IllegalArgumentException("Ambiguous identity");
        }
        Set<String> ids=new HashSet<>();Set<Integer> indices=new HashSet<>();
        for(JsonNode deal:deals) {
            String id=deal.path("deal_id").asText();
            if(!deal.path("match_id").asText().equals(matchId) || !UUID.fromString(id).toString().equals(id) || !ids.add(id)
                || !deal.path("deal_index").isIntegralNumber() || !indices.add(deal.path("deal_index").asInt()))throw new IllegalArgumentException("Invalid completed deal key");
        }
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
