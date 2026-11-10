package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LifetimeRetentionTest {
  static ManualQuarterMaintenanceTest.Storage fixture() {
    var s = new ManualQuarterMaintenanceTest.Storage();
    MatchCategoryEnvelopeTest.replace(s.f.tables, 1, LifetimeStatisticsTest.marked(s.f.expired,"FORMAL","FORFEIT"));
    return s;
  }
  static LifetimeStatistics.Carry carry(ManualQuarterMaintenanceTest.Storage s) {
    return LifetimeStatistics.readAccount(s.f.tables.get("accounts"), LifetimeStatisticsTest.account(s.f));
  }
  @Test void carryAndExpiredRemovalAreOneAtomicWriteAndCompletionOnlyChangesLedger() {
    var s = fixture(); var initial = s.f.tables.get("accounts"); final List<?>[] pendingAccounts = {null};
    s.afterWrite = count -> {
      assertEquals(1, carry(s).formal().matches()); assertEquals(1, carry(s).formal().losses());
      assertFalse(ManualQuarterMaintenance.decoded(s.f.tables).containsKey(s.f.expired.commitId()));
      if (count == 1) { pendingAccounts[0] = List.copyOf(s.f.tables.get("accounts")); assertFalse(new RetentionLedger(s.f.tables.get("retention_ledger")).complete()); }
      else assertEquals(pendingAccounts[0], s.f.tables.get("accounts"));
    };
    var service = s.service(); var plan = ManualQuarterMaintenanceTest.preview(s, service);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, ManualQuarterMaintenanceTest.execute(service, plan).outcome());
    assertEquals(2,s.writes); assertEquals(1,carry(s).formal().matches());
    for(int i=1;i<initial.size();i++) assertEquals(initial.get(i), s.f.tables.get("accounts").get(i).subList(0,2));
    var again = s.service(); var fresh = ManualQuarterMaintenanceTest.preview(s,again);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,ManualQuarterMaintenanceTest.execute(again,fresh).outcome());
    assertEquals(2,s.writes); assertEquals(1,carry(s).formal().matches());
  }

  @Test void failedUncertainAndPendingExecutionsNeverReplayOrDoubleFold() {
    for (int mode : List.of(1,2,3,4)) {
      var s=fixture();
      if(mode==1)s.failBeforeWrite=1; if(mode==2)s.timeoutAfterWrite=1;
      if(mode==3)s.readUnavailable=true; if(mode==4)s.failBeforeWrite=2;
      var service=s.service();var plan=ManualQuarterMaintenanceTest.preview(s,service);
      var result=ManualQuarterMaintenanceTest.execute(service,plan);
      assertEquals(mode==1?ManualQuarterMaintenance.Outcome.NOT_APPLIED:mode==2?ManualQuarterMaintenance.Outcome.COMPLETE:
          mode==3?ManualQuarterMaintenance.Outcome.UNCERTAIN:ManualQuarterMaintenance.Outcome.PENDING,result.outcome());
      int writes=s.writes; ManualQuarterMaintenanceTest.execute(service,plan);assertEquals(writes,s.writes);
      assertEquals(mode==1?0:1,carry(s).formal().matches());
      if(mode==4) {
        s.failBeforeWrite=0;var retry=s.service();var p=ManualQuarterMaintenanceTest.preview(s,retry);
        assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,ManualQuarterMaintenanceTest.execute(retry,p).outcome());
        assertEquals(1,carry(s).formal().matches());
      }
    }
  }

  @Test void accountErasureDeletesCarryWithProfileAndNeverRestoresTombstonedIdentity() throws Exception {
    var f=new OperatorAccountErasureTest.Fixture();String json=LifetimeStatistics.encode(LifetimeStatisticsTest.carry());
    f.db.tabs.put("accounts",new ArrayList<>(List.of(AccountProfileRows.LIFETIME_HEADER,
        List.of(f.account,"2","刪除者","erase@example.test",json),List.of(f.other,"2","保留者","keep@example.test",json))));
    var service=f.service(true);var p=f.preview(service);
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED,f.execute(service,p,"owner"));
    assertEquals(List.of(AccountProfileRows.LIFETIME_HEADER,List.of(f.other,"2","保留者","keep@example.test",json)),f.db.tabs.get("accounts"));
    assertEquals(LifetimeStatistics.empty(),LifetimeStatistics.readAccount(f.db.tabs.get("accounts"),f.account));
    assertThrows(SheetsGateway.Conflict.class,()->new SheetsIdentityStore(f.db).findOrCreateAccount(OperatorAccountErasureTest.ISSUER,"owner"));
  }

  @Test void malformedCarryAndChangedProfileStopMaintenanceBeforeWrites() {
    var s=fixture();var rows=s.f.tables.get("accounts");rows.set(0,AccountProfileRows.LIFETIME_HEADER);
    rows.set(1,List.of(rows.get(1).get(0),"1","","","{bad}"));
    assertThrows(IllegalStateException.class,()->ManualQuarterMaintenanceTest.preview(s,s.service()));assertEquals(0,s.writes);
  }
  @Test void release013RecordsFoldWithoutChangingOlderVersionCompatibility() {
    var s=fixture();var e=ManualQuarterMaintenance.decoded(s.f.tables).get(s.f.expired.commitId());
    var root=(com.fasterxml.jackson.databind.node.ObjectNode)e.payload();
    ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("match")).put("app_version","0.13.0");
    MatchCategoryEnvelopeTest.replace(s.f.tables,1,FixedRoomEnvelopeTest.envelope(root));
    var service=s.service();var plan=ManualQuarterMaintenanceTest.preview(s,service);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,ManualQuarterMaintenanceTest.execute(service,plan).outcome());
    assertEquals(1,carry(s).formal().matches());
  }

}
