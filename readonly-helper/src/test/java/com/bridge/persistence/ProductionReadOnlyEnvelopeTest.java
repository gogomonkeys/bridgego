package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyEnvelope.Reason.*;

/** Synthetic fixtures only; no backend dependencies, files, credentials, or network. */
class ProductionReadOnlyEnvelopeTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ID = "00000000-0000-0000-0000-000000000001";
    static ObjectNode legacy() {
        ObjectNode root = JSON.createObjectNode(), match = root.putObject("match");
        match.put("match_id", ID).put("revision", 1).put("schema_version", 1)
            .put("score_version", ProductionReadOnlyEnvelope.SCORE_VERSION).put("app_version", "synthetic")
            .put("completed_deals", 0);
        var people = root.putArray("participants");
        for (int i = 0; i < 4; i++) people.addObject().put("match_id", ID).put("seat", i);
        root.putArray("completed_deals");
        return root;
    }
    static ObjectNode marked(String rule) {
        ObjectNode root = legacy();
        match(root).put("statistics_rule", rule).put("outcome", "SCORED");
        boolean categorized = !rule.equals(ProductionReadOnlyEnvelope.LEGACY_STATISTICS_RULE);
        if (categorized) match(root).put("match_category", "FORMAL");
        for (int i = 0; i < 4; i++) {
            var person = person(root, i);
            person.put("player_type", "HUMAN").put("control_type", "HUMAN")
                .put("team_id", i % 2 == 0 ? "NS" : "EW").putNull("account_id").putNull("guest_id");
            if (categorized) person.put("settlement_control", "HUMAN");
        }
        return root;
    }
    static ObjectNode fixed() {
        var root = marked(ProductionReadOnlyEnvelope.STATISTICS_RULE);
        match(root).put("room_type", "FORMAL").put("planned_deals", 3).put("ns_total", 0).put("ew_total", 0)
            .put("outcome", "NO_CONTEST").put("reason", "BOTH_TEAMS_FAILED").putNull("winner_team_id")
            .put("deal_timing_rule", ProductionReadOnlyEnvelope.DEAL_TIMING_RULE);
        return root;
    }
    static ObjectNode match(ObjectNode root) { return (ObjectNode) root.path("match"); }
    static ObjectNode person(ObjectNode root, int index) { return (ObjectNode) root.path("participants").get(index); }
    static Object sorted(JsonNode node) {
        if (node.isObject()) { var map = new TreeMap<String,Object>(); node.fields().forEachRemaining(e -> map.put(e.getKey(), sorted(e.getValue()))); return map; }
        if (node.isArray()) { var list = new ArrayList<>(); node.forEach(n -> list.add(sorted(n))); return list; }
        return node;
    }
    static String json(ObjectNode root) throws Exception { return JSON.writeValueAsString(sorted(root)); }
    static String hash(String json) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
    static ProductionReadOnlyEnvelope.Decoded decode(ObjectNode root) throws Exception { return decode(json(root)); }
    static ProductionReadOnlyEnvelope.Decoded decode(String json) throws Exception { return ProductionReadOnlyEnvelope.decode(ID + ":1", ID, 1, json, hash(json)); }
    static void invalid(ObjectNode root) { assertEquals(INVALID_ENVELOPE, assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode(root)).reason()); }
    @Test void absentMarkersAreValidButUnavailableWithoutInventedModernRequirements() throws Exception {
        var d = decode(legacy()); assertFalse(d.supported()); assertFalse(d.lifetimeSupported());
        assertNull(d.statisticsCategory()); assertFalse(d.hasStatisticsRule()); assertFalse(d.hasDealTimingRule());
    }
    @Test void allThreeStatisticsGenerationsRemainSupported() throws Exception {
        for (String rule : List.of(ProductionReadOnlyEnvelope.LEGACY_STATISTICS_RULE, ProductionReadOnlyEnvelope.SETTLEMENT_STATISTICS_RULE)) {
            var d = decode(marked(rule)); assertTrue(d.supported()); assertEquals("FORMAL", d.statisticsCategory()); assertFalse(d.lifetimeSupported());
        }
        assertTrue(decode(fixed()).supported());
    }
    @Test void unknownExtensionsAreNotSilentlyFullySupported() throws Exception {
        for (int target = 0; target < 3; target++) {
            var root = fixed(); (target == 0 ? root : target == 1 ? match(root) : person(root,0)).put("future_field", "synthetic");
            var d = decode(root); assertTrue(d.hasUnknownFields()); assertFalse(d.supported());
        }
    }
    @Test void strictJsonCanonicalAndHash() throws Exception {
        String canonical = json(legacy());
        for (String bad : List.of(canonical + " {}", canonical + " ", " " + canonical,
                canonical.replace("\"revision\":1", "\"revision\":1,\"revision\":1"),
                canonical.replace("\"revision\":1", "\"revision\":1.0"), canonical.replace("\"revision\":1", "\"revision\":1e0"))) {
            assertEquals(INVALID_ENVELOPE, assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode(bad)).reason());
        }
        assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> ProductionReadOnlyEnvelope.decode(ID+":1", ID, 1, canonical, "0".repeat(64)));
        assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> ProductionReadOnlyEnvelope.decode(ID+":1", ID, 1, canonical, hash(canonical).toUpperCase(Locale.ROOT)));
    }
    @Test void identityAndRevisionAreExact() throws Exception {
        String data = json(legacy());
        for (String id : List.of("1-1-1-1-1", "SECRET_ID", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"))
            assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> ProductionReadOnlyEnvelope.decode(id+":1", id, 1, data, hash(data)));
        for (String commit : List.of(ID+":01", ID+":2", "SECRET_COMMIT"))
            assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> ProductionReadOnlyEnvelope.decode(commit, ID, 1, data, hash(data)));
        assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> ProductionReadOnlyEnvelope.decode(ID+":0", ID, 0, data, hash(data)));
    }
    @Test void wrongTypesAndIntegerTruncationFail() {
        List<Consumer<ObjectNode>> cases = List.of(r -> match(r).put("revision", 4294967297L),
            r -> match(r).put("completed_deals", 4294967296L), r -> person(r,0).put("seat", 4294967296L),
            r -> match(r).put("score_version", 10), r -> match(r).put("app_version", true),
            r -> match(r).put("revision", "1"), r -> match(r).put("schema_version", "1"),
            r -> match(r).put("schema_version", 4294967297L), r -> person(r,1).put("seat", 0),
            r -> person(r,0).put("match_id", "other"));
        for (var change : cases) { var r = legacy(); change.accept(r); invalid(r); }
    }
    @Test void unsupportedVersionsRemainDistinctFromMalformedMarkers() {
        var schema = legacy(); match(schema).put("schema_version", 2);
        assertEquals(UNSUPPORTED_SCHEMA, assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode(schema)).reason());
        var score = legacy(); match(score).put("score_version", "future-score");
        assertEquals(UNSUPPORTED_SCORE_VERSION, assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode(score)).reason());
        for (String field : List.of("statistics_rule", "deal_timing_rule", "lifetime_rule")) {
            var r = legacy(); match(r).put(field, "future-rule");
            assertEquals(UNSUPPORTED_RULE, assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode(r)).reason());
            match(r).putNull(field); invalid(r); match(r).put(field,true); invalid(r);
        }
    }
    @Test void statisticsParticipantsEnforceIdentityAndControllerSemantics() {
        List<Consumer<ObjectNode>> cases = List.of(r -> person(r,0).put("player_type", "BOT"),
            r -> person(r,0).put("team_id", "EW"), r -> person(r,0).remove("guest_id"),
            r -> person(r,0).remove("account_id"), r -> person(r,0).put("account_id", true),
            r -> person(r,0).put("guest_id", "guest"), r -> person(r,0).put("control_type", "MIXED"),
            r -> person(r,0).put("settlement_control", "BOT"), r -> person(r,0).put("erased_marker", "bad"));
        for (var change : cases) { var r = fixed(); change.accept(r); invalid(r); }
        var duplicate = fixed(); person(duplicate,0).put("account_id", ID); person(duplicate,1).put("account_id", ID); invalid(duplicate);
    }
    @Test void fixedLifecycleRequiresConsistentTotalsWinnerReasonAndTiming() {
        List<Consumer<ObjectNode>> cases = List.of(r -> match(r).remove("room_type"),
            r -> match(r).put("room_type", "CASUAL"), r -> match(r).put("planned_deals",2),
            r -> match(r).put("ns_total",1), r -> match(r).put("winner_team_id","NS"),
            r -> match(r).put("reason","MANUAL_EXIT"), r -> match(r).put("outcome","SCORED"),
            r -> match(r).remove("deal_timing_rule"));
        for (var change : cases) { var r = fixed(); change.accept(r); invalid(r); }
    }
    @Test void lifetimeRequiresFixedRuleAndValidChronologicalTimestamps() throws Exception {
        var r = fixed(); match(r).put("lifetime_rule", ProductionReadOnlyEnvelope.LIFETIME_RULE)
            .put("started_at","2026-01-01T00:00:00Z").put("finished_at","2026-01-01T00:01:00Z");
        assertTrue(decode(r).lifetimeSupported());
        match(r).put("started_at","2026-02-01T00:00:00Z"); invalid(r);
        match(r).put("started_at","SECRET_TIME"); invalid(r);
        var old = legacy(); match(old).put("lifetime_rule", ProductionReadOnlyEnvelope.LIFETIME_RULE); invalid(old);
    }
    @Test void explicitWorkBudgetsAreFinite() {
        assertEquals(BUDGET_EXCEEDED, assertThrows(ProductionReadOnlyEnvelope.Failure.class,
            () -> decode(" ".repeat(ProductionReadOnlyEnvelope.MAX_PAYLOAD_CHARS+1))).reason());
        var r = legacy(); var deals = r.withArray("completed_deals");
        for (int i=0;i<=ProductionReadOnlyEnvelope.MAX_DEALS;i++) deals.addObject();
        assertEquals(BUDGET_EXCEEDED, assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode(r)).reason());
        assertEquals(BUDGET_EXCEEDED, assertThrows(ProductionReadOnlyEnvelope.Failure.class,
            () -> decode("[".repeat(40)+"0"+"]".repeat(40))).reason());
    }
    static ObjectNode addDeal(ObjectNode root) {
        match(root).put("completed_deals",1);
        return root.withArray("completed_deals").addObject().put("match_id",ID)
            .put("deal_id","00000000-0000-0000-0000-000000000002").put("deal_index",1);
    }
    @Test void dealKeysAndIntegerBoundsAreStrictEvenForLegacy() throws Exception {
        var root = legacy(); var deal = addDeal(root); assertDoesNotThrow(() -> decode(root));
        deal.put("deal_index",4294967297L); invalid(root);
        deal.put("deal_index",1).put("deal_id","2-2-2-2-2"); invalid(root);
        deal.put("deal_id","00000000-0000-0000-0000-000000000002");
        root.withArray("completed_deals").add(deal.deepCopy()); match(root).put("completed_deals",2); invalid(root);
    }
    @Test void timingBoundsDoNotInventWallClockConstraints() throws Exception {
        var root = legacy(); var deal = addDeal(root);
        match(root).put("deal_timing_rule",ProductionReadOnlyEnvelope.DEAL_TIMING_RULE);
        for (long elapsed : new long[]{0,9007199254740991L}) {
            deal.put("elapsed_ms",elapsed); assertTrue(decode(root).hasDealTimingRule());
        }
        for (long elapsed : new long[]{-1,9007199254740992L,Long.MAX_VALUE}) { deal.put("elapsed_ms",elapsed); invalid(root); }
        deal.put("elapsed_ms","1"); invalid(root); deal.remove("elapsed_ms"); invalid(root);
        match(root).remove("deal_timing_rule"); deal.put("elapsed_ms",0); invalid(root);
    }
    @Test void historicalAndFixedCategoriesHaveDifferentControllerRules() throws Exception {
        var v1 = marked(ProductionReadOnlyEnvelope.LEGACY_STATISTICS_RULE);
        person(v1,0).put("control_type","MIXED"); assertEquals("FORMAL",decode(v1).statisticsCategory());
        var v2 = marked(ProductionReadOnlyEnvelope.SETTLEMENT_STATISTICS_RULE);
        person(v2,0).put("control_type","MIXED").put("settlement_control","BOT"); invalid(v2);
        match(v2).put("match_category","CASUAL"); assertEquals("CASUAL",decode(v2).statisticsCategory());
        for (int i=1;i<4;i++) person(v2,i).put("control_type","MIXED").put("settlement_control","BOT"); invalid(v2);
        var v3 = fixed(); match(v3).put("room_type","CASUAL").put("match_category","CASUAL").put("reason","NO_HUMANS_REMAIN");
        assertEquals("CASUAL",decode(v3).statisticsCategory());
        for (int i=0;i<4;i++) person(v3,i).put("control_type","MIXED").put("settlement_control","BOT");
        assertDoesNotThrow(() -> decode(v3));
        for (int i=0;i<4;i++) person(v3,i).put("player_type","BOT").put("control_type","BOT"); invalid(v3);
    }
    @Test void fixedScoredAndForfeitOutcomesDoNotConfuseScoresAndWinners() throws Exception {
        var root = fixed(); var deal = addDeal(root); deal.put("ns_total",7).put("ew_total",3).put("elapsed_ms",1);
        match(root).put("ns_total",7).put("ew_total",3).put("outcome","FORFEIT").put("reason","MANUAL_EXIT").put("winner_team_id","EW");
        assertDoesNotThrow(() -> decode(root));
        match(root).put("outcome","SCORED").put("planned_deals",1).put("reason","PLANNED_DEALS_COMPLETE"); invalid(root);
        match(root).put("winner_team_id","NS"); assertDoesNotThrow(() -> decode(root));
        deal.put("ns_total",3); match(root).put("ns_total",3).putNull("winner_team_id"); assertDoesNotThrow(() -> decode(root));
        deal.put("deal_index",2); invalid(root);
    }
    @Test void knownOptionalScalarTypesAreCheckedWithoutInventedLegacyRules() throws Exception {
        var root = legacy(); var deal = addDeal(root);
        deal.put("contract_level", "garbage"); invalid(root); deal.putNull("contract_level");
        deal.putObject("ns_base"); invalid(root); deal.remove("ns_base");
        deal.put("ns_privilege_used",1); invalid(root); deal.put("ns_privilege_used",true);
        deal.put("ns_total",-999999); assertDoesNotThrow(() -> decode(root));
        match(root).putObject("reason"); invalid(root); match(root).putNull("reason");
        person(root,0).put("account_id",false); invalid(root); person(root,0).putNull("account_id");
        deal.putObject("future_extension").put("some_key",true);
        assertTrue(decode(root).hasUnknownFields());
    }
    @Test void parserBudgetsCoverNamesNumbersAndTokenFloodsIndependently() {
        for (String input : List.of("{\"" + "a".repeat(129) + "\":0}",
                "{\"n\":" + "1".repeat(101) + "}", "[" + "0,".repeat(200000) + "0]"))
            assertEquals(BUDGET_EXCEEDED, assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode(input)).reason());
    }
    @Test void failuresAndDecodedStringsNeverIncludeSecretsOrCauses() throws Exception {
        var failure = assertThrows(ProductionReadOnlyEnvelope.Failure.class, () -> decode("SECRET_PAYLOAD"));
        assertEquals("INVALID_ENVELOPE",failure.getMessage()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("SECRET"));
        var r = legacy(); match(r).put("app_version","SECRET_PAYLOAD");
        assertEquals("Decoded[REDACTED]", decode(r).toString());
    }

    @Test void loneSurrogatesAreUnavailableWhileValidSupplementaryTextIsSupported() throws Exception {
        for(String bad:List.of(String.valueOf((char)0xd800),String.valueOf((char)0xdc00),new String(new char[]{0xdc00,0xd800}))) {
            var root=fixed();match(root).put("app_version",bad);
            assertEquals(UNSUPPORTED_TEXT_ENCODING,assertThrows(ProductionReadOnlyEnvelope.Failure.class,()->decode(root)).reason());
        }
        String escaped="{\"x\":\"\\ud800\"}";
        assertEquals(UNSUPPORTED_TEXT_ENCODING,assertThrows(ProductionReadOnlyEnvelope.Failure.class,()->decode(escaped)).reason());
        var root=fixed();match(root).put("app_version","synthetic "+new String(Character.toChars(0x1f600)));assertTrue(decode(root).supported());
    }
}
