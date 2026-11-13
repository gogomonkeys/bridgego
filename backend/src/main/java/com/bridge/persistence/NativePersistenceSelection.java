package com.bridge.persistence;

import java.util.Objects;
import org.springframework.core.env.Environment;

/** Explicit source cutover holder. Configuration is not authorization or ownership proof.
 * No credential lookup or Google operation is implemented here. */
public final class NativePersistenceSelection implements AutoCloseable {
    public static final String NATIVE="native-journal-v1";
    public record Request(DatasetBinding binding,String approvedBaselineSha256) {
        public Request {Objects.requireNonNull(binding);if(approvedBaselineSha256==null||!approvedBaselineSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Explicit cutover baseline required");}
    }
    public interface Factory {
        /** Must positively verify exact target/baseline, independent writer freeze/ownership,
         * capability and journal readiness. Request values alone cannot establish approval. */
        NativePersistenceBackend openVerified(Request request,QuarterMaintenanceGate gate);
        Factory UNAVAILABLE=(request,gate)->{throw new IllegalStateException("Verified native persistence capability is not installed");};
        static Factory unavailable(){return UNAVAILABLE;}
    }
    private final NativePersistenceBackend backend;
    public NativePersistenceSelection(Environment env,QuarterMaintenanceGate gate,Factory factory){
        Objects.requireNonNull(env);Objects.requireNonNull(gate);Objects.requireNonNull(factory);
        if(!selected(env)){backend=null;return;}
        if(!"true".equals(env.getProperty("BRIDGE_SHEETS_ENABLED","false"))||!gate.enabled()
            ||"single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE")))throw new IllegalStateException("Native cutover requires managed gate and Sheets configuration");
        String baseline=env.getProperty("BRIDGE_NATIVE_CUTOVER_BASELINE_SHA256");
        if(factory==Factory.UNAVAILABLE||baseline==null||!baseline.matches("[0-9a-f]{64}")){
            backend=new InertNativeMaintenanceBackend(gate);return;
        }
        Request request=new Request(DatasetBinding.from(env),baseline);
        backend=Objects.requireNonNull(factory.openVerified(request,gate),"Verified native backend unavailable");
    }
    /** Explicit trusted assembly only. No baseline or write authority is inferred here. */
    public static NativePersistenceSelection preinstalled(Environment env,QuarterMaintenanceGate gate,
            StagedNativeBackend staged,DatasetBinding expected){
        Objects.requireNonNull(staged);Objects.requireNonNull(gate);
        if(!selected(env)||!"true".equals(env.getProperty("BRIDGE_SHEETS_ENABLED","false"))||!gate.enabled()
            ||"single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE"))
            ||!staged.belongsTo(gate)||!staged.inertRoutingSafe()
            ||!Objects.requireNonNull(expected).equals(DatasetBinding.from(env)))
            throw new IllegalStateException("Trusted inert native stage and matching target required");
        return new NativePersistenceSelection(staged);
    }
    private NativePersistenceSelection(StagedNativeBackend staged){backend=staged;}
    public static boolean selected(Environment env){
        String driver=env.getProperty("BRIDGE_PERSISTENCE_DRIVER","legacy");
        if(!"legacy".equals(driver)&&!NATIVE.equals(driver))throw new IllegalArgumentException("Unknown persistence driver");
        return NATIVE.equals(driver);
    }
    /** Distinct interface-only facades prevent the same dual-interface backend appearing as
     * two Spring type candidates; lifecycle remains owned by this selection. */
    public StatsStore statistics(){var selected=requireBackend();return new StatsStore(){
        public SaveStatus submit(FinalMatchEnvelope e){return selected.submit(e);}
        public SaveStatus status(String id){return selected.status(id);}
        public java.util.List<FinalMatchEnvelope> exportCommitted(){return selected.exportCommitted();}
        public StatisticsData exportStatistics(String account){return selected.exportStatistics(account);}
    };}
    public com.bridge.room.IdentityStore identities(){var selected=requireBackend();return new com.bridge.room.IdentityStore(){
        public boolean enabled(){return selected.enabled();}
        public String findOrCreateAccount(String issuer,String subject){return selected.findOrCreateAccount(issuer,subject);}
        public String findOrCreateAccount(String issuer,String subject,String nickname,String email){return selected.findOrCreateAccount(issuer,subject,nickname,email);}
    };}
    public boolean maintenanceRoutingSafe(){return backend!=null&&(!(backend instanceof StagedNativeBackend staged)||staged.maintenanceRoutingSafe());}
    public boolean inertRoutingSafe(){return backend instanceof StagedNativeBackend staged && staged.inertRoutingSafe();}
    public NativePersistenceBackend requireBackend(){if(backend==null)throw new IllegalStateException("Native source not selected");return backend;}
    private volatile boolean orderlyStopping;
    private final java.util.concurrent.atomic.AtomicBoolean disposal=new java.util.concurrent.atomic.AtomicBoolean();
    void markOrderlyStopping(){orderlyStopping=true;}
    boolean orderlyShutdown(java.time.Duration budget,java.util.function.BooleanSupplier domain){
        return backend instanceof NativeOrderlyShutdown capable&&capable.orderlyShutdown(budget,domain);
    }
    void disposeAsynchronously(){
        if(backend!=null&&disposal.compareAndSet(false,true)){
            Thread t=new Thread(()->{try{backend.close();}catch(RuntimeException|Error ignored){}},"native-context-resource-release");t.setDaemon(true);t.start();
        }
    }
    @Override public void close(){if(orderlyStopping){disposeAsynchronously();return;}if(backend!=null&&disposal.compareAndSet(false,true))backend.close();}
}
