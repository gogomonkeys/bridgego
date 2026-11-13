package com.bridge.quartercore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeRestartContractSafetyTest {
 @Test void fixedCandidateIsApprovedOnlyForPinnedQ4Scope(){
  var c=NativeRestartCompiledRelease.pendingReview();
  assertFalse(c.approvalPending());assertTrue(c.audiencePinned());
  assertEquals("616174516143-entpg07e9oa4hqe8s2jb64bf64h8nj4j.apps.googleusercontent.com",c.audience());
  assertEquals("bridgego-managed",c.binding().purpose());
  assertEquals("f89030e0-6629-43d7-8a96-558f488c9da2",c.binding().datasetId());
  assertEquals("https://bridgego.onrender.com",c.origin());assertEquals("Asia/Taipei",c.zone());
  assertEquals(QuarterRetentionPolicy.RULES,c.retentionRules());
  assertEquals(NativeRestartCompiledRelease.pendingReview().binding(),NativeRestartCompiledRelease.require().binding());
  assertEquals("formal-cutover-20261113-user-approved-q4",NativeRestartCompiledRelease.require().policy().approvalReference());
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
 @Test void fixedCandidateAssemblyUsesOneSourceButCannotProvisionRelease() throws Exception {
  // Local test of private build-time wiring, not a production approval or exposed seam.
  var method=NativeRestartCompiledRelease.class.getDeclaredMethod("assemble",String.class);method.setAccessible(true);
  var c=NativeRestartCompiledRelease.pendingReview();
  var assembled=(NativeRestartContract)method.invoke(null,"local-test-not-a-real-approval");
  assertEquals(c.binding(),assembled.binding());assertEquals(c.origin(),assembled.origin());assertEquals(c.audience(),assembled.audience());
  assertEquals(c.protocol(),assembled.policy().sourceRevision());
  var retention=assembled.policy().retention();assertEquals(c.retentionVersion(),retention.version());assertEquals(c.zone(),retention.zone());assertEquals(c.retentionRules(),retention.rules());
  assertEquals(assembled.policy().approvalReference(),retention.approvalReference());
  assertNull(method.invoke(null,new Object[]{null}));assertEquals(NativeRestartCompiledRelease.pendingReview().binding(),NativeRestartCompiledRelease.require().binding());
 }
 @Test void managedInitialCannotOverrideCompiledApproval(){
  var c=NativeRestartCompiledRelease.pendingReview();String fake="environment-cannot-authorize";
  var policy=new QuarterRetentionPolicy.Configuration(c.binding(),c.retentionVersion(),c.zone(),c.retentionRules(),fake);
  assertThrows(RuntimeException.class,()->PlannedCutoverLauncher.initialRelease(settings(c.binding(),c.origin(),c.audience(),fake,policy)));
  var actual=NativeRestartCompiledRelease.require();
  assertEquals(actual.binding(),PlannedCutoverLauncher.initialRelease(settings(c.binding(),c.origin(),c.audience(),actual.policy().approvalReference(),actual.policy().retention())).binding());
  var restricted=NativeRestartRuntimeAssemblyTest.contract();
  assertEquals("bridgego-restricted-test",restricted.binding().purpose());
  assertNull(PlannedCutoverLauncher.initialRelease(settings(restricted.binding(),restricted.origin(),restricted.audience(),restricted.policy().approvalReference(),restricted.policy().retention())));
  assertEquals(NativeRestartCompiledRelease.pendingReview().binding(),NativeRestartCompiledRelease.require().binding());
 }
 private static PlannedCutoverRuntimeAssembly.Settings settings(GoogleSheetsBinding b,String origin,String audience,String approval,QuarterRetentionPolicy.Configuration policy){
  return new PlannedCutoverRuntimeAssembly.Settings(origin,audience,Set.of("synthetic-operator"),b,"tea-fixed","srv-fixed","reviewed-revision",Set.of("dep-old"),Set.of("manual-cli"),approval,
   new PlannedCutoverRuntimeAssembly.History("source","a".repeat(64),"c11f7a90-d46a-4eed-9c11-b5843c0c194d",new LifetimeStatistics.Counts(1,1,0,1,0,-1,1,0,0)),policy);
 }
}
