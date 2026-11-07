package com.bridge.persistence;

import java.time.Clock;
import java.util.*;

/** Strict target-bound adapter for the separate experiment schema. No arbitrary external routes. */
public final class GoogleExperimentGateway implements ExperimentGateway {
  interface Transport {byte[] read();void batch(Map<String,Object> body);}
  private final Transport google;
  private final ExperimentStorage.Settings settings;
  private final Clock clock;
  private ExperimentWorkbook.Workbook observed;
  public GoogleExperimentGateway(GoogleSheetsGateway google,ExperimentStorage.Settings settings,Clock clock){
    this(new Transport(){public byte[] read(){return google.experimentWorkbook();}public void batch(Map<String,Object> body){google.experimentBatch(body);}},settings,clock);
  }
  GoogleExperimentGateway(Transport transport,ExperimentStorage.Settings settings,Clock clock){
    this.google=Objects.requireNonNull(transport);this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);
  }
  @Override public synchronized Snapshot readComplete(){observed=ExperimentWorkbook.parse(google.read(),settings,clock);return observed.snapshot();}
  @Override public synchronized void claimAtomic(String guard,List<List<String>> guardRows,Map<String,List<List<Object>>> appendRows){
    if(observed==null||!ExperimentStorage.GUARDS.contains(guard)||observed.snapshot().tables().containsKey(guard))throw stopped();
    var expected=observed;readComplete();if(!expected.fingerprint().equals(observed.fingerprint()))throw stopped();
    Set<String> expectedTabs=guard.equals(ExperimentStorage.BOOT)?Set.of("retention_ledger"):
        guard.equals(ExperimentStorage.IDENTITY)?Set.of("accounts","auth_identities"):Set.of("envelopes","commits");
    if(!appendRows.keySet().equals(expectedTabs)||!guardRows.get(0).equals(ExperimentStorage.GUARD_HEADER))throw stopped();
    var candidate=new TreeMap<>(observed.snapshot().tables());candidate.put(guard,guardRows);
    appendRows.forEach((tab,rows)->{var combined=new ArrayList<>(candidate.get(tab));rows.forEach(row->combined.add(row.stream().map(Object::toString).toList()));candidate.put(tab,combined);});
    ExperimentStorage.validate(new Snapshot(settings.spreadsheetId(),true,candidate),settings,clock);
    if(!clock.instant().isBefore(settings.expiresAt()))throw stopped();
    int id=guard.equals(ExperimentStorage.BOOT)?1900000001:guard.equals(ExperimentStorage.IDENTITY)?1900000002:1900000003;
    if(observed.grids().values().stream().anyMatch(g->g.sheetId()==id))throw stopped();
    var requests=new ArrayList<Object>();
    requests.add(Map.of("addSheet",Map.of("properties",Map.of("sheetId",id,"title",guard,"gridProperties",Map.of("rowCount",2,"columnCount",3)))));
    requests.add(Map.of("updateCells",Map.of("range",Map.of("sheetId",id,"startRowIndex",0,"endRowIndex",2,"startColumnIndex",0,"endColumnIndex",3),"rows",cells(guardRows),"fields","userEnteredValue")));
    for(var entry:appendRows.entrySet())requests.add(Map.of("appendCells",Map.of("sheetId",observed.grids().get(entry.getKey()).sheetId(),"rows",cells(entry.getValue()),"fields","userEnteredValue")));
    // A duplicate fixed title/id rejects the complete batch, including associated private rows. No retry.
    google.batch(Map.of("requests",requests));observed=null;
  }
  /** Explicit maintenance facade only. Validates all guards but never exposes them to the legacy erasure engine. */
  public SheetsGateway asMaintenanceGateway(){return new SheetsGateway(){
    private final Set<String> cycle=new HashSet<>();
    private final Set<String> erasureTabs=Set.of("accounts","auth_identities","envelopes","commits","erasure_ledger","dataset");
    private ExperimentWorkbook.Workbook whole;
    public List<List<String>> read(String tab){synchronized(GoogleExperimentGateway.this){
      if(!erasureTabs.contains(tab))throw stopped();
      if(cycle.isEmpty()){readComplete();whole=observed;}
      if(!cycle.add(tab))throw stopped();
      var result=whole.snapshot().tables().get(tab);
      if(cycle.equals(erasureTabs))cycle.clear();
      return result;
    }}
    public void appendAtomic(Map<String,List<List<Object>>> rows){throw stopped();}
    public void replaceAtomic(Map<String,List<List<Object>>> rows){synchronized(GoogleExperimentGateway.this){
      if(!rows.keySet().equals(Set.of("accounts","auth_identities","envelopes","commits","erasure_ledger")))throw stopped();
      if(whole==null||!cycle.isEmpty())throw stopped();var prior=whole;readComplete();if(!prior.fingerprint().equals(observed.fingerprint()))throw stopped();
      var candidate=new TreeMap<>(observed.snapshot().tables());rows.forEach((t,r)->candidate.put(t,r.stream().map(x->x.stream().map(Object::toString).toList()).toList()));
      ExperimentStorage.validate(new Snapshot(settings.spreadsheetId(),true,candidate),settings,clock);
      Map<String,Integer> ids=new HashMap<>();observed.grids().forEach((name,g)->ids.put(name,g.sheetId()));
      google.batch(GoogleSheetsGateway.replacementBody(rows,ids));observed=null;whole=null;cycle.clear();
    }}
  };}
  private static List<Object> cells(List<? extends List<?>> rows){var result=new ArrayList<Object>();for(var row:rows){var cells=new ArrayList<Object>();for(Object v:row){if(!(v instanceof String))throw stopped();cells.add(Map.of("userEnteredValue",Map.of("stringValue",v)));}result.add(Map.of("values",cells));}return result;}
  private static IllegalStateException stopped(){return new IllegalStateException("Experiment storage stopped; inspect before retry");}
}
