package com.bridge.persistence;

import java.util.*;

/** Dedicated experiment dataset boundary. No generic append or destructive rewrite is exposed. */
public interface ExperimentGateway {
  record Snapshot(String spreadsheetId, boolean completeKnownLayout, Map<String,List<List<String>>> tables) {
    public Snapshot {
      var copy = new TreeMap<String,List<List<String>>>();
      tables.forEach((k,v)->copy.put(k,v.stream().map(List::copyOf).toList()));
      tables = Collections.unmodifiableMap(copy);
    }
  }
  Snapshot readComplete();
  /** Add a fixed unique guard title and all associated rows in ONE atomic batch. Never retry internally. */
  void claimAtomic(String guard, List<List<String>> guardRows, Map<String,List<List<Object>>> appendRows);
}
