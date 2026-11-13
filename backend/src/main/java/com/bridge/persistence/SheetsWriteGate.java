package com.bridge.persistence;

import java.time.Duration;
import java.util.*;

/**
 * Process-local admission only, NOT a transaction lock or durable recovery protocol.
 * Enabled instances start closed. No scheduler or automatic reopening is installed.
 * Reads remain available for diagnosis. After draining, this instance cannot reopen:
 * outstanding callers may have read a pre-maintenance snapshot before admission.
 */
public final class SheetsWriteGate {
    public enum State { DISABLED, STARTUP_BLOCKED, OPEN, DRAINING, DRAINED, UNKNOWN }
    private State state;
    private int active;
    public SheetsWriteGate(boolean enabled) { state=enabled?State.STARTUP_BLOCKED:State.DISABLED; }
    public synchronized State state() { return state; }
    public synchronized int activeMutations() { return active; }

    /** Future coordinator must verify durable state AND writer ownership here.
     * Not called by production wiring. A failed verification latches UNKNOWN. */
    public synchronized void verifyAndOpenAtStartup(Runnable readOnlyVerification) {
        if(state!=State.STARTUP_BLOCKED)throw new IllegalStateException("Startup admission unavailable");
        try { Objects.requireNonNull(readOnlyVerification).run();
            if(state!=State.STARTUP_BLOCKED)throw new IllegalStateException("Startup state changed");
            state=State.OPEN; }
        catch(RuntimeException|Error failure) { state=State.UNKNOWN; throw failure; }
    }
    /** Reject new mutations immediately, then wait a bounded duration for admitted calls.
     * True means local mutations drained only; it never authorizes cleanup. */
    public synchronized boolean pauseAndDrain(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if(timeout.isNegative())throw new IllegalArgumentException("Negative timeout");
        long remaining=timeout.toNanos(), deadline=System.nanoTime()+remaining;
        if(state==State.DISABLED)throw new IllegalStateException("Gate disabled");
        if(state==State.OPEN||state==State.STARTUP_BLOCKED)state=active==0?State.DRAINED:State.DRAINING;
        while(active>0 && remaining>0) {
            long millis=remaining/1_000_000, nanos=remaining%1_000_000;
            wait(millis,(int)nanos); remaining=deadline-System.nanoTime();
        }
        return active==0 && state==State.DRAINED;
    }
    public synchronized void markUnknown() { if(state!=State.DISABLED)state=State.UNKNOWN; notifyAll(); }
    private void mutate(Runnable operation) {
        synchronized(this) {
            if(state!=State.OPEN)throw new IllegalStateException("Sheets write admission closed: "+state);
            active++;
        }
        try { operation.run(); }
        catch(RuntimeException|Error failure) { markUnknown(); throw failure; }
        finally { synchronized(this) { active--; if(active==0&&state==State.DRAINING)state=State.DRAINED; notifyAll(); } }
    }
    public synchronized SheetsGateway wrap(SheetsGateway delegate) {
        Objects.requireNonNull(delegate);
        if(state==State.DISABLED)return delegate;
        return new SheetsGateway() {
            public List<List<String>> read(String tab) { return delegate.read(tab); }
            public Map<String,List<List<String>>> readBatch(List<String> tabs) { return delegate.readBatch(tabs); }
            public Optional<String> snapshotFingerprint() { return delegate.snapshotFingerprint(); }
            public void appendAtomic(Map<String,List<List<Object>>> rows) { mutate(()->delegate.appendAtomic(rows)); }
            public void replaceAtomic(Map<String,List<List<Object>>> rows) { mutate(()->delegate.replaceAtomic(rows)); }
            public void saveAccountProfile(int row,List<String> account,List<String> identity) { mutate(()->delegate.saveAccountProfile(row,account,identity)); }
        };
    }
}
