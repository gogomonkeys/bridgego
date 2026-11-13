package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Small fail-closed adapter for trusted, authenticated read-only deployment observations.
 * Not a Render client, distributed lease, approval UI or env-based attestation. A production
 * source must be installed explicitly; user/tool text must not be parsed as authenticated evidence. */
final class SingleServiceEvidenceProvider {
  record Binding(QuarterNativeControl.Target target,String workspaceId,String serviceId,String revision,
      String cutoverReference,String approvedBaselineToken) {
    Binding {Objects.requireNonNull(target);for(String s:List.of(workspaceId,serviceId,revision,cutoverReference))if(s.isBlank())throw new Rejected();
      if(!approvedBaselineToken.matches("[0-9a-f]{64}"))throw new Rejected();}
  }
  record Deployment(String id,String revision,String state) {}
  enum ExternalWriterState { STOPPED_RESTART_DISABLED, NOT_CONFIGURED, RUNNING, UNVERIFIED }
  enum HandoffState { CLEAN_CLOSED, UNKNOWN }
  /** A separately verified predecessor handoff, not inferred from Render instance count.
   * For native orderly shutdown: full committed CLOSED token and no unresolved dispatch.
   * Source must verify this from authenticated process handoff evidence + native readback.
   * Render deployment status alone cannot populate this record. */
  record Handoff(String predecessorDeployment,String predecessorProcess,String targetToken,
      String closedSnapshotToken,String predecessorProcessState,HandoffState state) {}
  record Observation(Instant capturedAt,String workspaceId,String serviceId,int serviceCount,int instances,
      boolean autoDeploy,boolean previews,List<Deployment> deployments,
      Map<String,ExternalWriterState> knownExternalWriters,String approvedCutoverReference,
      String approvedBaselineToken,Handoff handoff) {
    Observation {deployments=List.copyOf(deployments);knownExternalWriters=Map.copyOf(knownExternalWriters);}
  }
  @FunctionalInterface interface Source {
    /** Authenticated read-only Render inventory plus the separately trusted cutover/handoff
     * source. May return a bounded cached observation; must not invent missing fields.
     * The provider enforces freshness. No API keys or credential contents are included. */
    Observation read();
    default NativePredecessor nativePredecessor(){return null;}
    static Source unavailable(){return ()->{throw new Rejected();};}
  }
  record NativePredecessor(Instant capturedAt,String cutoverReference,String targetToken,String deploymentId,
      String processGeneration,String owner,String revision,String processState,String protocol,String acceptedWorkState,
      Set<String> excludedExternalWriters) {
    NativePredecessor {excludedExternalWriters=Set.copyOf(excludedExternalWriters);}
  }
  record Process(String generation,String deploymentId) {
    Process {if(generation==null||generation.isBlank()||deploymentId==null||deploymentId.isBlank())throw new Rejected();}
  }
  private final Binding binding;private final Source source;private final Clock clock;private final Process process;
  private final Duration freshness;private Instant lastObserved;private boolean revoked;
  private final Set<String> expectedExternalWriters;
  SingleServiceEvidenceProvider(Binding binding,Source source,Clock clock,Process process,Duration freshness,Set<String> expectedExternalWriters) {
    this.binding=Objects.requireNonNull(binding);this.source=Objects.requireNonNull(source);this.clock=Objects.requireNonNull(clock);this.process=Objects.requireNonNull(process);
    if(freshness==null||freshness.isZero()||freshness.isNegative()||freshness.compareTo(Duration.ofMinutes(5))>0)throw new Rejected();
    this.freshness=freshness;this.expectedExternalWriters=Set.copyOf(expectedExternalWriters);
  }
  static String targetToken(QuarterNativeControl.Target target){return FinalMatchEnvelope.hash(NativeCommitJournal.canonical(List.of(target.spreadsheetId(),target.datasetId(),target.purpose())));}
  private Observation check() {
    if(revoked)throw new Rejected();
    try {
      Instant now=clock.instant();Observation o=Objects.requireNonNull(source.read());
      if(lastObserved!=null&&now.isBefore(lastObserved)||o.capturedAt()==null||o.capturedAt().isAfter(now)||o.capturedAt().plus(freshness).isBefore(now))throw new Rejected();
      lastObserved=now;
      if(!binding.workspaceId().equals(o.workspaceId())||!binding.serviceId().equals(o.serviceId())||o.serviceCount()!=1||o.instances()!=1||o.autoDeploy()||o.previews()
          ||!binding.cutoverReference().equals(o.approvedCutoverReference())||!binding.approvedBaselineToken().equals(o.approvedBaselineToken()))throw new Rejected();
      if(!o.knownExternalWriters().keySet().equals(expectedExternalWriters)||o.knownExternalWriters().values().stream().anyMatch(v->v!=ExternalWriterState.STOPPED_RESTART_DISABLED&&v!=ExternalWriterState.NOT_CONFIGURED))throw new Rejected();
      var active=o.deployments().stream().filter(d->!d.state().equals("deactivated")&&!d.state().equals("failed")&&!d.state().equals("canceled")).toList();
      if(active.size()!=1||!active.get(0).id().equals(process.deploymentId())||!active.get(0).revision().equals(binding.revision())||!active.get(0).state().equals("live"))throw new Rejected();
      return o;
    }catch(RuntimeException invalid){revoked=true;throw invalid;}
  }
  /** Existing JVM, including sleep/wake. No repeated per-quarter cutover approval needed. */
  synchronized void verifyContinuity(QuarterNativeControl.Target target,String processGeneration) {
    if(!binding.target().equals(target)||!process.generation().equals(processGeneration)){revoked=true;throw new Rejected();}check();
  }
  /** New JVM is NOT continuity. Only a clean separately evidenced predecessor and exact
   * current CLOSED journal may proceed to the native unique-slot takeover protocol. */
  synchronized void verifyCleanRestart(QuarterNativeControl.Target target,Book closed,String predecessorProcess) {
    if(!binding.target().equals(target)||process.generation().equals(predecessorProcess)){revoked=true;throw new Rejected();}
    Observation o=check();
    try {
      NativeCommitJournal.verify(target.binding(),closed,clock);
      var root=NarrowQuarterPoc.root(new ArrayList<>(closed.metadata.values()));Handoff h=o.handoff();
      if(!root.state().equals("CLOSED")||h==null||h.state()!=HandoffState.CLEAN_CLOSED||!h.predecessorProcess().equals(predecessorProcess)
          ||!h.targetToken().equals(targetToken(target))||!h.closedSnapshotToken().equals(JournalSessionAuthority.fullToken(closed)))throw new Rejected();
      if(!h.predecessorProcessState().equals("terminated"))throw new Rejected();
      // Same deployment can restart its JVM: authenticated process-termination evidence
      // is required then. A deployment-level live flag cannot prove that process exited.
      if(!h.predecessorDeployment().equals(process.deploymentId()) && o.deployments().stream().noneMatch(d->d.id().equals(h.predecessorDeployment())&&d.state().equals("deactivated")))throw new Rejected();
    }catch(RuntimeException invalid){revoked=true;throw invalid;}
  }
  NativeClosedTakeover.RestartEvidence restartEvidence(String predecessorProcess,java.util.function.Supplier<Book> fullRead) {
    return (requested,closedToken,priorOwner,newOwner)->{
      if(!requested.equals(binding.target().binding())||newOwner.equals(priorOwner))throw new Rejected();
      Book fresh=fullRead.get();
      if(!JournalSessionAuthority.fullToken(fresh).equals(closedToken)||!NarrowQuarterPoc.root(new ArrayList<>(fresh.metadata.values())).owner().equals(priorOwner))throw new Rejected();
      verifyCleanRestart(binding.target(),fresh,predecessorProcess);
    };
  }
  /** Native-only OPEN predecessor proof; no inference from a lone service/OPEN marker.
   * This only enables the separate exact-slot takeover planner, never ordinary writes. */
  synchronized void verifyNativePredecessor(QuarterNativeControl.Target target,Book snapshot,String predecessorProcess,String priorOwner) {
    if(!binding.target().equals(target)||process.generation().equals(predecessorProcess))throw new Rejected();
    Observation inventory=check();
    try {
      NativePredecessor p=source.nativePredecessor();Instant now=clock.instant();
      if(p==null||p.capturedAt()==null||p.capturedAt().isAfter(now)||p.capturedAt().plus(freshness).isBefore(now)
          ||!p.cutoverReference().equals(binding.cutoverReference())||!p.targetToken().equals(targetToken(target))
          ||!p.processGeneration().equals(predecessorProcess)||!p.owner().equals(priorOwner)||!p.revision().equals(binding.revision())
          ||!p.processState().equals("terminated")||!p.protocol().equals("journal-native-only-v1")||!p.acceptedWorkState().equals("settled-or-durably-accounted")
          ||!p.excludedExternalWriters().equals(expectedExternalWriters))throw new Rejected();
      if(!p.deploymentId().equals(process.deploymentId())&&inventory.deployments().stream().noneMatch(d->d.id().equals(p.deploymentId())&&d.state().equals("deactivated")))throw new Rejected();
      NativeCommitJournal.verify(target.binding(),snapshot,clock);
      var root=NarrowQuarterPoc.root(new ArrayList<>(snapshot.metadata.values()));
      if(!root.state().equals("OPEN")||!root.owner().equals(priorOwner))throw new Rejected();
      RetentionLedger ledger=new RetentionLedger(snapshot.tables().get("retention_ledger"));
      if(!ledger.complete()||!ledger.cutoff().equals(QuarterlyRetention.currentQuarterStart(clock.instant())))throw new Rejected();
    }catch(RuntimeException invalid){revoked=true;throw invalid;}
  }
  NativeTakeoverAuthority.NativeSourceEvidence nativeTakeoverEvidence(Book exactBefore,String predecessorProcess) {
    Book captured=exactBefore.copy();String token=JournalSessionAuthority.fullToken(captured);
    return (requested,beforeToken,priorOwner,newOwner)->{
      if(!requested.manualBinding().equals(binding.target().binding())||!token.equals(beforeToken)||newOwner.equals(priorOwner))throw new Rejected();
      verifyNativePredecessor(binding.target(),captured,predecessorProcess,priorOwner);
    };
  }
  JournalSessionAuthority.ApprovedCutoverVerifier ordinaryVerifier(String exactOwner) {
    return sessionVerifier(exactOwner,binding.approvedBaselineToken());
  }
  JournalSessionAuthority.ApprovedCutoverVerifier restartOrdinaryVerifier(Book exactClosed,String predecessorProcess,String newOwner) {
    verifyCleanRestart(binding.target(),exactClosed,predecessorProcess);
    if(NarrowQuarterPoc.root(new ArrayList<>(exactClosed.metadata.values())).owner().equals(newOwner))throw new Rejected();
    return sessionVerifier(newOwner,JournalSessionAuthority.fullToken(exactClosed));
  }
  JournalSessionAuthority.ApprovedCutoverVerifier quarterOrdinaryVerifier(Book exactClosed,JournalQuarterAuthority completed,String newOwner) {
    verifyContinuity(binding.target(),process.generation());
    if(!completed.verifiedClosedToken().equals(JournalSessionAuthority.fullToken(exactClosed)))throw new Rejected();
    NativeCommitJournal.verify(binding.target().binding(),exactClosed,clock);
    if(NarrowQuarterPoc.root(new ArrayList<>(exactClosed.metadata.values())).owner().equals(newOwner))throw new Rejected();
    return sessionVerifier(newOwner,JournalSessionAuthority.fullToken(exactClosed));
  }
  JournalSessionAuthority.ApprovedCutoverVerifier takeoverOrdinaryVerifier(NativeTakeoverAuthority verifiedTakeover) {
    verifyContinuity(binding.target(),process.generation());
    var evidence=Objects.requireNonNull(verifiedTakeover).ownershipEvidence();
    if(!evidence.binding().manualBinding().equals(binding.target().binding()))throw new Rejected();
    return sessionVerifier(evidence.owner(),evidence.token());
  }
  private JournalSessionAuthority.ApprovedCutoverVerifier sessionVerifier(String exactOwner,String sessionBaseline) {
    if(exactOwner==null||exactOwner.isBlank())throw new Rejected();
    return (target,owner,revision,approval,baseline)->{
      if(!target.manualBinding().equals(binding.target().binding())||!exactOwner.equals(owner)||!binding.revision().equals(revision)||!binding.cutoverReference().equals(approval)||!sessionBaseline.equals(baseline))throw new Rejected();
      verifyContinuity(binding.target(),process.generation());
    };
  }
}
