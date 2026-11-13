package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;import java.util.concurrent.atomic.*;import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class SameProcessCutoverCoordinatorTest {
 @Test void migrationOnlyIsInertAndExplicitActivationUsesSameInstalledStage()throws Exception{
  var f=new HistoricalMigrationAuthorityTest.Fixture();var clock=HistoricalMigrationAuthorityTest.CLOCK;var binding=HistoricalMigrationAuthorityTest.B;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var c=new SameProcessCutoverCoordinator(binding,gate,stage);c.acceptBootstrap(f.bootstrap.verifiedResult());var a=f.authority((t,h,e)->{});c.executeMigrationOnly(a,f::execute);assertTrue(stage.inertRoutingSafe());assertSame(a.verifiedResult(),c.migrationResult());
  var built=new AtomicInteger();var guarded=new AtomicInteger();
  // No factory construction or activation occurs during migration. An explicit activation
  // invokes its live authorization before construction. Here cancellation denies it.
  assertThrows(RuntimeException.class,()->c.activate((proof,guard)->{built.incrementAndGet();throw new AssertionError();},()->{guarded.incrementAndGet();throw new Rejected();}));assertEquals(0,built.get());assertEquals(1,guarded.get());assertTrue(stage.inertRoutingSafe());assertSame(a.verifiedResult(),c.migrationResult());stage.close();
 }
 @Test void restartedCoordinatorHasNoTransferableProofAndWrongBootstrapIdentityRejects()throws Exception{
  var f=new HistoricalMigrationAuthorityTest.Fixture();var other=new HistoricalMigrationAuthorityTest.Fixture();var clock=HistoricalMigrationAuthorityTest.CLOCK;var b=HistoricalMigrationAuthorityTest.B;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var restarted=new SameProcessCutoverCoordinator(b,gate,stage);assertThrows(RuntimeException.class,restarted::bootstrapResult);assertThrows(RuntimeException.class,restarted::migrationResult);assertThrows(RuntimeException.class,()->restarted.activate((p,g)->{throw new AssertionError();},()->{}));restarted.acceptBootstrap(other.bootstrap.verifiedResult());assertThrows(RuntimeException.class,()->restarted.executeMigrationOnly(f.authority((t,h,e)->{}),x->{throw new AssertionError();}));assertTrue(stage.inertRoutingSafe());stage.close();
 }
 @Test void unknownMigrationStopsActivationButPreservesBootstrapFact()throws Exception{
  var f=new HistoricalMigrationAuthorityTest.Fixture();var clock=HistoricalMigrationAuthorityTest.CLOCK;var b=HistoricalMigrationAuthorityTest.B;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var c=new SameProcessCutoverCoordinator(b,gate,stage);c.acceptBootstrap(f.bootstrap.verifiedResult());var a=f.authority((t,h,e)->{});assertThrows(RuntimeException.class,()->c.executeMigrationOnly(a,x->x.execute(()->f.book,w->{x.beforeDispatch(b.path()+":batchUpdate",w).unknown();throw new Unknown();},()->f.book)));assertSame(f.bootstrap.verifiedResult(),c.bootstrapResult());assertThrows(RuntimeException.class,c::migrationResult);assertThrows(RuntimeException.class,()->c.activate((p,g)->{throw new AssertionError();},()->{}));stage.close();
 }
 @Test void explicitAuthorizedActivationClaimsOnlyAfterMigrationOnOriginalStage()throws Exception{
  var f=new HistoricalMigrationAuthorityTest.Fixture();var clock=HistoricalMigrationAuthorityTest.CLOCK;var b=HistoricalMigrationAuthorityTest.B;var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);var c=new SameProcessCutoverCoordinator(b,gate,stage);c.acceptBootstrap(f.bootstrap.verifiedResult());c.executeMigrationOnly(f.authority((t,h,e)->{}),f::execute);assertTrue(stage.inertRoutingSafe());
  var memory=new JournaledNativeWriterTest.Memory(f.book);var builds=new AtomicInteger();var guards=new AtomicInteger();
  c.activate((proof,guard)->{builds.incrementAndGet();assertSame(c.migrationResult(),proof);return new PlannedMaintenanceStartupFactory(new PlannedMaintenanceStartupFactory.Contract(b,proof.afterToken(),"reviewed-source","explicit-activation"),x->{},memory::snapshot,PlannedMaintenanceStartupFactoryTest.channels(memory),PlannedMaintenanceStartupFactoryTest.policy(clock),gate,clock,new PlannedMaintenanceStartupFactory.Deployment("w","s","d"),()->new PlannedMaintenanceStartupFactory.Inventory(clock.instant(),"w","s","d","reviewed-source",1,1,false,false,true,true),guard);},guards::incrementAndGet);
  assertEquals(1,builds.get());assertTrue(guards.get()>1);
  // Attaching a verified backend must not itself open admission. The existing
  // quarterly coordinator independently inspects the attached lifecycle first.
  assertFalse(stage.lifecycle().startupWaiting());assertFalse(gate.isOpen());assertFalse(stage.enabled());
  var maintenance=new com.bridge.persistence.QuarterMaintenanceCoordinator(gate,stage.lifecycle(),clock,()->true,()->{});maintenance.tick();
  assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Status.OPEN,maintenance.status());assertTrue(stage.enabled());assertThrows(RuntimeException.class,()->c.activate((p,g)->{throw new AssertionError();},()->{}));stage.close();
 }
}
