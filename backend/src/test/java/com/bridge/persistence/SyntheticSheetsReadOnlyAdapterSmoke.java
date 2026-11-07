package com.bridge.persistence;

import com.bridge.persistence.SyntheticSheetsErasurePreparation.*;
import com.bridge.persistence.SyntheticSheetsReadOnlyAdapter.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only runnable closure check: can run against the isolated JAR without JUnit or live HTTP. */
public final class SyntheticSheetsReadOnlyAdapterSmoke {
  static final ObjectMapper JSON = new ObjectMapper();
  static Manifest manifest() {
    return SyntheticSheetsErasurePreparation.generate("OFFLINE_SYNTHETIC_READ_ONLY_TEST",
        "0b9c4cdc-5062-475a-8eef-cfae103df712", Set.of("FORBIDDEN_PRODUCTION_SHEET"), Instant.parse("2026-11-06T10:00:00Z"));
  }
  static ObjectNode response(Manifest manifest, boolean sparse) {
    ObjectNode root = JSON.createObjectNode();
    root.put("spreadsheetId", manifest.sheetId());
    root.put("spreadsheetUrl", "https://docs.google.com/spreadsheets/d/" + manifest.sheetId() + "/edit");
    root.put("commentsViewMode", SyntheticSheetsReadOnlyAdapter.COMMENTS);
    root.putArray("comments");
    ObjectNode properties = root.putObject("properties");
    properties.put("title", "BridgeGo Synthetic Erasure " + manifest.datasetId());
    properties.put("locale", "en_US"); properties.put("timeZone", "Etc/UTC");
    ArrayNode sheets = root.putArray("sheets"); int index = 0;
    for (var entry : manifest.baseline().sheets().entrySet()) {
      Grid grid = entry.getValue(); ObjectNode sheet = sheets.addObject(); ObjectNode p = sheet.putObject("properties");
      p.put("sheetId", grid.sheetId()); p.put("title", entry.getKey()); p.put("index", index++); p.put("sheetType", "GRID");
      p.putObject("gridProperties").put("rowCount", 32).put("columnCount", 5);
      ObjectNode data = sheet.putArray("data").addObject();
      ArrayNode rowMetadata = data.putArray("rowMetadata"), columnMetadata = data.putArray("columnMetadata");
      for (int i = 0; i < 32; i++) rowMetadata.addObject().put("pixelSize", 21);
      for (int i = 0; i < 5; i++) columnMetadata.addObject().put("pixelSize", 100);
      int rowCount = 32;
      if (sparse) while (rowCount > 0 && grid.cells().get(rowCount - 1).stream().allMatch(c -> c.type().equals("EMPTY"))) rowCount--;
      ArrayNode rows = data.putArray("rowData");
      for (int r = 0; r < rowCount; r++) {
        ObjectNode row = rows.addObject(); int columns = 5;
        if (sparse) while (columns > 0 && grid.cells().get(r).get(columns - 1).type().equals("EMPTY")) columns--;
        ArrayNode values = row.putArray("values");
        for (int c = 0; c < columns; c++) {
          ObjectNode cell = values.addObject(); Cell actual = grid.cells().get(r).get(c);
          if (actual.type().equals("TEXT")) {
            cell.putObject("userEnteredValue").put("stringValue", actual.value());
            cell.putObject("effectiveValue").put("stringValue", actual.value());
            cell.put("formattedValue", actual.value());
          }
        }
      }
    }
    return root;
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException();
    var manifest = manifest(); byte[] bytes = JSON.writeValueAsBytes(response(manifest, true));
    AtomicInteger calls = new AtomicInteger();
    var adapter = new SyntheticSheetsReadOnlyAdapter(manifest, request -> {
      calls.incrementAndGet();
      return new ReadResponse(200, request.uri(), "application/json", "identity", bytes.length, new ByteArrayInputStream(bytes));
    });
    if (!adapter.readComplete().equals(manifest.baseline())) throw new AssertionError("Read-only package mismatch");
    boolean disabled = false;
    try { adapter.replaceAtomic(null); } catch (UnsupportedOperationException expected) { disabled = true; }
    if (!disabled || calls.get() != 1) throw new AssertionError("Write route unexpectedly available");
    System.out.println("PACKAGED_READ_ONLY_ADAPTER_OK: fake transport only; replacement disabled.");
  }
}
