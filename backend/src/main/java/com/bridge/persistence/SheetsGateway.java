package com.bridge.persistence;
import java.util.*;
/** Server-only storage boundary. appendAtomic must use one spreadsheets.batchUpdate. */
public interface SheetsGateway {
    List<List<String>> read(String tab);
    /**
     * Optional opaque fingerprint of the last complete, validated snapshot, including metadata.
     * Read-only adapters without that capability return empty; this never asserts metadata coverage for them.
     * Implementations must reject incomplete read cycles rather than return a stale fingerprint.
     */
    default Optional<String> snapshotFingerprint() { return Optional.empty(); }
    void appendAtomic(Map<String,List<List<Object>>> rows);
    /** Operator-maintenance only: all replacement rows including headers in one atomic batch. */
    default void replaceAtomic(Map<String,List<List<Object>>> rows) { throw new UnsupportedOperationException("Atomic replacement unavailable"); }
    class Failure extends RuntimeException {
        public final int status; public final long retryAfterMillis;
        public Failure(int status,long retryAfterMillis) { super("Private storage request failed ("+status+")"); this.status=status; this.retryAfterMillis=retryAfterMillis; }
        public boolean retryable() { return status==0 || status==429 || status>=500; }
    }
    class Conflict extends RuntimeException { public Conflict() { super("Storage integrity conflict"); } }
}
