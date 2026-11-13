package com.bridge.quartercore;
import java.time.*;import java.util.*;
import com.bridge.persistence.StagedNativeBackend;
import com.bridge.persistence.QuarterMaintenanceGate;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Same-process, fixed-baseline startup only. No restart takeover, default bean or deployment configuration. */
final class PlannedMaintenanceStartupFactory {
 record Contract(GoogleSheetsBinding binding,String baseline,String revision,String approvalReference){
  Contract{Objects.requireNonNull(binding);if(baseline==null||!baseline.matches("[a-f0-9]{64}")||revision==null||revision.isBlank()||approvalReference==null||approvalReference.isBlank())throw new Rejected();}
 }
 /** Trusted initial cutover authorization and excluded-source evidence, not a caller flag.
  * Must be bounded/local, with revocation; no network or calls back into writer locks.
  * The installation workflow must not reuse a consumed unknown attempt in a new JVM. */
 @FunctionalInterface interface Authorization {void verify(Contract fixed);}
 record Deployment(String workspace,String service,String deployment){Deployment{for(String s:List.of(workspace,service,deployment))if(s.isBlank())throw new Rejected();}}
 record Inventory(Instant observedAt,String workspace,String service,String deployment,String revision,int services,int instances,boolean autoDeploy,boolean previews,boolean live,boolean predecessorsInactive){}
 @FunctionalInterface interface InventorySource {Inventory read();default Inventory fresh(){return read();}}
 @FunctionalInterface interface Reader {Book read();}
 record Channel(NarrowSheetsTransport transport,Runnable release) implements AutoCloseable{
  Channel{Objects.requireNonNull(transport);Objects.requireNonNull(release);}public void close(){release.run();}
 }
 interface Channels {Channel ordinary(JournalSessionAuthority authority);Channel quarter(JournalQuarterAuthority authority);}
 static Channels googleChannels(Contract fixed,JournalGoogleFactory.Configuration c){
  if(c==null||!c.enabled()||c.credentials()==null||!fixed.binding().equals(new GoogleSheetsBinding(c.spreadsheetId(),c.datasetId(),c.purpose(),c.issuer())))throw new Rejected();
  return new Channels(){public Channel ordinary(JournalSessionAuthority a){var h=JournalGoogleFactory.open(c,a);return new Channel(h.transport(),h::close);}public Channel quarter(JournalQuarterAuthority a){var h=JournalQuarterGoogleFactory.open(c,a);return new Channel(h.transport(),h::close);}};
 }
 private final Contract contract;private final Authorization authorization;private final Reader reader;private final Channels channels;
 private final QuarterRetentionPolicy policy;private final QuarterMaintenanceGate gate;private final Clock clock;
 private boolean attempted,failed,owned;private Instant last;private Deployment deployment;private InventorySource inventory;private Runnable initialGuard=()->{};private Instant evidenceExpires;private long evidenceDeadline;
 PlannedMaintenanceStartupFactory(Contract contract,Authorization authorization,Reader reader,Channels channels,QuarterRetentionPolicy policy,QuarterMaintenanceGate gate,Clock clock){
  this.contract=Objects.requireNonNull(contract);this.authorization=Objects.requireNonNull(authorization);this.reader=Objects.requireNonNull(reader);this.channels=Objects.requireNonNull(channels);this.policy=Objects.requireNonNull(policy);this.gate=Objects.requireNonNull(gate);this.clock=Objects.requireNonNull(clock);policy.requireBinding(contract.binding());
 }
 /** Production initial-observation path; observations and approval are not renewed per quarter. */
 PlannedMaintenanceStartupFactory(Contract contract,Authorization authorization,Reader reader,Channels channels,QuarterRetentionPolicy policy,QuarterMaintenanceGate gate,Clock clock,Deployment deployment,InventorySource inventory,Runnable initialGuard){
  this(contract,authorization,reader,channels,policy,gate,clock);this.deployment=Objects.requireNonNull(deployment);this.inventory=Objects.requireNonNull(inventory);this.initialGuard=Objects.requireNonNull(initialGuard);
 }
 private void initialObservation(){
  continuity();if(inventory==null)return;
  Instant before=clock.instant();Inventory i=inventory.fresh(); // Never hold factory monitor across external I/O.
  synchronized(this){continuity();Instant after=clock.instant();
   if(after.isBefore(before)||i==null||i.observedAt()==null||i.observedAt().isAfter(after)||!after.isBefore(i.observedAt().plusSeconds(60))||!deployment.workspace().equals(i.workspace())||!deployment.service().equals(i.service())||!deployment.deployment().equals(i.deployment())||!contract.revision().equals(i.revision())||i.services()!=1||i.instances()!=1||i.autoDeploy()||i.previews()||!i.live()||!i.predecessorsInactive())throw new Rejected();
   evidenceExpires=i.observedAt().plusSeconds(60);evidenceDeadline=System.nanoTime()+Duration.between(after,evidenceExpires).toNanos();
  }
 }
 void requireCompletionBinding(GoogleSheetsBinding binding,String baseline,QuarterMaintenanceGate expectedGate){
  if(!contract.binding().equals(binding)||!contract.baseline().equals(baseline)||gate!=expectedGate)throw new Rejected();
 }
 private synchronized void continuity(){if(failed)throw new Rejected();try{authorization.verify(contract);if(!owned)initialGuard.run();Instant now=clock.instant();if(last!=null&&now.isBefore(last))throw new Rejected();if(!owned&&evidenceExpires!=null&&(!now.isBefore(evidenceExpires)||System.nanoTime()>=evidenceDeadline))throw new Rejected();last=now;}catch(RuntimeException|Error e){failed=true;throw e;}}
 void activate(StagedNativeBackend staged){
  synchronized(this){if(attempted||failed||!staged.inertRoutingSafe()||!staged.belongsTo(gate)||!gate.enabled()||gate.state()!=QuarterMaintenanceGate.State.STARTING)throw new Rejected();attempted=true;}
  try{initialObservation();Book before=reader.read();continuity();NativeCommitJournal.verify(contract.binding().manualBinding(),before,clock);
   if(!contract.baseline().equals(JournalSessionAuthority.fullToken(before))||JournalQuarterProtocol.inspect(contract.binding().manualBinding(),before,clock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();
   staged.activateOnce(()->assemble(before));
  }catch(RuntimeException|Error e){synchronized(this){failed=true;}gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw e;}
 }
 private NativeStoresBackend.Session ordinary(Book baseline,String owner){
  String token=JournalSessionAuthority.fullToken(baseline);var grant=JournalSessionAuthority.provision(contract.binding(),baseline,owner,contract.revision(),contract.approvalReference(),
   (b,o,r,a,t)->{continuity();if(!contract.binding().equals(b)||!owner.equals(o)||!contract.revision().equals(r)||!contract.approvalReference().equals(a)||!token.equals(t))throw new Rejected();},clock,Duration.ofHours(1));
  Channel ch=null;try{ch=channels.ordinary(grant);var tx=new JournaledNativeTransport(ch.transport(),contract.binding().manualBinding(),clock);return new NativeStoresBackend.Session(new Service(tx,clock,contract.binding().manualBinding(),owner,Limits.defaults()),tx,ch.release());}
  catch(RuntimeException|Error e){grant.close();if(ch!=null)ch.close();throw e;}
 }
 private StagedNativeBackend.Activation assemble(Book before){
  NativeStoresBackend backend=null;NativeStoresBackend.Session first=null;
  try{continuity();first=ordinary(before,"w-"+UUID.randomUUID());
   try(var scope=first.transport().operation("planned-claim",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){
    if(!contract.baseline().equals(JournalSessionAuthority.fullToken(first.transport().snapshot())))throw new Rejected();
    first.service().start();Book claimed=first.transport().snapshot();
    if(first.service().state()!=State.OPEN||!root(new ArrayList<>(claimed.metadata.values())).equals(first.service().known))throw new Rejected();
    continuity();policy.bindClaimedProcess(this,contract.binding(),claimed);synchronized(this){owned=true;}
   }
   backend=new NativeStoresBackend(first.service(),first.transport(),clock,gate,first.release());
   var target=new QuarterNativeControl.Target(contract.binding().spreadsheetId(),contract.binding().datasetId(),contract.binding().purpose());
   final JournalQuarterAuthority[] completed={null};
   NativeQuarterLifecycle.QuarterSessions sessions=(t,closed,op)->{
    continuity();synchronized(this){if(!owned)throw new Rejected();}
    if(!target.equals(t)||gate.state()!=QuarterMaintenanceGate.State.MAINTENANCE||gate.activeOperations()!=0)throw new Rejected();
    var approved=policy.operation(this,contract.binding(),closed,"q-"+op,op);
    var grant=JournalQuarterAuthority.provision(t.binding(),closed,"q-"+op,op,(b,q,token,owner,id)->{continuity();approved.verify(b,q,token,owner,id);},clock,Duration.ofMinutes(5));
    Channel ch;try{ch=channels.quarter(grant);}catch(RuntimeException|Error e){grant.unknown();throw e;}completed[0]=grant;return new NativeQuarterLifecycle.Session(ch.transport(),grant,ch.release());
   };
   var lifecycle=NativeQuarterLifecycle.assemble(target,backend,gate,(t,token)->{continuity();if(!target.equals(t))throw new Rejected();},sessions,(closed,owner)->{
    continuity();var proof=completed[0];if(proof==null||!proof.verifiedClosedToken().equals(JournalSessionAuthority.fullToken(closed)))throw new Rejected();
    var next=ordinary(closed,owner);completed[0]=null;return next;
   },()->{},clock);
   return new StagedNativeBackend.Activation(backend,lifecycle);
  }catch(RuntimeException|Error e){synchronized(this){failed=true;}if(backend!=null)backend.close();else if(first!=null){first.service().close();first.release().run();}throw e;}
 }
}
