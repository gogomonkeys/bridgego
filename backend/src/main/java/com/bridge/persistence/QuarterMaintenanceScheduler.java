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
  // Bit 0: active tick, bit 1: irreversible shutdown request. Never acquire coordinator's I/O lock.
  private final java.util.concurrent.atomic.AtomicInteger lifecycle=new java.util.concurrent.atomic.AtomicInteger();
  public boolean stopBeforeShutdown(){return (lifecycle.getAndUpdate(s->s|2)&1)==0;}
  @Scheduled(initialDelay=0, fixedDelay=30_000)
  public void tick() { if(!lifecycle.compareAndSet(0,1))return;try{if((lifecycle.get()&2)==0)coordinator.tick();}finally{lifecycle.getAndUpdate(s->s&2);} }
}
