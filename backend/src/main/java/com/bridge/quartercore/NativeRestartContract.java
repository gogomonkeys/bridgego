package com.bridge.quartercore;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Reviewed build-time contract, not a deserializable request or an environment flag.
 * Provisioning requires approval of target, source build, native-only writer exclusion,
 * durable-close reconciliation, and retention policy. No production instance is shipped. */
final class NativeRestartContract {
 static final String PROTOCOL_IDENTITY="bridgego-native-journal-close-restart-v1";
 static final String SOURCE_MODEL="approved-native-only-durable-close-cas-v1";
 private final CleanClosedRestartAssembly.Policy policy;private final String origin,audience,sourceModel;
 private NativeRestartContract(CleanClosedRestartAssembly.Policy policy,String origin,String audience,String sourceModel){
  this.policy=Objects.requireNonNull(policy);this.origin=Objects.requireNonNull(origin);this.audience=Objects.requireNonNull(audience);this.sourceModel=Objects.requireNonNull(sourceModel);
  var uri=java.net.URI.create(origin);if(!PROTOCOL_IDENTITY.equals(policy.sourceRevision())||!SOURCE_MODEL.equals(sourceModel)||audience.isBlank()||audience.length()>512||!"https".equals(uri.getScheme())||uri.getHost()==null||!uri.getRawPath().isEmpty()||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawUserInfo()!=null)throw new Rejected();
 }
 /** Package-private explicit trusted composition seam; never invoked by an env parser. */
 static NativeRestartContract fixedReviewedBuild(CleanClosedRestartAssembly.Policy policy,String origin,String audience){return new NativeRestartContract(policy,origin,audience,SOURCE_MODEL);}
 CleanClosedRestartAssembly.Policy policy(){return policy;}GoogleSheetsBinding binding(){return policy.binding();}String origin(){return origin;}String audience(){return audience;}
 void requirePolicy(CleanClosedRestartAssembly.Policy supplied){if(!SOURCE_MODEL.equals(sourceModel)||!policy.equals(supplied))throw new Rejected();}
 // Render observation still verifies settings.revision() as the actual deployment SHA.
 // This protocol identity is stable across builds, not a self-referential Git SHA.
 void requireInitialSettings(PlannedCutoverRuntimeAssembly.Settings s){if(!binding().equals(s.binding())||!policy.approvalReference().equals(s.approvalReference())||!policy.retention().equals(s.policy())||!origin.equals(s.origin())||!audience.equals(s.audience()))throw new Rejected();}
}
