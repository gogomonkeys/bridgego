package com.bridge.persistence;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyBusinessTest.*;

/** Entire native resource -> unchanged startup reader seam; never calls a transport. */
class ProductionReadOnlyBusinessIntegrationTest {
  static ObjectNode nativeResource(Map<String,List<List<String>>> tables) {
    var resource=ProductionReadOnlyWorkbookTest.fixture();
    for(var entry:tables.entrySet()) {
      var rows=ProductionReadOnlyWorkbookTest.block(resource,entry.getKey()).putArray("rowData");
      for(var row:entry.getValue())ProductionReadOnlyWorkbookTest.addRow(rows,row);
    }
    return resource;
  }
  @Test void nativeParseAndStartupPublishNarrowSummaryWithoutBroadVerification() throws Exception {
    var t=tables();append(t,payload());var n=nativeResource(t);var report=ProductionReadOnlyWorkbook.inspectAt(ProductionReadOnlyWorkbookTest.bytes(n),ProductionReadOnlyWorkbookTest.TARGET,NOW);
    assertTrue(report.supportedRecordContractVerified());assertFalse(report.businessSemanticsVerified());
    assertFalse(report.mutationPermitted());assertFalse(report.writerExclusionVerified());
    AtomicInteger reads=new AtomicInteger();var result=ProductionReadOnlyStartupTest.run(ProductionReadOnlyStartupTest.enabled(),(s,c)->{reads.incrementAndGet();return ProductionReadOnlyWorkbook.inspectAt(ProductionReadOnlyWorkbookTest.bytes(n),ProductionReadOnlyWorkbookTest.TARGET,NOW);});
    assertEquals(1,reads.get());assertEquals("OBSERVED_SUPPORTED_RECORD_CONTRACT",result.status());
    String output=ProductionReadOnlyEnvelopeTest.JSON.writeValueAsString(result);
    for(String secret:List.of(ACCOUNT,"SYNTHETIC_PRIVATE","synthetic@example.invalid","https://accounts.google.com"))assertFalse(output.contains(secret));
    assertTrue(output.contains("legacy-record-contract-v1"));
  }
  @Test void nativeParseRetainsIndependentSemanticFailuresInSafeStartupSummary() throws Exception {
    var t=tables();append(t,payload());set(t,"accounts",1,2,"");set(t,"envelopes",1,4,"SYNTHETIC_BAD_JSON");
    var n=nativeResource(t);var result=ProductionReadOnlyStartupTest.run(ProductionReadOnlyStartupTest.enabled(),(s,c)->ProductionReadOnlyWorkbook.inspectAt(ProductionReadOnlyWorkbookTest.bytes(n),ProductionReadOnlyWorkbookTest.TARGET,NOW));
    assertEquals("OBSERVED_STRUCTURE_ONLY",result.status());var summary=result.summary().businessSemantics();
    assertEquals(ProductionReadOnlyBusiness.State.INVALID,summary.checks().get(ProductionReadOnlyBusiness.Domain.ACCOUNTS).state());
    assertEquals(ProductionReadOnlyBusiness.State.INVALID,summary.checks().get(ProductionReadOnlyBusiness.Domain.ENVELOPE_RULES).state());
  }
  @Test void nativeDuplicateCommitAndChunkStillRejectBeforeBusinessScan() throws Exception {
    for(String tab:List.of("commits","envelopes")) {var t=tables();append(t,payload());t.get(tab).add(t.get(tab).get(1));var n=nativeResource(t);
      var diagnostics=new ProductionReadOnlyDiagnostics();assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspectDiagnosed(ProductionReadOnlyWorkbookTest.bytes(n),ProductionReadOnlyWorkbookTest.TARGET,diagnostics));
      assertEquals(ProductionReadOnlyDiagnostics.Stage.NUMERIC_ROWS,diagnostics.snapshot().stage());
      var result=ProductionReadOnlyStartupTest.run(ProductionReadOnlyStartupTest.enabled(),(s,c)->ProductionReadOnlyWorkbook.inspectAt(ProductionReadOnlyWorkbookTest.bytes(n),ProductionReadOnlyWorkbookTest.TARGET,NOW));
      assertEquals("READ_REJECTED",result.status());assertNull(result.summary());
    }
  }
  @Test void semanticInspectionDoesNotMutateLogicalOrNativeInputsOrObtainCredentials() throws Exception {
    var t=tables();append(t,payload());String before=ProductionReadOnlyEnvelopeTest.JSON.writeValueAsString(t);var n=nativeResource(t);byte[] bytes=ProductionReadOnlyWorkbookTest.bytes(n),copy=bytes.clone();
    ProductionReadOnlyWorkbook.inspect(bytes,ProductionReadOnlyWorkbookTest.TARGET);assertArrayEquals(copy,bytes);assertEquals(before,ProductionReadOnlyEnvelopeTest.JSON.writeValueAsString(t));
    assertEquals("DISABLED",ProductionReadOnlyStartupTest.run(Map.of(),(s,c)->{throw new AssertionError("disabled read");}).status());
  }
}
