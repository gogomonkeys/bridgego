package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.junit.jupiter.api.Test;

class LiveQuarterStorageTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static class FakeTransport implements LiveQuarterStorage.Transport {
    final LocalManualQuarterRehearsal.Fixture fixture = new LocalManualQuarterRehearsal.Fixture();
    final List<String> events = new ArrayList<>();
    final List<JsonNode> bodies = new ArrayList<>();
    Consumer<ObjectNode> mutate = tree -> {};
    IntConsumer beforeRead = count -> {}, afterWrite = count -> {};
    int reads, posts, failBefore, timeoutAfter;
    boolean unavailableAfterWrite, closed;
    ObjectNode last;
    public byte[] readWorkbook() {
      events.add("GET"); beforeRead.accept(++reads);
      if (unavailableAfterWrite && posts > 0) throw new IllegalStateException("PRIVATE-RAW-BODY");
      last = LiveQuarterWorkbookTest.fixtureTree(fixture); mutate.accept(last);
      return LiveQuarterWorkbookTest.bytes(last);
    }
    public void replaceWorkbook(byte[] body) {
      try {
        events.add("POST"); posts++;
        JsonNode parsed = JSON.readTree(body); bodies.add(parsed);
        if (failBefore == posts) throw new SheetsGateway.Failure(403, 0);
        assertEquals(Set.of("requests"), fields(parsed)); assertEquals(7, parsed.path("requests").size());
        Set<String> changed = new HashSet<>();
        for (var request : parsed.path("requests")) {
          assertEquals(Set.of("updateCells"), fields(request)); var update = request.path("updateCells");
          assertEquals(Set.of("range", "rows", "fields"), fields(update));
          assertEquals("userEnteredValue", update.path("fields").asText());
          var range = update.path("range"); int id = range.path("sheetId").asInt(); String title = null;
          for (var sheet : last.path("sheets")) if (sheet.path("properties").path("sheetId").asInt() == id)
            title = sheet.path("properties").path("title").asText();
          assertNotNull(title); assertNotEquals("dataset", title); assertTrue(changed.add(title));
          var grid = LiveQuarterWorkbookTest.properties(last, title).path("gridProperties");
          assertEquals(0, range.path("startRowIndex").asInt()); assertEquals(0, range.path("startColumnIndex").asInt());
          assertEquals(grid.path("rowCount").asInt(), range.path("endRowIndex").asInt());
          assertEquals(Math.min(5, grid.path("columnCount").asInt()), range.path("endColumnIndex").asInt());
          List<List<String>> rows = new ArrayList<>();
          for (var row : update.path("rows")) {
            List<String> values = new ArrayList<>();
            for (var cell : row.path("values")) {
              if (cell.isEmpty()) values.add("");
              else {
                assertEquals(Set.of("userEnteredValue"), fields(cell));
                assertEquals(Set.of("stringValue"), fields(cell.path("userEnteredValue")));
                values.add(cell.path("userEnteredValue").path("stringValue").textValue());
              }
            }
            rows.add(values);
          }
          fixture.tables.put(title, rows);
        }
        assertEquals(ManualQuarterMaintenance.MUTABLE, changed);
        afterWrite.accept(posts);
        if (timeoutAfter == posts) throw new SheetsGateway.Failure(0, 0);
      } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }
    public void close() { closed = true; }
  }
  static Set<String> fields(JsonNode node) { Set<String> keys = new HashSet<>(); node.fieldNames().forEachRemaining(keys::add); return keys; }
  record Run(LiveQuarterStorage storage, ManualQuarterMaintenance operator, ManualQuarterMaintenance.Preview plan) {
    ManualQuarterMaintenance.Result execute() { return operator.execute(plan.planId(), plan.confirmationText()); }
  }
  static Run prepared(FakeTransport fake) { return prepared(fake, fake.fixture.clock, () -> true); }
  static Run prepared(FakeTransport fake, Clock clock, BooleanSupplier stopped) {
    var storage = new LiveQuarterStorage(fake.fixture.binding, fake, clock, stopped);
    var operator = new ManualQuarterMaintenance(fake.fixture.binding, storage, clock, stopped, true);
    var plan = operator.preview(fake.fixture.binding.stoppedConfirmation());
    storage.authorize(plan, storage.fingerprint()); return new Run(storage, operator, plan);
  }

  @Test void realCoreWritesSevenFreshBoundedRangesInEachPhaseAndPreservesControlData() {
    var fake = new FakeTransport(); var run = prepared(fake);
    assertEquals(0, fake.posts);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, run.execute().outcome());
    fake.fixture.verify(); assertEquals(2, fake.posts); assertTrue(fake.reads >= 7);
    for (int i = 0; i < fake.events.size(); i++) if (fake.events.get(i).equals("POST")) assertEquals("GET", fake.events.get(i-1));
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, run.execute().outcome()); assertEquals(2, fake.posts);
    run.storage.close(); assertTrue(fake.closed);
  }

  @Test void observedDisplayMetadataIsPreservedByBothValueOnlyPhases() {
    var fake = new FakeTransport();
    fake.mutate = LiveQuarterWorkbookTest::applyObservedDisplayMetadata;
    var run = prepared(fake);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, run.execute().outcome());
    assertEquals(2, fake.posts); fake.fixture.verify();
    assertTrue(fake.last.path("properties").path("spreadsheetTheme").isObject());
    for (var body : fake.bodies) for (var request : body.path("requests"))
      assertEquals("userEnteredValue", request.path("updateCells").path("fields").asText());
  }

  @Test void benignDisplayDriftWithIdenticalValuesStillStopsBeforeEachWrite() {
    for (boolean afterFirstWrite : List.of(false, true)) {
      var fake = new FakeTransport();
      fake.mutate = LiveQuarterWorkbookTest::applyObservedDisplayMetadata;
      var run = prepared(fake);
      Consumer<ObjectNode> changed = root -> {
        LiveQuarterWorkbookTest.applyObservedDisplayMetadata(root);
        ((ObjectNode)LiveQuarterWorkbookTest.properties(root, "accounts").path("gridProperties"))
            .put("rowGroupControlAfter", false);
      };
      if (afterFirstWrite) fake.afterWrite = count -> fake.mutate = changed;
      else fake.mutate = changed;
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, run.execute().outcome());
      assertEquals(afterFirstWrite ? 1 : 0, fake.posts);
    }
  }

  @Test void exactObservedCellFormatsSurviveValueOnlyCleanup() {
    var fake = new FakeTransport(); fake.mutate = x -> {
      LiveQuarterWorkbookTest.applyObservedDisplayMetadata(x);
      ObjectNode format = x.path("properties").path("defaultFormat").deepCopy();
      format.put("horizontalAlignment", "LEFT"); format.put("hyperlinkDisplayType", "PLAIN_TEXT");
      LiveQuarterWorkbookTest.cell(x, "accounts", 0, 0).set("effectiveFormat", format);
    };
    var run = prepared(fake);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, run.execute().outcome());
    assertEquals(2, fake.posts); fake.fixture.verify();
    for (var body : fake.bodies) for (var request : body.path("requests"))
      assertEquals("userEnteredValue", request.path("updateCells").path("fields").asText());
  }

  @Test void inheritedDefaultFormatDisappearanceAfterClearingPermitsVerifiedSecondWrite() {
    var fake = new FakeTransport(); fake.mutate = LiveQuarterWorkbookTest::applyObservedCellDisplayMetadata;
    var run = prepared(fake);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, run.execute().outcome());
    assertEquals(2, fake.posts);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, run.execute().outcome());
    assertEquals(2, fake.posts);
  }

  @Test void acceptedCellFormatPresenceDriftStopsBeforeEitherPhase() {
    for (String key : List.of("horizontalAlignment", "hyperlinkDisplayType")) for (boolean afterFirst : List.of(false, true)) {
      var fake = new FakeTransport(); fake.mutate = LiveQuarterWorkbookTest::applyObservedCellDisplayMetadata;
      var run = prepared(fake);
      Consumer<ObjectNode> changed = x -> {
        LiveQuarterWorkbookTest.applyObservedCellDisplayMetadata(x);
        ((ObjectNode)LiveQuarterWorkbookTest.cell(x, "accounts", 0, 0).get("effectiveFormat")).remove(key);
      };
      if (afterFirst) fake.afterWrite = count -> fake.mutate = changed; else fake.mutate = changed;
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, run.execute().outcome());
      assertEquals(afterFirst ? 1 : 0, fake.posts);
    }
  }

  @Test void freshReturnedIdsAndFullThousandRowBoundAreUsedNotHardcodedOrCached() {
    var fake = new FakeTransport();
    fake.mutate = root -> {
      for (var sheet : root.path("sheets")) {
        var p = (ObjectNode)sheet.path("properties"); p.put("sheetId", p.path("sheetId").asInt()+2000);
        ((ObjectNode)p.path("gridProperties")).put("rowCount", 1000);
        var rm = (ArrayNode)sheet.path("data").get(0).path("rowMetadata"); while (rm.size()<1000) rm.addObject();
      }
    };
    var run = prepared(fake);
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE, run.execute().outcome());
    fake.fixture.verify();
    for (var body : fake.bodies) for (var request : body.path("requests"))
      assertTrue(request.path("updateCells").path("range").path("sheetId").asInt()>=2101);
  }

  @Test void noAuthorizationWrongTargetOrWrongWholeFingerprintCannotWrite() {
    var fake = new FakeTransport(); var binding = fake.fixture.binding;
    var storage = new LiveQuarterStorage(binding, fake, fake.fixture.clock, () -> true);
    assertThrows(IllegalStateException.class, () -> storage.replaceAtomic(binding, "bad", Map.of())); assertEquals(0, fake.reads);
    assertThrows(IllegalStateException.class, () -> storage.readComplete(new ManualQuarterMaintenance.Binding("WRONG_TARGET_ONLY", binding.datasetId())));
    assertEquals(0, fake.reads);
    var operator = new ManualQuarterMaintenance(binding, storage, fake.fixture.clock, () -> true, true);
    var p = operator.preview(binding.stoppedConfirmation());
    assertThrows(IllegalStateException.class, () -> storage.authorize(p, "0".repeat(64))); assertEquals(0, fake.posts);
  }

  @Test void tabReplacementBetweenReadsRejectsBeforeEachPhaseEvenWithSameValues() {
    for (int changedRead : List.of(2, 4, 7)) {
      var fake = new FakeTransport(); var run = prepared(fake);
      fake.beforeRead = count -> { if (count == changedRead) fake.mutate = root -> LiveQuarterWorkbookTest.properties(root, "accounts").put("sheetId", 999); };
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, run.execute().outcome());
      assertEquals(changedRead==7?1:0, fake.posts);
    }
  }

  @Test void staleValueDigestAndUnsupportedExtraDataBlockBeforePost() {
    for (int mode=0; mode<3; mode++) {
      var fake = new FakeTransport(); var run = prepared(fake);
      if (mode==0) fake.fixture.tables.get("auth_identities").set(1, List.of("https://accounts.google.com", "CHANGED-SYNTHETIC", fake.fixture.tables.get("accounts").get(1).get(0), "1"));
      if (mode==1) fake.mutate = root -> LiveQuarterWorkbookTest.cell(root,"accounts",1,1).putObject("userEnteredValue").put("numberValue",1);
      if (mode==2) fake.mutate = root -> {
        var block=((ArrayNode)LiveQuarterWorkbookTest.sheet(root,"envelopes").path("data")).addObject();
        block.put("startRow",31).put("startColumn",25); block.putArray("rowData").addObject().putArray("values").add(LiveQuarterWorkbookTest.literal("outside-data"));
      };
      assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN, run.execute().outcome()); assertEquals(0,fake.posts);
    }
  }

  @Test void exactLiteralDerivedHyperlinkIsAllowedAfterRewriteWithoutLosingMetadataBinding() {
    var fake = new FakeTransport(); var run = prepared(fake);
    fake.afterWrite = count -> fake.mutate = root -> LiveQuarterWorkbookTest.cell(root,"auth_identities",1,0).put("hyperlink","https://accounts.google.com");
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,run.execute().outcome()); assertEquals(2,fake.posts);
  }

  @Test void timeoutAfterEitherPhaseReadsBackAndNeverRepeatsThatPost() {
    for (int phase : List.of(1,2)) {
      var fake = new FakeTransport(); fake.timeoutAfter=phase; var run=prepared(fake);
      assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,run.execute().outcome());
      assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,run.execute().outcome()); assertEquals(2,fake.posts);
    }
  }

  @Test void rejectedWriteAndUnavailableReadbackRemainNotAppliedPendingOrUncertainWithoutRetry() {
    for (int mode : List.of(1,2,3)) {
      var fake=new FakeTransport(); if(mode<3)fake.failBefore=mode;else fake.unavailableAfterWrite=true;
      var run=prepared(fake); var expected=mode==1?ManualQuarterMaintenance.Outcome.NOT_APPLIED:mode==2?ManualQuarterMaintenance.Outcome.PENDING:ManualQuarterMaintenance.Outcome.UNCERTAIN;
      assertEquals(expected,run.execute().outcome()); assertEquals(expected,run.execute().outcome());
      assertEquals(mode==2?2:1,fake.posts);
    }
  }

  @Test void finalExtraReadCannotOutliveExpiryOrStoppedWriterAcknowledgement() {
    var fake=new FakeTransport(); var clock=new ManualQuarterMaintenanceTest.MovingClock(); var run=prepared(fake,clock,()->true);
    fake.beforeRead=count->{if(count==4)clock.now=clock.now.plusSeconds(301);};
    assertEquals(ManualQuarterMaintenance.Outcome.NOT_APPLIED,run.execute().outcome()); assertEquals(0,fake.posts);
    var second=new FakeTransport(); var stopped=new AtomicBoolean(true);var other=prepared(second,second.fixture.clock,stopped::get);
    second.beforeRead=count->{if(count==4)stopped.set(false);};
    assertEquals(ManualQuarterMaintenance.Outcome.UNCERTAIN,other.execute().outcome());assertEquals(0,second.posts);
  }
}
