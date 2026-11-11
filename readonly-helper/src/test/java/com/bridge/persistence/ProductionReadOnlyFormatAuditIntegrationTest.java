package com.bridge.persistence;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyWorkbookTest.*;

/** Fixed audit observations never authorize unknown structure or replace strict validation. */
class ProductionReadOnlyFormatAuditIntegrationTest {
  static final String CANARY="PRIVATE_format_value_url_must_not_escape";
  @Test void multipleFormatsAreObservedInOneResponseButStrictRejectionRemains()throws Exception{
    var n=fixture();cell(n,"accounts",1,0).putObject("userEnteredFormat").putObject("textFormat").put("fontFamily",CANARY);
    cell(n,"commits",1,0).putObject("userEnteredFormat").putObject("numberFormat").put("type","TEXT");
    var d=new ProductionReadOnlyDiagnostics();assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspectDiagnosed(bytes(n),TARGET,d));
    assertEquals(ProductionReadOnlyDiagnostics.Stage.CELL_FORMAT,d.snapshot().stage());assertNotNull(d.formatAudit());assertTrue(d.formatAudit().completeScan());
    String summary=JSON.writeValueAsString(d.formatAudit());for(String s:List.of(CANARY,ACCOUNT,DATASET,"synthetic@example.invalid","SYNTHETIC_SUBJECT"))assertFalse(summary.contains(s));
    assertThrows(UnsupportedOperationException.class,()->d.formatAudit().counts().clear());
    var outcome=ProductionReadOnlyStartup.run(ProductionReadOnlyStartupTest.enabled()::get,ProductionReadOnlyStartupTest.CLOCK,new java.util.concurrent.atomic.AtomicBoolean(),(s,c)->{throw new IllegalStateException(CANARY);},d);
    assertEquals("READ_REJECTED",outcome.status());assertNotNull(outcome.formatAudit());assertFalse(JSON.writeValueAsString(outcome).contains(CANARY));
  }
  @Test void successfulStrictResultIsUnchangedBySidecarAndStillNotWritePermission(){
    var n=fixture();var expected=inspect(n);var d=new ProductionReadOnlyDiagnostics();var actual=ProductionReadOnlyWorkbook.inspectDiagnosed(bytes(n),TARGET,d);
    assertEquals(expected,actual);assertFalse(actual.mutationPermitted());assertFalse(actual.businessSemanticsVerified());assertFalse(actual.writerExclusionVerified());assertNotNull(d.formatAudit());
  }
  @Test void bindingFailureNeverRunsTheAudit(){var n=fixture();n.put("spreadsheetId",CANARY);var d=new ProductionReadOnlyDiagnostics();assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspectDiagnosed(bytes(n),TARGET,d));assertEquals(ProductionReadOnlyDiagnostics.Stage.ROOT_BINDING,d.snapshot().stage());assertNull(d.formatAudit());}
  @Test void malformedStructureStillRejectsInsteadOfTrustingAudit(){var n=fixture();((ArrayNode)n.get("sheets")).remove(0);var d=new ProductionReadOnlyDiagnostics();assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspectDiagnosed(bytes(n),TARGET,d));assertEquals(ProductionReadOnlyDiagnostics.Stage.SHEET_COLLECTION,d.snapshot().stage());if(d.formatAudit()!=null)assertFalse(d.formatAudit().completeScan());}
  @Test void auditPublicationIsOneImmutableSnapshotPerRequest(){var first=new ProductionReadOnlyDiagnostics();var second=new ProductionReadOnlyDiagnostics();var summary=ProductionReadOnlyFormatAudit.inspect(fixture());first.recordFormatAudit(summary);assertSame(summary,first.formatAudit());assertNull(second.formatAudit());first.recordFormatAudit(ProductionReadOnlyFormatAudit.inspect(JSON.createObjectNode()));assertSame(summary,first.formatAudit());}
}
