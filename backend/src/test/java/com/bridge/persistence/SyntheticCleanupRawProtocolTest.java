package com.bridge.persistence;

import com.bridge.persistence.SyntheticCleanupExecutor.*;
import com.bridge.persistence.SyntheticCleanupRawProtocol.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupRawProtocolTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String MARKER="SYNTHETIC-ONLY-FORMAT-TEST";
  static class FakeWire implements Wire {
    byte[] raw;int writes;boolean unknown,loseControl,extraMetadata;
    List<Batch> batches=new ArrayList<>();
    String controlFault=""; int unknownAfter=0;
    FakeWire(byte[] raw){this.raw=raw;}
    public byte[] readWhole(String target){return raw.clone();}
    public void sendOnce(String target,Batch batch){
      writes++;batches.add(batch);
      try {
        ObjectNode n=(ObjectNode)JSON.readTree(raw);
        // Independent fake Sheets interpreter: apply actual batch request fields, NOT an expected result.
        for(JsonNode request:batch.requests()) {
          if(request.has("addSheet")) {
            JsonNode p=request.path("addSheet").path("properties");
            for(JsonNode old:n.path("sheets")) if(old.path("properties").path("sheetId").equals(p.path("sheetId")) || old.path("properties").path("title").equals(p.path("title")))throw new IllegalStateException();
            ObjectNode s=((ArrayNode)n.path("sheets")).addObject();s.set("properties",p.deepCopy());
            ObjectNode b=s.putArray("data").addObject().put("startRow",0).put("startColumn",0);b.putArray("rowData");
            for(int i=0;i<p.path("gridProperties").path("rowCount").asInt();i++)b.withArray("rowMetadata").addObject();
            for(int i=0;i<p.path("gridProperties").path("columnCount").asInt();i++)b.withArray("columnMetadata").addObject();
          } else if(request.has("updateCells")) {
            JsonNode u=request.path("updateCells"),range=u.path("range");ObjectNode sheet=null;
            for(JsonNode q:n.path("sheets"))if(q.path("properties").path("sheetId").asInt()==range.path("sheetId").asInt())sheet=(ObjectNode)q;
            if(sheet==null)throw new IllegalStateException();
            ArrayNode rows=(ArrayNode)sheet.path("data").get(0).path("rowData");int start=range.path("startRowIndex").asInt(),end=range.path("endRowIndex").asInt();
            while(rows.size()<end)rows.addObject().putArray("values");
            for(int r=start;r<end;r++) {
              ArrayNode cells=((ObjectNode)rows.get(r)).withArray("values");int c0=range.path("startColumnIndex").asInt(),c1=range.path("endColumnIndex").asInt();while(cells.size()<c1)cells.addObject();
              for(int c=c0;c<c1;c++) {
                JsonNode value=u.path("rows").path(r-start).path("values").path(c-c0).path("userEnteredValue");ObjectNode cell=JSON.createObjectNode();
                if(!value.isMissingNode()){cell.set("userEnteredValue",value.deepCopy());cell.set("effectiveValue",value.deepCopy());cell.put("formattedValue",value.path("stringValue").asText());}cells.set(c,cell);
              }
            }
          } else throw new IllegalStateException();
        }
        if(!controlFault.isEmpty()) {
          ObjectNode control=(ObjectNode)n.path("sheets").get(n.path("sheets").size()-1);
          switch(controlFault) {
            case "hidden" -> ((ObjectNode)control.path("properties")).put("hidden",true);
            case "formula" -> ((ObjectNode)control.path("data").get(0).path("rowData").get(1).path("values").get(0)).putObject("userEnteredValue").put("formulaValue","=1");
            case "extraRow" -> ((ArrayNode)control.path("data").get(0).path("rowData")).addObject().putArray("values");
            case "missingMetadata" -> ((ObjectNode)control.path("data").get(0)).remove("rowMetadata");
          }
        }
        if(loseControl)((ArrayNode)n.path("sheets")).remove(n.path("sheets").size()-1);
        if(extraMetadata)n.put("unsupportedExtraMetadata","untrusted");
        raw=SyntheticCleanupRawProtocol.bytes(n);
      }catch(Exception e){throw new IllegalStateException("Fake batch rejected atomically");}
      if(unknown || writes==unknownAfter)throw new IllegalStateException("private response must not escape");
    }
  }
  static ObjectNode rawScaffold(String target) {
    var local=new LocalManualQuarterRehearsal.Fixture();ObjectNode root=SyntheticCleanupScaffoldTest.scaffold(local);
    root.put("spreadsheetId",target);root.put("spreadsheetUrl","https://docs.google.com/spreadsheets/d/"+target+"/edit");
    String injected=System.getProperty("bridge.synthetic.test.tabids");Assumptions.assumeTrue(injected!=null);
    String[] ids=injected.split(",");assertEquals(8,ids.length);
    int i=0;
    for(JsonNode node:root.path("sheets")) {
      ObjectNode s=(ObjectNode)node,p=(ObjectNode)s.path("properties");p.put("sheetId",Integer.parseInt(ids[i++]));
      ((ObjectNode)p.path("gridProperties")).put("rowCount",1000).put("columnCount",26);
      ObjectNode block=(ObjectNode)s.path("data").get(0);ArrayNode rows=block.putArray("rowMetadata");for(int r=0;r<1000;r++)rows.addObject();
      ArrayNode columns=block.putArray("columnMetadata");for(int c=0;c<26;c++)columns.addObject();
    }
    return root;
  }
  record Setup(FixtureSet fixtures,FakeWire wire,SyntheticCleanupRawProtocol gateway,SyntheticCleanupExecutor executor,ObjectNode raw){}
  Setup setup(){
    String target=SyntheticCleanupExecutorTest.target();var f=SyntheticCleanupExecutor.generate(target,SyntheticCleanupExecutorTest.NOW);
    ObjectNode root=rawScaffold(target);var w=new FakeWire(SyntheticCleanupRawProtocol.bytes(root));
    var g=new SyntheticCleanupRawProtocol(f,w,w.raw);var e=new SyntheticCleanupExecutor(f,g,Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC),()->true);
    return new Setup(f,w,g,e,root);
  }
  @Test void completeRawFourPhaseRunUsesFixedExistingIdsPreservesDimensionsAndAllControlSheets() throws Exception {
    var s=setup();
    for(Phase phase:Phase.values()){
      var p=s.executor.preview(phase);assertEquals(Outcome.VERIFIED,s.executor.execute(p.planId(),p.confirmation()).outcome());
      JsonNode observed=JSON.readTree(s.wire.raw);assertEquals(9+phase.ordinal(),observed.path("sheets").size());
      for(int i=0;i<8;i++)assertEquals(s.raw.path("sheets").get(i).path("properties"),observed.path("sheets").get(i).path("properties"));
      JsonNode claim=s.wire.batches.get(phase.ordinal()).requests();assertEquals(10,claim.size());assertTrue(claim.get(0).has("addSheet"));
      assertEquals(CONTROL_ID_BASE()+phase.ordinal(),claim.get(0).path("addSheet").path("properties").path("sheetId").asInt());
      JsonNode rewrite=claim;
      for(int q=2;q<rewrite.size();q++){JsonNode request=rewrite.get(q);JsonNode u=request.path("updateCells");assertEquals(32,u.path("range").path("endRowIndex").asInt());assertEquals(5,u.path("range").path("endColumnIndex").asInt());assertEquals("userEnteredValue,userEnteredFormat",u.path("fields").asText());}
    }
    assertEquals(4,s.wire.writes);
  }
  static int CONTROL_ID_BASE(){return SyntheticCleanupExecutor.CONTROL_ID_BASE;}
  @Test void originalOmittedCommentsAndUnknownMetadataRejectedByStrictParserBeforeAnyWire() {
    var s=setup();for(String field:List.of("commentsViewMode","unknown")){
      var n=s.raw.deepCopy();n.put(field,field.equals("commentsViewMode")?"COMMENTS_VIEW_MODE_OMITTED":"x");
      assertThrows(IllegalStateException.class,()->new SyntheticCleanupRawProtocol(s.fixtures,s.wire,SyntheticCleanupRawProtocol.bytes(n)));
    }assertEquals(0,s.wire.writes);
  }
  @Test void everyRawMetadataChangeOutsideWriteRangeIsDetected() {
    for(String mode:List.of("hidden","rowMetadata","formula","comments","extraTab")){
      var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);var n=s.raw.deepCopy();
      switch(mode){
        case "hidden"->((ObjectNode)n.path("sheets").get(0).path("properties")).put("hidden",true);
        case "rowMetadata"->((ObjectNode)n.path("sheets").get(0).path("data").get(0).path("rowMetadata").get(999)).put("hiddenByUser",true);
        case "formula"->((ObjectNode)n.path("sheets").get(0).path("data").get(0).path("rowData").get(0).path("values").get(0)).putObject("userEnteredValue").put("formulaValue","=1");
        case "comments"->n.putArray("comments").addObject().put("content","untrusted");
        case "extraTab"->((ArrayNode)n.path("sheets")).add(n.path("sheets").get(0).deepCopy());
      }
      s.wire.raw=SyntheticCleanupRawProtocol.bytes(n);assertEquals(Outcome.REJECTED,s.executor.execute(p.planId(),p.confirmation()).outcome());assertEquals(0,s.wire.writes);
    }
  }
  @Test void unknownClaimNeverSendsDataBatchAndNewExecutorCannotAdvanceWithMarker() {
    var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);s.wire.unknown=true;
    assertEquals(Outcome.STOPPED_UNCERTAIN,s.executor.execute(p.planId(),p.confirmation()).outcome());assertEquals(1,s.wire.writes);
    var fresh=new SyntheticCleanupExecutor(s.fixtures,s.gateway,Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC),()->true);
    assertThrows(IllegalStateException.class,()->fresh.preview(Phase.ERASE_ACCOUNT));
  }
  @Test void droppedOrChangedControlReadbackStopsBeforeDataMutation(){
    for(boolean lost:List.of(true,false)){
      var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);s.wire.loseControl=lost;s.wire.extraMetadata=!lost;
      assertEquals(Outcome.STOPPED_UNCERTAIN,s.executor.execute(p.planId(),p.confirmation()).outcome());assertEquals(1,s.wire.writes);
    }
  }
  @Test void duplicateJsonKeysAndTrailingJsonRejected(){
    var s=setup();String raw=new String(s.wire.raw,java.nio.charset.StandardCharsets.UTF_8);
    s.wire.raw=(raw+"{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertThrows(IllegalStateException.class,()->s.gateway.readComplete(s.fixtures.target));
    s.wire.raw=raw.replaceFirst("\\{","{\"commentsViewMode\":\"OMITTED\",").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertThrows(IllegalStateException.class,()->s.gateway.readComplete(s.fixtures.target));
  }
  @Test void rawWriterRejectsWrongControlAndRepeatedAtomicWritesIndependentlyOfExecutor() {
    var s=setup();s.gateway.readComplete(s.fixtures.target);
    Control wrong=new Control(1,"wrong",List.of());
    assertThrows(IllegalStateException.class,()->s.gateway.applyPhaseOnce(s.fixtures.target,wrong,s.fixtures.states.get(0),s.fixtures.states.get(1)));
    assertEquals(0,s.wire.writes);
    var p=s.executor.preview(Phase.SEED_ERASURE);assertEquals(Outcome.VERIFIED,s.executor.execute(p.planId(),p.confirmation()).outcome());
    Control prior=s.gateway.readComplete(s.fixtures.target).controls().get(Phase.SEED_ERASURE);
    assertThrows(IllegalStateException.class,()->s.gateway.applyPhaseOnce(s.fixtures.target,prior,s.fixtures.states.get(0),s.fixtures.states.get(1)));
    assertEquals(1,s.wire.writes);
  }
  @Test void previewHashesBindCompleteRawFramesAndOriginalLayout() throws Exception {
    var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);
    String rawHash=SyntheticCleanupWorkbook.sha256(JSON.writeValueAsString(JSON.readTree(s.wire.raw)));
    assertEquals(rawHash,p.beforeHash());
    assertEquals(Outcome.VERIFIED,s.executor.execute(p.planId(),p.confirmation()).outcome());
    assertEquals(SyntheticCleanupWorkbook.sha256(JSON.writeValueAsString(JSON.readTree(s.wire.raw))),p.afterHash());
  }

  @Test void rejectedReadPoisonsLaterWritesEvenAfterOriginalBytesReturn() {
    var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);byte[] original=s.wire.raw.clone();
    s.wire.raw="not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class,()->s.gateway.readComplete(s.fixtures.target));
    s.wire.raw=original;
    assertEquals(Outcome.STOPPED_UNCERTAIN,s.executor.execute(p.planId(),p.confirmation()).outcome());
    assertEquals(0,s.wire.writes);
  }

  @Test void controlHiddenFormulaExtraRowOrMissingMetadataCannotBeIgnored() {
    for(String fault:List.of("hidden","formula","extraRow","missingMetadata")) {
      var s=setup();s.wire.controlFault=fault;var p=s.executor.preview(Phase.SEED_ERASURE);
      assertEquals(Outcome.STOPPED_UNCERTAIN,s.executor.execute(p.planId(),p.confirmation()).outcome());
      assertEquals(1,s.wire.writes);
    }
  }
  @Test void lostDataAcknowledgementMayReconcileReadOnlyButNeverContinueOrReplay() {
    var s=setup();s.wire.unknownAfter=1;var p=s.executor.preview(Phase.SEED_ERASURE);
    assertEquals(Outcome.STOPPED_UNCERTAIN,s.executor.execute(p.planId(),p.confirmation()).outcome());
    assertEquals(Outcome.VERIFIED,s.executor.inspect(p.planId()).outcome());
    assertThrows(IllegalStateException.class,()->s.executor.preview(Phase.ERASE_ACCOUNT));
    assertEquals(Outcome.REJECTED,s.executor.execute(p.planId(),p.confirmation()).outcome());
    assertEquals(1,s.wire.writes);
  }

  @Test void manifestExportsExactAtomicRequestsAndAuditsEveryPhaseWithoutGrantingWrites() throws Exception {
    var s=setup();
    for(Phase phase:Phase.values()) {
      var p=s.executor.preview(phase);var m=s.gateway.manifest(p);
      assertEquals("MATCHES_BEFORE",s.gateway.audit(m,s.wire.raw).snapshotStatus());
      JsonNode export=m.exportPreview();assertEquals(p.beforeHash(),export.path("beforeHash").asText());
      assertEquals(p.afterHash(),export.path("afterHash").asText());assertFalse(export.toString().contains(s.fixtures.target));
      assertEquals(10,export.path("requestsTemplate").size());
      assertEquals(export.path("templateRequestHash").asText(),SyntheticCleanupWorkbook.sha256(JSON.writeValueAsString(export.path("requestsTemplate"))));
      String rebound=JSON.writeValueAsString(export.path("requestsTemplate")).replace(PhaseManifest.TARGET_TOKEN,s.fixtures.target);
      assertEquals(m.requestHash(),SyntheticCleanupWorkbook.sha256(rebound));
      ((ObjectNode)export).put("beforeHash","untrusted");((ArrayNode)export.path("requestsTemplate")).removeAll();
      assertEquals(10,m.requests().size());assertEquals(p.beforeHash(),m.exportPreview().path("beforeHash").asText());
      assertEquals(Outcome.VERIFIED,s.executor.execute(p.planId(),p.confirmation()).outcome());
      var audit=s.gateway.audit(m,s.wire.raw);assertEquals("MATCHES_AFTER",audit.snapshotStatus());
      assertFalse(audit.writesAllowed());assertFalse(audit.cleanupAcceptance());
      assertEquals(m.requests(),s.wire.batches.get(phase.ordinal()).requests());
    }
    assertEquals(4,s.wire.writes);
  }
  @Test void duplicateFixedControlRejectsWholeAtomicBatchAndLeavesAllDataUnchanged() {
    var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);
    assertEquals(Outcome.VERIFIED,s.executor.execute(p.planId(),p.confirmation()).outcome());
    byte[] after=s.wire.raw.clone();var duplicate=s.wire.batches.get(0);
    assertThrows(IllegalStateException.class,()->s.wire.sendOnce(s.fixtures.target,duplicate));
    assertArrayEquals(after,s.wire.raw);
  }
  @Test void unexpectedKnownOldFrameStopsPlanEvenIfExpectedFrameReturns() {
    var s=setup();byte[] old=s.wire.raw.clone();var seed=s.executor.preview(Phase.SEED_ERASURE);
    assertEquals(Outcome.VERIFIED,s.executor.execute(seed.planId(),seed.confirmation()).outcome());
    byte[] expected=s.wire.raw.clone();var erase=s.executor.preview(Phase.ERASE_ACCOUNT);
    s.wire.raw=old;assertEquals(Outcome.REJECTED,s.executor.execute(erase.planId(),erase.confirmation()).outcome());
    s.wire.raw=expected;assertEquals(Outcome.REJECTED,s.executor.execute(erase.planId(),erase.confirmation()).outcome());
    assertThrows(IllegalStateException.class,()->s.executor.preview(Phase.ERASE_ACCOUNT));assertEquals(1,s.wire.writes);
  }
  @Test void auditMismatchPoisonsWriterAndMatchingLaterAuditCannotUnlockIt() {
    var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);var manifest=s.gateway.manifest(p);
    byte[] good=s.wire.raw.clone();var changed=s.raw.deepCopy();changed.put("unknownMetadata",true);
    assertEquals("UNVERIFIED",s.gateway.audit(manifest,SyntheticCleanupRawProtocol.bytes(changed)).snapshotStatus());
    assertEquals("MATCHES_BEFORE",s.gateway.audit(manifest,good).snapshotStatus());
    assertEquals(Outcome.STOPPED_UNCERTAIN,s.executor.execute(p.planId(),p.confirmation()).outcome());assertEquals(0,s.wire.writes);
  }
  @Test void foreignManifestAndMalformedRawCannotBeTrustedOrImported() {
    var a=setup();var b=setup();var pa=a.executor.preview(Phase.SEED_ERASURE);var ma=a.gateway.manifest(pa);
    assertThrows(IllegalStateException.class,()->b.gateway.audit(ma,b.wire.raw));
    assertThrows(IllegalStateException.class,()->a.gateway.audit(ma,"{}{}".getBytes()));
    assertEquals(Outcome.STOPPED_UNCERTAIN,a.executor.execute(pa.planId(),pa.confirmation()).outcome());
    assertEquals(0,a.wire.writes);
  }
  @Test void lostAcknowledgementCanBeAuditedAfterButCannotContinueOrRetry() {
    var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);var m=s.gateway.manifest(p);s.wire.unknown=true;
    assertEquals(Outcome.STOPPED_UNCERTAIN,s.executor.execute(p.planId(),p.confirmation()).outcome());
    assertEquals("MATCHES_AFTER",s.gateway.audit(m,s.wire.raw).snapshotStatus());
    assertThrows(IllegalStateException.class,()->s.executor.preview(Phase.ERASE_ACCOUNT));
    assertEquals(Outcome.REJECTED,s.executor.execute(p.planId(),p.confirmation()).outcome());assertEquals(1,s.wire.writes);
  }

  @Test void invalidFinalRequestRejectsWholeBatchIncludingEarlierControlAndDataUpdates() {
    var s=setup();var p=s.executor.preview(Phase.SEED_ERASURE);var m=s.gateway.manifest(p);
    ArrayNode requests=(ArrayNode)m.requests();requests.addObject().putObject("unsupportedRequest").put("x",true);
    byte[] before=s.wire.raw.clone();
    assertThrows(IllegalStateException.class,()->s.wire.sendOnce(s.fixtures.target,new Batch(requests,null)));
    assertArrayEquals(before,s.wire.raw);
  }

}
