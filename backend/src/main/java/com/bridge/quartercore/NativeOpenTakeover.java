package com.bridge.quartercore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Native-only controlled restart fencing. The external verifier must exclude legacy writers
 * and unresolved accepted work; CAS by itself does not make a volatile queue durable. */
final class NativeOpenTakeover {
  interface TakeoverSessions { Session open(NativeTakeoverAuthority authority); }
  record Session(NarrowSheetsTransport transport,Runnable release) implements AutoCloseable {
    Session {Objects.requireNonNull(transport);Objects.requireNonNull(release);}
    public void close(){release.run();}
  }
  private final GoogleSheetsBinding binding;private final Clock clock;private boolean attempted;
  NativeOpenTakeover(GoogleSheetsBinding binding,Clock clock){this.binding=Objects.requireNonNull(binding);this.clock=Objects.requireNonNull(clock);}
  synchronized NativeStoresBackend start(Supplier<Book> authorizedRead,NativeTakeoverAuthority.NativeSourceEvidence evidence,
      TakeoverSessions takeoverSessions,NativeStoresBackend.ReopenFactory ordinaryFactory,com.bridge.persistence.QuarterMaintenanceGate gate){
    if(attempted)throw new Rejected();attempted=true;
    NativeStoresBackend.Session ordinary=null;NativeTakeoverAuthority authority=null;
    try{
      if(!gate.enabled()||gate.state()!=com.bridge.persistence.QuarterMaintenanceGate.State.STARTING||gate.activeOperations()!=0)throw new Rejected();
      Book before=Objects.requireNonNull(authorizedRead.get());String owner="t-"+UUID.randomUUID();
      authority=NativeTakeoverAuthority.provision(binding,before,owner,evidence,clock,Duration.ofMinutes(5));Book exactOwned;
      try(Session session=takeoverSessions.open(authority)){
        authority.execute(session.transport());exactOwned=authority.verifiedBook();
      }finally{authority.closeUnknown();} // Single-use fencing capability is gone before ordinary writes.
      ordinary=Objects.requireNonNull(ordinaryFactory.prepare(exactOwned.copy(),owner));
      var service=ordinary.service();var tx=ordinary.transport();
      if(service.endpoint!=tx||!service.binding.equals(binding.manualBinding())||!service.owner.equals(owner)||service.state()!=State.STARTING||service.closed)throw new Rejected();
      synchronized(service.serial){
        try(var scope=tx.operation("native-takeover-adopt",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){
          Book fresh=service.full();
          if(!JournalSessionAuthority.fullToken(fresh).equals(JournalSessionAuthority.fullToken(exactOwned)))throw new Rejected();
          Root root=root(new ArrayList<>(fresh.metadata.values()));
          if(!root.state().equals("OPEN")||!root.owner().equals(owner))throw new Rejected();
          // No Service.startStoredStateOnlyForSyntheticTest and no second CLAIM. This path
          // is reachable only after this invocation's positively ACKed exact CAS readback.
          service.adoptBaseline(fresh);service.known=root;service.state=State.OPEN;
        }
      }
      return new NativeStoresBackend(service,tx,clock,gate,ordinary.release());
    }catch(RuntimeException|Error failure){
      if(authority!=null)authority.closeUnknown();
      if(ordinary!=null){ordinary.service().close();try{ordinary.release().run();}catch(RuntimeException release){failure.addSuppressed(release);}}
      gate.blockManual(com.bridge.persistence.QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw failure;
    }
  }
}
