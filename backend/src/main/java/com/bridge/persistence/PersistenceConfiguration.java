package com.bridge.persistence;
import com.bridge.room.IdentityStore;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.List;

@Configuration
public class PersistenceConfiguration {
    public QuarterMaintenanceGate quarterMaintenanceGate(Environment env) {
        return new QuarterMaintenanceGate("true".equals(env.getProperty("BRIDGE_QUARTER_GATE_ENABLED","false")),java.time.Clock.systemUTC());
    }
    @Bean public QuarterMaintenanceGate quarterMaintenanceGate(Environment env,org.springframework.beans.factory.ObjectProvider<PreinstalledNativeRuntime> runtimes) {
        var runtime=runtimes.getIfAvailable();
        if(runtime!=null){NativePersistenceSelection.preinstalled(env,runtime.gate(),runtime.staged(),runtime.binding());return runtime.gate();}
        return new QuarterMaintenanceGate("true".equals(env.getProperty("BRIDGE_QUARTER_GATE_ENABLED","false")),java.time.Clock.systemUTC());
    }
    // Deliberately unavailable: no production cleanup transport is installed in this candidate.
    public QuarterMaintenanceCoordinator.DurableControl quarterDurableControl() { return QuarterMaintenanceCoordinator.DurableControl.unavailable(); }
    @Bean public QuarterMaintenanceCoordinator.DurableControl quarterDurableControl(Environment env,
            NativePersistenceSelection selected,QuarterMaintenanceGate gate,
            org.springframework.beans.factory.ObjectProvider<NativeQuarterLifecycleFactory> providers) {
        if(!NativePersistenceSelection.selected(env))return QuarterMaintenanceCoordinator.DurableControl.unavailable();
        if(selected.requireBackend() instanceof InertNativeMaintenanceBackend)return QuarterMaintenanceCoordinator.DurableControl.unavailable();
        if(selected.requireBackend() instanceof StagedNativeBackend staged)return staged.lifecycle();
        var available=providers.orderedStream().toList();
        if(available.isEmpty())return QuarterMaintenanceCoordinator.DurableControl.unavailable();
        if(available.size()!=1)throw new IllegalStateException("Exactly one quarterly lifecycle provider is required");
        return java.util.Objects.requireNonNull(available.get(0).create(selected.requireBackend(),gate));
    }
    @Bean public QuarterMaintenanceCoordinator quarterMaintenanceCoordinator(QuarterMaintenanceGate gate,
            QuarterMaintenanceCoordinator.DurableControl durable,com.bridge.room.RoomService rooms) {
        rooms.quarterAdmission(gate::acceptsNewGames);
        rooms.quarterMatchRegistration(gate::registerMatch);
        rooms.quarterUncollectedMatch(gate::discardUncollectedMatch);
        return new QuarterMaintenanceCoordinator(gate,durable,java.time.Clock.systemUTC(),
            rooms::drainAndFreezeForQuarterMaintenance,rooms::finishQuarterMaintenance,rooms::freezeForQuarterMaintenance);
    }
    @Bean public QuarterMaintenanceScheduler quarterMaintenanceScheduler(QuarterMaintenanceCoordinator coordinator) {
        return new QuarterMaintenanceScheduler(coordinator);
    }
    @Bean public NativePersistenceSelection nativePersistenceSelection(Environment env,QuarterMaintenanceGate gate,
            org.springframework.beans.factory.ObjectProvider<NativePersistenceSelection.Factory> factories,
            org.springframework.beans.factory.ObjectProvider<PreinstalledNativeRuntime> runtimes) {
        var runtime=runtimes.getIfAvailable();
        if(runtime!=null){if(factories.stream().findAny().isPresent())throw new IllegalStateException("Ambiguous native source assembly");return NativePersistenceSelection.preinstalled(env,gate,runtime.staged(),runtime.binding());}
        return new NativePersistenceSelection(env,gate,factories.getIfAvailable(NativePersistenceSelection.Factory::unavailable));
    }
    @Bean public SheetsWriteGate sheetsWriteGate(Environment env) {
        return new SheetsWriteGate("true".equals(env.getProperty("BRIDGE_LOCAL_WRITE_GATE_ENABLED","false")));
    }
    public SheetsGateway sheetsGateway(Environment env) { return sheetsGateway(env,sheetsWriteGate(env),quarterMaintenanceGate(env)); }
    @Bean public SheetsGateway sheetsGateway(Environment env,SheetsWriteGate gate,QuarterMaintenanceGate operationGate) {
        if(NativePersistenceSelection.selected(env))return new SheetsGateway(){
            public List<List<String>> read(String tab){throw new IllegalStateException("Legacy source disabled by native cutover");}
            public void appendAtomic(java.util.Map<String,List<List<Object>>> rows){throw new IllegalStateException("Legacy writer disabled by native cutover");}
        };
        if(operationGate.enabled() && "single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE")))
            throw new IllegalStateException("Quarter gate does not support experiment writers");
        if("single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE")) || !Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false")))return new SheetsGateway(){public List<List<String>> read(String tab){throw new IllegalStateException("Sheets disabled");}public void appendAtomic(java.util.Map<String,List<List<Object>>> rows){throw new IllegalStateException("Sheets disabled");}};
        return operationGate.wrap(gate.wrap(DatasetBinding.open(env, sheet -> new GoogleSheetsGateway(sheet,
            env.getRequiredProperty("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")))));
    }
    @Bean public StatsStore statsStore(Environment env,SheetsGateway gateway,QuarterMaintenanceGate operationGate,NativePersistenceSelection nativeSource,org.springframework.beans.factory.ObjectProvider<ExperimentRuntimeConfiguration.Handle> experiment) {
        if(NativePersistenceSelection.selected(env))return nativeSource.statistics();
        if("single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE"))) {
            var store=experiment.getObject().store();
            return new StatsStore(){public SaveStatus submit(FinalMatchEnvelope e){return store.submit(e);}public SaveStatus status(String id){return store.status(id);}public List<FinalMatchEnvelope> exportCommitted(){return store.exportCommitted();}};
        }
        if(!Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false")))return new StatsStore(){public SaveStatus submit(FinalMatchEnvelope e){return SaveStatus.DISABLED;}public SaveStatus status(String id){return SaveStatus.DISABLED;}public List<FinalMatchEnvelope> exportCommitted(){return List.of();}};
        return new SheetsStatsStore(gateway,Integer.parseInt(env.getProperty("BRIDGE_SHEETS_QUEUE_CAPACITY","128")),java.time.Clock.systemUTC(),operationGate);
    }
    @Bean public IdentityStore identityStore(Environment env,SheetsGateway gateway,QuarterMaintenanceGate operationGate,NativePersistenceSelection nativeSource,org.springframework.beans.factory.ObjectProvider<ExperimentRuntimeConfiguration.Handle> experiment) {
        if(NativePersistenceSelection.selected(env))return nativeSource.identities();
        if("single_tester_experiment".equals(env.getProperty("BRIDGE_COLLECTION_MODE"))) {
            var store=experiment.getObject().store();
            return new IdentityStore(){public boolean enabled(){return store.admissionAvailable();}public String findOrCreateAccount(String issuer,String subject){return store.findOrCreateAccount(issuer,subject);}};
        }
        if(!Boolean.parseBoolean(env.getProperty("BRIDGE_SHEETS_ENABLED","false")))return new IdentityStore(){public boolean enabled(){return false;}public String findOrCreateAccount(String issuer,String subject){throw new IllegalStateException("Durable identity storage disabled");}};
        return new SheetsIdentityStore(gateway,java.time.Clock.systemUTC(),operationGate);
    }
}
