package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class HistoricalCasualMigrationPlanTest {
 @Test void exactSingleReplacementPreservesOtherTablesAndRejectsWrongApproval()throws Exception{
  var b=RecoveredFixture.book();var target=NativeCommitJournalTest.BINDING;var clock=Clock.systemUTC();String account=b.sheet("accounts").rows.get(1).get(0);var p=com.bridge.persistence.HistoricalAdmissionTest.original(account);var e=new FinalMatchEnvelope(p.commitId(),p.matchId(),p.revision(),p.canonicalJson(),p.payloadHash());
  b.sheet("commits").rows=new ArrayList<>(List.of(ManualQuarterMaintenance.HEADERS.get("commits"),List.of(e.commitId(),e.payloadHash(),"1",e.matchId(),"1")));b.sheet("envelopes").rows=new ArrayList<>(List.of(ManualQuarterMaintenance.HEADERS.get("envelopes"),List.of(e.commitId(),e.payloadHash(),"0","1",e.canonicalJson())));
  var before=NativeCommitJournal.bootstrap(target,b,NativeCommitJournal.digest(target,b),clock).expected();String ref=UUID.randomUUID().toString();var admitted=HistoricalCasualAdmission.migrate(e,ref);var count=LifetimeStatistics.fromEnvelopes(account,List.of(admitted)).casual();
  var approval=new HistoricalCasualMigrationPlan.Approval(target,NativeCommitJournal.digest(target,before),e.commitId(),e.payloadHash(),ref,count);var plan=HistoricalCasualMigrationPlan.prepare(target,before,approval,clock);var after=simulate(before,plan.wire());assertTrue(plan.matchesAfter(after));var records=ManualQuarterMaintenance.decoded(after.tables());assertEquals(1,records.size());assertEquals(admitted,records.values().iterator().next());
  for(var tab:before.tables().keySet())if(!Set.of("envelopes","commits").contains(tab))assertEquals(before.tables().get(tab),after.tables().get(tab));assertThrows(RuntimeException.class,()->HistoricalCasualMigrationPlan.prepare(target,after,approval,clock));
  var wrong=new HistoricalCasualMigrationPlan.Approval(target,approval.baselineDigest(),e.commitId(),"0".repeat(64),ref,count);assertThrows(RuntimeException.class,()->HistoricalCasualMigrationPlan.prepare(target,before,wrong,clock));
  var folded=LifetimeStatistics.fold(after.tables().get("accounts"),records.values(),Instant.parse("2026-12-31T16:00:00Z"));assertEquals(LifetimeStatistics.readAccount(before.tables().get("accounts"),account).casual().plus(count),LifetimeStatistics.readAccount(folded,account).casual());
 }
 @Test void productionAndNativeCodecsAreExactMirrors()throws Exception{
  var root=java.nio.file.Path.of(System.getProperty("basedir","."),"src/main/java/com/bridge");for(String name:List.of("HistoricalCasualAdmission.java","FinalMatchEnvelope.java"))assertEquals(java.nio.file.Files.readString(root.resolve("persistence").resolve(name)),java.nio.file.Files.readString(root.resolve("quartercore").resolve(name)).replace("com.bridge.quartercore","com.bridge.persistence"));
 }
}
