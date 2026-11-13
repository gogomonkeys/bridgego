package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class SyntheticQuarterRunnerTest {
 static SyntheticQuarterRunner.Release release(JournaledNativeWriterTest.Memory m){
  var a=List.of(fixedEnvelope(m.b,"FORMAL",false),fixedEnvelope(m.b,"CASUAL",false));var b=List.of(fixedEnvelope(m.b,"FORMAL",true),fixedEnvelope(m.b,"CASUAL",true));return new SyntheticQuarterRunner.Release(PlannedMaintenanceStartupFactoryTest.B,JournalSessionAuthority.fullToken(m.b),"synthetic-reviewed","explicit-synthetic-approval",CrossQuarterNativeSettlementTest.OLD_NOW,Instant.parse("2026-12-31T16:00:01Z"),a,b,SyntheticQuarterRunner.Release.manifest(SyntheticQuarterRunner.Release.all(a,b)));
 }
 static com.bridge.persistence.FinalMatchEnvelope fixedEnvelope(Book book,String category,boolean next){
  var e=NativeCategoryIdentitySafetyTest.envelope(book,category);var p=(com.fasterxml.jackson.databind.node.ObjectNode)e.payload();
  ((com.fasterxml.jackson.databind.node.ObjectNode)p.path("match")).put("started_at","2026-12-31T15:59:50Z").put("finished_at",next?"2026-12-31T16:00:01Z":"2026-12-31T15:59:57Z");
  return com.bridge.persistence.FinalMatchEnvelope.create(e.matchId(),e.revision(),JSON.convertValue(p,Map.class));
 }
 static SyntheticQuarterRunner runner(SyntheticQuarterRunner.Release r,JournaledNativeWriterTest.Memory m,java.util.function.BooleanSupplier domain){return new SyntheticQuarterRunner(r,m::snapshot,PlannedMaintenanceStartupFactoryTest.channels(m),Clock.systemUTC(),domain);}
 static void await(SyntheticQuarterRunner r)throws Exception {long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(!r.fixturesConfirmed()&&System.nanoTime()<until&&r.status()!=SyntheticQuarterRunner.Status.REVIEW)Thread.sleep(5);assertTrue(r.fixturesConfirmed(),r.status().name());}
 @Test void fixedFixturesQuarterLifetimeCurrentRetentionAndCleanClosed()throws Exception{
  var m=JournaledNativeWriterTest.initial();var release=release(m);String account=m.b.sheet("accounts").rows.get(1).get(0);var original=LifetimeStatistics.readAccount(m.b.sheet("accounts").rows,account);
  var folding=new ArrayList<FinalMatchEnvelope>(ManualQuarterMaintenance.decoded(m.b.tables()).values());for(var e:release.envelopes())folding.add(new FinalMatchEnvelope(e.commitId(),e.matchId(),e.revision(),e.canonicalJson(),e.payloadHash()));
  var contribution=LifetimeStatistics.fromEnvelopes(account,folding);assertTrue(contribution.formal().matches()>0);assertTrue(contribution.casual().matches()>0);var domain=new AtomicBoolean(true);try(var r=runner(release,m,domain::get)){
   int before=m.writes;assertEquals(SyntheticQuarterRunner.Status.UNARMED,r.status());assertThrows(RuntimeException.class,r::start);assertEquals(before,m.writes);
   r.arm(release.baselineToken());r.start();r.submitFixedFixtures();await(r);r.advanceOnce();assertEquals(SyntheticQuarterRunner.Status.ADVANCED,r.status());
   r.submitNewQuarterFixtures();await(r);r.finishClosed();assertEquals(SyntheticQuarterRunner.Status.VERIFIED,r.status());assertEquals(JournalSessionAuthority.fullToken(m.b),r.verifiedClosedToken());
   assertEquals("CLOSED",NarrowQuarterPoc.root(new ArrayList<>(m.b.metadata.values())).state());assertEquals(release.nextQuarter(),r.businessTime());
   var raw=ManualQuarterMaintenance.decoded(m.b.tables());assertEquals(new HashSet<>(release.newQuarterEnvelopes().stream().map(com.bridge.persistence.FinalMatchEnvelope::commitId).toList()),raw.keySet());for(var e:release.envelopes())assertFalse(raw.containsKey(e.commitId()));
   assertEquals(Set.of("FORMAL","CASUAL"),raw.values().stream().map(FinalMatchEnvelope::statisticsCategory).collect(java.util.stream.Collectors.toSet()));
   var lifetime=LifetimeStatistics.readAccount(m.b.sheet("accounts").rows,account);assertEquals(original.formal().plus(contribution.formal()),lifetime.formal());assertEquals(original.casual().plus(contribution.casual()),lifetime.casual());
   int writes=m.writes;assertThrows(RuntimeException.class,r::advanceOnce);assertThrows(RuntimeException.class,r::submitFixedFixtures);assertEquals(writes,m.writes);
  }
  // Fresh runner is not armed; exact original baseline cannot approve a partially/completely applied run.
  try(var restarted=runner(release,m,()->true)){int writes=m.writes;assertThrows(RuntimeException.class,()->restarted.arm(release.baselineToken()));assertEquals(writes,m.writes);assertEquals(SyntheticQuarterRunner.Status.REVIEW,restarted.status());}
 }
 @Test void domainBusyPreventsTimeAdvanceAndAllSubsequentWrites()throws Exception{
  var m=JournaledNativeWriterTest.initial();var rel=release(m);var domain=new AtomicBoolean(true);try(var r=runner(rel,m,domain::get)){r.arm(rel.baselineToken());r.start();r.submitFixedFixtures();await(r);domain.set(false);int writes=m.writes;assertThrows(RuntimeException.class,r::advanceOnce);assertEquals(rel.businessStart(),r.businessTime());assertEquals(writes,m.writes);domain.set(true);assertThrows(RuntimeException.class,r::advanceOnce);}
 }
 @Test void unknownFixedMatchCannotAdvanceOrResubmit()throws Exception{
  var m=JournaledNativeWriterTest.initial();var rel=release(m);try(var r=runner(rel,m,()->true)){r.arm(rel.baselineToken());r.start();m.delay=true;try{r.submitFixedFixtures();}catch(Rejected expected){assertEquals(SyntheticQuarterRunner.Status.REVIEW,r.status());}long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(r.status()!=SyntheticQuarterRunner.Status.REVIEW&&System.nanoTime()<until){r.fixturesConfirmed();Thread.sleep(5);}assertEquals(SyntheticQuarterRunner.Status.REVIEW,r.status());int writes=m.writes;assertThrows(RuntimeException.class,r::advanceOnce);assertThrows(RuntimeException.class,r::submitFixedFixtures);assertEquals(writes,m.writes);assertEquals(rel.businessStart(),r.businessTime());}
 }
 @Test void wrongPinOrManifestCannotArmAndNoDefaultRelease()throws Exception{
  assertThrows(IllegalStateException.class,()->SyntheticQuarterRunnerMain.requireProvisioned(null));var m=JournaledNativeWriterTest.initial();var rel=release(m);try(var r=runner(rel,m,()->true)){int writes=m.writes;assertThrows(RuntimeException.class,()->r.arm("0".repeat(64)));assertEquals(writes,m.writes);}
  assertThrows(RuntimeException.class,()->new SyntheticQuarterRunner.Release(rel.binding(),rel.baselineToken(),rel.revision(),rel.approval(),rel.businessStart(),rel.nextQuarter(),rel.envelopes(),rel.newQuarterEnvelopes(),"0".repeat(64)));
 }
 @Test void registeredMatchOrAcceptedQueueBlocksAdvanceEvenWithAllFixedResultsConfirmed()throws Exception{
  for(boolean accepted:List.of(false,true)){
   var m=JournaledNativeWriterTest.initial();var rel=release(m);try(var r=runner(rel,m,()->true)){r.arm(rel.baselineToken());r.start();r.submitFixedFixtures();await(r);
    if(accepted){var factory=org.springframework.test.util.ReflectionTestUtils.getField(r,"factory");var backend=org.springframework.test.util.ReflectionTestUtils.getField(factory,"syntheticBackend");((Set<String>)org.springframework.test.util.ReflectionTestUtils.getField(backend,"unsettledAccepted")).add("synthetic-unsettled");}
    else{var gate=(com.bridge.persistence.QuarterMaintenanceGate)org.springframework.test.util.ReflectionTestUtils.getField(r,"gate");gate.registerMatch("synthetic-registered");}
    int writes=m.writes;assertThrows(RuntimeException.class,r::advanceOnce);assertEquals(rel.businessStart(),r.businessTime());assertEquals(writes,m.writes);
   }
  }
 }
 @Test void outstandingGatePermitBlocksAdvanceBeforeClockMoves()throws Exception{
  var m=JournaledNativeWriterTest.initial();var rel=release(m);try(var r=runner(rel,m,()->true)){r.arm(rel.baselineToken());r.start();r.submitFixedFixtures();await(r);
   var gate=(com.bridge.persistence.QuarterMaintenanceGate)org.springframework.test.util.ReflectionTestUtils.getField(r,"gate");try(var permit=gate.enterRead()){int writes=m.writes;assertThrows(RuntimeException.class,r::advanceOnce);assertEquals(writes,m.writes);assertEquals(rel.businessStart(),r.businessTime());permit.success();}
  }
 }

 @Test void closeRevokesWithoutWaitingForBlockedChannelAndLateClaimCannotWrite()throws Exception{
  var m=JournaledNativeWriterTest.initial();var rel=release(m);var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);var base=PlannedMaintenanceStartupFactoryTest.channels(m);
  var channels=new PlannedMaintenanceStartupFactory.Channels(){
   public PlannedMaintenanceStartupFactory.Channel ordinary(JournalSessionAuthority grant){var actual=base.ordinary(grant);var tx=actual.transport();return new PlannedMaintenanceStartupFactory.Channel(new NarrowSheetsTransport(){
    public Scope operation(String label,NarrowQuarterPoc.Root root,Set<String> tabs,Duration duration){return tx.operation(label,root,tabs,duration);}
    public Book snapshot(){entered.countDown();try{if(!resume.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new Rejected();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Rejected();}return tx.snapshot();}
    public List<Metadata> control(){return tx.control();}public List<List<String>> related(String tab){return tx.related(tab);}public boolean unsupportedStructure(){return tx.unsupportedStructure();}public void batch(String wire){tx.batch(wire);}
   },actual.release());}
   public PlannedMaintenanceStartupFactory.Channel quarter(JournalQuarterAuthority grant){return base.quarter(grant);}
  };
  var r=new SyntheticQuarterRunner(rel,m::snapshot,channels,Clock.systemUTC(),()->true);r.arm(rel.baselineToken());int writes=m.writes;
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);try{
   var worker=pool.submit(r::start);assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
   pool.submit(r::close).get(1,java.util.concurrent.TimeUnit.SECONDS);assertEquals(SyntheticQuarterRunner.Status.CLOSED,r.status());
   resume.countDown();assertThrows(java.util.concurrent.ExecutionException.class,()->worker.get(3,java.util.concurrent.TimeUnit.SECONDS));
   assertEquals(writes,m.writes);assertEquals(SyntheticQuarterRunner.Status.CLOSED,r.status());assertThrows(RuntimeException.class,r::submitFixedFixtures);assertThrows(RuntimeException.class,r::verifiedClosedToken);
  }finally{resume.countDown();r.close();pool.shutdownNow();}
 }

 @Test void physicalFormalAndPreviousTestTargetsRemainDeniedIndependentOfPurpose(){
  // Hash-only fixtures avoid publishing the real spreadsheet identifiers. The constructor hashes
  // the physical spreadsheet ID before considering purpose, so relabelling cannot bypass this.
  for(String hash:List.of("2cb7e2b584982dbca014d681a91105f49a718b396bd72c38f41cbbb61a81cc0a","210fc8ddd190a97a4474c1df49adbb4cb5966dbc239ad4e372c70e6d2022f312","76b02d08ba9c730cebf2e0fa8159f554f10ce4d7fd36b01586354e8afee2af36"))assertThrows(RuntimeException.class,()->SyntheticQuarterRunner.Release.requireAllowedSpreadsheetHash(hash));
 }

}
