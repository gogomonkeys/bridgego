package com.bridge.quartercore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Controlled clean restart only. No public provisioning, Spring registration, credential
 * lookup, retry, OPEN adoption or inference of ownership from a Sheet marker. */
final class NativeClosedTakeover {
  @FunctionalInterface interface RestartEvidence {
    /** Independently prove acknowledged clean handoff of this exact snapshot, stopped old
     * writers and no unresolved dispatch; deployment-deactivated alone is insufficient. */
    void requireCleanHandoff(ManualQuarterMaintenance.Binding binding,String closedToken,String priorOwner,String newOwner);
  }
  private final ManualQuarterMaintenance.Binding binding;private final Clock clock;
  private boolean attempted;
  NativeClosedTakeover(ManualQuarterMaintenance.Binding binding,Clock clock){this.binding=Objects.requireNonNull(binding);this.clock=Objects.requireNonNull(clock);}
  synchronized NativeStoresBackend start(Supplier<Book> authorizedRead,RestartEvidence evidence,
      NativeStoresBackend.ReopenFactory factory,com.bridge.persistence.QuarterMaintenanceGate gate){
    if(attempted)throw new Rejected();attempted=true;
    NativeStoresBackend.Session candidate=null;
    try{
      if(!gate.enabled()||gate.state()!=com.bridge.persistence.QuarterMaintenanceGate.State.STARTING||gate.activeOperations()!=0)throw new Rejected();
      Book closed=Objects.requireNonNull(authorizedRead.get());NativeCommitJournal.verify(binding,closed,clock);
      var old=root(new ArrayList<>(closed.metadata.values()));
      // Due CLOSED needs the separately authorized maintenance path before ordinary admission.
      if(!old.state().equals("CLOSED")||JournalQuarterProtocol.inspect(binding,closed,clock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();
      String owner="r-"+UUID.randomUUID(),token=JournalSessionAuthority.fullToken(closed);
      Objects.requireNonNull(evidence).requireCleanHandoff(binding,token,old.owner(),owner);
      candidate=Objects.requireNonNull(factory.prepare(closed.copy(),owner));
      var service=candidate.service();var tx=candidate.transport();
      if(service.endpoint!=tx||!service.binding.equals(binding)||!service.owner.equals(owner)||service.state()!=State.STARTING||service.closed)throw new Rejected();
      try(var scope=tx.operation("clean-restart-claim",old,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){
        Book fresh=tx.snapshot();if(!JournalSessionAuthority.fullToken(fresh).equals(token))throw new Rejected();
        evidence.requireCleanHandoff(binding,token,old.owner(),owner);
        service.start();Book after=tx.snapshot();Root current=root(new ArrayList<>(after.metadata.values()));
        if(service.state()!=State.OPEN||!current.equals(service.known)||!current.owner().equals(owner)||!current.state().equals("OPEN")||!closed.tables().equals(after.tables()))throw new Rejected();
      }
      // Coordinator still performs its normal inspection before opening admission.
      return new NativeStoresBackend(service,tx,clock,gate,candidate.release());
    }catch(RuntimeException|Error failure){
      if(candidate!=null){candidate.service().close();try{candidate.release().run();}catch(RuntimeException close){failure.addSuppressed(close);}}
      gate.blockManual(com.bridge.persistence.QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw failure;
    }
  }
}
