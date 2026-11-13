package com.bridge.quartercore;

import com.bridge.persistence.QuarterMaintenanceCoordinator;
import com.bridge.persistence.QuarterMaintenanceGate;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** One selected writer lifecycle, not a second persistence source. No default authority,
 * session factory or Google endpoint is installed. Unknown handoff is terminal. */
public final class NativeQuarterLifecycle implements QuarterMaintenanceCoordinator.DurableControl {
  interface WriterLifecycle {
    Book lifecycleSnapshot();
    boolean lifecycleOpen();
    Book cleanCloseForQuarter();
    void retireClosedWriter();
    Book reopenAfterVerifiedQuarter(Book exactClosed);
  }
  @FunctionalInterface interface QuarterSessions {
    /** Separate maintenance authorization bound to this exact CLOSED baseline and operation.
     * Ordinary writer authority cannot be reused to authorize deletion. */
    Session open(QuarterNativeControl.Target target,Book exactClosed,String operationId);
  }
  record Session(NarrowSheetsTransport transport,JournalQuarterAuthority authority,Runnable cleanup) implements AutoCloseable {
    Session {Objects.requireNonNull(transport);Objects.requireNonNull(authority);Objects.requireNonNull(cleanup);}
    public void close(){cleanup.run();}
  }
  private final QuarterNativeControl.Target target;private final WriterLifecycle writer;
  private final QuarterMaintenanceGate gate;private final QuarterNativeControl.OwnershipVerifier ownership;
  private final QuarterSessions sessions;private final Clock clock;
  private boolean blocked;private Instant attemptedQuarter;private String completedOperation="",completedToken="";
  NativeQuarterLifecycle(QuarterNativeControl.Target target,WriterLifecycle writer,QuarterMaintenanceGate gate,
      QuarterNativeControl.OwnershipVerifier ownership,QuarterSessions sessions,Clock clock) {
    this.target=Objects.requireNonNull(target);this.writer=Objects.requireNonNull(writer);this.gate=Objects.requireNonNull(gate);
    this.ownership=Objects.requireNonNull(ownership);this.sessions=sessions;this.clock=Objects.requireNonNull(clock);
  }
  static NativeQuarterLifecycle assemble(QuarterNativeControl.Target target,NativeStoresBackend backend,
      QuarterMaintenanceGate gate,QuarterNativeControl.OwnershipVerifier ownership,QuarterSessions sessions,
      NativeStoresBackend.ReopenFactory reopen,Runnable retireOrdinarySession,Clock clock) {
    Objects.requireNonNull(backend);Objects.requireNonNull(reopen);Objects.requireNonNull(retireOrdinarySession);
    return new NativeQuarterLifecycle(target,new WriterLifecycle(){
      public Book lifecycleSnapshot(){return backend.lifecycleSnapshot();}
      public boolean lifecycleOpen(){return backend.lifecycleOpen();}
      public Book cleanCloseForQuarter(){return backend.cleanCloseForQuarter();}
      public void retireClosedWriter(){backend.retireClosedWriter();retireOrdinarySession.run();}
      public Book reopenAfterVerifiedQuarter(Book exactClosed){return backend.reopenAfterVerifiedQuarter(exactClosed,reopen);}
    },gate,ownership,sessions,clock);
  }
  static String token(Book book){return hash(NativeCommitJournal.canonical(book.nativeValue()));}
  private Book verify(Book book,Instant quarter) {
    if(!QuarterlyRetention.currentQuarterStart(clock.instant()).equals(quarter))throw new Rejected();
    NativeCommitJournal.verify(target.binding(),book,clock);ownership.verify(target,token(book));return book;
  }
  private QuarterMaintenanceCoordinator.Observation observation(Book book,Instant quarter) {
    verify(book,quarter);
    Root root=root(new ArrayList<>(book.metadata.values()));
    if(!writer.lifecycleOpen()||!root.state().equals("OPEN"))throw new Rejected();
    RetentionLedger ledger=new RetentionLedger(book.tables().get("retention_ledger"));
    if(!ledger.complete()||ledger.cutoff().isAfter(quarter))throw new Rejected();
    boolean compatible=true,expired=false;
    for(var e:ManualQuarterMaintenance.decoded(book.tables()).values())if(QuarterlyRetention.finishedAt(e).isBefore(quarter)) {
      expired=true;var match=e.payload().path("match");
      compatible&=LifetimeStatistics.eligibleForRetention(e);
    }
    var phase=ledger.cutoff().equals(quarter)?QuarterMaintenanceCoordinator.Phase.COMPLETE:QuarterMaintenanceCoordinator.Phase.DUE;
    if(phase==QuarterMaintenanceCoordinator.Phase.COMPLETE&&expired)throw new Rejected();
    String digest=token(book);
    return new QuarterMaintenanceCoordinator.Observation(phase,ledger.cutoff(),digest,digest.equals(completedToken)?completedOperation:"",true,true,true,compatible);
  }
  @Override public synchronized QuarterMaintenanceCoordinator.Observation inspect(Instant quarter) {
    if(blocked)return QuarterMaintenanceCoordinator.Observation.unavailable();
    try{return observation(writer.lifecycleSnapshot(),quarter);}catch(RuntimeException unknown){blocked=true;return QuarterMaintenanceCoordinator.Observation.unavailable();}
  }
  @Override public synchronized void executeOnce(Instant quarter,QuarterMaintenanceCoordinator.Observation expected,String operationId) {
    if(blocked||quarter.equals(attemptedQuarter)||sessions==null||gate.state()!=QuarterMaintenanceGate.State.MAINTENANCE||gate.activeOperations()!=0)throw new Rejected();
    uuid(operationId);
    Book before=writer.lifecycleSnapshot();var fresh=observation(before,quarter);
    if(expected==null||!fresh.equals(expected)||fresh.phase()!=QuarterMaintenanceCoordinator.Phase.DUE||!fresh.historicalRulesCompatible())throw new Rejected();
    attemptedQuarter=quarter;
    try {
      Book closed=verify(writer.cleanCloseForQuarter(),quarter);
      if(!before.tables().equals(closed.tables())||!root(new ArrayList<>(closed.metadata.values())).state().equals("CLOSED"))throw new Rejected();
      String closedToken=token(closed);
      writer.retireClosedWriter();
      try(Session session=sessions.open(target,closed.copy(),operationId)) {
        try(var scope=session.transport().operation("quarter-lifecycle-baseline",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))) {
          if(!token(verify(session.transport().snapshot(),quarter)).equals(closedToken))throw new Rejected();
        }
        session.authority().requireContext(target.binding(),closed,"q-"+operationId,operationId);
        JournalQuarterProtocol protocol=new JournalQuarterProtocol(target.binding(),session.transport(),clock,"q-"+operationId,session.authority());
        protocol.start();if(protocol.status()!=JournalQuarterProtocol.Status.VERIFIED)throw new Rejected();
        Book after;
        try(var scope=session.transport().operation("quarter-lifecycle-readback",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))) {
          after=verify(session.transport().snapshot(),quarter);
          if(JournalQuarterProtocol.inspect(target.binding(),after,clock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();
        }
        // Maintenance transport/capability must be closed before reauthorizing the ordinary writer.
        closed=after;
      }
      Book reopened=verify(writer.reopenAfterVerifiedQuarter(closed.copy()),quarter);
      if(!closed.tables().equals(reopened.tables()))throw new Rejected();
      var result=observation(reopened,quarter);
      if(result.phase()!=QuarterMaintenanceCoordinator.Phase.COMPLETE)throw new Rejected();
      completedOperation=operationId;completedToken=token(reopened);
    }catch(RuntimeException uncertain){blocked=true;throw uncertain;}
  }
}
