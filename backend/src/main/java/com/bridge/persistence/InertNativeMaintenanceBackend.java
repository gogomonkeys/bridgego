package com.bridge.persistence;
/** No-capability boot fallback. No target, credential, transport, activation or retry path. */
public final class InertNativeMaintenanceBackend implements NativePersistenceBackend {
    public InertNativeMaintenanceBackend(QuarterMaintenanceGate gate){
        if(!gate.enabled()||gate.state()!=QuarterMaintenanceGate.State.STARTING||gate.activeOperations()!=0)
            throw new IllegalStateException("Closed native admission required");
        gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);
    }
    public boolean enabled(){return false;}
    private IllegalStateException unavailable(){return new IllegalStateException("Native capability unavailable; maintenance only");}
    public String findOrCreateAccount(String issuer,String subject){throw unavailable();}
    public String findOrCreateAccount(String issuer,String subject,String nickname,String email){throw unavailable();}
    public SaveStatus submit(FinalMatchEnvelope envelope){return SaveStatus.REJECTED;}
    public SaveStatus status(String id){return SaveStatus.UNKNOWN;}
    public java.util.List<FinalMatchEnvelope> exportCommitted(){throw unavailable();}
    public StatisticsData exportStatistics(String account){throw unavailable();}
    public void close(){}
}
