package com.bridge.persistence;

import org.springframework.scheduling.annotation.Scheduled;

/** Explicit bean only; not component-scanned. A timer cannot wake a sleeping/free host.
 * The first tick on startup/wake observes the current Taipei quarter and fails closed.
 */
public final class QuarterMaintenanceScheduler {
  private final QuarterMaintenanceCoordinator coordinator;
  public QuarterMaintenanceScheduler(QuarterMaintenanceCoordinator coordinator) {
    this.coordinator=java.util.Objects.requireNonNull(coordinator);
  }
  @Scheduled(initialDelay=0, fixedDelay=30_000)
  public void tick() { coordinator.tick(); }
}
