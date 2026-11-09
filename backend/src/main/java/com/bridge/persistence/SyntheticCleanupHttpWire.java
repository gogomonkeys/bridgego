package com.bridge.persistence;

import com.bridge.persistence.SyntheticCleanupExecutor.Phase;
import com.bridge.persistence.SyntheticCleanupRawProtocol.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** Restricted transport seam, not a CLI or permission to execute. No files, environment,
 * credential discovery, retries, logging or public entry points. A validated durable host must
 * obtain action-time permission and arm its exact manifest before a single dispatch.
 * Not installed in the ordinary website or preflight artifact. */
final class SyntheticCleanupHttpWire implements Wire, AutoCloseable {
  private static final String DATASET = "00000000-0000-4000-8000-000000000001";
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(4_194_304)
          .maxNestingDepth(32).maxStringLength(32768).maxTokenCount(1_000_000).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final String target;
  private final Set<Integer> tabIds;
  private final LiveQuarterStorage.Transport http;
  private final Clock clock;
  private PhaseManifest armed;
  private String readHash, pendingAfter;
  private int phase;
  private boolean stopped, closed;
  private Instant lastTime;

  /** Inputs must already be specifically authorized. Constructs a fixed-route client, sends nothing. */
  static SyntheticCleanupHttpWire open(String target, byte[] authorizedServiceAccount,
      byte[] completeScaffold, Clock clock) {
    Set<Integer> ids = scaffold(target, completeScaffold, clock);
    return new SyntheticCleanupHttpWire(target, ids,
        LiveQuarterHttp.open(new ManualQuarterMaintenance.Binding(target, DATASET), authorizedServiceAccount), clock);
  }

  /** Fake-transport test seam; does not relax endpoint or target binding in the real factory. */
  SyntheticCleanupHttpWire(String target, byte[] completeScaffold,
      LiveQuarterStorage.Transport fake, Clock clock) {
    this(target, scaffold(target, completeScaffold, clock), fake, clock);
  }
  private SyntheticCleanupHttpWire(String target, Set<Integer> ids,
      LiveQuarterStorage.Transport http, Clock clock) {
    this.target=target;this.tabIds=Set.copyOf(ids);this.http=Objects.requireNonNull(http);
    this.clock=Objects.requireNonNull(clock);this.lastTime=clock.instant();
  }
  private static Set<Integer> scaffold(String target, byte[] bytes, Clock clock) {
    try {
      SyntheticCleanupWorkbook.requireApprovedTarget(target);
      var book=LiveQuarterWorkbook.parseScaffold(bytes,
          new ManualQuarterMaintenance.Binding(target, DATASET), Objects.requireNonNull(clock), "SYNTHETIC-ONLY-FORMAT-TEST");
      Set<Integer> ids=new HashSet<>();
      for(var grid:book.grids().values()) {
        if(grid.rowCount()!=1000 || grid.columnCount()!=26 || grid.sheetId()<0
            || (grid.sheetId()>=SyntheticCleanupExecutor.CONTROL_ID_BASE
                && grid.sheetId()<SyntheticCleanupExecutor.CONTROL_ID_BASE+Phase.values().length)
            || !ids.add(grid.sheetId()))throw failure();
      }
      if(ids.size()!=8)throw failure();return ids;
    } catch(Exception | LinkageError e) { throw failure(); }
  }
  /** Does not itself attest user approval or journal durability; the sole host owns those checks. */
  synchronized void arm(PhaseManifest manifest) {
    try {
      active(target);if(armed!=null || pendingAfter!=null || manifest==null || phase>=4)throw failure();
      var p=manifest.preview();Instant now=clock.instant();
      if(p.phase()!=Phase.values()[phase] || !SyntheticCleanupWorkbook.APPROVED_TARGET_SHA256.equals(p.targetHash())
          || !p.beforeHash().equals(readHash) || !p.expiresAt().isAfter(now)
          || p.expiresAt().isAfter(now.plusSeconds(300)))throw failure();
      validateBatch(manifest.requests(),phase);
      if(!hash(manifest.requests()).equals(manifest.requestHash()))throw failure();
      armed=manifest;
    } catch(Exception | LinkageError e) { stopped=true;throw failure(); }
  }
  @Override public synchronized byte[] readWhole(String fixedTarget) {
    try {
      active(fixedTarget);byte[] original=http.readWorkbook();
      if(original==null || original.length==0 || original.length>LiveQuarterWorkbook.MAX_RESPONSE_BYTES)throw failure();
      JsonNode raw=JSON.readTree(original);
      if(!raw.isObject() || !target.equals(raw.path("spreadsheetId").asText())
          || !"COMMENTS_VIEW_MODE_INCLUDED".equals(raw.path("commentsViewMode").asText()))throw failure();
      String observed=hash(raw);
      if(pendingAfter!=null && !pendingAfter.equals(observed))throw failure();
      pendingAfter=null;readHash=observed;active(fixedTarget);return original.clone();
    } catch(Exception | LinkageError e) { stopped=true;throw failure(); }
  }
  @Override public synchronized void sendOnce(String fixedTarget,Batch batch) {
    byte[] body=null;
    try {
      active(fixedTarget);PhaseManifest permission=armed;
      if(permission==null || pendingAfter!=null || batch==null || !permission.preview().expiresAt().isAfter(clock.instant())
          || !permission.requests().equals(batch.requests()))throw failure();
      validateBatch(batch.requests(),phase);
      ObjectNode request=JSON.createObjectNode();request.set("requests",batch.requests());
      body=JSON.writeValueAsBytes(request);
      if(body.length==0 || body.length>LiveQuarterHttp.MAX_REQUEST_BYTES)throw failure();
      // Consume before delegation, including an exception or unknown HTTP response.
      armed=null;pendingAfter=permission.preview().afterHash();phase++;
      http.replaceWorkbook(body);
      active(fixedTarget);
    } catch(Exception | LinkageError e) { stopped=true;armed=null;throw failure(); }
    finally {if(body!=null)Arrays.fill(body,(byte)0);}
  }
  private void validateBatch(JsonNode requests,int ordinal) {
    if(!requests.isArray() || requests.size()!=10)throw failure();
    JsonNode add=requests.get(0);fields(add,"addSheet");fields(add.path("addSheet"),"properties");
    JsonNode p=add.path("addSheet").path("properties");fields(p,"sheetId","title","index","sheetType","gridProperties");
    int marker=SyntheticCleanupExecutor.CONTROL_ID_BASE+ordinal;
    integer(p,"sheetId",marker);integer(p,"index",8+ordinal);
    if(!("_synthetic_once_"+Phase.values()[ordinal]).equals(p.path("title").asText())
        || !"GRID".equals(p.path("sheetType").asText()))throw failure();
    fields(p.path("gridProperties"),"rowCount","columnCount");integer(p.path("gridProperties"),"rowCount",2);integer(p.path("gridProperties"),"columnCount",8);
    Set<Integer> seen=new HashSet<>();
    for(int i=1;i<10;i++) {
      JsonNode r=requests.get(i);fields(r,"updateCells");JsonNode u=r.path("updateCells");fields(u,"range","fields","rows");
      if(!"userEnteredValue,userEnteredFormat".equals(u.path("fields").asText()))throw failure();
      JsonNode range=u.path("range");fields(range,"sheetId","startRowIndex","endRowIndex","startColumnIndex","endColumnIndex");
      if(!range.path("sheetId").isIntegralNumber() || !range.path("sheetId").canConvertToInt())throw failure();
      int id=range.path("sheetId").intValue(),rows=i==1?2:32,columns=i==1?8:5;
      if(i==1 ? id!=marker : !tabIds.contains(id)||!seen.add(id))throw failure();
      integer(range,"startRowIndex",0);integer(range,"startColumnIndex",0);integer(range,"endRowIndex",rows);integer(range,"endColumnIndex",columns);
      if(!u.path("rows").isArray() || u.path("rows").size()!=rows)throw failure();
      for(JsonNode row:u.path("rows")) {
        fields(row,"values");if(!row.path("values").isArray()||row.path("values").size()!=columns)throw failure();
        for(JsonNode cell:row.path("values")) {
          if(!cell.isObject())throw failure();if(cell.isEmpty())continue;
          fields(cell,"userEnteredValue");JsonNode value=cell.path("userEnteredValue");fields(value,"stringValue");
          if(!value.path("stringValue").isTextual()||value.path("stringValue").textValue().length()>8192)throw failure();
        }
      }
    }
    if(!seen.equals(tabIds))throw failure();
  }
  private static void fields(JsonNode n,String... allowed) {
    if(!n.isObject())throw failure();Set<String> got=new HashSet<>();n.fieldNames().forEachRemaining(got::add);
    if(!got.equals(Set.of(allowed)))throw failure();
  }
  private static void integer(JsonNode n,String name,int value) {
    if(!n.path(name).isIntegralNumber() || !n.path(name).canConvertToInt() || n.path(name).intValue()!=value)throw failure();
  }
  private static String hash(JsonNode n) {
    try{return SyntheticCleanupWorkbook.sha256(JSON.writeValueAsString(n));}catch(Exception e){throw failure();}
  }
  private void active(String fixedTarget) {
    Instant now=clock.instant();
    if(stopped||closed||Thread.currentThread().isInterrupted()||!target.equals(fixedTarget)||now.isBefore(lastTime))throw failure();
    lastTime=now;
  }
  @Override public synchronized void close(){closed=true;armed=null;try{http.close();}catch(Exception ignored){}}
  @Override public String toString(){return "SyntheticCleanupHttpWire[redacted]";}
  private static IllegalStateException failure(){return new IllegalStateException("Synthetic HTTP transfer stopped");}
}
