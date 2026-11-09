package com.bridge.persistence;

import com.bridge.persistence.SyntheticCleanupExecutor.*;
import com.bridge.persistence.SyntheticCleanupExecutor.Preview;
import com.bridge.persistence.SyntheticSheetsErasurePreparation.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Clock;
import java.util.*;

/** Pure bounded JSON/batch protocol with injected fake wire only; no credential or HTTP implementation. */
final class SyntheticCleanupRawProtocol implements Gateway {
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(4_194_304)
          .maxNestingDepth(32).maxStringLength(32768).maxTokenCount(1_000_000).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  interface Wire {
    /** Original complete response bytes, including comments and all grid/control metadata. */
    byte[] readWhole(String fixedTarget);
    /** One bounded batch, no retries. Implemented only by local tests. */
    void sendOnce(String fixedTarget, Batch batch);
  }
  static final class Batch {
    private final JsonNode requests;
    Batch(JsonNode requests, ObjectNode expected) { this.requests = requests.deepCopy(); }
    JsonNode requests() { return requests.deepCopy(); }
  }
  private record Frame(ObjectNode raw, Snapshot model) {}
  private final String target;
  private final FixtureSet fixtures;
  private final Wire wire;
  private final ObjectNode anchor;
  private final Map<String,Integer> tabIds = new TreeMap<>();
  private final Map<String,int[]> dimensions = new TreeMap<>();
  private final List<Frame> frames = new ArrayList<>();
  private Frame last;
  private final Set<Phase> attempts=EnumSet.noneOf(Phase.class);
  private final Map<String,Frame> forecast = new HashMap<>();
  private boolean poisoned;

  SyntheticCleanupRawProtocol(FixtureSet fixtures, Wire wire, byte[] originalScaffold) {
    this.fixtures = fixtures; this.target = fixtures.target; this.wire = Objects.requireNonNull(wire);
    SyntheticCleanupWorkbook.requireApprovedTarget(target);
    // The original strict parser validates metadata before any frame registration or projection.
    var binding = new ManualQuarterMaintenance.Binding(target,"00000000-0000-4000-8000-000000000001");
    var parsed = LiveQuarterWorkbook.parseScaffold(originalScaffold,binding,Clock.systemUTC(),"SYNTHETIC-ONLY-FORMAT-TEST");
    anchor = object(originalScaffold);
    fixtures.bindRawAnchor(SyntheticCleanupWorkbook.sha256(new String(bytes(anchor), java.nio.charset.StandardCharsets.UTF_8)));
    parsed.grids().forEach((name,grid)->{
      if (grid.rowCount()!=1000 || grid.columnCount()!=26) reject();
      tabIds.put(name,grid.sheetId()); dimensions.put(name,new int[]{grid.rowCount(),grid.columnCount()});
    });
    frames.add(new Frame(anchor.deepCopy(),fixtures.initial()));
  }
  public synchronized Snapshot readComplete(String fixedTarget) {
    target(fixedTarget); last=null;
    try {
      ObjectNode observed=object(wire.readWhole(target));
      // Exact full tree matching includes effective values, links, notes, formatting, hidden flags,
      // comments, every dimension metadata entry, every control and any unexpected field/tab.
      for(Frame f:frames) if(f.raw.equals(observed)) { last=f; return f.model; }
      throw rejected();
    } catch(RuntimeException | LinkageError failure) { poisoned=true;throw rejected(); }
  }
  /** Immutable synthetic-only batch contract. No raw key, Google identity or arbitrary target input. */
  static final class PhaseManifest {
    private final Preview preview;
    private final JsonNode requests;
    private final String requestHash;
    private final ObjectNode before,after;
    private final String fixedTarget;
    static final String TARGET_TOKEN="__CONFIGURED_PINNED_SYNTHETIC_TARGET__";
    private PhaseManifest(Preview preview,JsonNode requests,ObjectNode before,ObjectNode after,String fixedTarget) {
      this.fixedTarget=fixedTarget;
      this.preview=preview;this.requests=requests.deepCopy();this.before=before.deepCopy();this.after=after.deepCopy();
      this.requestHash=hash(requests);
    }
    Preview preview(){return preview;}
    JsonNode requests(){return requests.deepCopy();}
    String requestHash(){return requestHash;}
    /** Public-safe synthetic transfer contract; private spreadsheet ID and raw snapshot are omitted.
     * This is a preview, never an authorization or an importable resume capability. */
    JsonNode exportPreview(){
      ObjectNode n=JSON.createObjectNode();n.put("version",1).put("purpose","SYNTHETIC_ONLY_PREVIEW_NOT_AUTHORIZATION")
          .put("targetHash",preview.targetHash()).put("phase",preview.phase().name()).put("planId",preview.planId())
          .put("beforeHash",preview.beforeHash()).put("afterHash",preview.afterHash()).put("requestHash",requestHash)
          .put("expiresAt",preview.expiresAt().toString()).put("confirmation",preview.confirmation())
          .put("proceduralFreezeOnly",true).put("cleanupAcceptance",false);
      String encoded=new String(bytes(requests),java.nio.charset.StandardCharsets.UTF_8);
      if(encoded.contains(TARGET_TOKEN))reject();
      JsonNode template;
      try {template=JSON.readTree(encoded.replace(fixedTarget,TARGET_TOKEN));}catch(Exception invalid){throw rejected();}
      n.put("targetToken",TARGET_TOKEN).put("templateRequestHash",hash(template));
      n.set("requestsTemplate",template);return n;
    }
    @Override public String toString(){return "Synthetic phase manifest (contents redacted)";}
  }
  record Audit(String phase,String snapshotStatus,String fingerprint,String requestHash,
      boolean writesAllowed,boolean cleanupAcceptance) {}
  private final Set<PhaseManifest> manifests=Collections.newSetFromMap(new IdentityHashMap<>());

  /** Prepare exact requests from internally generated, complete raw before/after frames. No I/O. */
  synchronized PhaseManifest manifest(Preview view) {
    if(manifests.size()>=16 || poisoned || last==null || !hash(last.raw).equals(view.beforeHash())
        || !SyntheticCleanupWorkbook.APPROVED_TARGET_SHA256.equals(view.targetHash()))reject();
    Frame after=forecast.get(view.afterHash());
    if(after==null || after.model.controls().size()!=view.phase().ordinal()+1)reject();
    Control c=after.model.controls().get(view.phase());
    if(c==null || !c.cells().get(1).get(2).equals(view.planId())
        || !c.cells().get(1).get(6).equals(view.expiresAt().toString()))reject();
    Prepared prepared=prepare(c,last.model.data(),after.model.data());
    if(!hash(prepared.raw).equals(view.afterHash()))reject();
    PhaseManifest m=new PhaseManifest(view,prepared.requests,last.raw,prepared.raw,target);manifests.add(m);return m;
  }
  /** Phase-aware audit of original complete bytes. Only known locally generated manifests are accepted.
   * Matching AFTER is observational only: it never unlocks replay or claims delivery/cleanup acceptance. */
  synchronized Audit audit(PhaseManifest manifest,byte[] originalCompleteRaw) {
    if(!manifests.contains(manifest))reject();
    try {
      ObjectNode raw=object(originalCompleteRaw);
      String status=raw.equals(manifest.before)?"MATCHES_BEFORE":raw.equals(manifest.after)?"MATCHES_AFTER":"UNVERIFIED";
      if(status.equals("UNVERIFIED"))poisoned=true;
      return new Audit(manifest.preview.phase().name(),status,hash(raw),manifest.requestHash,false,false);
    }catch(RuntimeException | LinkageError failure){poisoned=true;throw rejected();}
  }
  private record Prepared(ObjectNode raw,Snapshot model,ArrayNode requests) {}
  private Prepared prepare(Control control,Workbook before,Workbook after) {
    if(last==null || !last.model.data().equals(before) || !target.equals(after.spreadsheetId()))reject();
    int ordinal=fixtures.states.indexOf(before);
    if(ordinal<0 || ordinal>=4 || last.model.controls().size()!=ordinal)reject();
    Phase phase=Phase.values()[ordinal];
    if(last.model.controls().containsKey(phase) || !after.equals(fixtures.states.get(ordinal+1)))reject();
    var controls=new TreeMap<>(last.model.controls());controls.put(phase,control);
    Snapshot model=new Snapshot(after,controls,true,true,false,fixtures.anchorDigest());
    SyntheticCleanupExecutor.validate(model,fixtures);
    ObjectNode candidate=last.raw.deepCopy();
    ObjectNode marker=controlSheet(control,candidate.path("sheets").size());
    ((ArrayNode)candidate.path("sheets")).add(marker);
    ArrayNode requests=JSON.createArrayNode();
    requests.addObject().putObject("addSheet").set("properties",marker.path("properties").deepCopy());
    requests.add(cellUpdate(control.sheetId(),0,2,0,8,control.cells(),true));
    requests.addAll(renderData(candidate,after));
    return new Prepared(candidate,model,requests);
  }
  public synchronized void applyPhaseOnce(String fixedTarget,Control control,Workbook before,Workbook after) {
    target(fixedTarget);if(poisoned)reject();
    Prepared prepared=prepare(control,before,after);
    Phase phase=Phase.valueOf(control.cells().get(1).get(1));
    if(attempts.contains(phase))reject();
    // Consume before sending. Fixed-ID addSheet and all data are in this SAME atomic batch.
    attempts.add(phase);register(prepared.raw,prepared.model);send(new Batch(prepared.requests,prepared.raw));
  }
  private ArrayNode renderData(ObjectNode candidate,Workbook after) {
    ArrayNode requests=JSON.createArrayNode();
    for(var e:after.sheets().entrySet()) {
      Grid grid=e.getValue();
      if(grid.rowCount()!=32 || grid.columnCount()!=5 || grid.cells().size()!=32)reject();
      List<List<String>> rows=new ArrayList<>();
      for(var line:grid.cells()) {
        if(line.size()!=5)reject(); List<String> values=new ArrayList<>();
        for(Cell c:line) { if(!Set.of("TEXT","EMPTY").contains(c.type()) || !c.note().isEmpty() || c.value().length()>8192)reject(); values.add(c.value()); }
        rows.add(values);
      }
      ObjectNode sheet=sheet(candidate,e.getKey()); ObjectNode block=(ObjectNode)sheet.path("data").get(0);
      ArrayNode old=(ArrayNode)block.path("rowData");
      while(old.size()<32)old.addObject().putArray("values");
      for(int r=0;r<32;r++) {
        ObjectNode row=(ObjectNode)old.get(r);ArrayNode values=(ArrayNode)row.withArray("values");
        while(values.size()<5)values.addObject();
        for(int c=0;c<5;c++) values.set(c,literal(rows.get(r).get(c)));
      }
      // Value+format in one field mask clears auto-link formatting rather than preserving issuer links.
      requests.add(cellUpdate(tabIds.get(e.getKey()),0,32,0,5,rows,true));
    }
    // Exact whole candidate retains all control tabs and all dimensions; no clear/delete/resize request.
    return requests;
  }
  public synchronized String fullFingerprint(Snapshot model) {
    SyntheticCleanupExecutor.validate(model,fixtures);
    ObjectNode raw=anchor.deepCopy();
    if(!model.data().equals(fixtures.states.get(0)))renderData(raw,model.data());
    for(Control c:model.controls().values())((ArrayNode)raw.path("sheets")).add(controlSheet(c,raw.path("sheets").size()));
    String digest=hash(raw);if(!forecast.containsKey(digest) && forecast.size()>=32)reject();forecast.put(digest,new Frame(raw.deepCopy(),model));return digest;
  }
  private static String hash(JsonNode raw){return SyntheticCleanupWorkbook.sha256(new String(bytes(raw),java.nio.charset.StandardCharsets.UTF_8));}
  private void send(Batch batch) {
    try { wire.sendOnce(target,batch); }
    catch(RuntimeException | LinkageError unknown){poisoned=true;throw rejected();}
  }
  private void register(ObjectNode root,Snapshot model) {
    SyntheticCleanupExecutor.validate(model,fixtures);
    if(frames.size()>=9)reject();frames.add(new Frame(root.deepCopy(),model));
  }
  private static ObjectNode controlSheet(Control c,int index) {
    ObjectNode s=JSON.createObjectNode(),p=s.putObject("properties");
    p.put("sheetId",c.sheetId()).put("title",c.title()).put("index",index).put("sheetType","GRID");
    p.putObject("gridProperties").put("rowCount",2).put("columnCount",8);
    ObjectNode b=s.putArray("data").addObject().put("startRow",0).put("startColumn",0);
    ArrayNode rows=b.putArray("rowData");
    for(var row:c.cells()) {ArrayNode values=rows.addObject().putArray("values");for(String value:row)values.add(literal(value));}
    for(int i=0;i<2;i++)b.withArray("rowMetadata").addObject();
    for(int i=0;i<8;i++)b.withArray("columnMetadata").addObject();
    return s;
  }
  private static ObjectNode cellUpdate(int id,int r0,int r1,int c0,int c1,List<List<String>> rows,boolean formats) {
    ObjectNode outer=JSON.createObjectNode(),u=outer.putObject("updateCells");
    u.putObject("range").put("sheetId",id).put("startRowIndex",r0).put("endRowIndex",r1).put("startColumnIndex",c0).put("endColumnIndex",c1);
    u.put("fields",formats?"userEnteredValue,userEnteredFormat":"userEnteredValue");
    ArrayNode output=u.putArray("rows");
    for(var row:rows){ArrayNode values=output.addObject().putArray("values");for(String value:row){ObjectNode cell=values.addObject();if(!value.isEmpty())cell.putObject("userEnteredValue").put("stringValue",value);}}
    return outer;
  }
  private static ObjectNode literal(String text) {
    ObjectNode cell=JSON.createObjectNode();if(!text.isEmpty()) {
      cell.putObject("userEnteredValue").put("stringValue",text);cell.putObject("effectiveValue").put("stringValue",text);cell.put("formattedValue",text);
    } return cell;
  }
  private static ObjectNode sheet(ObjectNode root,String name) {
    for(JsonNode s:root.path("sheets"))if(name.equals(s.path("properties").path("title").asText()))return (ObjectNode)s;
    throw rejected();
  }
  private void target(String value){if(!target.equals(value))reject();}
  private static ObjectNode object(byte[] raw) {
    try { if(raw==null||raw.length==0||raw.length>4_194_304)reject();JsonNode n=JSON.readTree(raw);if(!(n instanceof ObjectNode))reject();return (ObjectNode)n; }
    catch(Exception invalid){throw rejected();}
  }
  static byte[] bytes(JsonNode node){try{return JSON.writeValueAsBytes(node);}catch(Exception invalid){throw rejected();}}
  private static IllegalStateException rejected(){return new IllegalStateException("Synthetic raw protocol rejected");}
  private static void reject(){throw rejected();}
}
