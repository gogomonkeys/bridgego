package com.bridge.persistence;
import java.util.*;
/** Server-only storage boundary. appendAtomic must use one spreadsheets.batchUpdate. */
public interface SheetsGateway {
    List<List<String>> read(String tab);
    /** Read-only batch; implementations may override with one transport request. */
    default Map<String,List<List<String>>> readBatch(List<String> tabs) {
        var names = batchTabs(tabs); var result = new LinkedHashMap<String,List<List<String>>>();
        for (String tab : names) result.put(tab, read(tab));
        return immutableBatch(names, result);
    }
    static List<String> batchTabs(List<String> tabs) {
        if (tabs == null || tabs.isEmpty() || tabs.stream().anyMatch(t -> t == null || t.isBlank())
            || new HashSet<>(tabs).size() != tabs.size()) throw new IllegalArgumentException("Invalid batch tables");
        return List.copyOf(tabs);
    }
    static Map<String,List<List<String>>> immutableBatch(List<String> tabs, Map<String,List<List<String>>> rows) {
        var names = batchTabs(tabs);
        if (rows == null || !rows.keySet().equals(new HashSet<>(names))) throw new Conflict();
        var result = new LinkedHashMap<String,List<List<String>>>();
        try { for (String tab : names) result.put(tab, rows.get(tab).stream().map(List::copyOf).toList()); }
        catch (RuntimeException invalid) { throw new Conflict(); }
        return Collections.unmodifiableMap(result);
    }
    /**
     * Optional opaque fingerprint of the last complete, validated snapshot, including metadata.
     * Read-only adapters without that capability return empty; this never asserts metadata coverage for them.
     * Implementations must reject incomplete read cycles rather than return a stale fingerprint.
     */
    default Optional<String> snapshotFingerprint() { return Optional.empty(); }
    void appendAtomic(Map<String,List<List<Object>>> rows);
    /** Operator-maintenance only: all replacement rows including headers in one atomic batch. */
    default void replaceAtomic(Map<String,List<List<Object>>> rows) { throw new UnsupportedOperationException("Atomic replacement unavailable"); }
    /** Narrow account-profile write. Existing row is 1-based; zero appends account and identity atomically.
     * Header C:D is upgraded in the same batch. Never rewrites other accounts or statistics. */
    default void saveAccountProfile(int row, List<String> account, List<String> identity) {
        throw new UnsupportedOperationException("Account profile storage unavailable");
    }
    class Failure extends RuntimeException {
        public final int status; public final long retryAfterMillis;
        public Failure(int status,long retryAfterMillis) { super("Private storage request failed ("+status+")"); this.status=status; this.retryAfterMillis=retryAfterMillis; }
        public boolean retryable() { return status==0 || status==429 || status>=500; }
    }
    class Conflict extends RuntimeException { public Conflict() { super("Storage integrity conflict"); } }
}
