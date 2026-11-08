package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ExperimentWorkbookHyperlinkTest {
  static final String ISSUER="https://accounts.google.com";
  static ObjectNode fixture() {
    var wire=new GoogleExperimentGatewayTest.Wire();
    try(var store=new ExperimentStorage(ExperimentStorageTest.SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)) {
      ExperimentStorageTest.bootstrap(store); ExperimentStorageTest.link(store);
    }
    ObjectNode root=wire.tree(),props=(ObjectNode)root.get("properties");
    ObjectNode theme=props.putObject("spreadsheetTheme").put("primaryFontFamily","Carlito");
    ArrayNode colors=theme.putArray("themeColors");
    Map<String,List<Double>> map=new LinkedHashMap<>();
    map.put("TEXT",List.of());map.put("BACKGROUND",List.of(1d,1d,1d));
    map.put("ACCENT1",List.of(.08235294,.3764706,.50980395));map.put("ACCENT2",List.of(.9137255,.44313726,.19607843));
    map.put("ACCENT3",List.of(.09803922,.41960785,.14117648));map.put("ACCENT4",List.of(.05882353,.61960787,.8352941));
    map.put("ACCENT5",List.of(.627451,.16862746,.5764706));map.put("ACCENT6",List.of(.30588236,.654902,.18039216));
    map.put("LINK",List.of(.27450982,.47058824,.5254902));
    map.forEach((name,v)->{var rgb=colors.addObject().put("colorType",name).putObject("color").putObject("rgbColor");if(!v.isEmpty())rgb.put("red",v.get(0)).put("green",v.get(1)).put("blue",v.get(2));});
    props.putObject("defaultFormat").putObject("textFormat").put("fontFamily","Carlito").put("fontSize",11);
    return root;
  }
  static ObjectNode cell(ObjectNode root,String tab,int row,int column) {
    for(JsonNode s:root.path("sheets"))if(s.path("properties").path("title").asText().equals(tab))return (ObjectNode)s.path("data").get(0).path("rowData").get(row).path("values").get(column);
    throw new AssertionError();
  }
  static ObjectNode linked(ObjectNode root) {
    ObjectNode cell=cell(root,"auth_identities",1,0);cell.put("hyperlink",ISSUER);
    cell.putObject("userEnteredFormat").putObject("textFormat").putObject("link").put("uri",ISSUER);
    var format=cell.putObject("effectiveFormat").put("hyperlinkDisplayType","LINKED").put("horizontalAlignment","LEFT").put("verticalAlignment","BOTTOM").put("wrapStrategy","OVERFLOW_CELL");
    var t=format.putObject("textFormat").put("fontFamily","Carlito").put("fontSize",11).put("underline",true).put("bold",false).put("italic",false).put("strikethrough",false);
    t.putObject("link").put("uri",ISSUER);t.putObject("foregroundColor").put("red",.27450982).put("green",.47058824).put("blue",.5254902);
    t.putObject("foregroundColorStyle").set("rgbColor",t.path("foregroundColor").deepCopy());
    return cell;
  }
  static ExperimentWorkbook.Workbook parse(ObjectNode root){return ExperimentWorkbook.parse(GoogleExperimentGatewayTest.bytes(root),ExperimentStorageTest.SETTINGS,ExperimentStorageTest.CLOCK);}
  static void rejected(Consumer<ObjectNode> mutation){var root=fixture();linked(root);mutation.accept(root);assertThrows(RuntimeException.class,()->parse(root));}
  @Test void acceptsOnlyObservedAutoLinkedLiteralAndPreservesMetadataFingerprint(){var root=fixture();var plain=parse(root);linked(root);var link=parse(root);assertEquals(plain.snapshot(),link.snapshot());assertNotEquals(plain.metadataFingerprint(),link.metadataFingerprint());}
  @Test void rejectsDifferentUriInEveryLocation(){for(String key:List.of("hyperlink","userEnteredFormat","effectiveFormat"))rejected(r->{var c=cell(r,"auth_identities",1,0);if(key.equals("hyperlink"))c.put(key,"https://evil.invalid");else ((ObjectNode)c.path(key).path("textFormat").path("link")).put("uri","https://evil.invalid");});}
  @Test void rejectsLinkedFormatsOutsideIssuerCell(){rejected(r->cell(r,"accounts",1,0).set("userEnteredFormat",cell(r,"auth_identities",1,0).get("userEnteredFormat").deepCopy()));rejected(r->cell(r,"auth_identities",0,0).set("effectiveFormat",cell(r,"auth_identities",1,0).get("effectiveFormat").deepCopy()));rejected(r->cell(r,"auth_identities",1,1).set("effectiveFormat",cell(r,"auth_identities",1,0).get("effectiveFormat").deepCopy()));}
  @Test void rejectsFormulaNoteRichTextAndHiddenLink(){rejected(r->cell(r,"auth_identities",1,0).putObject("userEnteredValue").put("formulaValue","=HYPERLINK(\"https://accounts.google.com\")"));rejected(r->cell(r,"auth_identities",1,0).put("note","private"));rejected(r->cell(r,"auth_identities",1,0).putArray("textFormatRuns").addObject());rejected(r->cell(r,"auth_identities",1,0).remove("hyperlink"));}
  @Test void rejectsUnknownFieldsAndWrongDisplay(){rejected(r->((ObjectNode)cell(r,"auth_identities",1,0).path("userEnteredFormat")).put("horizontalAlignment","LEFT"));rejected(r->((ObjectNode)cell(r,"auth_identities",1,0).path("effectiveFormat")).put("hyperlinkDisplayType","UNKNOWN"));rejected(r->((ObjectNode)cell(r,"auth_identities",1,0).path("effectiveFormat").path("textFormat")).put("bold",true));rejected(r->((ObjectNode)cell(r,"auth_identities",1,0).path("effectiveFormat").path("textFormat").path("link")).put("extra","metadata"));}
  @Test void rejectsArbitraryColorsAndNonBooleanUnderline(){rejected(r->((ObjectNode)cell(r,"auth_identities",1,0).path("effectiveFormat").path("textFormat").path("foregroundColor")).put("red",.1));rejected(r->((ObjectNode)cell(r,"auth_identities",1,0).path("effectiveFormat").path("textFormat")).put("underline","true"));}
  @Test void keepsPlainCellsStrict(){var root=fixture();cell(root,"accounts",0,0).putObject("effectiveFormat").put("hyperlinkDisplayType","LINKED");assertThrows(RuntimeException.class,()->parse(root));}
}
