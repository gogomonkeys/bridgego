package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SeparatedBusinessClockTest {
 static CrossQuarterNativeSettlementTest.Time time(Instant now){var t=new CrossQuarterNativeSettlementTest.Time();t.now=now;return t;}
 @Test void ordinaryBusinessQuarterJumpDoesNotExtendRealExpiryOrReviveUnknown()throws Exception{
  var business=time(JournalSessionAuthorityTest.CLOCK.instant());var security=time(business.instant());var b=JournalSessionAuthorityTest.BINDING;var before=JournalSessionAuthorityTest.clean();
  var grant=JournalSessionAuthority.provision(b,before,JournalSessionAuthorityTest.OWNER,"source","approval",(x,o,s,a,t)->assertEquals(JournalSessionAuthority.fullToken(before),t),security,business,Duration.ofHours(1));
  business.now=business.now.plusSeconds(100*86400);grant.requireSession(b);assertEquals(JournalSessionAuthority.State.READY,grant.state());
  security.now=security.now.plusSeconds(3601);assertThrows(RuntimeException.class,()->grant.requireSession(b));assertEquals(JournalSessionAuthority.State.EXPIRED_CLEAN,grant.state());
  grant.closeUnknown();business.now=business.now.plusSeconds(100*86400);assertThrows(RuntimeException.class,()->grant.renew(before,Duration.ofHours(1)));assertEquals(JournalSessionAuthority.State.UNKNOWN,grant.state());
 }
 @Test void quarterSecurityExpiryDoesNotFollowFrozenOrDifferentBusinessDate()throws Exception{
  var business=time(JournalQuarterProtocolTest.CLOCK.instant());var security=time(Instant.parse("2026-11-13T00:00:00Z"));var baseline=JournalQuarterProtocolTest.baseline();
  var grant=JournalQuarterAuthority.provision(JournalQuarterProtocolTest.BINDING,baseline,"split-quarter",UUID.randomUUID().toString(),(b,q,t,o,id)->assertEquals(QuarterlyRetention.currentQuarterStart(business.instant()),q),security,business,Duration.ofMinutes(5));
  var plan=grant.nextPlan();security.now=security.now.plusSeconds(301);assertThrows(RuntimeException.class,()->grant.beforeDispatch(plan));assertEquals(JournalQuarterAuthority.State.UNKNOWN,grant.state());
  business.now=business.now.plusSeconds(1);assertThrows(RuntimeException.class,grant::nextPlan);
 }
 @Test void actualFactoryTwoBusinessQuartersKeepSecurityClockFixed()throws Exception{
  var business=time(Instant.parse("2026-12-30T00:00:00Z"));var security=time(Instant.parse("2026-11-13T00:00:00Z"));var m=JournaledNativeWriterTest.initial();
  var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,business);var staged=new com.bridge.persistence.StagedNativeBackend(gate);var policy=PlannedMaintenanceStartupFactoryTest.policy(business);
  var f=PlannedMaintenanceStartupFactory.isolatedClockTest(PlannedMaintenanceStartupFactoryTest.contract(m.b),c->{},m::snapshot,PlannedMaintenanceStartupFactoryTest.channels(m),policy,gate,security,business,PlannedMaintenanceStartupFactoryTest.B);
  f.activate(staged);var coordinator=new com.bridge.persistence.QuarterMaintenanceCoordinator(gate,staged.lifecycle(),business,()->true,()->{});coordinator.tick();
  for(String boundary:List.of("2027-01-01T00:00:02Z","2027-04-01T00:00:02Z")){business.now=Instant.parse(boundary);int writes=m.writes;coordinator.tick();assertEquals(com.bridge.persistence.QuarterMaintenanceCoordinator.Status.OPEN,coordinator.status());assertEquals(7,m.writes-writes);}
  assertEquals(Instant.parse("2026-11-13T00:00:00Z"),security.instant());staged.close();
 }
 @Test void testAssemblyRejectsWrongTargetBeforeReadOrWrite()throws Exception{
  var business=time(JournalSessionAuthorityTest.CLOCK.instant());var m=JournaledNativeWriterTest.initial();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,business);var b=PlannedMaintenanceStartupFactoryTest.B;
  var wrong=new GoogleSheetsBinding(b.spreadsheetId(),UUID.randomUUID().toString(),b.purpose(),b.serviceAccountIssuer());
  assertThrows(RuntimeException.class,()->PlannedMaintenanceStartupFactory.isolatedClockTest(PlannedMaintenanceStartupFactoryTest.contract(m.b),c->{},()->{throw new AssertionError();},PlannedMaintenanceStartupFactoryTest.channels(m),PlannedMaintenanceStartupFactoryTest.policy(business),gate,business,business,wrong));
 }
 @Test void localAssemblyRefusesManagedPurposeEvenWhenPinMatches()throws Exception{
  var t=time(JournalSessionAuthorityTest.CLOCK.instant());var m=JournaledNativeWriterTest.initial();var b=PlannedMaintenanceStartupFactoryTest.B;
  var managed=new GoogleSheetsBinding(b.spreadsheetId(),b.datasetId(),com.bridge.persistence.DatasetBinding.MANAGED_PURPOSE,b.serviceAccountIssuer());
  var contract=new PlannedMaintenanceStartupFactory.Contract(managed,JournalSessionAuthority.fullToken(m.b),"source","approval");
  var policy=QuarterRetentionPolicy.explicitlyApproved(new QuarterRetentionPolicy.Configuration(managed,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"approval"),t);
  assertThrows(RuntimeException.class,()->PlannedMaintenanceStartupFactory.isolatedClockTest(contract,c->{},()->{throw new AssertionError();},PlannedMaintenanceStartupFactoryTest.channels(m),policy,new com.bridge.persistence.QuarterMaintenanceGate(true,t),t,t,managed));
 }

 @Test void businessQuarterChangesMidPhaseAndSecurityRollbackBothStopWithoutReplay()throws Exception{
  for(boolean changeBusiness:List.of(false,true)){
   var business=time(JournalQuarterProtocolTest.CLOCK.instant());var security=time(Instant.parse("2026-11-13T00:00:00Z"));var baseline=JournalQuarterProtocolTest.baseline();
   var grant=JournalQuarterAuthority.provision(JournalQuarterProtocolTest.BINDING,baseline,"split-midphase",UUID.randomUUID().toString(),(b,q,t,o,id)->{},security,business,Duration.ofMinutes(5));var plan=grant.nextPlan();
   if(changeBusiness)business.now=business.now.plusSeconds(100*86400);else security.now=security.now.minusSeconds(1);
   assertThrows(RuntimeException.class,()->grant.beforeDispatch(plan));assertEquals(JournalQuarterAuthority.State.UNKNOWN,grant.state());assertThrows(RuntimeException.class,grant::nextPlan);
  }
 }

}
