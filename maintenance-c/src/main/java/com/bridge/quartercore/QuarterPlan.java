package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** One immutable in-memory business intent, not a durable queue or a replay capability.
 * PREPARE/FOLD are exact. COMPLETE is deliberately absent until real APPLIED readback.
 * All source controls, foreign metadata, physical layout and literal tables are bound.
 */
final class QuarterPlan {
  private final ManualQuarterMaintenance.Binding binding;
  private final String planId,digest,sourceDigest;
  private final Instant createdAt,cutoff,expiresAt;
  private final Root sourceRoot,preparedRoot,foldedRoot;
  private final Map<String,List<List<String>>> beforeTables,pendingTables;
  private final Book source,prepared,folded;
  private final Ticket prepareTicket,foldTicket;
  private final Limits limits;

  private QuarterPlan(ManualQuarterMaintenance.Binding binding,Book observed,String owner,Clock clock,Limits limits) {
    this.binding=Objects.requireNonNull(binding);this.limits=Objects.requireNonNull(limits);
    createdAt=clock.instant();cutoff=QuarterlyRetention.currentQuarterStart(createdAt);expiresAt=createdAt.plusSeconds(300);
    source=observed.copy();sourceRoot=root(new ArrayList<>(source.metadata.values()));
    if(!sourceRoot.dataset().equals(binding.datasetId())||!sourceRoot.owner().equals(owner)||!sourceRoot.state().equals("OPEN"))throw new Rejected();
    validate(source,binding,clock,limits);beforeTables=ManualQuarterMaintenance.immutable(source.tables());sourceDigest=bookDigest(source);
    RetentionLedger ledger=new RetentionLedger(beforeTables.get("retention_ledger"));
    if(ledger.complete()&&ledger.cutoff().equals(cutoff))throw new Rejected();
    // Never delete raw data that the existing lifetime policy does not count.
    for(FinalMatchEnvelope e:ManualQuarterMaintenance.decoded(beforeTables).values())
      if(QuarterlyRetention.finishedAt(e).isBefore(cutoff)&&(!LifetimeStatistics.RULE.equals(e.payload().path("match").path("lifetime_rule").asText())
          ||!LifetimeStatistics.SCORE_VERSION.equals(e.payload().path("match").path("score_version").asText())))throw new Rejected();
    var seven=new TreeMap<>(beforeTables);seven.remove("dataset");
    var local=new LocalPlanningSnapshot(seven,binding,clock,"QUARTER");
    var reducer=new QuarterlyRetention(local,clock,()->true,false,QuarterlyRetention.AggregatePolicy.suppressAll());
    var preview=reducer.preview();planId=preview.planId();pendingTables=reducer.pendingIntent(preview);
    if(!preview.cutoff().equals(cutoff))throw new Rejected();
    digest=hash(List.of("immutable-quarter-intent-v1",binding,planId,sourceDigest,createdAt.toString(),cutoff.toString(),expiresAt.toString(),pendingTables));
    preparedRoot=phaseRoot(sourceRoot,sourceRoot.next()+1,"PENDING",planId,digest);
    foldedRoot=phaseRoot(sourceRoot,sourceRoot.next()+2,"APPLIED",planId,digest);
    if(spreadsheetChars(new ArrayList<>(source.metadata.values()))+TRANSITION_RESERVE>METADATA_LIMIT)throw new Rejected();
    for(Metadata m:source.metadata.values())if(m.key().equals(RECEIPT_KEY)&&parse(m.value()).path("operation").asText().equals(planId))throw new Rejected();
    String pw=phaseWire(sourceRoot,preparedRoot,List.of());prepared=simulate(source,pw);check(prepared,pw,clock);
    prepareTicket=ticket(sourceRoot,preparedRoot,pw,prepared,beforeTables);
    String fw=phaseWire(preparedRoot,foldedRoot,replacements(source,beforeTables,pendingTables));folded=simulate(prepared,fw);check(folded,fw,clock);
    foldTicket=ticket(preparedRoot,foldedRoot,fw,folded,pendingTables);
    // Capacity-only projection. No completion/proof object or dispatchable final ticket exists here.
    // A 30-character timestamp reserves the largest supported Instant encoding. It is NEVER sent.
    Map<String,List<List<String>>> capacity=completeTables("0".repeat(30));
    Root capacityRoot=phaseRoot(sourceRoot,sourceRoot.next()+3,"OPEN",planId+"-VERIFIED","0".repeat(64));
    String capacityWire=phaseWire(foldedRoot,capacityRoot,replacements(source,pendingTables,capacity));
    Book capacityBook=simulate(folded,capacityWire);bounds(capacityBook,capacityWire,limits);
    requireWindow(clock);
  }
  static QuarterPlan create(ManualQuarterMaintenance.Binding binding,Book observed,String owner,Clock clock,Limits limits) {
    return new QuarterPlan(binding,observed,owner,clock,limits);
  }
  String planId(){return planId;}
  String digest(){return digest;}
  Instant createdAt(){return createdAt;}
  Instant cutoff(){return cutoff;}
  Instant expiresAt(){return expiresAt;}
  Root sourceRoot(){return sourceRoot;}
  Map<String,List<List<String>>> beforeTables(){return beforeTables;}
  Map<String,List<List<String>>> pendingTables(){return pendingTables;}
  Ticket prepareTicket(){return prepareTicket;}
  Ticket foldTicket(){return foldTicket;}
  ManualQuarterMaintenance.Binding binding(){return binding;}
  String sourceDigest(){return sourceDigest;}
  void requireWindow(Clock clock){requireWindow(clock.instant());}
  void requireWindow(Instant now){if(now.isBefore(createdAt)||!now.isBefore(expiresAt)||!cutoff.equals(QuarterlyRetention.currentQuarterStart(now)))throw new Rejected();}
  boolean sourceMatches(Book b){return sourceDigest.equals(bookDigest(b));}
  boolean preparedMatches(Book b){return bookDigest(prepared).equals(bookDigest(b));}
  boolean foldedMatches(Book b){return bookDigest(folded).equals(bookDigest(b));}
  boolean expectedMatches(Ticket t,Completion completion,Book b){
    if(t==prepareTicket)return preparedMatches(b);
    if(t==foldTicket)return foldedMatches(b);
    return completion!=null&&completion.plan==this&&t==completion.ticket&&completion.expectedDigest.equals(bookDigest(b));
  }
  /** Caller supplies a real fresh whole-dataset read, after positive FOLD reconciliation.
   * The returned immutable proof/ticket is allocated once and retained before its first attempt. */
  Completion completeAfterVerifiedRead(Book applied,Clock clock){return completeAfterVerifiedRead(applied,clock,bookDigest(folded));}
  private Completion completeAfterVerifiedRead(Book applied,Clock clock,String expectedAppliedDigest){
    requireWindow(clock);validate(applied,binding,clock,limits);if(!expectedAppliedDigest.equals(bookDigest(applied)))throw new Rejected();
    Instant verifiedAt=clock.instant();requireWindow(clock);
    Map<String,List<List<String>>> tables=completeTables(verifiedAt.toString());
    String proofHash=hash(List.of("immutable-quarter-completion-v1",digest,bookDigest(applied),verifiedAt.toString(),tables));
    Root verified=phaseRoot(sourceRoot,sourceRoot.next()+3,"OPEN",planId+"-VERIFIED",proofHash);
    String wire=phaseWire(foldedRoot,verified,replacements(source,pendingTables,tables));
    Book expected=simulate(applied,wire);check(expected,wire,clock);requireWindow(clock);
    return new Completion(this,verifiedAt,ticket(foldedRoot,verified,wire,expected,tables),bookDigest(expected),bookDigest(applied));
  }
  /** Immutable execution envelope; expected Books receive precisely the verified arm.
   * Actual readbacks are compared whole, never stripped or projected. The reducer runs once. */
  static final class Execution {
    private final QuarterPlan plan;
    private final String sourceDigest,preparedDigest,foldedDigest;
    private Execution(QuarterPlan plan,Metadata exactArm){
      this.plan=plan;plan.validateExactArm(exactArm,plan.limits);
      sourceDigest=armedDigest(plan.source,exactArm);preparedDigest=armedDigest(plan.prepared,exactArm);foldedDigest=armedDigest(plan.folded,exactArm);
    }
    boolean sourceMatches(Book b){return sourceDigest.equals(bookDigest(b));}
    boolean preparedMatches(Book b){return preparedDigest.equals(bookDigest(b));}
    boolean foldedMatches(Book b){return foldedDigest.equals(bookDigest(b));}
    boolean expectedMatches(Ticket t,Completion completion,Book b){
      if(t==plan.prepareTicket)return preparedMatches(b);
      if(t==plan.foldTicket)return foldedMatches(b);
      return completion!=null&&completion.plan==plan&&t==completion.ticket&&completion.expectedDigest.equals(bookDigest(b));
    }
    Completion completeAfterVerifiedRead(Book applied,Clock clock){return plan.completeAfterVerifiedRead(applied,clock,foldedDigest);}
  }
  Execution bindExactArm(Metadata exactArm){return new Execution(this,exactArm);}
  private static String armedDigest(Book expected,Metadata exactArm){
    if(expected.metadata.containsKey(exactArm.id()))throw new Rejected();
    Book armed=expected.copy();armed.metadata.put(exactArm.id(),exactArm);return bookDigest(armed);
  }
  void validateExactArm(Metadata exactArm,Limits expectedLimits){
    if(exactArm==null||!limits.equals(expectedLimits)||source.metadata.containsKey(exactArm.id()))throw new Rejected();
    for(Book prefix:List.of(source,prepared,folded)){
      Book armed=prefix.copy();armed.metadata.put(exactArm.id(),exactArm);
      if(spreadsheetChars(new ArrayList<>(armed.metadata.values()))+TRANSITION_RESERVE>METADATA_LIMIT)throw new Rejected();
      bounds(armed,foldTicket.wire(),limits);
    }
    Book armed=folded.copy();armed.metadata.put(exactArm.id(),exactArm);
    var capacity=completeTables("0".repeat(30));
    Root verified=phaseRoot(sourceRoot,sourceRoot.next()+3,"OPEN",planId+"-VERIFIED","0".repeat(64));
    String complete=phaseWire(foldedRoot,verified,replacements(source,pendingTables,capacity));
    Book done=simulate(armed,complete);bounds(done,complete,limits);
    Root closed=new Root(verified.dataset(),verified.epoch(),verified.anchor(),verified.nextSheet(),verified.next()+1,
        verified.owner(),"CLOSED","CLOSE-"+verified.owner(),hash(verified.owner()));
    String close=phaseWire(verified,closed,List.of());bounds(simulate(done,close),close,limits);
  }
  /** Small successful-completion diagnostic. No player data, wire, ticket, or plan reference. */
  record Summary(String planId,String digest,Instant cutoff,Instant verifiedAt,Root verifiedRoot) {}
  static final class Completion {
    private final QuarterPlan plan;private final Instant verifiedAt;private final Ticket ticket;
    private final String expectedDigest,appliedDigest;
    private Completion(QuarterPlan plan,Instant verifiedAt,Ticket ticket,String expectedDigest,String appliedDigest){this.plan=plan;this.verifiedAt=verifiedAt;this.ticket=ticket;this.expectedDigest=expectedDigest;this.appliedDigest=appliedDigest;}
    QuarterPlan plan(){return plan;}
    Instant verifiedAt(){return verifiedAt;}
    Ticket ticket(){return ticket;}
    String appliedDigest(){return appliedDigest;}
  }
  private Map<String,List<List<String>>> completeTables(String actualTime){
    var complete=new TreeMap<>(pendingTables);var rows=new ArrayList<>(complete.get("retention_ledger"));int found=0;
    for(int n=1;n<rows.size();n++)if(rows.get(n).get(0).equals(QuarterlyRetention.quarter(cutoff))){
      rows.set(n,List.of(QuarterlyRetention.quarter(cutoff),cutoff.toString(),"COMPLETE",actualTime,"1"));found++;
    }
    if(found!=1)throw new Rejected();complete.put("retention_ledger",rows);return ManualQuarterMaintenance.immutable(complete);
  }
  private Ticket ticket(Root prior,Root after,String wire,Book expected,Map<String,List<List<String>>> tables){
    var counts=new TreeMap<String,Integer>();tables.forEach((name,rows)->counts.put(name,rows.size()));
    return new Ticket(after.operation(),after.hash(),null,wire,prior.next(),prior.epoch(),prior.anchor(),prior.owner(),after,List.of(),bytes(expected.nativeValue()),counts,tables,cutoff);
  }
  private Root phaseRoot(Root r,int next,String state,String operation,String hash){return new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),next,r.owner(),state,operation,hash);}
  static String phaseWire(Root before,Root after,List<Object> changes){
    var req=new ArrayList<Object>();req.add(guard(before.anchor(),"retention_ledger"));req.add(NarrowQuarterPoc.create(receipt(before,after.operation(),after.hash(),after.state())));req.addAll(changes);req.add(updateRoot(after));return wire(req);
  }
  private static List<Object> replacements(Book source,Map<String,List<List<String>>> before,Map<String,List<List<String>>> after){
    var changes=new ArrayList<Object>();for(String tab:after.keySet())if(!before.get(tab).equals(after.get(tab))){
      if(tab.equals("dataset"))throw new Rejected();changes.add(update(source.sheet(tab).id,0,Math.max(before.get(tab).size(),after.get(tab).size()),0,after.get(tab).get(0).size(),after.get(tab)));
    }return changes;
  }
  static String bookDigest(Book b){return hash(b.nativeValue());}
  private void check(Book b,String wire,Clock clock){bounds(b,wire,limits);validate(b,binding,clock,limits);}
  private static void bounds(Book b,String wire,Limits limits){
    if(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>limits.requestBytes()||bytes(b.nativeValue())>limits.futureReadBytes()
        ||b.sheets.values().stream().anyMatch(s->s.rows.size()>limits.rowsPerSheet()))throw new Rejected();b.budgets();
  }
  private static void validate(Book b,ManualQuarterMaintenance.Binding binding,Clock clock,Limits limits){
    ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(),true,b.tables()),binding,clock);
    if(b.sheets.size()!=8||!b.tables().keySet().equals(ManualQuarterMaintenance.HEADERS.keySet()))throw new Rejected();
    b.budgets();Root r=root(new ArrayList<>(b.metadata.values()));
    if(!r.dataset().equals(binding.datasetId())||r.anchor()!=b.sheet("retention_ledger").id||b.sheets.keySet().stream().anyMatch(id->id>=r.nextSheet())||bytes(b.nativeValue())>limits.futureReadBytes())throw new Rejected();
    for(Sheet s:b.sheets.values())if(s.rowCount!=1000||s.columnCount!=26||s.rows.size()>limits.rowsPerSheet())throw new Rejected();
  }
}
