package com.bridge.quartercore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeRestartContractSafetyTest {
 @Test void pendingCandidateIsNotAnEnabledContract(){
  var c=NativeRestartCompiledRelease.pendingReview();
  assertTrue(c.approvalPending());assertTrue(c.audiencePinned());
  assertEquals("616174516143-entpg07e9oa4hqe8s2jb64bf64h8nj4j.apps.googleusercontent.com",c.audience());
  assertEquals("bridgego-managed",c.binding().purpose());
  assertEquals("f89030e0-6629-43d7-8a96-558f488c9da2",c.binding().datasetId());
  assertEquals("https://bridgego.onrender.com",c.origin());assertEquals("Asia/Taipei",c.zone());
  assertEquals(QuarterRetentionPolicy.RULES,c.retentionRules());
  assertNull(NativeRestartCompiledRelease.current());
  assertThrows(IllegalStateException.class,NativeRestartCompiledRelease::require);
 }
 @Test void initialAndRestartMustMatchEveryAuthorityDimension(){
  var c=NativeRestartRuntimeAssemblyTest.contract();
  var p=c.policy();var b=c.binding();
  c.requireInitialSettings(settings(b,c.origin(),c.audience(),p.approvalReference(),p.retention()));
  assertThrows(RuntimeException.class,()->c.requireInitialSettings(settings(b,"https://other.example",c.audience(),p.approvalReference(),p.retention())));
  assertThrows(RuntimeException.class,()->c.requireInitialSettings(settings(b,c.origin(),"another-client",p.approvalReference(),p.retention())));
  assertThrows(RuntimeException.class,()->c.requireInitialSettings(settings(b,c.origin(),c.audience(),"different-cutover-approval",p.retention())));
  var otherRetention=new QuarterRetentionPolicy.Configuration(b,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"different-retention-approval");
  assertThrows(RuntimeException.class,()->c.requireInitialSettings(settings(b,c.origin(),c.audience(),p.approvalReference(),otherRetention)));
  var other=NativeRestartCompiledRelease.pendingReview().binding();
  var otherPolicy=new QuarterRetentionPolicy.Configuration(other,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,p.retention().approvalReference());
  assertThrows(RuntimeException.class,()->c.requireInitialSettings(settings(other,c.origin(),c.audience(),p.approvalReference(),otherPolicy)));
 }
 private static PlannedCutoverRuntimeAssembly.Settings settings(GoogleSheetsBinding b,String origin,String audience,String approval,QuarterRetentionPolicy.Configuration policy){
  return new PlannedCutoverRuntimeAssembly.Settings(origin,audience,Set.of("synthetic-operator"),b,"tea-fixed","srv-fixed","reviewed-revision",Set.of("dep-old"),Set.of("manual-cli"),approval,
   new PlannedCutoverRuntimeAssembly.History("source","a".repeat(64),"c11f7a90-d46a-4eed-9c11-b5843c0c194d",new LifetimeStatistics.Counts(1,1,0,1,0,-1,1,0,0)),policy);
 }
}
