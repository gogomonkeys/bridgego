package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LiveQuarterLifetimeTest {
 @Test void fullWorkbookAcceptsFiveColumnAccountsAndValidatesCarry(){
  var s=LifetimeRetentionTest.fixture();var service=s.service();var plan=ManualQuarterMaintenanceTest.preview(s,service);
  assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,ManualQuarterMaintenanceTest.execute(service,plan).outcome());
  var parsed=LiveQuarterWorkbook.parse(LiveQuarterWorkbookTest.fixtureJson(s.f),s.f.binding,s.f.clock);
  assertEquals(s.f.tables.get("accounts"),parsed.snapshot().tables().get("accounts"));
  assertEquals(1,LifetimeStatistics.readAccount(parsed.snapshot().tables().get("accounts"),LifetimeStatisticsTest.account(s.f)).formal().matches());
  var bad=new java.util.ArrayList<>(s.f.tables.get("accounts").get(1));bad.set(4,"{bad}");s.f.tables.get("accounts").set(1,bad);
  assertThrows(IllegalStateException.class,()->LiveQuarterWorkbook.parse(LiveQuarterWorkbookTest.fixtureJson(s.f),s.f.binding,s.f.clock));
 }
 @Test void directAdapterRejectsForgedCarryOrProfileEvenWithOtherwiseValidPendingBatch(){
  for(boolean changeProfile:java.util.List.of(false,true)){
   var fake=new LiveQuarterStorageTest.FakeTransport();
   MatchCategoryEnvelopeTest.replace(fake.fixture.tables,1,LifetimeStatisticsTest.marked(fake.fixture.expired,"FORMAL","FORFEIT"));
   var original=new java.util.TreeMap<>(fake.fixture.tables);
   final java.util.Map<String,java.util.List<java.util.List<String>>>[] pending=new java.util.Map[]{null};
   fake.afterWrite=count->{if(count==1)pending[0]=new java.util.TreeMap<>(fake.fixture.tables);};
   assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,LiveQuarterStorageTest.prepared(fake).execute().outcome());
   fake.fixture.tables.clear();fake.fixture.tables.putAll(original);fake.posts=0;fake.afterWrite=count->{};
   var run=LiveQuarterStorageTest.prepared(fake);
   var accounts=new java.util.ArrayList<>(pending[0].get("accounts"));
   var row=new java.util.ArrayList<>(accounts.get(1));
   if(changeProfile){row.set(1,"2");row.set(2,"Fake");}else row.set(4,"");
   accounts.set(1,row);pending[0].put("accounts",accounts);
   assertDoesNotThrow(()->ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(fake.fixture.binding.spreadsheetId(),true,pending[0]),fake.fixture.binding,fake.fixture.clock));
   pending[0].remove("dataset");
   assertThrows(IllegalStateException.class,()->run.storage().replaceAtomic(fake.fixture.binding,run.plan().beforeDigest(),pending[0]));
   assertEquals(0,fake.posts);
  }
 }
}
