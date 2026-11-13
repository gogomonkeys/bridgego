package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.SingleServiceEvidenceProvider.*;
class SingleServiceEvidenceProviderTest {
  static final Clock CLOCK=JournalQuarterProtocolTest.CLOCK;
  static final QuarterNativeControl.Target TARGET=new QuarterNativeControl.Target(RecoveredFixture.ID,RecoveredFixture.DATASET,JournalQuarterProtocolTest.BINDING.purpose());
  static final Set<String> WRITERS=Set.of("operator-kit","erasure-maintenance","experiment");
  static Binding binding(){return new Binding(TARGET,"workspace","service","native-revision","approved-cutover","a".repeat(64));}
  static Observation observation(Handoff handoff){return new Observation(CLOCK.instant(),"workspace","service",1,1,false,false,
    List.of(new Deployment("current","native-revision","live"),new Deployment("previous","legacy-revision","deactivated")),
    Map.of("operator-kit",ExternalWriterState.NOT_CONFIGURED,"erasure-maintenance",ExternalWriterState.STOPPED_RESTART_DISABLED,"experiment",ExternalWriterState.NOT_CONFIGURED),
    "approved-cutover","a".repeat(64),handoff);}
  static SingleServiceEvidenceProvider provider(Source source){return new SingleServiceEvidenceProvider(binding(),source,CLOCK,new SingleServiceEvidenceProvider.Process("new-jvm","current"),Duration.ofMinutes(1),WRITERS);}
  @Test void verifiedSameProcessContinuityDoesNotRequireRepeatedQuarterHandoff() {
    var p=provider(()->observation(null));for(int i=0;i<3;i++)assertDoesNotThrow(()->p.verifyContinuity(TARGET,"new-jvm"));
    assertThrows(RuntimeException.class,()->p.verifyContinuity(TARGET,"other-jvm"));
    assertThrows(RuntimeException.class,()->p.verifyContinuity(TARGET,"new-jvm"));
  }
  @Test void staleOverlapMissingWritersAndWrongBaselineRevoke() {
    var good=observation(null);
    var bad=List.of(
      new Observation(CLOCK.instant().minusSeconds(61),good.workspaceId(),good.serviceId(),1,1,false,false,good.deployments(),good.knownExternalWriters(),good.approvedCutoverReference(),good.approvedBaselineToken(),null),
      new Observation(CLOCK.instant(),good.workspaceId(),good.serviceId(),1,1,false,false,List.of(new Deployment("current","native-revision","live"),new Deployment("rolling","native-revision","update_in_progress")),good.knownExternalWriters(),good.approvedCutoverReference(),good.approvedBaselineToken(),null),
      new Observation(CLOCK.instant(),good.workspaceId(),good.serviceId(),1,1,false,false,good.deployments(),Map.of(),good.approvedCutoverReference(),good.approvedBaselineToken(),null),
      new Observation(CLOCK.instant(),good.workspaceId(),good.serviceId(),1,1,false,false,good.deployments(),good.knownExternalWriters(),good.approvedCutoverReference(),"b".repeat(64),null));
    for(var observation:bad){Observation[] source={observation};var p=provider(()->source[0]);assertThrows(RuntimeException.class,()->p.verifyContinuity(TARGET,"new-jvm"));source[0]=good;assertThrows(RuntimeException.class,()->p.verifyContinuity(TARGET,"new-jvm"));}
    assertThrows(RuntimeException.class,()->provider(Source.unavailable()).verifyContinuity(TARGET,"new-jvm"));
  }
  @Test void cleanRestartNeedsExactSnapshotAndSeparateTerminatedPredecessorEvidence()throws Exception {
    var book=JournalQuarterProtocolTest.baseline();
    var actualTarget=new QuarterNativeControl.Target(RecoveredFixture.ID,RecoveredFixture.DATASET,JournalQuarterProtocolTest.BINDING.purpose());
    // Fixture binding uses its exact configured purpose; no inferred target.
    var b=new Binding(actualTarget,"workspace","service","native-revision","approved-cutover","a".repeat(64));
    Handoff handoff=new Handoff("previous","old-jvm",targetToken(actualTarget),JournalSessionAuthority.fullToken(book),"terminated",HandoffState.CLEAN_CLOSED);
    var p=new SingleServiceEvidenceProvider(b,()->observation(handoff),CLOCK,new SingleServiceEvidenceProvider.Process("new-jvm","current"),Duration.ofMinutes(1),WRITERS);
    assertDoesNotThrow(()->p.verifyCleanRestart(actualTarget,book,"old-jvm"));
    var unknown=new SingleServiceEvidenceProvider(b,()->observation(null),CLOCK,new SingleServiceEvidenceProvider.Process("new-jvm","current"),Duration.ofMinutes(1),WRITERS);
    assertThrows(RuntimeException.class,()->unknown.verifyCleanRestart(actualTarget,book,"old-jvm"));
    var changed=book.copy();changed.sheet("accounts").rows.get(1).set(2,"changed");assertThrows(RuntimeException.class,()->p.verifyCleanRestart(actualTarget,changed,"old-jvm"));
  }
  @Test void verifiedTakeoverCanDeriveOrdinaryVerifierAfterCapabilityIsClosed()throws Exception {
    var before=NativeTakeoverAuthorityTest.openBook();var google=JournalSessionAuthorityTest.BINDING;
    var authority=NativeTakeoverAuthority.provision(google,before,"next-owner",(b,t,o,n)->{},JournalSessionAuthorityTest.CLOCK,Duration.ofMinutes(5));
    var memory=new JournalQuarterProtocolTest.Memory(before){@Override public void batch(String wire){
      var dispatch=authority.beforeDispatch(google.path()+":batchUpdate",wire);super.batch(wire);dispatch.acknowledged();}};
    try(var handle=new JournalQuarterGoogleFactory.Handle(memory,()->{},authority,google)){authority.execute(handle.transport());}
    assertThrows(RuntimeException.class,authority::verifiedBook);
    var target=new QuarterNativeControl.Target(google.spreadsheetId(),google.datasetId(),google.purpose());
    var bound=new Binding(target,"workspace","service","native-revision","approved-cutover","a".repeat(64));
    var p=new SingleServiceEvidenceProvider(bound,()->observation(null),CLOCK,new SingleServiceEvidenceProvider.Process("new-jvm","current"),Duration.ofMinutes(1),WRITERS);
    var verifier=p.takeoverOrdinaryVerifier(authority);String token=JournalSessionAuthority.fullToken(memory.book);
    assertDoesNotThrow(()->verifier.verify(google,"next-owner","native-revision","approved-cutover",token));
    assertThrows(RuntimeException.class,()->verifier.verify(google,"fake-owner","native-revision","approved-cutover",token));
    assertThrows(RuntimeException.class,()->verifier.verify(google,"next-owner","native-revision","approved-cutover","b".repeat(64)));
    assertDoesNotThrow(()->JournalSessionAuthority.provision(google,memory.book,"next-owner","native-revision","approved-cutover",verifier,JournalSessionAuthorityTest.CLOCK,Duration.ofMinutes(5)));
  }
  @Test void ordinaryVerifierBindsExactOwnerAndBaseline() {
    var p=provider(()->observation(null));var verifier=p.ordinaryVerifier("owner-one");
    var google=new GoogleSheetsBinding(TARGET.spreadsheetId(),TARGET.datasetId(),TARGET.purpose(),"svc@test.iam.gserviceaccount.com");
    assertDoesNotThrow(()->verifier.verify(google,"owner-one","native-revision","approved-cutover","a".repeat(64)));
    assertThrows(RuntimeException.class,()->verifier.verify(google,"another","native-revision","approved-cutover","a".repeat(64)));
    assertThrows(RuntimeException.class,()->verifier.verify(google,"owner-one","native-revision","approved-cutover","b".repeat(64)));
  }
  @Test void nativePredecessorCannotInferEmptyQueueFromTermination()throws Exception {
    var memory=new JournalQuarterProtocolTest.Memory(JournalQuarterProtocolTest.baseline());
    var quarter=JournalQuarterProtocolTest.protocol(memory);quarter.start();
    var closed=memory.book;var r=NarrowQuarterPoc.root(new ArrayList<>(closed.metadata.values()));
    var next=new NarrowQuarterPoc.Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,"old-owner","OPEN","CLAIM-old-owner",NarrowQuarterPoc.hash("old-owner"));
    var open=NativeCommitJournal.append(TARGET.binding(),closed,QuarterPlan.phaseWire(r,next,List.of()),CLOCK).expected();
    for(String work:List.of("settled-or-durably-accounted","unknown")) {
      Source source=new Source(){public Observation read(){return observation(null);}
        public NativePredecessor nativePredecessor(){return new NativePredecessor(CLOCK.instant(),"approved-cutover",targetToken(TARGET),"previous","old-jvm","old-owner","native-revision","terminated","journal-native-only-v1",work,WRITERS);}};
      var p=provider(source);
      if(work.equals("unknown"))assertThrows(RuntimeException.class,()->p.verifyNativePredecessor(TARGET,open,"old-jvm","old-owner"));
      else assertDoesNotThrow(()->p.verifyNativePredecessor(TARGET,open,"old-jvm","old-owner"));
    }
  }
  @Test void sameDeploymentJvmRestartRequiresProcessTerminationNotDeploymentLiveAlone()throws Exception {
    var book=JournalQuarterProtocolTest.baseline();var target=new QuarterNativeControl.Target(RecoveredFixture.ID,RecoveredFixture.DATASET,JournalQuarterProtocolTest.BINDING.purpose());
    var b=new Binding(target,"workspace","service","native-revision","approved-cutover","a".repeat(64));
    for(String state:List.of("terminated","running","unknown")) {
      Handoff h=new Handoff("current","old-jvm",targetToken(target),JournalSessionAuthority.fullToken(book),state,HandoffState.CLEAN_CLOSED);
      var p=new SingleServiceEvidenceProvider(b,()->observation(h),CLOCK,new SingleServiceEvidenceProvider.Process("new-jvm","current"),Duration.ofMinutes(1),WRITERS);
      if(state.equals("terminated"))assertDoesNotThrow(()->p.verifyCleanRestart(target,book,"old-jvm"));else assertThrows(RuntimeException.class,()->p.verifyCleanRestart(target,book,"old-jvm"));
    }
  }
}
