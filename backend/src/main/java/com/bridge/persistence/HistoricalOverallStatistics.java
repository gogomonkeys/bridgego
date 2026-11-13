package com.bridge.persistence;
import java.time.*;import java.util.*;
/** Internal storage result only, not a public statistics DTO. Missing rows and unavailable
 * v1 counts are not zero, and these values are never added to current-quarter totals. */
public record HistoricalOverallStatistics(Instant beforeExclusive,Map<String,Optional<QuarterlyOverallSummary.Summary>> quarters){
 public HistoricalOverallStatistics{quarters=Map.copyOf(quarters);if(beforeExclusive==null&&!quarters.isEmpty())throw new IllegalArgumentException("Unobserved history cannot contain rows");}
 public static HistoricalOverallStatistics notRead(){return new HistoricalOverallStatistics(null,Map.of());}
 public boolean observed(){return beforeExclusive!=null;}
 public Optional<QuarterlyOverallSummary.Summary> countsFor(String quarter){return quarters.getOrDefault(quarter,Optional.empty());}
 public static HistoricalOverallStatistics read(List<List<String>> rows,Instant boundary){
  if(boundary==null||!QuarterlyRetention.currentQuarterStart(boundary).equals(boundary)||rows==null||rows.isEmpty()||!rows.get(0).equals(QuarterlyRetention.AGGREGATE_HEADER))throw new SheetsGateway.Conflict();
  var decoded=new LinkedHashMap<String,Optional<QuarterlyOverallSummary.Summary>>();
  for(var row:rows.subList(1,rows.size())){
   var value=QuarterlyOverallSummary.decodeRow(row);String q=value.quarter();
   Instant end=LocalDate.of(Integer.parseInt(q.substring(0,4)),(Integer.parseInt(q.substring(6))-1)*3+1,1).atStartOfDay(QuarterlyRetention.TAIPEI).plusMonths(3).toInstant();
   if(end.isAfter(boundary)||decoded.putIfAbsent(q,value.counts())!=null)throw new SheetsGateway.Conflict();
  }
  return new HistoricalOverallStatistics(boundary,decoded);
 }
}
