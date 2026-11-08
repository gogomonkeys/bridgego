package com.bridge.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupScaffoldTest {
  private static final String MARKER = "SYNTHETIC-ONLY-FORMAT-TEST";
  static ObjectNode scaffold(LocalManualQuarterRehearsal.Fixture fixture) {
    fixture.tables.clear();
    ManualQuarterMaintenance.HEADERS.forEach((name,header) -> fixture.tables.put(name, new ArrayList<>(List.of(header))));
    fixture.tables.get("auth_identities").add(List.of("", MARKER, "", ""));
    return LiveQuarterWorkbookTest.fixtureTree(fixture);
  }
  @Test void scaffoldPassesOnlySeparateReadOnlyEntryAndNeverQuarterValidation() {
    var fixture = new LocalManualQuarterRehearsal.Fixture(); var root = scaffold(fixture);
    var result = LiveQuarterWorkbook.parseScaffold(LiveQuarterWorkbookTest.bytes(root), fixture.binding, fixture.clock, MARKER);
    assertEquals(8, result.grids().size()); assertEquals(fixture.tables, result.snapshot().tables());
    assertThrows(IllegalStateException.class, () -> LiveQuarterWorkbook.parse(LiveQuarterWorkbookTest.bytes(root), fixture.binding, fixture.clock));
  }
  @Test void commentsOmittedMissingAndPopulatedAlwaysReject() {
    var fixture = new LocalManualQuarterRehearsal.Fixture(); var original = scaffold(fixture);
    for (String mode : List.of("COMMENTS_VIEW_MODE_OMITTED", "", "COMMENTS_VIEW_MODE_UNSPECIFIED")) {
      var copy = original.deepCopy(); copy.put("commentsViewMode", mode); reject(copy, fixture);
    }
    var absent = original.deepCopy(); absent.remove("commentsViewMode"); reject(absent, fixture);
    var comments = original.deepCopy(); comments.putArray("comments").addObject().put("content", "synthetic comment"); reject(comments, fixture);
  }
  @Test void extraRowsOtherMarkersAndHiddenOrMissingMetadataReject() {
    var fixture = new LocalManualQuarterRehearsal.Fixture(); var original = scaffold(fixture);
    var extra = original.deepCopy(); ((ArrayNode)LiveQuarterWorkbookTest.block(extra, "accounts").path("rowData"))
        .addObject().putArray("values").add(LiveQuarterWorkbookTest.literal("unexpected-account")); reject(extra, fixture);
    var marker = original.deepCopy(); LiveQuarterWorkbookTest.cell(marker, "auth_identities", 1, 1)
        .set("userEnteredValue", new ObjectMapper().createObjectNode().put("stringValue", "other")); reject(marker, fixture);
    var missing = original.deepCopy(); LiveQuarterWorkbookTest.block(missing, "accounts").remove("rowMetadata"); reject(missing, fixture);
    var hidden = original.deepCopy(); LiveQuarterWorkbookTest.properties(hidden, "accounts").put("hidden", true); reject(hidden, fixture);
    var unknown = original.deepCopy(); unknown.put("unrecognized", "data"); reject(unknown, fixture);
  }
  @Test void observedPlainDisplayMetadataRemainsSupportedWithoutRelaxingScaffold() {
    var fixture = new LocalManualQuarterRehearsal.Fixture(); var root = scaffold(fixture);
    LiveQuarterWorkbookTest.applyObservedCellDisplayMetadata(root);
    assertNotNull(LiveQuarterWorkbook.parseScaffold(LiveQuarterWorkbookTest.bytes(root), fixture.binding, fixture.clock, MARKER));
  }
  @Test void targetGuardNeverAcceptsArbitrarySheet() {
    for (String target : List.of("", "test-target-123456", "https://evil.example", "original-sheet"))
      assertThrows(IllegalArgumentException.class, () -> SyntheticCleanupWorkbook.requireApprovedTarget(target));
  }
  private void reject(ObjectNode root, LocalManualQuarterRehearsal.Fixture fixture) {
    assertThrows(IllegalStateException.class, () -> LiveQuarterWorkbook.parseScaffold(LiveQuarterWorkbookTest.bytes(root), fixture.binding, fixture.clock, MARKER));
  }
}
