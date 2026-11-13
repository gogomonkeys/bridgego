package com.bridge.persistence;
/** Trusted provisioning seam. It receives the SAME selected backend used by identity/stats.
 * No default implementation, network action, or environment-derived authority is supplied. */
@FunctionalInterface
public interface NativeQuarterLifecycleFactory {
  QuarterMaintenanceCoordinator.DurableControl create(NativePersistenceBackend selectedBackend,QuarterMaintenanceGate gate);
}
