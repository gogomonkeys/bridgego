package com.bridge.quartercore;
import java.time.*;import java.util.*;
import com.bridge.persistence.StagedNativeBackend;import com.bridge.persistence.QuarterMaintenanceGate;
import com.bridge.room.GoogleCredentialVerifier;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Explicit single-process runtime. No HTTP-auth inference, no baseline from a request, no restart takeover. */
final class PlannedCutoverRuntimeAssembly implements AutoCloseable {
 record History(String sourceCommit,String sourceHash,String approvalReference,LifetimeStatistics.Counts contribution){History{Objects.requireNonNull(contribution);NarrowQuarterPoc.uuid(approvalReference);if(sourceCommit==null||sourceCommit.isBlank()||sourceHash==null||!sourceHash.matches("[a-f0-9]{64}"))throw new Rejected();}}
 record Settings(String origin,String audience,Set<String> subjects,GoogleSheetsBinding binding,String workspace,String service,String revision,
   Set<String> legacyDeployments,Set<String> manualSources,String approvalReference,History history,QuarterRetentionPolicy.Configuration policy){
  Settings{subjects=Set.copyOf(subjects);legacyDeployments=Set.copyOf(legacyDeployments);manualSources=Set.copyOf(manualSources);Objects.requireNonNull(history);Objects.requireNonNull(binding);
   if(!binding.equals(policy.binding())||subjects.isEmpty()||subjects.size()>32||legacyDeployments.isEmpty()||legacyDeployments.size()>32||manualSources.size()>32)throw new Rejected();
   for(String s:List.of(origin,audience,workspace,service,revision,approvalReference))if(s.isBlank()||s.length()>512)throw new Rejected();
   for(String s:subjects)if(s.isBlank()||s.length()>255)throw new Rejected();for(String s:legacyDeployments)if(s.isBlank()||s.length()>256)throw new Rejected();for(String s:manualSources)if(s.isBlank()||s.length()>256)throw new Rejected();
   var u=java.net.URI.create(origin);if(!"https".equals(u.getScheme())||u.getHost()==null||!u.getRawPath().isEmpty()||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getRawUserInfo()!=null)throw new Rejected();
  }
 }
 interface IO {Book readLegacy();Book readNative();Book bootstrap(InitialCutoverAuthority authority);Book migrate(HistoricalMigrationAuthority authority);PlannedMaintenanceStartupFactory.Channels channels();}
 record Prepared(String baselineToken,Instant expires){}
 record FreshFreeze(CutoverAssessment.FreezeEvidence evidence,CutoverAssessment.FreezeVerifier verifier){}
 private final Settings settings;private final IO io;private final GoogleCredentialVerifier verifier;private final CurrentSignedObservationRelay relay;private final Clock clock;
 private final QuarterMaintenanceGate gate;private final StagedNativeBackend staged;private final SameProcessCutoverCoordinator coordinator;
 private NativeRestartContract restartContract;
 private boolean prepared,closed,prepareAttempted,activationApproved;private String deployment,baseline;private Book initial;private Instant preparedUntil,last;private long prepareDeadline;
 PlannedCutoverRuntimeAssembly(Settings settings,IO io,GoogleCredentialVerifier verifier,CurrentSignedObservationRelay relay,Clock clock){
  this.settings=Objects.requireNonNull(settings);this.io=Objects.requireNonNull(io);this.verifier=Objects.requireNonNull(verifier);this.relay=Objects.requireNonNull(relay);this.clock=Objects.requireNonNull(clock);
  gate=new QuarterMaintenanceGate(true,clock);staged=new StagedNativeBackend(gate);coordinator=new SameProcessCutoverCoordinator(settings.binding(),gate,staged);
 }
 PlannedCutoverRuntimeAssembly(Settings settings,IO io,GoogleCredentialVerifier verifier,CurrentSignedObservationRelay relay,Clock clock,NativeRestartContract restartContract){
  this(settings,io,verifier,relay,clock);this.restartContract=Objects.requireNonNull(restartContract);restartContract.requireInitialSettings(settings);
 }
 String verifyOperator(String token,String nonce){if(token==null||token.isBlank()||token.length()>12000||nonce==null||!nonce.matches("[a-f0-9]{64}"))throw new Rejected();var p=verifier.verify(token,settings.audience(),nonce);if(p==null||!"https://accounts.google.com".equals(p.issuer())||!settings.subjects().contains(p.subject()))throw new Rejected();return p.subject();}
 private synchronized void live(){if(closed)throw new Rejected();Instant now=clock.instant();if(last!=null&&now.isBefore(last)){closed=true;relay.close();throw new Rejected();}last=now;}
 private void allowed(String operator){live();if(!settings.subjects().contains(operator))throw new Rejected();}
 Prepared prepare(String verifiedOperator,String deploymentHint,Runnable scope){
  allowed(verifiedOperator);Objects.requireNonNull(scope).run();
  synchronized(this){if(prepareAttempted||deploymentHint==null||!deploymentHint.matches("dep-[A-Za-z0-9_-]{4,100}")||settings.legacyDeployments().contains(deploymentHint))throw new Rejected();prepareAttempted=true;deployment=deploymentHint;}
  try{Book b=io.readLegacy();scope.run();live();if(!b.metadata.isEmpty())throw new Rejected();var report=CutoverAssessment.inspect(settings.binding().manualBinding(),b,clock);if(report.status()!=CutoverAssessment.Status.LEGACY_BOOTSTRAP_READY)throw new Rejected();
   synchronized(this){initial=b.copy();baseline=report.baselineToken();preparedUntil=clock.instant().plusSeconds(300);prepareDeadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();prepared=true;return new Prepared(baseline,preparedUntil);}
  }catch(RuntimeException|Error e){close();throw e;}
 }
 synchronized Book preparedBook(){requirePrepared();return initial.copy();}
 synchronized Prepared prepared(){requirePrepared();return new Prepared(baseline,preparedUntil);}
 private void requirePrepared(){live();if(!prepared||!clock.instant().isBefore(preparedUntil)||System.nanoTime()>=prepareDeadline)throw new Rejected();}
 private SignedObservationControlPlane.Verified observe(Runnable scope){
  scope.run();live();String dep; synchronized(this){if(deployment==null)throw new Rejected();dep=deployment;}
  String target=FinalMatchEnvelope.hash(NativeCommitJournal.canonical(List.of(settings.binding().spreadsheetId(),settings.binding().datasetId(),settings.binding().purpose())));
  var context=new SignedObservationControlPlane.Context(UUID.randomUUID().toString(),target,settings.workspace(),settings.service(),dep,settings.revision(),settings.legacyDeployments());
  var cycle=relay.begin(context);try{var v=cycle.await();scope.run();live();return v;}catch(RuntimeException|Error e){cycle.close();throw e;}
 }
 FreshFreeze freeze(String exactBaseline,String verifiedOperator,Set<String> stoppedSources,boolean noUnknown,Runnable scope){
  allowed(verifiedOperator);if(exactBaseline==null||!exactBaseline.matches("[a-f0-9]{64}")||!settings.manualSources().equals(stoppedSources)||!noUnknown)throw new Rejected();
  var observed=observe(scope);Instant now=clock.instant();Instant expires=observed.observedAt().plusSeconds(60);long deadline=System.nanoTime()+Duration.between(now,expires).toNanos();
  var evidence=new CutoverAssessment.FreezeEvidence(settings.approvalReference(),settings.revision(),expires);
  CutoverAssessment.FreezeVerifier frozen=(target,token,e)->{scope.run();live();if(!settings.binding().manualBinding().equals(target)||!exactBaseline.equals(token)||!evidence.equals(e)||!clock.instant().isBefore(expires)||System.nanoTime()>=deadline)throw new Rejected();};
  frozen.verify(settings.binding().manualBinding(),exactBaseline,evidence);return new FreshFreeze(evidence,frozen);
 }
 Book bootstrap(InitialCutoverAuthority authority){live();Book after=io.bootstrap(authority);coordinator.acceptBootstrap(authority.verifiedResult());return after;}
 Book readNative(){live();return io.readNative();}
 void migrate(HistoricalMigrationAuthority authority){live();coordinator.executeMigrationOnly(authority,io::migrate);}
 void activationWithGuard(String verifiedOperator,String exactB2,Runnable scope){
  allowed(verifiedOperator);scope.run();if(!coordinator.migrationResult().afterToken().equals(exactB2))throw new Rejected();
  synchronized(this){if(activationApproved)throw new Rejected();activationApproved=true;}
  coordinator.activate((proof,liveGuard)->{
   var contract=new PlannedMaintenanceStartupFactory.Contract(settings.binding(),proof.afterToken(),settings.revision(),settings.approvalReference());
   PlannedMaintenanceStartupFactory.Authorization auth=c->{live();if(restartContract!=null)restartContract.requireInitialSettings(settings);if(!activationApproved||!contract.equals(c))throw new Rejected();};
   return new PlannedMaintenanceStartupFactory(contract,auth,io::readNative,io.channels(),QuarterRetentionPolicy.explicitlyApproved(settings.policy(),clock),gate,clock,
    new PlannedMaintenanceStartupFactory.Deployment(settings.workspace(),settings.service(),deployment()),()->{
     var v=observe(liveGuard);return new PlannedMaintenanceStartupFactory.Inventory(v.observedAt(),v.workspace(),v.service(),v.deployment(),v.revision(),v.services(),v.instances(),v.autoDeploy(),v.previews(),true,true);
    },liveGuard);
  },scope);
 }
 private InitialCutoverOperatorController.Outcome lastVerified;
 private record RuntimeProposal(InitialCutoverOperatorController.Stage stage,Book before,String after,Set<String> stopped,boolean noUnknown){}
 InitialCutoverOperatorController.Operations operations(){return new InitialCutoverOperatorController.Operations(){
  public String verifyOperator(String token,String nonce){return PlannedCutoverRuntimeAssembly.this.verifyOperator(token,nonce);}
  public Map<String,Object> prepare(String who,String dep,Runnable guard){var p=PlannedCutoverRuntimeAssembly.this.prepare(who,dep,guard);return Map.of("code","READ_ONLY_PREPARED","expires",p.expires().toString(),"scope","唯讀完整資料核對完成，尚未寫入");}
  public InitialCutoverOperatorController.Proposal preview(InitialCutoverOperatorController.Stage stage,String who,Set<String> stopped,boolean noUnknown,Runnable guard){
   allowed(who);guard.run();Book before;String after;Map<String,Object> details=new LinkedHashMap<>();details.put("stage",stage.name());
   switch(stage){
    case METADATA->{before=preparedBook();var p=prepared();var freeze=freeze(p.baselineToken(),who,stopped,noUnknown,guard);
     var plan=CutoverAssessment.plan(settings.binding().manualBinding(),before,p.baselineToken(),freeze.evidence(),freeze.verifier(),clock);after=JournalSessionAuthority.fullToken(plan.expected());details.put("scope","只初始化管理 metadata；不刪資料、不開放遊戲");}
    case HISTORY->{before=readNative();if(!coordinator.bootstrapResult().afterToken().equals(JournalSessionAuthority.fullToken(before)))throw new Rejected();
     freeze(JournalSessionAuthority.fullToken(before),who,stopped,noUnknown,guard);
     var plan=HistoricalCasualMigrationPlan.prepare(settings.binding().manualBinding(),before,historyApproval(before),clock);after=JournalSessionAuthority.fullToken(plan.expected());details.put("scope","僅修正已核准的歷史休閒場資格；保留原始資料與帳號");details.put("contribution",settings.history().contribution());details.put("sourceCommit",settings.history().sourceCommit());}
    case ACTIVATE->{before=readNative();after=coordinator.migrationResult().afterToken();if(!after.equals(JournalSessionAuthority.fullToken(before)))throw new Rejected();details.put("scope","啟用同程序 Google 登入、遊戲保存與已核准季度維護");details.put("deployment",deployment());details.put("revision",settings.revision());details.put("policyVersion",settings.policy().version());details.put("policyZone",settings.policy().zone());details.put("policyRules",settings.policy().rules());}
    default->throw new Rejected();
   }
   guard.run();details.put("beforeToken",stage==InitialCutoverOperatorController.Stage.METADATA?CutoverAssessment.token(settings.binding().manualBinding(),before):JournalSessionAuthority.fullToken(before));details.put("afterToken",after);
   return new InitialCutoverOperatorController.Proposal(new RuntimeProposal(stage,before.copy(),after,Set.copyOf(stopped),noUnknown),details);
  }
  public InitialCutoverOperatorController.Outcome execute(InitialCutoverOperatorController.Stage stage,String who,InitialCutoverOperatorController.Proposal proposal,Runnable guard){
   allowed(who);guard.run();if(!(proposal.opaque() instanceof RuntimeProposal p)||p.stage()!=stage)throw new Rejected();
   switch(stage){
    case METADATA->{requirePrepared();var f=freeze(baseline,who,p.stopped(),p.noUnknown(),guard);var plan=CutoverAssessment.plan(settings.binding().manualBinding(),p.before(),baseline,f.evidence(),f.verifier(),clock);
     if(!p.after().equals(JournalSessionAuthority.fullToken(plan.expected())))throw new Rejected();var a=new InitialCutoverAuthority(settings.binding(),plan,clock);
     Book after=io.bootstrap(a);var proof=a.verifiedResult();String token=JournalSessionAuthority.fullToken(after);if(!proof.afterToken().equals(token))throw new Rejected();lastVerified=new InitialCutoverOperatorController.Outcome("METADATA_VERIFIED",token);coordinator.acceptBootstrap(proof);return lastVerified;}
    case HISTORY->{var f=freeze(JournalSessionAuthority.fullToken(p.before()),who,p.stopped(),p.noUnknown(),guard);
     var check=HistoricalCasualMigrationPlan.prepare(settings.binding().manualBinding(),p.before(),historyApproval(p.before()),clock);if(!p.after().equals(JournalSessionAuthority.fullToken(check.expected())))throw new Rejected();
     var a=HistoricalMigrationAuthority.approveFromBootstrap(settings.binding(),p.before(),coordinator.bootstrapResult(),historyApproval(p.before()),true,clock,f.evidence(),f.verifier());
     coordinator.executeMigrationOnly(a,approved->{var after=io.migrate(approved);var proof=approved.verifiedResult();String token=JournalSessionAuthority.fullToken(after);if(!proof.afterToken().equals(token))throw new Rejected();lastVerified=new InitialCutoverOperatorController.Outcome("HISTORY_VERIFIED",token);return after;});return lastVerified;}
    case ACTIVATE->{activationWithGuard(who,p.after(),guard);return lastVerified=new InitialCutoverOperatorController.Outcome("ACTIVATE_VERIFIED",p.after());}
    default->throw new Rejected();
   }
  }
  public void checkpointRead(InitialCutoverOperatorController.Stage next,String who,String token,Runnable guard){
   guard.run();live();if(lastVerified==null||!lastVerified.afterToken().equals(token)||!settings.subjects().contains(who))throw new Rejected();
   String expected=switch(next){case HISTORY->coordinator.bootstrapResult().afterToken();case ACTIVATE->coordinator.migrationResult().afterToken();default->throw new Rejected();};
   if(!expected.equals(token))throw new Rejected();Book fresh=readNative();guard.run();NativeCommitJournal.verify(settings.binding().manualBinding(),fresh,clock);
   if(!JournalSessionAuthority.fullToken(fresh).equals(token))throw new Rejected();
  }
  public InitialCutoverOperatorController.Outcome lastVerified(){return lastVerified;}
 };}
 private HistoricalCasualMigrationPlan.Approval historyApproval(Book before){var h=settings.history();return new HistoricalCasualMigrationPlan.Approval(settings.binding().manualBinding(),NativeCommitJournal.digest(settings.binding().manualBinding(),before),h.sourceCommit(),h.sourceHash(),h.approvalReference(),h.contribution());}
 Settings settings(){return settings;}Clock clock(){return clock;}QuarterMaintenanceGate gate(){return gate;}StagedNativeBackend staged(){return staged;}SameProcessCutoverCoordinator coordinator(){return coordinator;}
 synchronized String deployment(){if(deployment==null)throw new Rejected();return deployment;}
 CurrentSignedObservationRelay relay(){return relay;}
 public void close(){synchronized(this){if(closed)return;closed=true;}relay.close();staged.closeAsynchronously();}
 static IO googleIO(Settings settings,JournalGoogleFactory.Configuration c){
  if(c==null||!c.enabled()||c.credentials()==null||!settings.binding().equals(new GoogleSheetsBinding(c.spreadsheetId(),c.datasetId(),c.purpose(),c.issuer())))throw new Rejected();
  return new IO(){private QuarterGoogleFactory.ReadConfiguration rc(){return new QuarterGoogleFactory.ReadConfiguration(true,c.spreadsheetId(),c.datasetId(),c.purpose(),c.issuer(),c.credentials());}
   public Book readLegacy(){try(var h=QuarterGoogleFactory.openLegacyReadOnly(rc())){return h.readVerified();}}
   public Book readNative(){try(var h=QuarterGoogleFactory.openReadOnly(rc())){return h.readVerified();}}
   public Book bootstrap(InitialCutoverAuthority a){try(var h=InitialCutoverGoogleFactory.open(c,a)){return h.execute();}}
   public Book migrate(HistoricalMigrationAuthority a){try(var h=HistoricalMigrationGoogleFactory.open(c,a)){return h.execute();}}
   public PlannedMaintenanceStartupFactory.Channels channels(){return PlannedMaintenanceStartupFactory.googleChannels(new PlannedMaintenanceStartupFactory.Contract(settings.binding(),"0".repeat(64),settings.revision(),settings.approvalReference()),c);}
  };
 }
}
