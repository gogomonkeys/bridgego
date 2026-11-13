package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import com.bridge.persistence.SheetsGateway;
import com.bridge.persistence.SheetsStatsStore;
import com.bridge.persistence.QuarterlyOverallSummary;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Real production statistics decoder, immutable synthetic snapshot, no ordinary CLAIM. */
class FixedEOverallV2StatisticsReadTest {
 @Test void productionReadExportsEmptyCurrentExactLifetimeAndUnavailableOlderOverall() throws Exception {
  var memory=new JournaledNativeWriterTest.Memory(FixedEOverallV2Runner.baseline());
  var runner=FixedEOverallV2RunnerTest.runner(memory,new FixedERestartClockSafetyTest.Time(),q->{});
  runner.arm();runner.run();assertEquals(5,memory.writes);
  var tables=memory.b.tables();var appendAttempts=new AtomicInteger();var reads=new ArrayList<String>();
  SheetsGateway snapshot=new SheetsGateway(){
   public List<List<String>> read(String tab){reads.add(tab);var rows=tables.get(tab);if(rows==null)throw new IllegalArgumentException();return rows.stream().map(List::copyOf).toList();}
   public void appendAtomic(Map<String,List<List<Object>>> values){appendAttempts.incrementAndGet();throw new AssertionError("Read-only decoder cannot append");}
  };
  try(var in=getClass().getResourceAsStream("/synthetic-e/overall-v2-independent-expected.json");var store=new SheetsStatsStore(snapshot,1,FixedEOverallV2Runner.businessClock())){
   var expected=JSON.readTree(in).path("accountsAfter");
   for(int i=1;i<expected.size();i++){
    String account=expected.get(i).get(0).asText();var carryExpected=JSON.readTree(expected.get(i).get(4).asText());
    var data=store.exportStatistics(account);
    assertTrue(data.current().isEmpty());assertNotNull(data.lifetime());
    assertEquals(carryExpected.path("through").asText(),data.lifetime().through().toString());
    assertEquals(carryExpected.path("trackedFromQuarter").asText(),data.lifetime().trackedFromQuarter());
    assertCounts(carryExpected.path("formal"),JSON.valueToTree(data.lifetime().formal()));
    assertCounts(carryExpected.path("casual"),JSON.valueToTree(data.lifetime().casual()));
    var history=data.historicalOverall();assertTrue(history.observed());
    assertEquals(java.time.Instant.parse("2027-03-31T16:00:00Z"),history.beforeExclusive());
    assertEquals(Set.of("2026-Q3","2026-Q4","2027-Q1"),history.quarters().keySet());
    assertTrue(history.countsFor("2026-Q3").isEmpty());assertTrue(history.countsFor("2026-Q4").isEmpty());
    var q1=history.countsFor("2027-Q1").orElseThrow();
    assertEquals(new QuarterlyOverallSummary.Counts(1,1,0,1,0,0),q1.formal());
    assertEquals(new QuarterlyOverallSummary.Counts(1,1,0,1,0,0),q1.casual());
   }
  }
  assertTrue(reads.containsAll(List.of("envelopes","commits","accounts","quarterly_aggregates","retention_ledger")));
  assertEquals(0,appendAttempts.get());assertEquals(5,memory.writes);
  assertEquals("CLOSED",NarrowQuarterPoc.root(new ArrayList<>(memory.b.metadata.values())).state());
 }
 private static void assertCounts(com.fasterxml.jackson.databind.JsonNode expected,com.fasterxml.jackson.databind.JsonNode actual){
  var keys=Set.of("matches","completedDeals","wins","losses","draws","totalScore","scoredMatches","forfeitMatches","interruptedMatches");
  var expectedKeys=new HashSet<String>();expected.fieldNames().forEachRemaining(expectedKeys::add);
  var actualKeys=new HashSet<String>();actual.fieldNames().forEachRemaining(actualKeys::add);
  assertEquals(keys,expectedKeys);assertEquals(keys,actualKeys);
  for(String key:keys){assertTrue(expected.get(key).isIntegralNumber());assertTrue(actual.get(key).isIntegralNumber());assertTrue(expected.get(key).canConvertToLong());assertTrue(actual.get(key).canConvertToLong());assertEquals(expected.get(key).longValue(),actual.get(key).longValue(),key);}
 }
}
