package com.bridge.quartercore;
import java.nio.file.Path;import java.time.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CutoverBoundedHttpFixtureTest {
 @TempDir Path dir;
 @Test void exactManagedBootstrapAndHistoryThroughBoundedHttp()throws Exception{
  var clock=Clock.systemUTC();var f=new CutoverManagedFixture(clock);var b=CutoverManagedFixture.B;var remote=new CutoverBoundedHttpFixture(b,f.before,dir);
  var freeze=new CutoverAssessment.FreezeEvidence("synthetic-approved","source",clock.instant().plusSeconds(180));
  var plan=CutoverAssessment.plan(b.manualBinding(),f.before,CutoverAssessment.token(b.manualBinding(),f.before),freeze,(target,token,e)->assertEquals(b.manualBinding(),target),clock);
  var a=new InitialCutoverAuthority(b,plan,clock);var nativeBefore=remote.bootstrap(a);assertEquals(f.before.tables(),nativeBefore.tables());
  var approval=new HistoricalCasualMigrationPlan.Approval(b.manualBinding(),NativeCommitJournal.digest(b.manualBinding(),nativeBefore),f.historical.commitId(),f.historical.payloadHash(),f.approval,f.contribution);
  var history=HistoricalMigrationAuthority.approveFromBootstrap(b,nativeBefore,a.verifiedResult(),approval,true,clock,freeze,(target,token,e)->{assertEquals(b.manualBinding(),target);assertEquals(a.verifiedResult().afterToken(),token);});
  var migrated=remote.migrate(history);assertEquals(2,ManualQuarterMaintenance.decoded(migrated.tables()).values().iterator().next().revision());assertEquals(2,remote.writes);assertEquals(4,remote.gets);
  assertThrows(RuntimeException.class,()->remote.bootstrap(a));assertThrows(RuntimeException.class,()->remote.migrate(history));assertEquals(2,remote.writes);
 }
 @Test void appliedUnknownNeverReplays()throws Exception{
  var clock=Clock.systemUTC();var f=new CutoverManagedFixture(clock);var b=CutoverManagedFixture.B;var remote=new CutoverBoundedHttpFixture(b,f.before,dir);
  var plan=CutoverAssessment.plan(b.manualBinding(),f.before,CutoverAssessment.token(b.manualBinding(),f.before),new CutoverAssessment.FreezeEvidence("synthetic-approved","source",clock.instant().plusSeconds(180)),(target,token,e)->assertEquals(b.manualBinding(),target),clock);
  var a=new InitialCutoverAuthority(b,plan,clock);remote.unknownNextWrite=true;assertThrows(RuntimeException.class,()->remote.bootstrap(a));assertEquals(1,remote.writes);assertThrows(RuntimeException.class,a::verifiedResult);assertThrows(RuntimeException.class,()->remote.bootstrap(a));assertEquals(1,remote.writes);
 }
}
