package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LifetimeStatsExportTest {
  static final Instant NOW=Instant.parse("2026-11-10T12:00:00Z");
  static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
  static LifetimeStatistics.Carry carry() {
    return new LifetimeStatistics.Carry(QuarterlyRetention.currentQuarterStart(NOW),"2026-Q3",
        new LifetimeStatistics.Counts(1,1,1,0,0,7,1,0,0),LifetimeStatistics.Counts.zero());
  }
  static FinalMatchEnvelope current() {
    var old=new LocalManualQuarterRehearsal.Fixture().expired;
    var root=FixedRoomEnvelopeTest.record(old,"FORMAL","FORFEIT");
    ((ObjectNode)root.path("match")).put("started_at",NOW.minusSeconds(5).toString())
        .put("finished_at",NOW.toString()).put("lifetime_rule",LifetimeStatistics.RULE);
    return FixedRoomEnvelopeTest.envelope(root);
  }
  static String account(FinalMatchEnvelope e){return e.payload().path("participants").get(0).path("account_id").asText();}
  static void setup(SheetsStatsStoreTest.Fake fake,FinalMatchEnvelope current) {
    SheetsStatsStoreTest.ready(fake,NOW);
    fake.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LIFETIME_HEADER,
        List.of(account(current),"1","","",LifetimeStatistics.encode(carry())))));
    fake.appendAtomic(Map.of("envelopes",List.of(List.of(current.commitId(),current.payloadHash(),0,1,current.canonicalJson())),
        "commits",List.of(List.of(current.commitId(),current.payloadHash(),1,current.matchId(),1))));
  }

  @Test void combinesValidatedCurrentRecordsAndOnlyRequestedAccountCarryWithoutWriting() {
    var fake=new SheetsStatsStoreTest.Fake();var current=current();setup(fake,current);int writes=fake.writes;
    try(var store=new SheetsStatsStore(fake,1,CLOCK)) {
      var data=store.exportStatistics(account(current));
      assertEquals(List.of(current),data.current());assertEquals(carry(),data.lifetime());assertEquals(writes,fake.writes);
      assertThrows(SheetsGateway.Conflict.class,()->store.exportStatistics(UUID.randomUUID().toString()));
    }
  }

  @Test void legacyAccountWithoutCarryIsEmptyButCorruptOrFutureCarryIsNeverSilentlyZero() {
    var fake=new SheetsStatsStoreTest.Fake();var current=current();setup(fake,current);
    fake.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LEGACY_HEADER,List.of(account(current),"1"))));
    try(var store=new SheetsStatsStore(fake,1,CLOCK)) {
      assertEquals(LifetimeStatistics.Carry.empty(),store.exportStatistics(account(current)).lifetime());
      fake.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LIFETIME_HEADER,List.of(account(current),"1","","","{}"))));
      assertThrows(RuntimeException.class,()->store.exportStatistics(account(current)));
      var future=new LifetimeStatistics.Carry(Instant.parse("2026-12-31T16:00:00Z"),"2026-Q3",carry().formal(),carry().casual());
      fake.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LIFETIME_HEADER,List.of(account(current),"1","","",LifetimeStatistics.encode(future)))));
      assertThrows(SheetsGateway.Conflict.class,()->store.exportStatistics(account(current)));
    }
  }

  @Test void erasureOrCarryMutationDuringReadFailsClosedRatherThanExposingMixedGenerations() {
    for(boolean erase:List.of(false,true)) {
      var fake=new SheetsStatsStoreTest.Fake(){int accountReads;
        @Override public synchronized List<List<String>> read(String tab) {
          if(tab.equals("accounts") && ++accountReads==2) {
            if(erase) tabs.get("accounts").remove(1);
            else {var row=new ArrayList<>(tabs.get("accounts").get(1));row.set(4,"");tabs.get("accounts").set(1,row);}
          }
          return super.read(tab);
        }
      };
      var current=current();setup(fake,current);
      try(var store=new SheetsStatsStore(fake,1,CLOCK)) {
        assertThrows(SheetsGateway.Conflict.class,()->store.exportStatistics(account(current)));
      }
    }
  }

  @Test void erasureTombstoneAndPendingQuarterBlockCareerRead() {
    var fake=new SheetsStatsStoreTest.Fake();var current=current();setup(fake,current);
    fake.tabs.get("erasure_ledger").add(List.of("ACCOUNT",ErasureLedger.digest("ACCOUNT",account(current)),"1"));
    try(var store=new SheetsStatsStore(fake,1,CLOCK)) {
      assertThrows(SheetsGateway.Conflict.class,()->store.exportStatistics(account(current)));
      fake.tabs.get("erasure_ledger").remove(2);
      var row=new ArrayList<>(fake.tabs.get("retention_ledger").get(1));row.set(2,"PENDING");row.set(3,"");fake.tabs.get("retention_ledger").set(1,row);
      assertThrows(IllegalStateException.class,()->store.exportStatistics(account(current)));
    }
  }
}
