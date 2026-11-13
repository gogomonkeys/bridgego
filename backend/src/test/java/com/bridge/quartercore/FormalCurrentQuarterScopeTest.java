package com.bridge.quartercore;
import java.time.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FormalCurrentQuarterScopeTest {
 static final GoogleSheetsBinding B=NativeRestartCompiledRelease.pendingReview().binding();
 static Clock at(String s){return Clock.fixed(Instant.parse(s),ZoneOffset.UTC);}
 static NativeRestartContract contract(){var r=new QuarterRetentionPolicy.Configuration(B,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"local-test-only");return NativeRestartContract.fixedReviewedBuild(new CleanClosedRestartAssembly.Policy(B,NativeRestartContract.PROTOCOL_IDENTITY,"local-test-only",r),"https://bridgego.onrender.com",NativeRestartCompiledRelease.pendingReview().audience());}
 @Test void onlyQ4StartupIsWithinScopeButNeverConfersDeletionPermission(){
  assertDoesNotThrow(()->FormalCurrentQuarterScope.requireStartup(B,at("2026-09-30T16:00:00Z")));
  assertDoesNotThrow(()->FormalCurrentQuarterScope.requireStartup(B,at("2026-12-31T15:59:59Z")));
  for(String time:new String[]{"2026-09-30T15:59:59Z","2026-12-31T16:00:00Z","2027-04-01T00:00:00Z"})assertThrows(RuntimeException.class,()->FormalCurrentQuarterScope.requireStartup(B,at(time)));
  assertThrows(RuntimeException.class,()->FormalCurrentQuarterScope.requireQuarterDeletionApproval(B));
  var policy=QuarterRetentionPolicy.explicitlyApproved(contract().policy().retention(),at("2027-01-01T00:00:00Z"));
  assertThrows(RuntimeException.class,()->policy.operation(new Object(),B,null,"q-unused","unused"));
  assertEquals(NativeRestartCompiledRelease.pendingReview().binding(),NativeRestartCompiledRelease.require().binding());
 }
 @Test void futureRestartBlocksBeforeReadingOrOpeningAnyWriter(){
  AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();
  var io=new NativeRestartRuntimeAssembly.IO(){public NativeSheetsModel.Book readNative(){reads.incrementAndGet();throw new AssertionError("No read permitted outside initial quarter");}public PlannedMaintenanceStartupFactory.Channels channels(){return new PlannedMaintenanceStartupFactory.Channels(){public PlannedMaintenanceStartupFactory.Channel ordinary(JournalSessionAuthority a){writes.incrementAndGet();throw new AssertionError();}public PlannedMaintenanceStartupFactory.Channel quarter(JournalQuarterAuthority a){writes.incrementAndGet();throw new AssertionError();}};}};
  try(var runtime=new NativeRestartRuntimeAssembly(contract(),io,at("2027-01-01T00:00:00Z"))){assertThrows(RuntimeException.class,runtime::resume);assertEquals(NativeRestartRuntimeAssembly.Status.BLOCKED,runtime.status());assertFalse(runtime.staged().enabled());}
  assertEquals(0,reads.get());assertEquals(0,writes.get());
 }
 @Test void restrictedAndUnrelatedManagedFixturesRemainUnchanged(){
  for(var b:new GoogleSheetsBinding[]{FixedERestartClock.binding(),new GoogleSheetsBinding("unrelated-sheet","00000000-0000-0000-0000-000000000001","bridgego-managed",B.serviceAccountIssuer())}){
   assertDoesNotThrow(()->FormalCurrentQuarterScope.requireStartup(b,at("2027-04-01T00:00:00Z")));
   assertDoesNotThrow(()->FormalCurrentQuarterScope.requireQuarterDeletionApproval(b));
  }
 }
 static JournaledNativeWriterTest.Memory formalFixture() throws Exception {
  var book=JournaledNativeWriterTest.initial().b.copy();book.metadata.clear();
  book.sheet("dataset").rows.set(1,new java.util.ArrayList<>(java.util.List.of(B.purpose(),B.datasetId())));
  return new JournaledNativeWriterTest.Memory(LegacyToNativeCutoverPlan.prepare(B.manualBinding(),book,at("2026-12-30T00:00:00Z")).expected());
 }
 @Test void actualAttachedTimerStopsInMaintenanceWithoutQuarterAuthorityOrBusinessChanges() throws Exception {
  var time=new CrossQuarterNativeSettlementTest.Time();time.now=Instant.parse("2026-12-30T00:00:00Z");
  var m=formalFixture();var quarterOpened=new AtomicInteger();
  var channels=FixedERestartClockSafetyTest.channels(B,m,new AtomicReference<>(),q->{quarterOpened.incrementAndGet();throw new AssertionError("Deletion authority must not be issued");});
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);var stage=new com.bridge.persistence.StagedNativeBackend(gate);
  var c=contract();var factory=new PlannedMaintenanceStartupFactory(new PlannedMaintenanceStartupFactory.Contract(B,JournalSessionAuthority.fullToken(m.b),c.policy().sourceRevision(),c.policy().approvalReference()),ignored->{},m::snapshot,channels,QuarterRetentionPolicy.explicitlyApproved(c.policy().retention(),time),gate,time);
  try {
   factory.activate(stage);var timer=new com.bridge.persistence.QuarterMaintenanceCoordinator(gate,stage.lifecycle(),time,()->true,()->{});timer.tick();
   assertTrue(stage.enabled());assertTrue(gate.isOpen());var tables=m.b.copy().tables();int before=m.writes;
   time.now=Instant.parse("2026-12-31T16:00:00Z");timer.tick();
   assertFalse(stage.enabled());assertFalse(gate.isOpen());assertNotEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Status.OPEN,timer.status());
   assertEquals(0,quarterOpened.get());assertEquals(tables,m.b.tables());
   // Native CLOSE is allowed to fence the existing writer, but no quarter phase may follow.
   assertTrue(m.writes-before<=1);int stopped=m.writes;timer.tick();assertEquals(stopped,m.writes);
  } finally {stage.close();}
 }
 @Test void firstStartupReadCrossingQuarterCannotClaim() throws Exception {
  var time=new CrossQuarterNativeSettlementTest.Time();time.now=Instant.parse("2026-12-31T15:59:59Z");var m=formalFixture();
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var c=contract();
  var factory=new PlannedMaintenanceStartupFactory(new PlannedMaintenanceStartupFactory.Contract(B,JournalSessionAuthority.fullToken(m.b),c.policy().sourceRevision(),c.policy().approvalReference()),ignored->{},()->{var snapshot=m.snapshot();time.now=Instant.parse("2026-12-31T16:00:00Z");return snapshot;},FixedERestartClockSafetyTest.channels(B,m,new AtomicReference<>(),q->{throw new AssertionError();}),QuarterRetentionPolicy.explicitlyApproved(c.policy().retention(),time),gate,time);
  try{assertThrows(RuntimeException.class,()->factory.activate(stage));assertEquals(0,m.writes);assertFalse(stage.enabled());}finally{stage.close();}
 }

}
