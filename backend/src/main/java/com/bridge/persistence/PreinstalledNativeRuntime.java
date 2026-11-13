package com.bridge.persistence;
/** Only an explicit trusted assembly registers this holder. Never component-scanned or env-created. */
public record PreinstalledNativeRuntime(QuarterMaintenanceGate gate,StagedNativeBackend staged,DatasetBinding binding){
    public PreinstalledNativeRuntime{
        java.util.Objects.requireNonNull(binding);java.util.Objects.requireNonNull(gate);java.util.Objects.requireNonNull(staged);
        if(!gate.enabled()||!staged.belongsTo(gate)||!staged.inertRoutingSafe())throw new IllegalStateException("Inert same-gate runtime required");
    }
}
