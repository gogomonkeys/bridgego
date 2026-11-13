package com.bridge.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;

/** Internal aggregate storage only. No identity, per-match mapping or publication permission. */
public final class QuarterlyOverallSummary {
 private QuarterlyOverallSummary(){}
 private static final String SCORE_VERSION="bridgego-custom-v0.11-1";
 private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private static final List<String> FIELDS=List.of("matches","completedDeals","draws","scoredMatches","forfeitMatches","interruptedMatches");
 public record Counts(long matches,long completedDeals,long draws,long scoredMatches,long forfeitMatches,long interruptedMatches){
  public Counts {if(matches<0||completedDeals<0||draws<0||scoredMatches<0||forfeitMatches<0||interruptedMatches<0||draws>scoredMatches||Math.addExact(Math.addExact(scoredMatches,forfeitMatches),interruptedMatches)!=matches||completedDeals<scoredMatches||completedDeals>Math.multiplyExact(matches,5))throw bad();}
  public static Counts zero(){return new Counts(0,0,0,0,0,0);}
  public Counts plus(Counts b){return new Counts(Math.addExact(matches,b.matches),Math.addExact(completedDeals,b.completedDeals),Math.addExact(draws,b.draws),Math.addExact(scoredMatches,b.scoredMatches),Math.addExact(forfeitMatches,b.forfeitMatches),Math.addExact(interruptedMatches,b.interruptedMatches));}
 }
 public record Summary(Counts formal,Counts casual){public Summary{Objects.requireNonNull(formal);Objects.requireNonNull(casual);}}
 /** Empty counts means unavailable historical information, never a zero total. */
 public record Decoded(String quarter,Optional<Summary> counts){public Decoded{QuarterlyOverallSummary.quarter(quarter);Objects.requireNonNull(counts);}}
 public static Summary summarize(String quarter,Collection<FinalMatchEnvelope> input,Instant now){
  quarter(quarter);Objects.requireNonNull(now);Objects.requireNonNull(input);
  var unique=new HashMap<String,FinalMatchEnvelope>();
  for(var e:input){Objects.requireNonNull(e);var old=unique.putIfAbsent(e.matchId(),e);if(old!=null&&!old.equals(e))throw bad();}
  Counts formal=Counts.zero(),casual=Counts.zero();
  for(var e:unique.values()){
   var m=e.payload().path("match");String category=e.statisticsCategory();
   if(category==null||!SCORE_VERSION.equals(m.path("score_version").asText()))continue;
   Instant finish=Instant.parse(m.path("finished_at").asText()),start=Instant.parse(m.path("started_at").asText());
   if(start.isAfter(finish)||finish.isAfter(now))throw bad();
   if(!QuarterlyRetention.quarter(finish).equals(quarter))continue;
   String outcome=m.path("outcome").asText();boolean fixed=FinalMatchEnvelope.STATISTICS_RULE.equals(m.path("statistics_rule").asText());
   if(!(fixed?Set.of("SCORED","FORFEIT","NO_CONTEST").contains(outcome):"SCORED".equals(outcome)))throw bad();
   long completed=number(m.path("completed_deals")),planned=number(m.path("planned_deals"));
   if(!Set.of(1L,3L,5L).contains(planned)||completed>planned||("SCORED".equals(outcome)&&completed!=planned))throw bad();
   var c=new Counts(1,completed,"SCORED".equals(outcome)&&m.path("winner_team_id").isNull()?1:0,"SCORED".equals(outcome)?1:0,"FORFEIT".equals(outcome)?1:0,"NO_CONTEST".equals(outcome)?1:0);
   if("FORMAL".equals(category))formal=formal.plus(c);else if("CASUAL".equals(category))casual=casual.plus(c);else throw bad();
  }
  return new Summary(formal,casual);
 }
 public static List<String> encodeRow(String quarter,Summary summary){quarter(quarter);return List.of(quarter,"INTERNAL_COUNTS",canonical(summary),"","2");}
 public static Decoded decodeRow(List<String> row){
  if(row==null||row.size()!=5||row.stream().anyMatch(Objects::isNull))throw bad();quarter(row.get(0));
  if("1".equals(row.get(4))){
   if("SUPPRESSED".equals(row.get(1))){if(!row.get(2).isEmpty()||!row.get(3).isEmpty())throw bad();}
   else if("CANDIDATE_COUNTS".equals(row.get(1))){for(int i=2;i<=3;i++){String s=row.get(i);if(!s.matches("[0-9]+")||Long.parseLong(s)%10!=0)throw bad();}}
   else throw bad();return new Decoded(row.get(0),Optional.empty());
  }
  if(!"2".equals(row.get(4))||!"INTERNAL_COUNTS".equals(row.get(1))||!row.get(3).isEmpty()||row.get(2).length()>2048)throw bad();
  try{var n=JSON.readTree(row.get(2));exact(n,Set.of("version","FORMAL","CASUAL"));if(number(n.get("version"))!=2)throw bad();var s=new Summary(readCounts(n.get("FORMAL")),readCounts(n.get("CASUAL")));if(!canonical(s).equals(row.get(2)))throw bad();return new Decoded(row.get(0),Optional.of(s));}catch(Exception e){throw bad();}
 }
 private static Counts readCounts(JsonNode n){exact(n,new HashSet<>(FIELDS));return new Counts(number(n.get(FIELDS.get(0))),number(n.get(FIELDS.get(1))),number(n.get(FIELDS.get(2))),number(n.get(FIELDS.get(3))),number(n.get(FIELDS.get(4))),number(n.get(FIELDS.get(5))));}
 private static void exact(JsonNode n,Set<String> keys){if(n==null||!n.isObject()||n.size()!=keys.size())throw bad();n.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw bad();});}
 private static long number(JsonNode n){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw bad();return n.longValue();}
 private static String canonical(Summary s){try{var n=JSON.createObjectNode();n.put("version",2);n.set("FORMAL",node(s.formal()));n.set("CASUAL",node(s.casual()));return JSON.writeValueAsString(n);}catch(Exception e){throw bad();}}
 private static JsonNode node(Counts c){var n=JSON.createObjectNode();long[] v={c.matches(),c.completedDeals(),c.draws(),c.scoredMatches(),c.forfeitMatches(),c.interruptedMatches()};for(int i=0;i<v.length;i++)n.put(FIELDS.get(i),v[i]);return n;}
 private static void quarter(String q){if(q==null||!q.matches("[0-9]{4}-Q[1-4]"))throw bad();}
 private static IllegalArgumentException bad(){return new IllegalArgumentException("Invalid internal quarterly overall summary");}
}
