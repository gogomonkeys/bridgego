package com.bridge.persistence;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyWorkbookTest.*;

/** Maximum-grid audit-only cost test. Not a claim about complete network/JSON-parse memory use. */
class ProductionReadOnlyFormatAuditBudgetTest {
  @Test void maximumGridEnumerationWithinExistingHeapAndWatchdogBudget(){
    if(Boolean.getBoolean("formatAuditBudget"))assertTrue(Runtime.getRuntime().maxMemory()<=128L*1024*1024);
    var n=fixture();var textFormat=JSON.createObjectNode();textFormat.putObject("numberFormat").put("type","TEXT");
    for(var sh:n.path("sheets")){
      ArrayNode rows=((ObjectNode)sh.path("data").get(0)).putArray("rowData");
      for(int r=0;r<1000;r++){ArrayNode cells=rows.addObject().putArray("values");for(int c=0;c<26;c++){var cell=cells.addObject();cell.set("userEnteredFormat",textFormat);}}
    }
    long start=System.nanoTime();var result=ProductionReadOnlyFormatAudit.inspect(n);long millis=(System.nanoTime()-start)/1000000;
    assertTrue(result.completeScan());assertTrue(millis<20000,"Audit-only elapsed budget exceeded");
    assertTrue(result.counts().values().stream().allMatch(x->x>=0 && x<=208000));
    System.out.println("FORMAT_AUDIT_SYNTHETIC_BUDGET cells=208000 elapsed_ms="+millis+" max_heap_bytes="+Runtime.getRuntime().maxMemory());
  }
}
