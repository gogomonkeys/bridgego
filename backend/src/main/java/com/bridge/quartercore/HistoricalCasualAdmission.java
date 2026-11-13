package com.bridge.quartercore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
/** Explicit historical extension. Pure transformation, not permission to write. */
public final class HistoricalCasualAdmission {
 public static final String RULE="historical-casual-admission-v1";
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final Set<String> FIELDS=Set.of("rule","source_commit","source_hash","source_facts_hash","approval_reference","redacted_source_hash");
 public static boolean applies(JsonNode root){return root.has("historical_admission");}
 public static Set<String> rootFields(JsonNode root){return applies(root)?Set.of("match","participants","completed_deals","historical_admission"):Set.of("match","participants","completed_deals");}
 private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("Invalid explicit historical admission");}
 private static String uuid(String s){require(UUID.fromString(s).toString().equals(s));return s;}
 private static String digest(JsonNode n){try{return FinalMatchEnvelope.hash(JSON.writeValueAsString(canonical(n)));}catch(Exception e){throw new IllegalArgumentException(e);}}
 private static JsonNode canonical(JsonNode n){if(n.isObject()){var o=JSON.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->o.set(k,canonical(n.get(k))));return o;}if(n.isArray()){var a=JSON.createArrayNode();n.forEach(v->a.add(canonical(v)));return a;}return n;}
 public static ObjectNode reconstructed(JsonNode root){var n=(ObjectNode)root.deepCopy();n.remove("historical_admission");var m=(ObjectNode)n.path("match");m.remove(List.of("statistics_rule","match_category","lifetime_rule"));m.put("revision",1);return n;}
 private static String facts(JsonNode n){var copy=n.deepCopy();for(var p:copy.path("participants")){((ObjectNode)p).remove(List.of("account_id","erased_marker"));}return digest(copy);}
 private static void sourceShape(JsonNode n,boolean erased){
  var rootNames=new HashSet<String>();n.fieldNames().forEachRemaining(rootNames::add);require(rootNames.equals(Set.of("match","participants","completed_deals")));
  var m=n.path("match");require(m.path("revision").asInt()==1&&m.path("app_version").asText().equals("0.12.1")&&m.path("score_version").asText().equals(LifetimeStatistics.SCORE_VERSION));
  require(!m.has("statistics_rule")&&!m.has("match_category")&&!m.has("lifetime_rule")&&!m.has("room_type")&&!m.has("deal_timing_rule"));
  require(m.path("outcome").asText().equals("SCORED")&&m.path("winner_team_id").asText().equals("EW")&&m.path("reason").asText().equals("PLANNED_DEALS_COMPLETE")&&m.path("planned_deals").asInt()==1&&m.path("completed_deals").asInt()==1&&m.path("ns_total").asInt()==-1&&m.path("ew_total").asInt()==5);
  require(n.path("participants").size()==4&&n.path("completed_deals").size()==1);
  var seats=new HashSet<Integer>();for(var p:n.path("participants")){int seat=p.path("seat").asInt(-1);require(seat>=0&&seat<4&&seats.add(seat)&&p.path("team_id").asText().equals(seat%2==0?"NS":"EW")&&!p.has("settlement_control")&&p.path("guest_id").isNull());
   require(p.path("player_type").asText().equals(seat==0?"HUMAN":"BOT")&&p.path("control_type").asText().equals(seat==0?"HUMAN":"BOT"));
   if(seat==0){if(erased){require(p.path("account_id").isNull());uuid(p.path("erased_marker").asText());}else{uuid(p.path("account_id").asText());require(!p.has("erased_marker"));}}else require(p.path("account_id").isNull()&&!p.has("erased_marker"));
  }
  var d=n.path("completed_deals").get(0);require(!d.has("elapsed_ms")&&d.path("deal_index").asInt()==1&&d.path("ns_total").asInt()==-1&&d.path("ew_total").asInt()==5);
 }
 public static FinalMatchEnvelope migrate(FinalMatchEnvelope original,String approval){
  uuid(approval);require(original.revision()==1&&!applies(original.payload()));sourceShape(original.payload(),false);
  var n=(ObjectNode)original.payload();var m=(ObjectNode)n.path("match");m.put("revision",2);m.put("statistics_rule",RULE);m.put("match_category","CASUAL");m.put("lifetime_rule",LifetimeStatistics.RULE);
  var a=n.putObject("historical_admission");a.put("rule",RULE);a.put("source_commit",original.commitId());a.put("source_hash",original.payloadHash());a.put("source_facts_hash",facts(original.payload()));a.put("approval_reference",approval);a.putNull("redacted_source_hash");
  return FinalMatchEnvelope.create(original.matchId(),2,JSON.convertValue(n,Map.class));
 }
 public static void validate(JsonNode root){
  var m=root.path("match");require(applies(root)==RULE.equals(m.path("statistics_rule").asText()));if(!applies(root))return;
  var a=root.path("historical_admission");var names=new HashSet<String>();a.fieldNames().forEachRemaining(names::add);require(a.isObject()&&names.equals(FIELDS));uuid(a.path("approval_reference").asText());
  require(a.path("rule").asText().equals(RULE)&&a.path("source_commit").asText().equals(m.path("match_id").asText()+":1")&&m.path("revision").asInt()==2&&m.path("match_category").asText().equals("CASUAL")&&m.path("lifetime_rule").asText().equals(LifetimeStatistics.RULE));
  require(a.path("source_hash").asText().matches("[0-9a-f]{64}")&&a.path("source_facts_hash").asText().matches("[0-9a-f]{64}"));
  var source=reconstructed(root);boolean erased=!a.path("redacted_source_hash").isNull();sourceShape(source,erased);require(facts(source).equals(a.path("source_facts_hash").asText()));
  require(digest(source).equals(a.path(erased?"redacted_source_hash":"source_hash").asText()));
 }
 public static void afterAccountErasure(ObjectNode root){if(!applies(root))return;var source=reconstructed(root);sourceShape(source,true);var a=(ObjectNode)root.path("historical_admission");require(facts(source).equals(a.path("source_facts_hash").asText()));a.put("redacted_source_hash",digest(source));validate(root);}
}
