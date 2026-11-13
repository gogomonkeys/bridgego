package com.bridge.quartercore;

import java.time.Clock;
import java.util.*;
import com.bridge.persistence.NativePersistenceBackend;
import com.bridge.persistence.QuarterMaintenanceGate;
import com.bridge.persistence.StatsStore;

/** Assembly bridge only: no public constructor, credentials, ownership mint or live provider.
 * One native Service supplies both stores. Statistics reuse the existing read-only decoder.
 */
public final class NativeStoresBackend implements NativePersistenceBackend {
    private NativeWriterAdapter writer;
    private volatile NarrowQuarterPoc.Service service;
    private final Object lifecycleLock=new Object();
    private volatile boolean lifecycleFailed;
    private boolean writerRetired;
    private Runnable replacementRelease=()->{};
    private final QuarterMaintenanceGate gate;
    private JournaledNativeTransport transport;
    private final Clock clock;
    private final Set<String> unsettledAccepted=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String,Entry> statuses=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ThreadPoolExecutor queue=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(128),r->{var t=new Thread(r,"native-journal-writer");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final java.util.ArrayDeque<String> terminal=new java.util.ArrayDeque<>();
    private volatile boolean closed;
    private record Entry(String hash,SaveStatus status){}
    NativeStoresBackend(NarrowQuarterPoc.Service service,JournaledNativeTransport transport,Clock clock,QuarterMaintenanceGate gate){
        this(service,transport,clock,gate,()->{}); // model-only ownership: no external handle
    }
    NativeStoresBackend(NarrowQuarterPoc.Service service,JournaledNativeTransport transport,Clock clock,QuarterMaintenanceGate gate,Runnable release){
        replacementRelease=Objects.requireNonNull(release);
        this.service=Objects.requireNonNull(service);this.gate=Objects.requireNonNull(gate);
        if(service.endpoint!=transport||service.state()!=NarrowQuarterPoc.State.OPEN)throw new IllegalStateException("Verified native session required");
        writer=new NativeWriterAdapter(service);
        this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);
    }
    /** Trusted local assembly only. Factory must provision a fresh bounded ordinary authority
     * against the exact CLOSED snapshot; it must not dispatch a CLAIM itself. */
    interface ReopenFactory { Session prepare(NativeSheetsModel.Book exactClosed,String freshOwner); }
    record Session(NarrowQuarterPoc.Service service,JournaledNativeTransport transport,Runnable release) {
        Session(NarrowQuarterPoc.Service service,JournaledNativeTransport transport){this(service,transport,()->{});}
        Session { Objects.requireNonNull(service);Objects.requireNonNull(transport);Objects.requireNonNull(release); }
    }
    boolean lifecycleOpen(){return !closed&&!lifecycleFailed&&service.state()==NarrowQuarterPoc.State.OPEN;}
    private void requireExclusiveLifecycle(){
        if(closed||lifecycleFailed||!gate.enabled()||gate.state()!=QuarterMaintenanceGate.State.MAINTENANCE
            ||gate.activeOperations()!=0||gate.registeredMatchCount()!=0||!unsettledAccepted.isEmpty()||!queue.getQueue().isEmpty()
            ||statuses.values().stream().anyMatch(e->e.status()==SaveStatus.PENDING||e.status()==SaveStatus.UNKNOWN))
            throw new IllegalStateException("Native lifecycle is not exclusively drained");
    }
    private static boolean exact(NativeSheetsModel.Book a,NativeSheetsModel.Book b){
        return NativeCommitJournal.canonical(a.nativeValue()).equals(NativeCommitJournal.canonical(b.nativeValue()));
    }
    /** Read-only inspection never grants ownership. The current local owner must match. */
    NativeSheetsModel.Book lifecycleSnapshot(){synchronized(lifecycleLock){synchronized(service.serial){
        if(closed||lifecycleFailed)throw new IllegalStateException("Native lifecycle unavailable");
        try(var scope=nativeScope("lifecycle-inspect")){
            var b=transport.snapshot();var root=NarrowQuarterPoc.root(new ArrayList<>(b.metadata.values()));
            if(!root.equals(service.known)||!root.owner().equals(service.owner))throw new IllegalStateException("Native owner changed");
            return b;
        }
    }}}
    NativeSheetsModel.Book cleanCloseForQuarter(){synchronized(lifecycleLock){synchronized(service.serial){
        requireExclusiveLifecycle();
        try(var scope=nativeScope("lifecycle-close")){
            requireOpen();service.cleanClose();var b=transport.snapshot();
            var root=NarrowQuarterPoc.root(new ArrayList<>(b.metadata.values()));
            if(!root.equals(service.known)||!root.state().equals("CLOSED")||!root.owner().equals(service.owner)
                ||service.pending!=null)throw new IllegalStateException("Clean close unverified");
            return b;
        }catch(RuntimeException|Error uncertain){lifecycleFailed=true;service.close();gate.block(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw uncertain;}
    }}}
    /** Called only after the exact CLOSED readback; revokes the original capability without
     * closing an independently shared model/workbook. Live assembly supplies Handle::close. */
    void retireClosedWriter(){synchronized(lifecycleLock){synchronized(service.serial){
        requireExclusiveLifecycle();
        if(service.state()!=NarrowQuarterPoc.State.CLOSED||service.pending!=null||service.known==null||!service.known.state().equals("CLOSED"))
            throw new IllegalStateException("Cannot retire unverified writer");
        try{service.close();replacementRelease.run();replacementRelease=()->{};writerRetired=true;}
        catch(RuntimeException|Error failure){lifecycleFailed=true;gate.block(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw failure;}
    }}}
    NativeSheetsModel.Book reopenAfterVerifiedQuarter(NativeSheetsModel.Book exactClosed,ReopenFactory factory){synchronized(lifecycleLock){
        requireExclusiveLifecycle();var old=service;
        synchronized(old.serial){
            if(!writerRetired||old.known==null||!old.known.state().equals("CLOSED"))throw new IllegalStateException("Writer did not close");
            Session candidate=null;
            try{
                var root=NarrowQuarterPoc.root(new ArrayList<>(exactClosed.metadata.values()));
                NativeCommitJournal.verify(old.binding,exactClosed,clock);
                if(!root.state().equals("CLOSED")||JournalQuarterProtocol.inspect(old.binding,exactClosed,clock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)
                    throw new IllegalStateException("Quarter not verified");
                String freshOwner="w-"+UUID.randomUUID();
                candidate=Objects.requireNonNull(factory.prepare(exactClosed.copy(),freshOwner));var next=candidate.service();var tx=candidate.transport();
                if(next==old||next.endpoint!=tx||!next.binding.equals(old.binding)||!next.owner.equals(freshOwner)
                    ||next.state()!=NarrowQuarterPoc.State.STARTING||next.closed)throw new IllegalStateException("Invalid replacement session");
                try(var scope=tx.operation("lifecycle-reopen",root,BatchedSheetsTransport.ALL,java.time.Duration.ofSeconds(60))){
                    var fresh=tx.snapshot();if(!exact(fresh,exactClosed))throw new IllegalStateException("Quarter baseline changed");
                    next.start();var after=tx.snapshot();var opened=NarrowQuarterPoc.root(new ArrayList<>(after.metadata.values()));
                    if(next.state()!=NarrowQuarterPoc.State.OPEN||!opened.equals(next.known)||!opened.state().equals("OPEN")
                        ||!opened.owner().equals(freshOwner))throw new IllegalStateException("Reopen unverified");
                    old.close();replacementRelease.run();replacementRelease=candidate.release();service=next;transport=tx;writer=new NativeWriterAdapter(next);writerRetired=false;return after;
                }
            }catch(RuntimeException|Error uncertain){lifecycleFailed=true;old.close();if(candidate!=null){candidate.service().close();try{candidate.release().run();}catch(RuntimeException releaseFailure){uncertain.addSuppressed(releaseFailure);}}gate.block(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw uncertain;}
        }
    }}
    private com.bridge.persistence.SheetsStatsStore snapshotReader(){
        Map<String,List<List<String>>> tables;
        try(var scope=nativeScope("statistics")){tables=transport.snapshot().tables();}
        var readOnly=new com.bridge.persistence.SheetsGateway(){
            public List<List<String>> read(String tab){var rows=tables.get(tab);if(rows==null)throw new IllegalArgumentException("Unknown table");return rows.stream().map(List::copyOf).toList();}
            public void appendAtomic(Map<String,List<List<Object>>> rows){throw new UnsupportedOperationException("Native statistics reader is read-only");}
        };
        return new com.bridge.persistence.SheetsStatsStore(readOnly,1,clock);
    }
    @Override public boolean enabled(){return !closed&&gate.isOpen()&&service.state()==NarrowQuarterPoc.State.OPEN;}
    @Override public String findOrCreateAccount(String issuer,String subject){
        // Legacy no-profile path still uses the exact native identity mapping, never a second backend.
        try(var permit=gate.enterWrite();var scope=permit.attach()){
            synchronized(service.serial){requireOpen();try(var nativeScope=nativeScope("identity")){var result=service.managedIdentity("IDENTITY-"+UUID.randomUUID(),issuer,subject,null,null,false);
                if(result.ticket()!=null)service.send(result.ticket());
                if(service.state()!=NarrowQuarterPoc.State.OPEN)throw new IllegalStateException("Native identity result unknown");
                permit.success();return result.account();}}
        }
    }
    @Override public String findOrCreateAccount(String issuer,String subject,String nickname,String verifiedEmail){
        try(var permit=gate.enterWrite();var scope=permit.attach()){
            synchronized(service.serial){requireOpen();try(var nativeScope=nativeScope("profile")){var result=writer.identity("PROFILE-"+UUID.randomUUID(),issuer,subject,nickname,verifiedEmail==null?"":verifiedEmail);
                if(result.outcome()!=NativeWriterAdapter.Outcome.VERIFIED)throw new IllegalStateException("Native profile result unknown");
                permit.success();return result.accountId();}}
        }
    }
    @Override public SaveStatus submit(com.bridge.persistence.FinalMatchEnvelope envelope){
        Objects.requireNonNull(envelope);
        Entry previous;
        for(;;){
            previous=statuses.get(envelope.commitId());
            if(previous==null)break;
            if(!previous.hash().equals(envelope.payloadHash()))return SaveStatus.CONFLICT;
            if(previous.status()!=SaveStatus.REJECTED)return previous.status();
            if(statuses.remove(envelope.commitId(),previous))break; // Definitely unsubmitted: may requeue, never UNKNOWN.
        }
        QuarterMaintenanceGate.Permit permit;
        try{permit=gate.enterSettlement(envelope.matchId());}catch(QuarterMaintenanceGate.Unavailable denied){return SaveStatus.REJECTED;}
        previous=statuses.putIfAbsent(envelope.commitId(),new Entry(envelope.payloadHash(),SaveStatus.PENDING));
        if(previous!=null){permit.success();permit.close();return previous.hash().equals(envelope.payloadHash())?previous.status():SaveStatus.CONFLICT;}
        unsettledAccepted.add(envelope.commitId());
        try{queue.execute(new QueuedMatch(envelope,permit));}
        catch(java.util.concurrent.RejectedExecutionException full){permit.success();permit.close();remember(envelope,SaveStatus.REJECTED);}
        return status(envelope.commitId());
    }
    private final class QueuedMatch implements Runnable {
        final com.bridge.persistence.FinalMatchEnvelope envelope;final QuarterMaintenanceGate.Permit permit;
        QueuedMatch(com.bridge.persistence.FinalMatchEnvelope e,QuarterMaintenanceGate.Permit p){envelope=e;permit=p;}
        public void run(){try(permit;var scope=permit.attach()){
            synchronized(service.serial){requireOpen();try(var nativeScope=nativeScope("match")){
                var boundary=com.bridge.persistence.QuarterlyRetention.currentQuarterStart(clock.instant());
                boolean draining=gate.enabled()&&gate.verifiedBoundary()!=null&&gate.verifiedBoundary().isBefore(boundary);
                var result=draining?writer.matchDrain("MATCH-DRAIN-"+UUID.randomUUID(),envelope,permit.settlementGrant())
                    :writer.match("MATCH-"+UUID.randomUUID(),envelope);
                var status=result.outcome()==NativeWriterAdapter.Outcome.VERIFIED?SaveStatus.CONFIRMED:SaveStatus.UNKNOWN;
                remember(envelope,status);if(status==SaveStatus.CONFIRMED){permit.settlementRecorded();permit.success();}
            }}
        }catch(RuntimeException unknown){remember(envelope,SaveStatus.UNKNOWN);}}
        void cancel(){permit.success();permit.close();remember(envelope,SaveStatus.REJECTED);}
    }
    private synchronized void remember(com.bridge.persistence.FinalMatchEnvelope e,SaveStatus status){
        if(status==SaveStatus.CONFIRMED)unsettledAccepted.remove(e.commitId());
        statuses.put(e.commitId(),new Entry(e.payloadHash(),status));terminal.remove(e.commitId());terminal.addLast(e.commitId());
        while(terminal.size()>4096){String id=terminal.removeFirst();var prior=statuses.get(id);if(prior!=null&&prior.status()!=SaveStatus.PENDING)statuses.remove(id,prior);}
    }
    private NarrowSheetsTransport.Scope nativeScope(String label){
        NarrowSheetsTransport.Scope scope=null;
        try{scope=transport.operation(label,service.known,Set.copyOf(ManualQuarterMaintenance.HEADERS.keySet()),java.time.Duration.ofSeconds(60));transport.renewIfDue();return scope;}
        catch(RuntimeException|Error invalid){try{if(scope!=null)scope.close();}finally{service.close();gate.block(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);}throw invalid;}
    }
    @Override public SaveStatus status(String commitId){var value=statuses.get(commitId);return value==null?SaveStatus.UNKNOWN:value.status();}
    @Override public List<com.bridge.persistence.FinalMatchEnvelope> exportCommitted(){
        try(var permit=gate.enterRead();var scope=permit.attach()){synchronized(service.serial){requireOpen();try(var reader=snapshotReader()){return reader.exportCommitted();}}}
    }
    @Override public StatsStore.StatisticsData exportStatistics(String accountId){
        try(var permit=gate.enterRead();var scope=permit.attach()){synchronized(service.serial){requireOpen();try(var reader=snapshotReader()){return reader.exportStatistics(accountId);}}}
    }
    private void requireOpen(){if(closed||service.state()!=NarrowQuarterPoc.State.OPEN)throw new IllegalStateException("Native session unavailable");}
    @Override public void close(){
        closed=true;for(var pending:queue.shutdownNow())((QueuedMatch)pending).cancel();
        synchronized(lifecycleLock){synchronized(service.serial){service.close();replacementRelease.run();replacementRelease=()->{};}}
    }
}
