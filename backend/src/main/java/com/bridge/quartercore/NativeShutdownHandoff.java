package com.bridge.quartercore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;import java.util.function.BooleanSupplier;
import com.bridge.persistence.QuarterMaintenanceGate;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Explicit one-shot orderly shutdown only. Not a Spring hook, restart authority or automatic
 * recovery. close() on the backend retains its resource-only semantics. No forced game loss. */
final class NativeShutdownHandoff {
 enum State { NEW,RUNNING,COMPLETE,REFUSED,UNKNOWN }
 record Result(State state,String closedToken,String owner,long journalSequence) {
  static Result status(State s){return new Result(s,"","",0);}
 }
 /** domainQuiescent must be a local, nonblocking admission/freeze observation, never external I/O. */
 private final NativeStoresBackend backend;private final QuarterMaintenanceGate gate;private final BooleanSupplier domainQuiescent;
 private final ManualQuarterMaintenance.Binding binding;private final Clock clock;
 private final AtomicBoolean releaseScheduled=new AtomicBoolean();
 private final AtomicReference<Result> result=new AtomicReference<>(Result.status(State.NEW));
 NativeShutdownHandoff(NativeStoresBackend backend,QuarterMaintenanceGate gate,BooleanSupplier domainQuiescent,ManualQuarterMaintenance.Binding binding,Clock clock){
  this.backend=Objects.requireNonNull(backend);this.gate=Objects.requireNonNull(gate);this.domainQuiescent=Objects.requireNonNull(domainQuiescent);this.binding=Objects.requireNonNull(binding);this.clock=Objects.requireNonNull(clock);
 }
 Result result(){return result.get();}
 Result attempt(Duration budget){
  if(budget==null||budget.isZero()||budget.isNegative()||budget.compareTo(Duration.ofSeconds(15))>0)throw new Rejected();
  Result initial=result.get(),running=Result.status(State.RUNNING);if(initial.state()!=State.NEW||!result.compareAndSet(initial,running))throw new Rejected();
  long deadline=System.nanoTime()+budget.toNanos();
  try{
   // Gate prohibition is established before the final exclusive check. Existing permits and
   // queued/registered work are never cancelled or silently discarded to manufacture a handoff.
   var admission=gate.state();
   if(!gate.enabled()||(admission!=QuarterMaintenanceGate.State.OPEN&&admission!=QuarterMaintenanceGate.State.FINISHING)||!domainQuiescent.getAsBoolean()
       ||gate.registeredMatchCount()!=0||gate.activeOperations()!=0||gate.activeWrites()!=0)throw new Rejected();
   gate.beginDrain();if(!gate.beginExclusive()||!domainQuiescent.getAsBoolean())throw new Rejected();
  }catch(RuntimeException denied){result.compareAndSet(running,Result.status(State.REFUSED));return result.get();}
  var executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"native-shutdown-handoff");t.setDaemon(true);return t;});
  Future<?> pending=executor.submit(()->{
   try{
    check(running,deadline);var closed=backend.cleanCloseForShutdown(remaining(deadline));check(running,deadline);
    var root=NarrowQuarterPoc.root(new ArrayList<>(closed.metadata.values()));var proof=NativeCommitJournal.verify(binding,closed,clock);
    var recovery=JournalQuarterProtocol.inspect(binding,closed,clock).state();
    if(!root.state().equals("CLOSED")||!root.operation().equals("CLOSE-"+root.owner())||!root.hash().equals(NarrowQuarterPoc.hash(root.owner()))
        ||(recovery!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT&&recovery!=JournalQuarterProtocol.RecoveryState.READY_DUE))throw new Rejected();
    check(running,deadline);backend.retireClosedWriter();check(running,deadline);
    result.compareAndSet(running,new Result(State.COMPLETE,JournalSessionAuthority.fullToken(closed),root.owner(),proof.sequence()));
   }catch(RuntimeException|Error unknown){unknown(running);}
  });
  try{pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
  catch(TimeoutException|ExecutionException unknown){unknown(running);}
  catch(InterruptedException cancelled){Thread.currentThread().interrupt();unknown(running);}
  finally{executor.shutdown();} // Never interrupt a worker holding serial or retry an unknown CLOSE.
  return result.get();
 }
 /** Resource disposal only: may wait behind serial on its own daemon, never retry CLOSE or
  * assert that cancelled queued work was saved. UNKNOWN is terminal even after a late response. */
 private void unknown(Result running){
  result.compareAndSet(running,Result.status(State.UNKNOWN));gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);
  if(releaseScheduled.compareAndSet(false,true)){
   Thread cleanup=new Thread(()->{try{backend.close();}catch(RuntimeException|Error ignored){/* remains UNKNOWN */}},"native-shutdown-resource-release");
   cleanup.setDaemon(true);cleanup.start();
  }
 }
 private void check(Result running,long deadline){if(result.get()!=running||System.nanoTime()>=deadline)throw new Rejected();}
 private static Duration remaining(long deadline){long n=deadline-System.nanoTime();if(n<=0)throw new Rejected();return Duration.ofNanos(n);}
}
