package com.bridge.persistence;

import java.time.Clock;
import java.util.*;

  /** Final, transport-free literal snapshot reducer. Never accepts a gateway or write callback. */
  final class LocalPlanningSnapshot implements SheetsGateway {
    private final ManualQuarterMaintenance.Binding binding;
    private final Clock clock;
    private final String kind;
    Map<String,List<List<String>>> tables;
    Map<String,List<List<String>>> pending;
    int replacements;

    LocalPlanningSnapshot(Map<String,List<List<String>>> seven, ManualQuarterMaintenance.Binding binding, Clock clock, String kind) {
      this.binding = binding; this.clock = clock; this.kind = kind;
      if (seven == null || !seven.keySet().equals(ManualQuarterMaintenance.MUTABLE)) reject();
      var all = new TreeMap<>(ManualQuarterMaintenance.immutable(seven));
      all.put("dataset", List.of(ManualQuarterMaintenance.HEADERS.get("dataset"),
          List.of(binding.purpose(), binding.datasetId())));
      validate(all); tables = ManualQuarterMaintenance.immutable(all);
    }
    void validate(Map<String,List<List<String>>> candidate) {
      ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(), true, candidate), binding, clock);
      Set<String> matches = new HashSet<>();
      for (var envelope : ManualQuarterMaintenance.decoded(candidate).values())
        if (!matches.add(envelope.matchId())) reject();
    }
    public List<List<String>> read(String tab) {
      if (!ManualQuarterMaintenance.HEADERS.containsKey(tab)) reject();
      return tables.get(tab);
    }
    Map<String,List<List<String>>> business() {
      var result = new TreeMap<>(tables); result.remove("dataset");
      return ManualQuarterMaintenance.immutable(result);
    }
    private void publish(Map<String,List<List<String>>> candidate) {
      validate(candidate); tables = ManualQuarterMaintenance.immutable(candidate);
    }
    public void appendAtomic(Map<String,List<List<Object>>> added) {
      Set<String> allowed = switch (kind) {
        case "MATCH" -> Set.of("envelopes", "commits");
        case "IDENTITY" -> Set.of("accounts", "auth_identities");
        default -> throw rejected();
      };
      if (!added.keySet().equals(allowed)) reject();
      var next = new TreeMap<>(tables);
      added.forEach((tab, rows) -> {
        var combined = new ArrayList<>(next.get(tab)); combined.addAll(literal(rows)); next.put(tab, combined);
      });
      publish(next);
    }
    public void saveAccountProfile(int row, List<String> account, List<String> identity) {
      if (!kind.equals("PROFILE") || account == null || account.size() != 4) reject();
      var next = new TreeMap<>(tables);
      var accounts = new ArrayList<>(tables.get("accounts"));
      boolean lifetime = accounts.get(0).equals(AccountProfileRows.LIFETIME_HEADER);
      accounts.set(0, lifetime ? AccountProfileRows.LIFETIME_HEADER : AccountProfileRows.HEADER);
      if (row == 0) {
        if (identity == null || identity.size() != 4 || !account.get(0).equals(identity.get(2))) reject();
        accounts.add(List.copyOf(account));
        var identities = new ArrayList<>(tables.get("auth_identities"));
        identities.add(List.copyOf(identity)); next.put("auth_identities", identities);
      } else {
        if (identity != null || row < 2 || row > accounts.size()
            || !accounts.get(row - 1).get(0).equals(account.get(0))) reject();
        var changed = new ArrayList<>(account);
        if (accounts.get(row - 1).size() == 5) changed.add(accounts.get(row - 1).get(4));
        accounts.set(row - 1, List.copyOf(changed));
      }
      next.put("accounts", accounts); publish(next);
    }
    public void replaceAtomic(Map<String,List<List<Object>>> replacement) {
      Set<String> allowed = switch (kind) {
        case "ERASE" -> Set.of("accounts", "auth_identities", "envelopes", "commits", "erasure_ledger");
        case "QUARTER" -> ManualQuarterMaintenance.MUTABLE;
        default -> throw rejected();
      };
      if (!replacement.keySet().equals(allowed) || replacements >= (kind.equals("QUARTER") ? 2 : 1)) reject();
      var next = new TreeMap<>(tables);
      replacement.forEach((tab, rows) -> next.put(tab, literal(rows)));
      publish(next); replacements++;
      if (kind.equals("QUARTER") && replacements == 1) {
        if (new RetentionLedger(read("retention_ledger")).complete()) reject();
        pending = business();
      }
    }
    private static List<List<String>> literal(List<List<Object>> rows) {
      return rows.stream().map(row -> row.stream().map(value -> {
        if (!(value instanceof String)) throw rejected(); return (String)value;
      }).toList()).toList();
    }
    static void requireLegacyOrLocal(SheetsGateway gateway) {
      if (!(gateway instanceof LocalPlanningSnapshot)) LegacyWriterGuard.requireLegacyProcess();
    }
    private static void reject() { throw rejected(); }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("Sheet command or dataset rejected"); }
}
