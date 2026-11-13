package com.bridge.quartercore;
/** Deliberately unprovisioned. A future separately approved reviewed build may return its
 * fixed contract here. Environment settings, Sheet values and HTTP cannot provision it. */
final class NativeRestartCompiledRelease {
 /** Review data only. This is NOT a Policy, approval, contract or provisioning path.
  * Recheck the public audience against the deployed game before actual cutover.
  * No environment or HTTP lookup is used. */
 record PendingCandidate(GoogleSheetsBinding binding,String origin,String audience,
   String protocol,String sourceModel,String retentionVersion,String zone,String retentionRules) {
  boolean approvalPending(){return true;}
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
 static NativeRestartContract current(){return null;}
 static NativeRestartContract require(){var release=current();if(release==null)throw new IllegalStateException("Native restart release is not provisioned");return release;}
}
