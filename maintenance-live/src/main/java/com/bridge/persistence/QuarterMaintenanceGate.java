package com.bridge.persistence;

import java.time.*;
import java.util.*;

/** Process-local fail-closed barrier. This is NOT proof of exclusive ownership of a remote workbook. */
public final class QuarterMaintenanceGate {
  public enum State { STARTING, OPEN, DRAINING, MAINTENANCE, RECONCILING, BLOCKED_MANUAL, BLOCKED }
  public enum Reason { NONE, CHECKING, QUARTER_CHANGE, DRAIN_REQUESTED, MAINTENANCE,
    EXCLUSIVITY_UNVERIFIED, UNRESOLVED_WRITES, STORAGE_UNAVAILABLE, VERIFICATION_FAILED, UNSUPPORTED_MODE, SHUTDOWN, RETRY_EXHAUSTED, REQUEST_BUDGET_EXHAUSTED }
  public static final class Unavailable extends IllegalStateException {
    public Unavailable() { super("Quarter maintenance unavailable"); }
  }
  private final boolean enabled;
  private final Clock clock;
  private State state;
  private Reason reason;
  private Instant boundary;
  private int operations, writes;
  private final ThreadLocal<Permit> attached = new ThreadLocal<>();
  public QuarterMaintenanceGate(boolean enabled, Clock clock) {
    this.enabled=enabled;this.clock=Objects.requireNonNull(clock);
    state=enabled?State.STARTING:State.OPEN;reason=enabled?Reason.CHECKING:Reason.NONE;
  }
  public static QuarterMaintenanceGate disabled() { return new QuarterMaintenanceGate(false,Clock.systemUTC()); }
  public boolean enabled() { return enabled; }
  private void observe() {
    if(enabled && state==State.OPEN && !QuarterlyRetention.currentQuarterStart(clock.instant()).equals(boundary)) {
      state=State.DRAINING;reason=Reason.QUARTER_CHANGE;
    }
  }
  public synchronized State state() { observe();return state; }
  public synchronized Reason reason() { observe();return reason; }
  public synchronized boolean isOpen() { observe();return state==State.OPEN; }
  public synchronized int activeOperations() { return operations; }
  public synchronized int activeWrites() { return writes; }
  public synchronized void beginDrain() {
    if(!enabled)return;observe();
    if(state==State.OPEN || state==State.STARTING){state=State.DRAINING;reason=Reason.DRAIN_REQUESTED;}
  }
  public synchronized void block(Reason cause) {
    Objects.requireNonNull(cause);if(!enabled)return;
    if(state!=State.BLOCKED){state=State.BLOCKED;reason=cause;}
  }
  /** Guarded-only transitions never release or reinterpret a legacy/manual block. */
  public synchronized void reconciling() {
    if(!enabled || state==State.BLOCKED || state==State.BLOCKED_MANUAL)return;
    state=State.RECONCILING;reason=Reason.STORAGE_UNAVAILABLE;
  }
  public synchronized void blockManual(Reason cause) {
    Objects.requireNonNull(cause);if(!enabled || state==State.BLOCKED || state==State.BLOCKED_MANUAL)return;
    state=State.BLOCKED_MANUAL;reason=cause;
  }
  public synchronized boolean beginExclusive() {
    if(!enabled)return false;observe();
    if((state!=State.DRAINING && state!=State.RECONCILING) || operations!=0)return false;
    state=State.MAINTENANCE;reason=Reason.MAINTENANCE;return true;
  }
  /** Caller must first prove exclusive ownership and verify durable maintenance outcome. */
  public synchronized void openVerified(Instant verifiedBoundary) {
    if(!enabled)return;
    if((state!=State.STARTING && state!=State.MAINTENANCE) || operations!=0 || verifiedBoundary==null
        || !QuarterlyRetention.currentQuarterStart(clock.instant()).equals(verifiedBoundary))throw new Unavailable();
    boundary=verifiedBoundary;state=State.OPEN;reason=Reason.NONE;
  }
  public Permit enterRead() { return enter(false); }
  public Permit enterWrite() { return enter(true); }
  private synchronized Permit enter(boolean write) {
    observe();if(enabled && state!=State.OPEN)throw new Unavailable();
    if(enabled){operations++;if(write)writes++;}return new Permit(write);
  }
  public final class Permit implements AutoCloseable {
    private final boolean write;
    private boolean closed, successful;
    private Thread owner;
    private Permit(boolean write) { this.write=write; }
    public Scope attach() {
      synchronized(QuarterMaintenanceGate.this) {
        if(closed || owner!=null)throw new Unavailable();
        owner=Thread.currentThread();var previous=attached.get();attached.set(this);return new Scope(this,previous);
      }
    }
    public void success() { synchronized(QuarterMaintenanceGate.this){if(closed)throw new Unavailable();successful=true;} }
    @Override public void close() {
      synchronized(QuarterMaintenanceGate.this) {
        if(closed)return;
        if(owner!=null)throw new IllegalStateException("Detach operation before closing permit");
        closed=true;
        if(enabled){operations--;if(write)writes--;if(write&&!successful)block(Reason.UNRESOLVED_WRITES);}
      }
    }
  }
  public final class Scope implements AutoCloseable {
    private final Permit permit, previous;private boolean closed;
    private Scope(Permit permit,Permit previous){this.permit=permit;this.previous=previous;}
    @Override public void close(){synchronized(QuarterMaintenanceGate.this){
      if(closed)return;
      if(permit.owner!=Thread.currentThread() || attached.get()!=permit)throw new Unavailable();
      closed=true;permit.owner=null;if(previous==null)attached.remove();else attached.set(previous);
    }}
  }
  private synchronized void requireWrite() {
    if(!enabled)return;observe();var permit=attached.get();
    if((state!=State.OPEN&&state!=State.DRAINING) || permit==null || permit.closed || !permit.write
        || permit.owner!=Thread.currentThread())throw new Unavailable();
  }
  public SheetsGateway wrap(SheetsGateway delegate) {
    Objects.requireNonNull(delegate);if(!enabled)return delegate;
    return new SheetsGateway(){
      public List<List<String>> read(String tab){return delegate.read(tab);}
      public Map<String,List<List<String>>> readBatch(List<String> tabs){return delegate.readBatch(tabs);}
      public Optional<String> snapshotFingerprint(){return delegate.snapshotFingerprint();}
      public void appendAtomic(Map<String,List<List<Object>>> rows){requireWrite();delegate.appendAtomic(rows);}
      public void replaceAtomic(Map<String,List<List<Object>>> rows){requireWrite();delegate.replaceAtomic(rows);}
      public void saveAccountProfile(int row,List<String> account,List<String> identity){requireWrite();delegate.saveAccountProfile(row,account,identity);}
    };
  }
}
