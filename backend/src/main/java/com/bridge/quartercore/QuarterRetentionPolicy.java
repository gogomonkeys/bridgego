package com.bridge.quartercore;
import java.time.*;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Explicit approved product setting. No default bean, environment switch or cross-JVM ownership. */
final class QuarterRetentionPolicy {
 static final String VERSION="taiwan-quarter-retention-v1";
 static final String RULES="expired-personal-results-fold-verified-lifetime-preserve-accounts-overall-v2";
 record Configuration(GoogleSheetsBinding binding,String version,String zone,String rules,String approvalReference){
  Configuration{Objects.requireNonNull(binding);if(!VERSION.equals(version)||!"Asia/Taipei".equals(zone)||!RULES.equals(rules)||approvalReference==null||approvalReference.isBlank()||approvalReference.length()>200)throw new Rejected();}
 }
 private final Configuration config;private final Clock clock;private Object process;private Instant last;private boolean revoked;
 private final Set<Instant> attempted=new HashSet<>();
 private QuarterRetentionPolicy(Configuration config,Clock clock){this.config=Objects.requireNonNull(config);this.clock=Objects.requireNonNull(clock);check();}
 /** Trusted composition chooses this after actual policy approval. It does not grant account access. */
 static QuarterRetentionPolicy explicitlyApproved(Configuration config,Clock clock){return new QuarterRetentionPolicy(config,clock);}
 synchronized void requireBinding(GoogleSheetsBinding binding){check();if(!config.binding().equals(binding))throw new Rejected();}
 synchronized void bindClaimedProcess(Object instance,GoogleSheetsBinding binding,Book claimed){
  requireBinding(binding);if(process!=null||instance==null)throw new Rejected();NativeCommitJournal.verify(binding.manualBinding(),claimed,clock);
  if(!NarrowQuarterPoc.root(new ArrayList<>(claimed.metadata.values())).state().equals("OPEN"))throw new Rejected();process=instance;
 }
 synchronized JournalQuarterAuthority.ApprovalVerifier operation(Object instance,GoogleSheetsBinding binding,Book closed,String owner,String operation){
  FormalCurrentQuarterScope.requireQuarterDeletionApproval(binding);
  requireBinding(binding);if(process==null||instance!=process)throw new Rejected();NarrowQuarterPoc.uuid(operation);
  Instant quarter=QuarterlyRetention.currentQuarterStart(clock.instant());
  if(!owner.equals("q-"+operation)||JournalQuarterProtocol.inspect(binding.manualBinding(),closed,clock).state()!=JournalQuarterProtocol.RecoveryState.READY_DUE||!attempted.add(quarter))throw new Rejected();
  String baseline=JournalSessionAuthority.fullToken(closed);
  return (b,q,t,o,id)->{synchronized(this){check();if(instance!=process||!binding.manualBinding().equals(b)||!quarter.equals(q)||!quarter.equals(QuarterlyRetention.currentQuarterStart(clock.instant()))||!baseline.equals(t)||!owner.equals(o)||!operation.equals(id))throw new Rejected();}};
 }
 synchronized void revoke(){revoked=true;}
 private void check(){if(revoked)throw new Rejected();Instant now=clock.instant();if(last!=null&&now.isBefore(last)){revoked=true;throw new Rejected();}last=now;}
}
