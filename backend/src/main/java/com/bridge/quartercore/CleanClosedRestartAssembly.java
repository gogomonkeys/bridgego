package com.bridge.quartercore;
import java.time.*;import java.util.*;
import com.bridge.persistence.QuarterMaintenanceGate;import com.bridge.persistence.StagedNativeBackend;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Explicit local composition only: no bean, environment grant, key loading or deployment.
 * Uses durable CLOSE lineage, never prior JVM B1/B2 objects or a caller-supplied hash. */
final class CleanClosedRestartAssembly {
 record Policy(GoogleSheetsBinding binding,String sourceRevision,String approvalReference,QuarterRetentionPolicy.Configuration retention){
  Policy{Objects.requireNonNull(binding);Objects.requireNonNull(retention);if(!binding.equals(retention.binding())||sourceRevision==null||sourceRevision.isBlank()||approvalReference==null||approvalReference.isBlank())throw new Rejected();}
 }
 /** Independent, explicitly approved native-only source policy. Must establish excluded
  * legacy/manual/import writers and fixed approved native-only protocol/source contract,
  * exact target and permission for the configured quarter maintenance then a NEW claim.
  * It does not require old RAM acknowledgement or platform death evidence: trusted CLOSE
  * checked quiescence, and receipt CAS fences delayed native writes. An existing process
  * UNKNOWN latch is never cleared here. A journal is not this policy authorization.
  * Must be bounded local and revocable, not I/O under writer locks. Returning authorizes
  * only this exact fixed policy and freshly observed CLOSE lineage. */
 @FunctionalInterface interface SourcePolicyVerifier {void verify(Policy policy,DurableCleanHandoffAssessment.Result close);}
 private final Policy policy;private final SourcePolicyVerifier source;private final PlannedMaintenanceStartupFactory.Reader reader;private final PlannedMaintenanceStartupFactory.Channels channels;private final QuarterMaintenanceGate gate;private final StagedNativeBackend staged;private final Clock clock;private Clock businessClock;private boolean isolatedE;
 private boolean attempted,failed,claimStarted;private DurableCleanHandoffAssessment.Result close;private Instant last;
 CleanClosedRestartAssembly(Policy policy,SourcePolicyVerifier source,PlannedMaintenanceStartupFactory.Reader reader,PlannedMaintenanceStartupFactory.Channels channels,QuarterMaintenanceGate gate,StagedNativeBackend staged,Clock clock){this.policy=Objects.requireNonNull(policy);this.source=Objects.requireNonNull(source);this.reader=Objects.requireNonNull(reader);this.channels=Objects.requireNonNull(channels);this.gate=Objects.requireNonNull(gate);this.staged=Objects.requireNonNull(staged);this.clock=Objects.requireNonNull(clock);this.businessClock=clock;if(!staged.belongsTo(gate)||!staged.inertRoutingSafe())throw new Rejected();}
 static CleanClosedRestartAssembly isolatedEClockTest(Policy policy,SourcePolicyVerifier source,PlannedMaintenanceStartupFactory.Reader reader,PlannedMaintenanceStartupFactory.Channels channels,QuarterMaintenanceGate gate,StagedNativeBackend staged,Clock securityClock,Clock businessClock){
  FixedERestartClock.require(policy.binding(),businessClock);var assembly=new CleanClosedRestartAssembly(policy,source,reader,channels,gate,staged,securityClock);assembly.businessClock=businessClock;assembly.isolatedE=true;return assembly;
 }
 private synchronized void continuity(){if(failed||close==null)throw new Rejected();if(isolatedE)FixedERestartClock.require(policy.binding(),businessClock);Instant now=clock.instant();if(last!=null&&now.isBefore(last))throw new Rejected();last=now;if(!claimStarted)requireInert();source.verify(policy,close);if(!claimStarted)requireInert();}
 private void requireInert(){if(!staged.belongsTo(gate)||!staged.inertRoutingSafe()||gate.state()!=QuarterMaintenanceGate.State.STARTING)throw new Rejected();}
 private void requireInitialClaim(){if(!staged.belongsTo(gate)||!staged.maintenanceRoutingSafe()||gate.state()!=QuarterMaintenanceGate.State.STARTING)throw new Rejected();}
 void resume(){synchronized(this){if(attempted||failed||!staged.inertRoutingSafe())throw new Rejected();attempted=true;}
  try{
   Book before=reader.read();var inspected=DurableCleanHandoffAssessment.inspect(policy.binding().manualBinding(),before,businessClock);if(inspected.state()==DurableCleanHandoffAssessment.State.BLOCKED)throw new Rejected();synchronized(this){close=inspected;}continuity();
   Book ready=before;
   if(inspected.state()==DurableCleanHandoffAssessment.State.CLEAN_DUE_REQUIRES_MAINTENANCE)ready=maintain(before);
   String token=JournalSessionAuthority.fullToken(ready);if(JournalQuarterProtocol.inspect(policy.binding().manualBinding(),ready,businessClock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();continuity();
   var contract=new PlannedMaintenanceStartupFactory.Contract(policy.binding(),token,policy.sourceRevision(),policy.approvalReference());var retention=QuarterRetentionPolicy.explicitlyApproved(policy.retention(),businessClock);
   PlannedMaintenanceStartupFactory.Authorization auth=c->{continuity();if(!contract.equals(c))throw new Rejected();};
   var factory=isolatedE?PlannedMaintenanceStartupFactory.isolatedClockTest(contract,auth,reader,channels,retention,gate,clock,businessClock,FixedERestartClock.binding(),this::requireInitialClaim):new PlannedMaintenanceStartupFactory(contract,auth,reader,channels,retention,gate,clock,this::requireInitialClaim);
   factory.requireCompletionBinding(policy.binding(),token,gate);synchronized(this){requireInert();claimStarted=true;}factory.activate(staged);
  }catch(RuntimeException|Error e){synchronized(this){failed=true;}gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw e;}
 }
 private Book maintain(Book before){
  String baseline=JournalSessionAuthority.fullToken(before),operation=UUID.randomUUID().toString(),owner="q-"+operation;Instant quarter=QuarterlyRetention.currentQuarterStart(businessClock.instant());continuity();
  var authority=JournalQuarterAuthority.provision(policy.binding().manualBinding(),before,owner,operation,(b,q,t,o,id)->{continuity();if(!b.equals(policy.binding().manualBinding())||!q.equals(quarter)||!quarter.equals(QuarterlyRetention.currentQuarterStart(businessClock.instant()))||!t.equals(baseline)||!o.equals(owner)||!id.equals(operation))throw new Rejected();},clock,businessClock,Duration.ofMinutes(5));
  try(var channel=channels.quarter(authority)){
   var protocol=new JournalQuarterProtocol(policy.binding().manualBinding(),channel.transport(),businessClock,owner,authority);protocol.start();if(protocol.status()!=JournalQuarterProtocol.Status.VERIFIED)throw new Rejected();
   Book after=reader.read();continuity();if(!authority.verifiedClosedToken().equals(JournalSessionAuthority.fullToken(after)))throw new Rejected();return after;
  }catch(RuntimeException|Error e){authority.unknown();throw e;}
 }
}
