package com.bridge.quartercore;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class PlannedMaintenanceStartupFactoryTest {
 static final GoogleSheetsBinding B=JournalSessionAuthorityTest.BINDING;
 static QuarterRetentionPolicy policy(Clock c){return QuarterRetentionPolicy.explicitlyApproved(new QuarterRetentionPolicy.Configuration(B,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"approved-recurring-policy"),c);}
 static PlannedMaintenanceStartupFactory.Contract contract(Book b){return new PlannedMaintenanceStartupFactory.Contract(B,JournalSessionAuthority.fullToken(b),"reviewed-source","approved-initial-cutover");}
 static PlannedMaintenanceStartupFactory.Channels channels(JournaledNativeWriterTest.Memory memory){return new PlannedMaintenanceStartupFactory.Channels(){
  NarrowSheetsTransport endpoint(GoogleDispatchAuthority authority){return new NarrowSheetsTransport(){
   public Scope operation(String l,Root r,Set<String> t,Duration d){return memory.operation(l,r,t,d);}
   public Book snapshot(){return memory.snapshot();}public List<Metadata> control(){return memory.control();}public List<List<String>> related(String t){return memory.related(t);}public boolean unsupportedStructure(){return false;}
   public void batch(String wire){var dispatch=authority.beforeDispatch(B.path()+":batchUpdate",wire);try{memory.batch(wire);dispatch.acknowledged();}catch(RuntimeException e){dispatch.unknown();throw e;}}
  };}
  public PlannedMaintenanceStartupFactory.Channel ordinary(JournalSessionAuthority grant){var slot=new JournalAuthoritySlot(grant);var h=new JournalGoogleFactory.Handle(endpoint(slot),()->{},slot,B);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
  public PlannedMaintenanceStartupFactory.Channel quarter(JournalQuarterAuthority grant){var cap=new QuarterAuthorityChannel(B,grant);var h=new JournalQuarterGoogleFactory.Handle(endpoint(cap),()->{},cap,B);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
 };}
 @Test void sameJvmCleanExpiryRenewsThenTwoQuartersWithoutExternalObservationAndRevocationStops()throws Exception {
  var time=new CrossQuarterNativeSettlementTest.Time();time.now=Instant.parse("2026-12-30T00:00:00Z");var m=JournaledNativeWriterTest.initial();
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);var staged=new com.bridge.persistence.StagedNativeBackend(gate);var policy=policy(time);var initialReads=new java.util.concurrent.atomic.AtomicInteger();
  var f=new PlannedMaintenanceStartupFactory(contract(m.b),c->{},()->{initialReads.incrementAndGet();return m.snapshot();},channels(m),policy,gate,time);
  f.activate(staged);var coordinator=new com.bridge.persistence.QuarterMaintenanceCoordinator(gate,staged.lifecycle(),time,()->true,()->{});coordinator.tick();
  assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Status.OPEN,coordinator.status());
  time.now=time.now.plusSeconds(7200);var row=m.b.sheet("auth_identities").rows.get(1);assertEquals(row.get(2),staged.findOrCreateAccount(row.get(0),row.get(1),"Renewed","test@example.com"));
  for(String boundary:List.of("2027-01-01T00:00:02Z","2027-04-01T00:00:02Z")){time.now=Instant.parse(boundary);int writes=m.writes;coordinator.tick();assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Status.OPEN,coordinator.status());assertEquals(7,m.writes-writes);assertTrue(staged.enabled());}
  assertEquals(1,initialReads.get());policy.revoke();time.now=Instant.parse("2027-07-01T00:00:02Z");coordinator.tick();assertNotEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Status.OPEN,coordinator.status());assertFalse(staged.enabled());staged.close();
 }
 @Test void unknownClaimCannotRetryAndPolicyCannotAuthorizeBeforeClaim()throws Exception {
  var time=new CrossQuarterNativeSettlementTest.Time();var m=JournaledNativeWriterTest.initial();var policy=policy(time);String op=UUID.randomUUID().toString();
  assertThrows(RuntimeException.class,()->policy.operation(new Object(),B,m.b,"q-"+op,op));
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);var staged=new com.bridge.persistence.StagedNativeBackend(gate);
  var f=new PlannedMaintenanceStartupFactory(contract(m.b),c->{},m::snapshot,channels(m),policy,gate,time);m.delay=true;int writes=m.writes;
  assertThrows(RuntimeException.class,()->f.activate(staged));assertThrows(RuntimeException.class,()->f.activate(staged));assertEquals(writes+1,m.writes);assertFalse(staged.enabled());staged.close();
 }
 @Test void wrongPolicyAndGateRejectBeforeAnyWrite()throws Exception {
  var time=new CrossQuarterNativeSettlementTest.Time();var m=JournaledNativeWriterTest.initial();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);
  assertThrows(RuntimeException.class,()->new QuarterRetentionPolicy.Configuration(B,"unsupported","Asia/Taipei",QuarterRetentionPolicy.RULES,"approval"));
  var staged=new com.bridge.persistence.StagedNativeBackend(new com.bridge.persistence.QuarterMaintenanceGate(true,time));int writes=m.writes;
  var f=new PlannedMaintenanceStartupFactory(contract(m.b),c->{},m::snapshot,channels(m),policy(time),gate,time);assertThrows(RuntimeException.class,()->f.activate(staged));assertEquals(writes,m.writes);staged.close();
 }
 @Test void externalWaitRevalidatesActivationGuardBeforeAnyClaim()throws Exception {
  var time=new CrossQuarterNativeSettlementTest.Time();var m=JournaledNativeWriterTest.initial();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);var staged=new com.bridge.persistence.StagedNativeBackend(gate);
  var live=new java.util.concurrent.atomic.AtomicBoolean(true);int writes=m.writes;
  var f=new PlannedMaintenanceStartupFactory(contract(m.b),c->{},m::snapshot,channels(m),policy(time),gate,time,
   new PlannedMaintenanceStartupFactory.Deployment("workspace","service","deployment"),()->{live.set(false);return new PlannedMaintenanceStartupFactory.Inventory(time.instant(),"workspace","service","deployment","reviewed-source",1,1,false,false,true,true);},()->{if(!live.get())throw new Rejected();});
  assertThrows(RuntimeException.class,()->f.activate(staged));assertEquals(writes,m.writes);staged.close();
 }

}
