package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class CleanClosedRestartAssemblyTest {
 @TempDir Path dir;
 static CleanClosedRestartAssembly.Policy policy(){var b=PlannedMaintenanceStartupFactoryTest.B;return new CleanClosedRestartAssembly.Policy(b,"test-native-source","test-explicit-restart",new QuarterRetentionPolicy.Configuration(b,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"test-explicit-quarter"));}
 @Test void newJvmCurrentClaimsOnceAndDueMaintainsBeforeClaim()throws Exception{
  Book closed=DurableCleanHandoffAssessmentTest.closed();var now=JournaledNativeWriterTest.CLOCK;var next=Clock.fixed(QuarterlyRetention.currentQuarterStart(now.instant()).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1),ZoneOffset.UTC);
  for(Clock clock:List.of(now,next)){Path source=dir.resolve(UUID.randomUUID()+".json"),log=dir.resolve(UUID.randomUUID()+".log");JournalRestartProbe.save(source,closed);var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),CleanClosedRestartProbe.class.getName(),source.toString(),clock.instant().toString(),clock==now?"1":"6").redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("restart test timeout");}assertEquals(0,process.exitValue(),Files.readString(log));assertNotEquals(ProcessHandle.current().pid(),process.pid());}
 }
 @Test void missingPolicyUnknownAndNonCloseSnapshotsCannotEnableOrRetry()throws Exception{
  var time=JournaledNativeWriterTest.CLOCK;
  for(int mode=0;mode<3;mode++){final int variant=mode;var m=new JournaledNativeWriterTest.Memory(mode==2?JournaledNativeWriterTest.initial().b:DurableCleanHandoffAssessmentTest.closed());var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);var stage=new com.bridge.persistence.StagedNativeBackend(gate);if(mode==1)m.delay=true;
   var r=new CleanClosedRestartAssembly(policy(),(p,c)->{if(variant==0)throw new Rejected();},m::snapshot,PlannedMaintenanceStartupFactoryTest.channels(m),gate,stage,time);assertThrows(RuntimeException.class,r::resume);int writes=m.writes;assertEquals(mode==1?1:0,writes);assertThrows(RuntimeException.class,r::resume);assertEquals(writes,m.writes);assertFalse(stage.enabled());stage.close();}
 }
 @Test void dueUnknownStopsBeforeOrdinaryClaim()throws Exception{
  var base=JournaledNativeWriterTest.CLOCK;var time=Clock.fixed(QuarterlyRetention.currentQuarterStart(base.instant()).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1),ZoneOffset.UTC);var m=new JournaledNativeWriterTest.Memory(DurableCleanHandoffAssessmentTest.closed());m.delay=true;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var r=new CleanClosedRestartAssembly(policy(),(p,c)->{},m::snapshot,PlannedMaintenanceStartupFactoryTest.channels(m),gate,stage,time);assertThrows(RuntimeException.class,r::resume);assertEquals(1,m.writes);assertFalse(stage.enabled());assertFalse(gate.isOpen());assertThrows(RuntimeException.class,r::resume);assertEquals(1,m.writes);stage.close();
 }

 static Book competitorClaim(Book before,Clock clock){
  var other=new JournaledNativeWriterTest.Memory(before.copy());var tx=new JournaledNativeTransport(other,JournaledNativeWriterTest.BINDING,clock);
  var service=new NarrowQuarterPoc.Service(tx,clock,JournaledNativeWriterTest.BINDING,"competing-native-owner",NarrowQuarterPoc.Limits.defaults());service.start();assertEquals(NarrowQuarterPoc.State.OPEN,service.state());service.close();return other.b;
 }
 @Test void receiptRaceLosesWithoutOverwritingWinningOwner()throws Exception{
  var clock=JournaledNativeWriterTest.CLOCK;var winner=new java.util.concurrent.atomic.AtomicReference<Book>();
  var m=new JournaledNativeWriterTest.Memory(DurableCleanHandoffAssessmentTest.closed()){
   @Override public void batch(String wire){if(winner.get()==null){b=competitorClaim(b,clock);winner.set(b.copy());}super.batch(wire);}
  };
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var r=new CleanClosedRestartAssembly(policy(),(p,c)->{},m::snapshot,PlannedMaintenanceStartupFactoryTest.channels(m),gate,stage,clock);
  assertThrows(RuntimeException.class,r::resume);assertEquals(1,m.writes);assertEquals(JournalSessionAuthority.fullToken(winner.get()),JournalSessionAuthority.fullToken(m.b));assertFalse(stage.enabled());assertFalse(gate.isOpen());assertThrows(RuntimeException.class,r::resume);assertEquals(1,m.writes);stage.close();
 }
 @Test void dueCompletionThenChangedFreshBaselineRejectsBeforeClaim()throws Exception{
  var base=JournaledNativeWriterTest.CLOCK;var clock=Clock.fixed(QuarterlyRetention.currentQuarterStart(base.instant()).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1),ZoneOffset.UTC);var m=new JournaledNativeWriterTest.Memory(DurableCleanHandoffAssessmentTest.closed());var reads=new java.util.concurrent.atomic.AtomicInteger();var winner=new java.util.concurrent.atomic.AtomicReference<Book>();
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var r=new CleanClosedRestartAssembly(policy(),(p,c)->{},()->{if(reads.incrementAndGet()==3){assertEquals(5,m.writes);m.b=competitorClaim(m.b,clock);winner.set(m.b.copy());}return m.snapshot();},PlannedMaintenanceStartupFactoryTest.channels(m),gate,stage,clock);
  assertThrows(RuntimeException.class,r::resume);assertEquals(3,reads.get());assertEquals(5,m.writes);assertEquals(JournalSessionAuthority.fullToken(winner.get()),JournalSessionAuthority.fullToken(m.b));assertFalse(stage.enabled());assertFalse(gate.isOpen());stage.close();
 }
 @Test void policyRevokedAfterInitialReadRejectsBeforeClaim()throws Exception{
  var clock=JournaledNativeWriterTest.CLOCK;var m=new JournaledNativeWriterTest.Memory(DurableCleanHandoffAssessmentTest.closed());var reads=new java.util.concurrent.atomic.AtomicInteger();var revoked=new java.util.concurrent.atomic.AtomicBoolean();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);
  var r=new CleanClosedRestartAssembly(policy(),(p,c)->{if(revoked.get())throw new Rejected();},()->{if(reads.incrementAndGet()==2)revoked.set(true);return m.snapshot();},PlannedMaintenanceStartupFactoryTest.channels(m),gate,stage,clock);assertThrows(RuntimeException.class,r::resume);assertEquals(0,m.writes);assertFalse(stage.enabled());stage.close();
 }
 @Test void dueGateBlockedOrStageClosedAfterPhaseCannotWriteNextPhase()throws Exception{
  for(boolean closeStage:new boolean[]{false,true}){
   var base=JournaledNativeWriterTest.CLOCK;var clock=Clock.fixed(QuarterlyRetention.currentQuarterStart(base.instant()).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1),ZoneOffset.UTC);var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);
   var m=new JournaledNativeWriterTest.Memory(DurableCleanHandoffAssessmentTest.closed()){
    @Override public void batch(String wire){super.batch(wire);if(writes==1){if(closeStage)stage.close();else gate.blockManual(com.bridge.persistence.QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);}}
   };
   var r=new CleanClosedRestartAssembly(policy(),(p,c)->{},m::snapshot,PlannedMaintenanceStartupFactoryTest.channels(m),gate,stage,clock);assertThrows(RuntimeException.class,r::resume);assertEquals(1,m.writes);assertFalse(stage.enabled());assertFalse(gate.isOpen());assertThrows(RuntimeException.class,r::resume);assertEquals(1,m.writes);stage.close();
  }
 }
}
