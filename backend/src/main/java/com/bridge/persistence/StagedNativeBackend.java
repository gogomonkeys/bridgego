package com.bridge.persistence;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
/** Explicit trusted startup-factory result only. Inert until one independently verified
 * activation; no credentials, network, env provisioning or legacy fallback. */
public final class StagedNativeBackend implements NativePersistenceBackend,NativeOrderlyShutdown {
  public record Activation(NativePersistenceBackend backend,QuarterMaintenanceCoordinator.DurableControl lifecycle) {
    public Activation {Objects.requireNonNull(backend);Objects.requireNonNull(lifecycle);}
  }
  private enum State { WAITING, ACTIVATING, ATTACHED, FAILED, CLOSED }
  private final QuarterMaintenanceGate gate;private State state=State.WAITING;private Activation activation;
  public StagedNativeBackend(QuarterMaintenanceGate gate){
    this.gate=Objects.requireNonNull(gate);if(!gate.enabled()||gate.state()!=QuarterMaintenanceGate.State.STARTING||gate.activeOperations()!=0)throw new IllegalStateException("Staged startup requires closed admission");
  }
  public boolean belongsTo(QuarterMaintenanceGate expected){return gate==expected;}
  public synchronized boolean maintenanceRoutingSafe(){return state!=State.CLOSED&&gate.enabled()&&!gate.isOpen();}
  public synchronized boolean inertRoutingSafe(){return state==State.WAITING&&activation==null&&gate.state()==QuarterMaintenanceGate.State.STARTING&&gate.activeOperations()==0;}
  /** Caller may poll authenticated read-only deployment evidence while WAITING. Invoke only
   * after predecessor exclusion is proven. Any activation exception is terminal, never retry. */
  public void activateOnce(Supplier<Activation> independentlyVerifiedFactory){
    synchronized(this){if(!inertRoutingSafe())throw new IllegalStateException("Staged startup unavailable");state=State.ACTIVATING;}
    Activation created=null;
    try{
      created=Objects.requireNonNull(independentlyVerifiedFactory.get());
      synchronized(this){
        if(state!=State.ACTIVATING||created.backend()==this||created.backend() instanceof StagedNativeBackend||gate.state()!=QuarterMaintenanceGate.State.STARTING||gate.activeOperations()!=0)throw new IllegalStateException("Invalid staged activation");
        activation=created;state=State.ATTACHED;
      }
    }catch(RuntimeException|Error failure){
      synchronized(this){if(state!=State.CLOSED)state=State.FAILED;}
      if(created!=null)try{created.backend().close();}catch(RuntimeException close){failure.addSuppressed(close);}
      gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw failure;
    }
  }
  public QuarterMaintenanceCoordinator.DurableControl lifecycle(){return new QuarterMaintenanceCoordinator.DurableControl(){
    public boolean startupWaiting(){synchronized(StagedNativeBackend.this){return state==State.WAITING||state==State.ACTIVATING;}}
    public QuarterMaintenanceCoordinator.Observation inspect(Instant quarter){Activation a;synchronized(StagedNativeBackend.this){a=state==State.ATTACHED?activation:null;}return a==null?QuarterMaintenanceCoordinator.Observation.unavailable():a.lifecycle().inspect(quarter);}
    public void executeOnce(Instant quarter,QuarterMaintenanceCoordinator.Observation expected,String operation){require().lifecycle().executeOnce(quarter,expected,operation);}
  };}
  private synchronized Activation require(){if(state!=State.ATTACHED)throw new IllegalStateException("Native startup not verified");return activation;}
  public synchronized boolean enabled(){return state==State.ATTACHED&&activation.backend().enabled();}
  public String findOrCreateAccount(String issuer,String subject){return require().backend().findOrCreateAccount(issuer,subject);}
  public String findOrCreateAccount(String issuer,String subject,String nickname,String email){return require().backend().findOrCreateAccount(issuer,subject,nickname,email);}
  public SaveStatus submit(FinalMatchEnvelope envelope){synchronized(this){if(state!=State.ATTACHED)return SaveStatus.REJECTED;}return require().backend().submit(envelope);}
  public SaveStatus status(String id){synchronized(this){if(state!=State.ATTACHED)return SaveStatus.UNKNOWN;}return require().backend().status(id);}
  public List<FinalMatchEnvelope> exportCommitted(){return require().backend().exportCommitted();}
  public StatisticsData exportStatistics(String id){return require().backend().exportStatistics(id);}
  public boolean orderlyShutdown(java.time.Duration budget,java.util.function.BooleanSupplier domain){
    Activation a;synchronized(this){a=state==State.ATTACHED?activation:null;}
    return a!=null&&a.backend() instanceof NativeOrderlyShutdown capable&&capable.orderlyShutdown(budget,domain);
  }
  /** Runtime finalizer after SmartLifecycle; never waits behind native in-flight I/O. */
  public void closeAsynchronously(){Activation a;synchronized(this){if(state==State.CLOSED)return;state=State.CLOSED;a=activation;}
    if(a!=null){Thread t=new Thread(()->{try{a.backend().close();}catch(RuntimeException|Error ignored){}},"native-stage-resource-release");t.setDaemon(true);t.start();}
  }
  public void close(){Activation a;synchronized(this){if(state==State.CLOSED)return;state=State.CLOSED;a=activation;}if(a!=null)a.backend().close();}
}
