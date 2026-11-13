package com.bridge.persistence;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.context.SmartLifecycle;
/** Stops before bean destruction. The timer never waits for RoomService or a native serial lock. */
public final class NativeShutdownLifecycle implements SmartLifecycle {
 private final NativePersistenceSelection selection;private final QuarterMaintenanceGate gate;private final BooleanSupplier idle;
 private final QuarterMaintenanceScheduler scheduler;private final Runnable shutdownAdmission;
 private final AtomicBoolean stopped=new AtomicBoolean();private volatile boolean running;
 public NativeShutdownLifecycle(NativePersistenceSelection selection,QuarterMaintenanceGate gate,BooleanSupplier idle,QuarterMaintenanceScheduler scheduler,Runnable shutdownAdmission){this.shutdownAdmission=java.util.Objects.requireNonNull(shutdownAdmission);this.selection=selection;this.gate=gate;this.idle=idle;this.scheduler=scheduler;}
 public void start(){running=true;}public boolean isRunning(){return running;}public int getPhase(){return Integer.MAX_VALUE;}
 public void stop(){stop(()->{});}
 public void stop(Runnable callback){
  if(!stopped.compareAndSet(false,true)){callback.run();return;}shutdownAdmission.run();running=false;selection.markOrderlyStopping();boolean schedulerIdle=scheduler.stopBeforeShutdown();
  AtomicBoolean finished=new AtomicBoolean();
  Runnable finish=()->{if(finished.compareAndSet(false,true)){gate.blockManual(QuarterMaintenanceGate.Reason.DRAIN_REQUESTED);selection.disposeAsynchronously();callback.run();}};
  Thread worker=new Thread(()->{try{if(schedulerIdle)selection.orderlyShutdown(Duration.ofSeconds(10),()->!finished.get()&&idle.getAsBoolean());}finally{finish.run();}},"native-context-clean-handoff");worker.setDaemon(true);
  Thread deadline=new Thread(()->{try{Thread.sleep(11000);}catch(InterruptedException e){Thread.currentThread().interrupt();}finish.run();},"native-context-shutdown-deadline");deadline.setDaemon(true);
  deadline.start();worker.start();
 }
}
