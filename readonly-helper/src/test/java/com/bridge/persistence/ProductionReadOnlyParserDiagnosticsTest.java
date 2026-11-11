package com.bridge.persistence;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyWorkbookTest.*;

class ProductionReadOnlyParserDiagnosticsTest {
  static final String CANARY="PRIVATE_dynamic_value_must_not_escape";
  static void check(ProductionReadOnlyDiagnostics.Stage stage,Consumer<ObjectNode> mutation)throws Exception{
    var fixture=fixture();mutation.accept(fixture);var diagnostic=new ProductionReadOnlyDiagnostics();
    assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspectDiagnosed(bytes(fixture),TARGET,diagnostic));
    assertEquals(stage,diagnostic.snapshot().stage());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.REJECTED,diagnostic.snapshot().error());
    String text=JSON.writeValueAsString(diagnostic.snapshot());assertFalse(text.contains(CANARY));assertFalse(text.contains(ACCOUNT));assertFalse(text.contains(DATASET));
    var snapshot=diagnostic.snapshot();diagnostic.mark(ProductionReadOnlyDiagnostics.Stage.COMPLETE);assertEquals(snapshot,diagnostic.snapshot());
  }
  @Test void malformedJsonOnlyNamesFixedJsonStage(){var d=new ProductionReadOnlyDiagnostics();assertThrows(IllegalStateException.class,()->ProductionReadOnlyWorkbook.inspectDiagnosed(CANARY.getBytes(java.nio.charset.StandardCharsets.UTF_8),TARGET,d));assertEquals(ProductionReadOnlyDiagnostics.Stage.WORKBOOK_JSON,d.snapshot().stage());}
  @Test void rootChecksHaveFixedNonValueCategories()throws Exception{
    check(ProductionReadOnlyDiagnostics.Stage.ROOT_FIELDS,n->n.put(CANARY,CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.ROOT_BINDING,n->n.put("spreadsheetId",CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.ROOT_METADATA,n->n.putArray("developerMetadata").addObject().put("metadataValue",CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.ROOT_TITLE,n->((ObjectNode)n.get("properties")).put("title",CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.WORKBOOK_THEME,n->((ObjectNode)n.get("properties")).put(CANARY,CANARY));
  }
  @Test void sheetChecksDoNotEmitSheetTitleIdOrCounts()throws Exception{
    check(ProductionReadOnlyDiagnostics.Stage.SHEET_COLLECTION,n->((ArrayNode)n.get("sheets")).remove(0));
    check(ProductionReadOnlyDiagnostics.Stage.SHEET_FEATURES,n->sheet(n,"accounts").putArray("charts").addObject().put("title",CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.SHEET_PROPERTIES,n->((ObjectNode)sheet(n,"accounts").get("properties")).put("sheetId",999));
    check(ProductionReadOnlyDiagnostics.Stage.SHEET_GRID,n->((ObjectNode)sheet(n,"accounts").path("properties").path("gridProperties")).put("rowCount",999));
  }
  @Test void gridChecksDoNotEmitCoordinatesOrValues()throws Exception{
    check(ProductionReadOnlyDiagnostics.Stage.GRID_BLOCK,n->block(n,"accounts").put("startRow",-1));
    check(ProductionReadOnlyDiagnostics.Stage.GRID_DIMENSIONS,n->((ObjectNode)block(n,"accounts").path("rowMetadata").get(0)).put("pixelSize",-1));
    check(ProductionReadOnlyDiagnostics.Stage.GRID_ROW,n->block(n,"accounts").put("rowData",CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.GRID_COVERAGE,n->((ArrayNode)block(n,"accounts").get("rowMetadata")).remove(999));
  }
  @Test void cellFeatureValueAndFormatChecksAreDistinct()throws Exception{
    check(ProductionReadOnlyDiagnostics.Stage.CELL_FEATURES,n->cell(n,"accounts",1,0).put("note",CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.CELL_VALUE,n->cell(n,"accounts",1,0).putObject("effectiveValue").put("stringValue",CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.CELL_FORMAT,n->cell(n,"accounts",1,0).putObject("userEnteredFormat").put(CANARY,CANARY));
  }
  @Test void tableAndDatasetChecksNeverPrintLabelsOrIdentifiers()throws Exception{
    check(ProductionReadOnlyDiagnostics.Stage.TABLE_HEADERS,n->string(cell(n,"accounts",0,0),CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.TABLE_ROWS,n->string(cell(n,"commits",1,25),CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.DATASET_MARKER,n->string(cell(n,"dataset",1,1),CANARY));
    check(ProductionReadOnlyDiagnostics.Stage.NUMERIC_ROWS,n->{var rows=(ArrayNode)block(n,"commits").get("rowData");rows.add(rows.get(1).deepCopy());});
  }
  @Test void repeatedStageMarksReuseImmutableVocabularyButFailureStaysLocal(){
    var a=new ProductionReadOnlyDiagnostics();var b=new ProductionReadOnlyDiagnostics();a.mark(ProductionReadOnlyDiagnostics.Stage.CELL_VALUE);var fixed=a.snapshot();
    for(int i=0;i<100000;i++){a.mark(ProductionReadOnlyDiagnostics.Stage.CELL_FORMAT);a.mark(ProductionReadOnlyDiagnostics.Stage.CELL_VALUE);assertSame(fixed,a.snapshot());}
    b.mark(ProductionReadOnlyDiagnostics.Stage.CELL_VALUE);assertSame(fixed,b.snapshot());a.fail(ProductionReadOnlyDiagnostics.ErrorType.REJECTED);assertNotSame(a.snapshot(),b.snapshot());assertEquals(ProductionReadOnlyDiagnostics.ErrorType.NONE,b.snapshot().error());
  }
  @Test void successfulParseAndSeparateFailureDoNotShareState(){var first=new ProductionReadOnlyDiagnostics();var good=ProductionReadOnlyWorkbook.inspectDiagnosed(bytes(fixture()),TARGET,first);assertNotNull(good);assertEquals(ProductionReadOnlyDiagnostics.ErrorType.NONE,first.snapshot().error());assertEquals(ProductionReadOnlyDiagnostics.Stage.LAYOUT_HASH,first.snapshot().stage());var second=new ProductionReadOnlyDiagnostics();assertEquals(ProductionReadOnlyDiagnostics.Stage.START,second.snapshot().stage());}
}
