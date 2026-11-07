package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.persistence.SyntheticSheetsErasurePreparation.*;
import com.bridge.persistence.SyntheticSheetsReadOnlyAdapter.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class SyntheticSheetsReadOnlyAdapterTest {
  Manifest manifest; ObjectNode root;
  @BeforeEach void setup() { manifest = SyntheticSheetsReadOnlyAdapterSmoke.manifest(); root = SyntheticSheetsReadOnlyAdapterSmoke.response(manifest, true); }
  byte[] bytes(ObjectNode value) throws Exception { return SyntheticSheetsReadOnlyAdapterSmoke.JSON.writeValueAsBytes(value); }
  ObjectNode sheet(ObjectNode value) { return (ObjectNode) value.path("sheets").get(0); }
  ObjectNode block(ObjectNode value) { return (ObjectNode) sheet(value).path("data").get(0); }
  ObjectNode cell(ObjectNode value) { return (ObjectNode) block(value).path("rowData").get(0).path("values").get(0); }
  void rejected(ObjectNode value) throws Exception {
    byte[] body = bytes(value);
    var failure = assertThrows(ReadFailure.class, () -> SyntheticSheetsReadOnlyAdapter.parse(body, manifest));
    assertNull(failure.getCause()); assertEquals("Whole-workbook read rejected or unavailable", failure.getMessage());
  }
  ReadResponse response(ReadRequest request, byte[] body) { return new ReadResponse(200, request.uri(), "application/json; charset=UTF-8", "identity", body.length, new ByteArrayInputStream(body)); }

  @Test void denseAndSparseKnownResponsesRoundTripExactFixture() throws Exception {
    for (boolean sparse : List.of(true, false))
      assertEquals(manifest.baseline(), SyntheticSheetsReadOnlyAdapter.parse(bytes(SyntheticSheetsReadOnlyAdapterSmoke.response(manifest, sparse)), manifest));
  }
  @Test void targetBoundRequestIncludesCommentsAndNoMasksRangesPaginationOrCredentials() throws Exception {
    byte[] body = bytes(root); AtomicInteger calls = new AtomicInteger();
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, request -> {
      calls.incrementAndGet(); assertEquals("GET", request.method()); assertFalse(request.followRedirects());
      assertEquals("https://sheets.googleapis.com/v4/spreadsheets/" + manifest.sheetId()
          + "?includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED", request.uri().toString());
      assertEquals(Map.of("Accept", "application/json", "Accept-Encoding", "identity"), request.headers());
      assertEquals(Duration.ofSeconds(5), request.timeout()); assertEquals(262144, request.maxResponseBytes());
      return response(request, body);
    });
    assertEquals(manifest.baseline(), adapter.readComplete()); assertEquals(1, calls.get());
  }
  @Test void replaceIsUnconditionallyDisabledBeforeAnyTransport() {
    AtomicInteger calls = new AtomicInteger();
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, request -> { calls.incrementAndGet(); throw new AssertionError(); });
    assertThrows(UnsupportedOperationException.class, () -> adapter.replaceAtomic(null)); assertEquals(0, calls.get());
  }
  @Test void redirectsErrorsAndDifferentFinalDestinationNeverReadBodyOrRetry() {
    for (int status : List.of(206, 301, 302, 401, 403, 429, 503)) {
      AtomicInteger calls = new AtomicInteger(), reads = new AtomicInteger(), closed = new AtomicInteger();
      var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, request -> {
        calls.incrementAndGet(); return new ReadResponse(status, request.uri(), "application/json", "identity", -1,
            new InputStream() { public int read() { reads.incrementAndGet(); return -1; } public void close() { closed.incrementAndGet(); } });
      });
      assertThrows(ReadFailure.class, adapter::readComplete); assertEquals(1, calls.get()); assertEquals(0, reads.get()); assertEquals(1, closed.get());
    }
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, request -> new ReadResponse(200, URI.create("https://evil.example/"), "application/json", "identity", 0, new ByteArrayInputStream(new byte[0])));
    assertThrows(ReadFailure.class, adapter::readComplete);
  }
  @Test void bodyTypeEncodingSizeAndDeclaredLengthAreBounded() throws Exception {
    byte[] valid = bytes(root);
    for (String type : List.of("text/html", "application/json; charset=shift_jis", "application/json; fields=values")) {
      var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, r -> new ReadResponse(200, r.uri(), type, "identity", valid.length, new ByteArrayInputStream(valid)));
      assertThrows(ReadFailure.class, adapter::readComplete);
    }
    var compressed = new SyntheticSheetsReadOnlyAdapter(manifest, r -> new ReadResponse(200, r.uri(), "application/json", "gzip", valid.length, new ByteArrayInputStream(valid)));
    assertThrows(ReadFailure.class, compressed::readComplete);
    for (long length : List.of(-2L, (long) valid.length + 1, 262145L)) {
      var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, r -> new ReadResponse(200, r.uri(), "application/json", "identity", length, new ByteArrayInputStream(valid)));
      assertThrows(ReadFailure.class, adapter::readComplete);
    }
    var oversized = new SyntheticSheetsReadOnlyAdapter(manifest, r -> new ReadResponse(200, r.uri(), "application/json", "identity", -1, new ByteArrayInputStream(new byte[262145])));
    assertThrows(ReadFailure.class, oversized::readComplete);
    var chunked = new SyntheticSheetsReadOnlyAdapter(manifest, r -> new ReadResponse(200, r.uri(), "application/json", "identity", -1, new ByteArrayInputStream(valid)));
    assertEquals(manifest.baseline(), chunked.readComplete());
  }
  @Test void transportAndParserExceptionsNeverExposeBodiesTargetsOrCauses() {
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, r -> { throw new IOException("PRIVATE credential or response"); });
    var failure = assertThrows(ReadFailure.class, adapter::readComplete);
    assertNull(failure.getCause()); assertFalse(failure.toString().contains("PRIVATE"));
    assertFalse(failure.toString().contains(manifest.sheetId()));
  }
  @Test void timeoutDoesNotRetryAndRefusesOverlappingUnresponsiveTransport() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, r -> {
      calls.incrementAndGet(); entered.countDown();
      while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { /* simulate a broken transport */ }
      return response(r, bytes(root));
    }, 100);
    try {
      long start = System.nanoTime(); assertThrows(ReadFailure.class, adapter::readComplete);
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
      assertThrows(ReadFailure.class, adapter::readComplete); assertEquals(1, calls.get());
    } finally { release.countDown(); }
  }
  @Test void malformedDuplicateTrailingDeepAndOversizedJsonIsRejected() throws Exception {
    for (String invalid : List.of("", "null", "[]", "{", "{\"spreadsheetId\":\"a\",\"spreadsheetId\":\"b\"}",
        "{\"deep\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}", new String(bytes(root), StandardCharsets.UTF_8) + "{}"))
      assertThrows(ReadFailure.class, () -> SyntheticSheetsReadOnlyAdapter.parse(invalid.getBytes(StandardCharsets.UTF_8), manifest));
    ObjectNode longValue = root.deepCopy(); cell(longValue).putObject("userEnteredValue").put("stringValue", "x".repeat(8193)); rejected(longValue);
  }
  @Test void commentsMustBeIncludedAndAllUnknownMetadataFailsClosed() throws Exception {
    for (String field : List.of("commentsViewMode", "spreadsheetId", "spreadsheetUrl", "properties", "sheets")) {
      ObjectNode changed = root.deepCopy(); changed.remove(field); rejected(changed);
    }
    ObjectNode omitted = root.deepCopy(); omitted.put("commentsViewMode", "COMMENTS_VIEW_MODE_OMITTED"); rejected(omitted);
    for (String field : List.of("comments", "namedRanges", "developerMetadata", "dataSources", "dataSourceSchedules", "nextPageToken", "unknownField")) {
      ObjectNode changed = root.deepCopy(); changed.putArray(field).addObject().put("private", "untrusted"); rejected(changed);
    }
    for (String field : List.of("commentAnchors", "merges", "charts", "tables", "protectedRanges", "conditionalFormats", "filterViews", "rowGroups", "columnGroups", "slicers", "bandedRanges")) {
      ObjectNode changed = root.deepCopy(); sheet(changed).putArray(field).addObject(); rejected(changed);
    }
  }
  @Test void sheetIdsTypesDimensionsAndHiddenStateAreStrict() throws Exception {
    for (String field : List.of("sheetId", "title", "gridProperties")) {
      ObjectNode changed = root.deepCopy(); ((ObjectNode) sheet(changed).path("properties")).remove(field); rejected(changed);
    }
    for (String type : List.of("OBJECT", "DATA_SOURCE", "SHEET_TYPE_UNSPECIFIED")) {
      ObjectNode changed = root.deepCopy(); ((ObjectNode) sheet(changed).path("properties")).put("sheetType", type); rejected(changed);
    }
    for (String field : List.of("hidden", "rightToLeft")) {
      ObjectNode changed = root.deepCopy(); ((ObjectNode) sheet(changed).path("properties")).put(field, true); rejected(changed);
    }
    for (String count : List.of("rowCount", "columnCount")) {
      ObjectNode changed = root.deepCopy(); ((ObjectNode) sheet(changed).path("properties").path("gridProperties")).put(count, Long.MAX_VALUE); rejected(changed);
    }
    ObjectNode duplicate = root.deepCopy(); ((ArrayNode) duplicate.path("sheets")).set(1, sheet(duplicate).deepCopy()); rejected(duplicate);
    ObjectNode extra = root.deepCopy(); ((ArrayNode) extra.path("sheets")).add(sheet(extra).deepCopy()); rejected(extra);
  }
  @Test void cellTypesNeverCoerceFormulasNumbersBooleansOrEffectiveOnlyValues() throws Exception {
    for (String type : List.of("numberValue", "boolValue", "formulaValue", "errorValue")) {
      ObjectNode changed = root.deepCopy(); cell(changed).putObject("userEnteredValue").put(type, "PRIVATE"); rejected(changed);
    }
    for (String field : List.of("userEnteredValue", "effectiveValue", "formattedValue")) {
      ObjectNode changed = root.deepCopy(); cell(changed).remove(field); rejected(changed);
    }
    ObjectNode mismatch = root.deepCopy(); cell(mismatch).putObject("effectiveValue").put("stringValue", "different"); rejected(mismatch);
    for (String field : List.of("note", "hyperlink")) {
      ObjectNode changed = root.deepCopy(); cell(changed).put(field, "private data"); rejected(changed);
    }
    for (String field : List.of("dataValidation", "pivotTable", "dataSourceTable", "dataSourceFormula", "userEnteredFormat", "unknown")) {
      ObjectNode changed = root.deepCopy(); cell(changed).putObject(field).put("private", "data"); rejected(changed);
    }
  }
  @Test void sparseOffsetsAreAppliedWithoutDroppingLastCellAndThenManifestRejectsUnknownData() throws Exception {
    ObjectNode offset = ((ArrayNode) sheet(root).path("data")).addObject();
    offset.put("startRow", 31); offset.put("startColumn", 4);
    ObjectNode cell = offset.putArray("rowData").addObject().putArray("values").addObject();
    cell.putObject("userEnteredValue").put("stringValue", "unknown-last-cell");
    cell.putObject("effectiveValue").put("stringValue", "unknown-last-cell"); cell.put("formattedValue", "unknown-last-cell");
    Workbook parsed = SyntheticSheetsReadOnlyAdapter.parse(bytes(root), manifest);
    assertEquals("unknown-last-cell", parsed.sheets().get("accounts").cells().get(31).get(4).value());
    byte[] body = bytes(root); AtomicInteger calls = new AtomicInteger();
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, r -> { calls.incrementAndGet(); return response(r, body); });
    var core = new Operator(manifest, adapter, Clock.systemUTC(), () -> true);
    assertThrows(IllegalStateException.class, core::preview); assertEquals(1, calls.get());
  }
  @Test void overlapBadOffsetsMissingCoverageAndHiddenDimensionsReject() throws Exception {
    ObjectNode overlap = root.deepCopy(); ((ArrayNode) sheet(overlap).path("data")).add(block(overlap).deepCopy()); rejected(overlap);
    for (String field : List.of("startRow", "startColumn")) for (long value : List.of(-1L, Long.MAX_VALUE)) {
      ObjectNode changed = root.deepCopy(); block(changed).put(field, value); rejected(changed);
    }
    for (String field : List.of("rowMetadata", "columnMetadata")) {
      ObjectNode changed = root.deepCopy(); block(changed).remove(field); rejected(changed);
      changed = root.deepCopy(); ((ArrayNode) block(changed).path(field)).remove(0); rejected(changed);
      for (String hidden : List.of("hiddenByUser", "hiddenByFilter")) {
        changed = root.deepCopy(); ((ObjectNode) block(changed).path(field).get(0)).put(hidden, true); rejected(changed);
      }
    }
    ObjectNode noData = root.deepCopy(); sheet(noData).remove("data"); rejected(noData);
  }
  @Test void supportedPlainDefaultFormatIsBoundedAndThemeOrLinksAreRejected() throws Exception {
    ObjectNode format = ((ObjectNode) root.path("properties")).putObject("defaultFormat");
    format.putObject("backgroundColor").put("red", 1.0).put("green", 1.0).put("blue", 1.0);
    format.put("verticalAlignment", "BOTTOM"); format.put("wrapStrategy", "OVERFLOW_CELL");
    format.putObject("textFormat").put("fontFamily", "Arial").put("fontSize", 10).put("bold", false);
    assertEquals(manifest.baseline(), SyntheticSheetsReadOnlyAdapter.parse(bytes(root), manifest));
    ObjectNode changed = root.deepCopy(); ((ObjectNode) changed.path("properties")).putObject("spreadsheetTheme").put("primaryFontFamily", "unknown"); rejected(changed);
    changed = root.deepCopy(); ((ObjectNode) changed.path("properties").path("defaultFormat").path("textFormat")).putObject("link").put("uri", "https://evil.example"); rejected(changed);
  }
  @Test void coreCanPreviewButCannotGainWriteCapabilityFromThisAdapter() throws Exception {
    byte[] body = bytes(root); AtomicInteger calls = new AtomicInteger();
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, r -> { calls.incrementAndGet(); return response(r, body); });
    var core = new Operator(manifest, adapter, Clock.systemUTC(), () -> true);
    var preview = core.preview(); assertEquals(1, calls.get());
    assertEquals(Outcome.NOT_APPLIED, core.execute(preview.planId(), preview.confirmationText())); assertEquals(3, calls.get());
  }
}
