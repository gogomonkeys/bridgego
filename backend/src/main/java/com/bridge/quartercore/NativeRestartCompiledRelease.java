package com.bridge.quartercore;
/** Fixed user-approved Q4 startup contract. Environment settings, Sheet values and HTTP
 * cannot provision another contract or authorize irreversible quarterly deletion. */
final class NativeRestartCompiledRelease {
 /** Review data only. This is NOT a Policy, approval, contract or provisioning path.
  * Recheck the public audience against the deployed game before actual cutover.
  * No environment or HTTP lookup is used. */
 record PendingCandidate(GoogleSheetsBinding binding,String origin,String audience,
   String protocol,String sourceModel,String retentionVersion,String zone,String retentionRules) {
  boolean approvalPending(){return APPROVED_REFERENCE==null;}
  boolean audiencePinned(){return audience!=null;}
 }
 private static final PendingCandidate CANDIDATE=new PendingCandidate(
   new GoogleSheetsBinding("1_AqJ_-jXFNSzUZhsTylnXqTmIbtL-PHA1VDd23tV5p0",
     "f89030e0-6629-43d7-8a96-558f488c9da2","bridgego-managed",
     "bridgego-sheets@bridgego-513805.iam.gserviceaccount.com"),
   "https://bridgego.onrender.com","616174516143-entpg07e9oa4hqe8s2jb64bf64h8nj4j.apps.googleusercontent.com",NativeRestartContract.PROTOCOL_IDENTITY,
   NativeRestartContract.SOURCE_MODEL,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES);
 static PendingCandidate pendingReview(){return CANDIDATE;}
 private NativeRestartCompiledRelease(){}
 // Approval relayed by the coordinating root for this exact Q4 scope only.
 // FormalCurrentQuarterScope still denies every irreversible quarterly deletion.
 private static final String APPROVED_REFERENCE="formal-cutover-20261113-user-approved-q4";
 private static NativeRestartContract assemble(String approvedReference){
  if(approvedReference==null)return null;
  var c=CANDIDATE;
  var retention=new QuarterRetentionPolicy.Configuration(c.binding(),c.retentionVersion(),c.zone(),c.retentionRules(),approvedReference);
  var policy=new CleanClosedRestartAssembly.Policy(c.binding(),c.protocol(),approvedReference,retention);
  return NativeRestartContract.fixedReviewedBuild(policy,c.origin(),c.audience());
 }
 static NativeRestartContract current(){return assemble(APPROVED_REFERENCE);}
 static NativeRestartContract require(){var release=current();if(release==null)throw new IllegalStateException("Native restart release is not provisioned");return release;}
}
