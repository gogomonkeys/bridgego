package com.bridge.quartercore;

import com.bridge.persistence.QuarterMaintenanceCoordinator;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Native protocol adapter. No credentials, endpoint, legacy writer fallback or automatic
 * permit minting. Read-only by default. CLOSED is NOT proof of ownership/exclusivity.
 * The caller must supply a separately verified source-cutover/lease implementation;
 * current legacy gameplay writers do not yet provide the necessary durable receipts.
 */
public final class QuarterNativeControl implements QuarterMaintenanceCoordinator.DurableControl {
  public record Target(String spreadsheetId,String datasetId,String purpose) {
    public Target { new ManualQuarterMaintenance.Binding(spreadsheetId,datasetId,purpose); }
    ManualQuarterMaintenance.Binding binding(){return new ManualQuarterMaintenance.Binding(spreadsheetId,datasetId,purpose);}
  }
  @FunctionalInterface public interface Reader {
    /** Exact full native response, with comments coverage; never a projected/reconstructed Book. */
    byte[] readFull(Target fixedTarget);
  }
  @FunctionalInterface public interface OwnershipVerifier {
    /** Must throw unless all writers use the guarded protocol, no other process can write,
     * and no outstanding/late unknown request exists. Must bind proof to target+snapshot token.
     * A local flag, CLOSED root, service count or prior digest alone is NOT a valid implementation.
     */
    void verify(Target target,String snapshotToken);
    static OwnershipVerifier unavailable(){return (t,s)->{throw new IllegalStateException("Writer ownership unverified");};}
  }
  @FunctionalInterface public interface CommittedSnapshotVerifier {
    /** Verify independently durable, trusted commit evidence binding this exact complete
     * snapshot (including carry, every row and receipt) to the configured target.
     * A CLOSED root, ledger flag, hash(owner), or self-computed digest is insufficient.
     * Legitimate gameplay/profile commits must update this evidence through the sole writer.
     */
    void verify(Target target,String completeSnapshotToken);
    static CommittedSnapshotVerifier unavailable(){return (t,s)->{throw new IllegalStateException("Committed snapshot evidence unavailable");};}
  }
  // Only factory code whose full snapshot path already uses the strict parser may supply this.
  @FunctionalInterface interface NativeReader { Book readVerified(); }
  @FunctionalInterface interface ArmedSessions {
    Session open(Target target,Book exactClosedBaseline,String operationId,Clock clock);
  }
  record Session(NarrowSheetsTransport transport,WritePermit permit,String owner,Runnable cleanup) implements AutoCloseable {
    Session {Objects.requireNonNull(transport);Objects.requireNonNull(permit);Objects.requireNonNull(owner);Objects.requireNonNull(cleanup);}
    public void close(){cleanup.run();}
  }
  private final Target target;private final NativeReader reader;private final OwnershipVerifier ownership;
  private final ArmedSessions sessions;private final Clock clock;
  private final CommittedSnapshotVerifier committed;
  private boolean attempted;private String completedOperation="",completedToken="";
  public QuarterNativeControl(Target target,Reader reader,Clock clock) {
    this(target,reader,OwnershipVerifier.unavailable(),clock);
  }
  public QuarterNativeControl(Target target,Reader reader,OwnershipVerifier ownership,Clock clock) {
    this(target,reader,ownership,CommittedSnapshotVerifier.unavailable(),clock);
  }
  public QuarterNativeControl(Target target,Reader reader,OwnershipVerifier ownership,CommittedSnapshotVerifier committed,Clock clock) {
    this(target,()->{
      byte[] nativeBytes=Objects.requireNonNull(reader).readFull(target);
      if(nativeBytes==null)throw new Rejected();
      return StrictNativeSheetsResource.decodeFull(new ByteArrayInputStream(nativeBytes),
          System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20),target.binding());
    },ownership,committed,null,clock);
  }
  QuarterNativeControl(Target target,NativeReader reader,OwnershipVerifier ownership,ArmedSessions sessions,Clock clock) {
    this(target,reader,ownership,CommittedSnapshotVerifier.unavailable(),sessions,clock);
  }
  QuarterNativeControl(Target target,NativeReader reader,OwnershipVerifier ownership,CommittedSnapshotVerifier committed,ArmedSessions sessions,Clock clock) {
    this.target=Objects.requireNonNull(target);this.reader=Objects.requireNonNull(reader);
    this.ownership=Objects.requireNonNull(ownership);this.committed=Objects.requireNonNull(committed);this.sessions=sessions;this.clock=Objects.requireNonNull(clock);
  }
  private Book full() {
    Book book=reader.readVerified();
    NarrowSheetsTransport snapshot=new NarrowSheetsTransport(){
      public Book snapshot(){return book.copy();}public List<Metadata> control(){return new ArrayList<>(book.metadata.values());}
      public List<List<String>> related(String t){return book.sheet(t).rows;}
      public void batch(String wire){throw new Rejected();}public boolean unsupportedStructure(){return false;}
    };
    // Reuse all dataset, account, identity, envelope, layout, budget and control validation.
    return new Service(snapshot,clock,target.binding(),"read-only",Limits.defaults()).full();
  }
  private static String token(Book book){return QuarterPlan.bookDigest(book);}
  private static void latestReceipt(Book book,Root root) {
    for(int id=2;id<root.next();id++) {
      var receipt=book.metadata.get(id);if(receipt==null)throw new Rejected();var data=parse(receipt.value());
      var keys=new HashSet<String>();data.fieldNames().forEachRemaining(keys::add);
      if(!keys.equals(Set.of("epoch","owner","operation","hash","phase"))||!data.path("epoch").isIntegralNumber()
          ||data.path("epoch").asInt()!=root.epoch()||!data.path("hash").asText().matches("[0-9a-f]{64}")
          ||!Set.of("OPEN","PENDING","APPLIED","CLOSED","ROTATE").contains(data.path("phase").asText()))throw new Rejected();
      if(!data.path("owner").isTextual()||!data.path("operation").isTextual())throw new Rejected();
      ascii(data.path("owner").asText(),40);ascii(data.path("operation").asText(),96);
      if(id>2) {
        var prior=parse(book.metadata.get(id-1).value());String phase=prior.path("phase").asText();
        if(phase.equals("PENDING")&&(!data.path("phase").asText().equals("APPLIED")
            ||!data.path("operation").asText().equals(prior.path("operation").asText())
            ||!data.path("hash").asText().equals(prior.path("hash").asText())))throw new Rejected();
        if(phase.equals("APPLIED")&&(!data.path("phase").asText().equals("OPEN")
            ||!data.path("operation").asText().equals(prior.path("operation").asText()+"-VERIFIED")))throw new Rejected();
      }
    }
    if(root.next()==2) {
      if(!root.state().equals("CLOSED")||!root.owner().isEmpty()||!root.operation().equals("BOOTSTRAP")
          ||!root.hash().equals(hash("BOOTSTRAP")))throw new Rejected();
      return;
    }
    var actual=book.metadata.get(root.next()-1);
    if(actual==null||!actual.key().equals(RECEIPT_KEY)||actual.sheet()!=null)throw new Rejected();
    var value=parse(actual.value());
    if(!value.path("phase").asText().equals(root.state())||value.path("epoch").asInt()!=root.epoch()
        ||!value.path("operation").asText().equals(root.operation())||!value.path("hash").asText().equals(root.hash()))throw new Rejected();
    // CLAIM receipts use the prior CLOSED owner's name; CLOSED receipt uses current owner.
    if(root.state().equals("CLOSED")&&!value.path("owner").asText().equals(root.owner()))throw new Rejected();
  }
  @Override public synchronized QuarterMaintenanceCoordinator.Observation inspect(Instant quarter) {
    try {
      if(!QuarterlyRetention.currentQuarterStart(clock.instant()).equals(quarter))throw new Rejected();
      Book book=full();String token=token(book);Root root=root(new ArrayList<>(book.metadata.values()));latestReceipt(book,root);
      ownership.verify(target,token); // Never inferred from the table/receipt itself.
      committed.verify(target,token); // Must survive process restart and cover all native contents.
      if(!root.state().equals("CLOSED"))return QuarterMaintenanceCoordinator.Observation.unavailable();
      RetentionLedger ledger=new RetentionLedger(book.tables().get("retention_ledger"));
      boolean compatible=true,expired=false;
      for(var envelope:ManualQuarterMaintenance.decoded(book.tables()).values())if(QuarterlyRetention.finishedAt(envelope).isBefore(quarter)) {
        expired=true;var match=envelope.payload().path("match");
        compatible&=LifetimeStatistics.eligibleForRetention(envelope);
      }
      if(!ledger.complete()||ledger.cutoff().isAfter(quarter))throw new Rejected();
      var phase=ledger.cutoff().equals(quarter)?QuarterMaintenanceCoordinator.Phase.COMPLETE:QuarterMaintenanceCoordinator.Phase.DUE;
      if(phase==QuarterMaintenanceCoordinator.Phase.COMPLETE&&expired)throw new Rejected();
      String operation=token.equals(completedToken)?completedOperation:"";
      // On restart only a clean CLOSED complete state may be observed. No execution is
      // resumed, recreated, or authorized from a surviving root/receipt.
      return new QuarterMaintenanceCoordinator.Observation(phase,ledger.cutoff(),token,operation,true,true,true,compatible);
    }catch(RuntimeException unverified){return QuarterMaintenanceCoordinator.Observation.unavailable();}
  }
  @Override public synchronized void executeOnce(Instant quarter,QuarterMaintenanceCoordinator.Observation expected,String operationId) {
    if(sessions==null||attempted||expected==null||expected.phase()!=QuarterMaintenanceCoordinator.Phase.DUE
        ||!expected.fullReadVerified()||!expected.ownershipVerified()||!expected.receiptsVerified()
        ||!expected.historicalRulesCompatible()||!QuarterlyRetention.currentQuarterStart(clock.instant()).equals(quarter))throw new Rejected();
    uuid(operationId);Book baseline=full();String digest=token(baseline);
    if(!digest.equals(expected.token()))throw new Rejected();ownership.verify(target,digest);committed.verify(target,digest);
    Root before=root(new ArrayList<>(baseline.metadata.values()));latestReceipt(baseline,before);
    if(!before.state().equals("CLOSED"))throw new Rejected();
    attempted=true; // A local attempted operation cannot be retried, even after exceptions.
    String owner="q-"+operationId;
    try(Session session=sessions.open(target,baseline.copy(),operationId,clock)) {
      if(!owner.equals(session.owner()))throw new Rejected();
      Service service=new Service(session.transport(),clock,target.binding(),owner,Limits.defaults());
      try {
        session.permit().bindQuarter(service);
        try(var scope=session.transport().operation("quarter",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){service.start();}
        if(service.state()!=State.OPEN||service.quarterSummary()==null||session.permit().status()!=WritePermit.Status.READY)throw new Rejected();
        try(var scope=session.transport().operation("quarter-close",service.known,BatchedSheetsTransport.ALL,Duration.ofSeconds(30))){service.cleanClose();}
        if(service.state()!=State.CLOSED||session.permit().status()!=WritePermit.Status.FINISHED)throw new Rejected();
        Book after=full();Root finalRoot=root(new ArrayList<>(after.metadata.values()));latestReceipt(after,finalRoot);
        if(!finalRoot.equals(service.known)||!finalRoot.owner().equals(owner))throw new Rejected();
        ownership.verify(target,token(after));committed.verify(target,token(after));
        completedOperation=operationId;completedToken=token(after);
      }finally{service.close();}
    }
  }
}
