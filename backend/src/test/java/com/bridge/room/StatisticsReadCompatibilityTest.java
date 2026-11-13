package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Fully synthetic compatibility case; no live records, identities, network or write path. */
class StatisticsReadCompatibilityTest {
  @Test void oldFourColumnAccountAndUnmarkedHistoryCoexistWithOneModernCasualLoss() {
    var old=MatchCategoryProjectionTest.record(3,null,true,-2,6);
    var modern=MatchCategoryProjectionTest.changed(MatchCategoryProjectionTest.record(3,
        FinalMatchEnvelope.SETTLEMENT_STATISTICS_RULE,true,-4,1),root->{
      var match=(ObjectNode)root.path("match");
      match.put("statistics_rule",FinalMatchEnvelope.STATISTICS_RULE).put("room_type","CASUAL")
          .put("deal_timing_rule",FinalMatchEnvelope.DEAL_TIMING_RULE)
          .put("lifetime_rule",LifetimeStatistics.RULE).put("reason","PLANNED_DEALS_COMPLETE");
      ((ObjectNode)root.path("completed_deals").get(0)).put("ns_total",-4).put("ew_total",1).put("elapsed_ms",123456);
    });
    var records=List.of(old,modern);String account=MatchCategoryProjectionTest.ACCOUNT;
    var now=MatchCategoryProjectionTest.NOW;var boundary=QuarterlyRetention.currentQuarterStart(now);
    Map<String,List<List<String>>> tabs=new HashMap<>();
    tabs.put("accounts",List.of(AccountProfileRows.HEADER,List.of(account,"2","Test","")));
    tabs.put("retention_ledger",List.of(RetentionLedger.HEADER,List.of(QuarterlyRetention.quarter(now),boundary.toString(),"COMPLETE",now.toString(),"1")));
    tabs.put("erasure_ledger",List.of(List.of("kind","identifier_hash","schema_version"),List.of("META","erasure-ledger-v1","1")));
    tabs.put("envelopes",records.stream().map(e->List.of(e.commitId(),e.payloadHash(),"0","1",e.canonicalJson())).toList());
    tabs.put("commits",records.stream().map(e->List.of(e.commitId(),e.payloadHash(),"1",e.matchId(),"1")).toList());
    var gateway=new SheetsGateway(){
      public List<List<String>> read(String tab){return tabs.get(tab);}
      public void appendAtomic(Map<String,List<List<Object>>> rows){fail("Read-only statistics must never write");}
    };
    try(var store=new SheetsStatsStore(gateway,1,Clock.fixed(now,ZoneOffset.UTC))){
      var saved=store.exportStatistics(account);
      var report=StatisticsService.project(saved.current(),saved.lifetime(),account,now);
      assertEquals("AVAILABLE",report.status());assertEquals(0,report.personal().matches());
      assertEquals(1,report.casualPersonal().matches());assertEquals(1,report.casualPersonal().losses());
      assertEquals(-4,report.casualPersonal().totalScore());assertEquals(0,report.lifetimePersonal().matches());
      assertEquals(1,report.lifetimeCasualPersonal().matches());assertEquals(-4,report.lifetimeCasualPersonal().totalScore());
    }
  }
}
