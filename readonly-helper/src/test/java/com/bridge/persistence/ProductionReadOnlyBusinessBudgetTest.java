package com.bridge.persistence;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyBusinessTest.*;

/** Real JSON parse + canonical payload validation at native profile's 999 data-row ceiling. */
class ProductionReadOnlyBusinessBudgetTest {
  @Test void maximumCommitRowsCompleteWithinBoundedHelperHeapAndLocalTime() throws Exception {
    if(Boolean.getBoolean("semanticBudget"))assertTrue(Runtime.getRuntime().maxMemory()<=128L*1024*1024);
    var t=tables();for(int i=1;i<=999;i++) {var p=payload();replaceMatch(p,String.format("00000000-0000-0000-0000-%012d",i),1);append(t,p);}
        String carryJson=carry(Q4);addCarry(t,carryJson);
    for(int i=1;i<=998;i++)t.get("accounts").add(java.util.List.of(String.format("00000000-0000-0000-0000-%012d",100000000000L+i),"2","Synthetic","",carryJson));
    var nativeResource=ProductionReadOnlyBusinessIntegrationTest.nativeResource(t);
    byte[] resource=ProductionReadOnlyWorkbookTest.bytes(nativeResource);
    assertTrue(resource.length<ProductionReadOnlyWorkbook.MAX_RESPONSE_BYTES);
    long start=System.nanoTime();
    var report=assertTimeout(Duration.ofSeconds(25),()->ProductionReadOnlyWorkbook.inspectAt(resource,ProductionReadOnlyWorkbookTest.TARGET,NOW));
    long elapsed=(System.nanoTime()-start)/1_000_000;
    assertEquals(999,report.businessSemantics().decodedRecords());assertFalse(report.supportedRecordContractVerified());
    assertEquals(ProductionReadOnlyBusiness.State.VERIFIED,report.businessSemantics().checks().get(ProductionReadOnlyBusiness.Domain.LIFETIME_CARRY).state());
    assertEquals(999,report.businessSemantics().checks().get(ProductionReadOnlyBusiness.Domain.LIFETIME_CONSISTENCY).reasons().get(ProductionReadOnlyBusiness.Reason.CARRY_HISTORY_UNAVAILABLE));
    assertFalse(report.businessSemanticsVerified());assertFalse(report.mutationPermitted());
    System.out.println("SEMANTIC_SYNTHETIC_BUDGET records=999 carries=999 elapsed_ms="+elapsed+" max_heap_bytes="+Runtime.getRuntime().maxMemory());
  }
}
