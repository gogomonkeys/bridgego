package com.bridge.persistence;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;

public final class SheetsStatsStore implements StatsStore, AutoCloseable {
    private final SheetsGateway gateway;
    private final Clock clock;
    private final ThreadPoolExecutor writer;
    private final ConcurrentHashMap<String, Entry> entries=new ConcurrentHashMap<>();
    private final ArrayDeque<String> terminalOrder=new ArrayDeque<>();
    private static final int MAX_TERMINAL_STATUSES=4096;
    private record Entry(String hash, SaveStatus status) {}
    public SheetsStatsStore(SheetsGateway gateway,int capacity) {
        this(gateway,capacity,Clock.systemUTC());
    }
    public SheetsStatsStore(SheetsGateway gateway,int capacity,Clock clock) {
        this.gateway=gateway;this.clock=Objects.requireNonNull(clock);
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity), r->{Thread t=new Thread(r,"private-sheets-writer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Override public synchronized SaveStatus submit(FinalMatchEnvelope e) {
        try {requireCurrentTime(e);}catch(RuntimeException expired){finish(e,SaveStatus.REJECTED);return SaveStatus.REJECTED;}
        Entry old=entries.get(e.commitId());
        if(old!=null) {
            if(!old.hash.equals(e.payloadHash())) return SaveStatus.CONFLICT;
            if(old.status!=SaveStatus.REJECTED && old.status!=SaveStatus.FAILED) return old.status;
        }
        entries.put(e.commitId(),new Entry(e.payloadHash(),SaveStatus.PENDING));
        try { writer.execute(()->write(e)); } catch(RejectedExecutionException full) { finish(e,SaveStatus.REJECTED); }
        return entries.get(e.commitId()).status;
    }
    public SaveStatus status(String id) { Entry e=entries.get(id); return e==null?SaveStatus.UNKNOWN:e.status; }
    private void write(FinalMatchEnvelope e) {
        for(int attempt=0;attempt<4;attempt++) {
            try {
                QuarterlyRetention.requireCollectionReady(gateway,clock);
                QuarterlyRetention.requireCurrent(e,clock,new RetentionLedger(gateway.read("retention_ledger")));
                new ErasureLedger(gateway.read("erasure_ledger")).requireAllowed(e);
                FinalMatchEnvelope found=readVerified(gateway).get(e.commitId());
                if(found!=null) { finish(e,found.payloadHash().equals(e.payloadHash())?SaveStatus.CONFIRMED:SaveStatus.CONFLICT);return; }
                List<List<Object>> chunks=new ArrayList<>(); String json=e.canonicalJson(); List<String> textChunks=new ArrayList<>();
                for(int start=0;start<json.length();) { int end=Math.min(json.length(),start+20000); if(end<json.length() && Character.isHighSurrogate(json.charAt(end-1)))end--; textChunks.add(json.substring(start,end));start=end; }
                int count=textChunks.size();
                for(int i=0;i<count;i++) chunks.add(List.of(e.commitId(),e.payloadHash(),i,count,textChunks.get(i)));
                requireCurrentTime(e);
                gateway.appendAtomic(Map.of("envelopes",chunks,"commits",List.of(List.of(e.commitId(),e.payloadHash(),count,e.matchId(),e.revision()))));
                found=readVerified(gateway).get(e.commitId());
                if(found==null || !found.payloadHash().equals(e.payloadHash())) throw new SheetsGateway.Conflict();
                finish(e,SaveStatus.CONFIRMED);return;
            } catch(SheetsGateway.Conflict conflict) { finish(e,SaveStatus.CONFLICT);return;
            } catch(SheetsGateway.Failure failure) {
                if(!failure.retryable() || failure.retryAfterMillis>30000 || attempt==3) { finish(e,SaveStatus.FAILED);return; }
                try { Thread.sleep(Math.min(30000,Math.max(failure.retryAfterMillis,(250L<<attempt)+ThreadLocalRandom.current().nextLong(100)))); } catch(InterruptedException stop) { Thread.currentThread().interrupt();finish(e,SaveStatus.FAILED);return; }
            } catch(RuntimeException failure) { finish(e,SaveStatus.FAILED);return; }
        }
    }
    private synchronized void finish(FinalMatchEnvelope e,SaveStatus s) { entries.put(e.commitId(),new Entry(e.payloadHash(),s)); terminalOrder.remove(e.commitId());terminalOrder.addLast(e.commitId());while(terminalOrder.size()>MAX_TERMINAL_STATUSES){String id=terminalOrder.removeFirst();Entry old=entries.get(id);if(old!=null&&old.status!=SaveStatus.PENDING)entries.remove(id,old);} }
    private void requireCurrentTime(FinalMatchEnvelope e) {
        Instant now=clock.instant(),boundary=QuarterlyRetention.currentQuarterStart(now),finish=QuarterlyRetention.finishedAt(e),start;
        try {start=Instant.parse(e.payload().path("match").path("started_at").asText());}catch(RuntimeException invalid){throw new SheetsGateway.Conflict();}
        if(finish.isBefore(boundary)||finish.isAfter(now)||start.isAfter(finish))throw new SheetsGateway.Conflict();
    }
    @Override public List<FinalMatchEnvelope> exportCommitted() {
        QuarterlyRetention.requireCollectionReady(gateway,clock);
        RetentionLedger ledger=new RetentionLedger(gateway.read("retention_ledger"));
        List<FinalMatchEnvelope> current=new ArrayList<>();
        for(FinalMatchEnvelope e:readVerified(gateway).values()) {
            if(QuarterlyRetention.finishedAt(e).isBefore(QuarterlyRetention.currentQuarterStart(clock.instant())))continue;
            QuarterlyRetention.requireCurrent(e,clock,ledger);current.add(e);
        }
        QuarterlyRetention.requireCollectionReady(gateway,clock); // fail closed if the boundary crossed during export
        return List.copyOf(current);
    }
    /** Read-only decoder: no writer or thread resources are created. */
    static Map<String,FinalMatchEnvelope> readVerified(SheetsGateway gateway) {
        List<List<String>> rows=gateway.read("envelopes"), commits=gateway.read("commits");
        Map<String,List<String>> markers=new LinkedHashMap<>();
        for(List<String> row:commits) {
            if(row.isEmpty()||row.get(0).equals("commit_id"))continue;
            if(row.size()!=5)throw new SheetsGateway.Conflict();
            List<String> old=markers.putIfAbsent(row.get(0),row); if(old!=null&&!old.equals(row))throw new SheetsGateway.Conflict();
        }
        Map<String,TreeMap<Integer,String>> chunks=new HashMap<>();
        for(List<String> row:rows) {
            if(row.isEmpty()||row.get(0).equals("commit_id"))continue;
            if(row.size()!=5)throw new SheetsGateway.Conflict();
            List<String> marker=markers.get(row.get(0));
            if(marker==null||!marker.get(1).equals(row.get(1))||!marker.get(2).equals(row.get(3)))throw new SheetsGateway.Conflict();
            try {
                int index=Integer.parseInt(row.get(2)),count=Integer.parseInt(row.get(3));
                if(index<0||index>=count||count>10000)throw new SheetsGateway.Conflict();
                String old=chunks.computeIfAbsent(row.get(0),k->new TreeMap<>()).putIfAbsent(index,row.get(4));
                if(old!=null&&!old.equals(row.get(4)))throw new SheetsGateway.Conflict();
            } catch(NumberFormatException ex) {throw new SheetsGateway.Conflict();}
        }
        Map<String,FinalMatchEnvelope> out=new LinkedHashMap<>();
        for(List<String> marker:markers.values())try {
            TreeMap<Integer,String> parts=chunks.get(marker.get(0));
            if(parts==null||parts.size()!=Integer.parseInt(marker.get(2)))throw new SheetsGateway.Conflict();
            out.put(marker.get(0),new FinalMatchEnvelope(marker.get(0),marker.get(3),Integer.parseInt(marker.get(4)),String.join("",parts.values()),marker.get(1)));
        }catch(IllegalArgumentException ex){throw new SheetsGateway.Conflict();}
        ErasureLedger ledger=new ErasureLedger(gateway.read("erasure_ledger"));
        out.values().forEach(ledger::requireAllowed);
        return out;
    }
    /** True only after queued work is cancelled and the active worker has exited. */
    public boolean isStopped() { return writer.isTerminated(); }
    @Override public void close() { writer.shutdownNow(); }
}
