package com.bridge.quartercore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Captured component regression only. Does not alter comments or claim full-resource acceptance. */
class CarlitoIssuerCompatibilityTest {
  ObjectNode fixture()throws Exception {
    try(var input=getClass().getResourceAsStream("/quartercore/c2-observed-carlito-issuer.json")){
      assertNotNull(input);return (ObjectNode)BoundedSheetsProjection.MAPPER.readTree(input);
    }
  }
  String parse(ObjectNode fixture,JsonNode cell,String title,int row,int column)throws Exception{
    var properties=StrictNativeSheetsResource.class.getDeclaredMethod("properties",JsonNode.class);properties.setAccessible(true);
    var profile=properties.invoke(null,fixture.get("properties"));assertEquals("CARLITO",profile.toString());
    var method=StrictNativeSheetsResource.class.getDeclaredMethod("parseCell",JsonNode.class,String.class,int.class,int.class,profile.getClass(),JsonNode.class);method.setAccessible(true);
    return (String)method.invoke(null,cell,title,row,column,profile,fixture.path("properties").get("defaultFormat"));
  }
  @Test void exactObservedCarlitoIssuerAccepted()throws Exception{
    var f=fixture();assertEquals("https://accounts.google.com",parse(f,f.get("cell"),"auth_identities",1,0));
    assertEquals("https://accounts.google.com",parse(f,f.get("cell"),"auth_identities",2,0));
  }
  @Test void sameDisplayOutsideIssuerPositionRejected()throws Exception{
    var f=fixture();for(Object[] position:new Object[][]{{"accounts",1,0},{"auth_identities",0,0},{"auth_identities",1,1}})
      assertThrows(InvocationTargetException.class,()->parse(f,f.get("cell"),(String)position[0],(Integer)position[1],(Integer)position[2]));
  }
  @Test void unknownFormatWrongColorOrOtherUrlRejected()throws Exception{
    var f=fixture();var original=(ObjectNode)f.get("cell");
    var wrongColor=original.deepCopy();((ObjectNode)wrongColor.path("effectiveFormat").path("textFormat").path("foregroundColor")).put("red",0.06666667);
    var wrongUrl=original.deepCopy();wrongUrl.put("hyperlink","https://example.test");
    var unknown=original.deepCopy();((ObjectNode)unknown.path("userEnteredFormat")).put("unsupported",false);
    var bold=original.deepCopy();((ObjectNode)bold.path("effectiveFormat").path("textFormat")).put("bold",true);
    var enteredLink=original.deepCopy();((ObjectNode)enteredLink.path("userEnteredFormat").path("textFormat").path("link")).put("uri","https://example.test");
    for(var changed:java.util.List.of(wrongColor,wrongUrl,unknown,bold,enteredLink))
      assertThrows(InvocationTargetException.class,()->parse(f,changed,"auth_identities",1,0));
  }
}
