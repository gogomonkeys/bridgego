package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.bridge.room.GoogleCredentialVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Pure local transport: models value-only clears retaining Sheets' auto-link format. */
class ExperimentErasureMetadataTest {
  static final ExperimentStorage.Settings SETTINGS=ExperimentStorageTest.SETTINGS;
  static final Clock EXPIRED=Clock.fixed(SETTINGS.expiresAt().plusSeconds(1),ZoneOffset.UTC);
  static final Set<String> REPLACED=Set.of("accounts","auth_identities","envelopes","commits","erasure_ledger");

  static class Wire implements GoogleExperimentGateway.Transport {
    ObjectNode root;
    final List<JsonNode> writes=new ArrayList<>();
    boolean dropFormatClear,failAfterWrite,failReadback,failBeforeWrite,unreadable;
    Runnable beforeNextRead,afterWrite;
    Wire(boolean saved) throws Exception {
      var setup=new GoogleExperimentGatewayTest.Wire();
      try(var store=new ExperimentStorage(SETTINGS,setup.gateway(),ExperimentStorageTest.CLOCK)){
        ExperimentStorageTest.bootstrap(store);ExperimentStorageTest.link(store);
      }
      String nonce=setup.f.tables.get(ExperimentStorage.IDENTITY).get(1).get(2);
      try(var recovered=new ExperimentStorage(SETTINGS,setup.gateway(),ExperimentStorageTest.CLOCK)){
        String account=recovered.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),nonce);
        if(saved){var result=ExperimentStorageTest.envelope(account,"linked-erasure");recovered.submit(result);
          assertEquals(StatsStore.SaveStatus.CONFIRMED,ExperimentStorageTest.waitStatus(recovered,result));}
      }
      root=setup.tree();
      root.set("properties",ExperimentWorkbookHyperlinkTest.fixture().get("properties").deepCopy());
      ExperimentWorkbookHyperlinkTest.linked(root);
      // A permitted unrelated display setting must survive the targeted clear byte-for-byte.
      cell(root,"accounts",0,1).putObject("effectiveFormat").put("horizontalAlignment","LEFT");
    }
    public byte[] read(){
      if(beforeNextRead!=null){Runnable mutation=beforeNextRead;beforeNextRead=null;mutation.run();}
      if(unreadable)throw new SheetsGateway.Failure(503,0);
      return GoogleExperimentGatewayTest.bytes(root);
    }
    public void batch(Map<String,Object> body){
      JsonNode batch=GoogleExperimentGatewayTest.JSON.valueToTree(body);writes.add(batch);
      if(failBeforeWrite)throw new SheetsGateway.Failure(503,0);
      ObjectNode next=root.deepCopy();
      for(JsonNode request:batch.path("requests")){
        assertEquals(Set.of("updateCells"),fields(request));
        JsonNode op=request.path("updateCells"),range=op.path("range");
        ObjectNode sheet=sheetById(next,range.path("sheetId").asInt());
        int r0=range.path("startRowIndex").asInt(),r1=range.path("endRowIndex").asInt();
        int c0=range.path("startColumnIndex").asInt(),c1=range.path("endColumnIndex").asInt();
        String title=sheet.path("properties").path("title").asText(),mask=op.path("fields").asText();
        for(int r=r0;r<r1;r++)for(int c=c0;c<c1;c++){
          ObjectNode target=cell(next,title,r,c);
          if(mask.equals("userEnteredFormat")){
            if(!dropFormatClear){target.remove("userEnteredFormat");target.remove("effectiveFormat");target.remove("hyperlink");}
          }else{
            assertEquals("userEnteredValue",mask);
            target.remove(List.of("userEnteredValue","effectiveValue","formattedValue"));
            JsonNode supplied=op.path("rows").path(r-r0).path("values").path(c-c0).path("userEnteredValue");
            if(!supplied.isMissingNode()){
              target.set("userEnteredValue",supplied.deepCopy());target.set("effectiveValue",supplied.deepCopy());
              target.put("formattedValue",supplied.path("stringValue").asText());
            }
          }
        }
      }
      root=next;if(afterWrite!=null)afterWrite.run();if(failReadback)unreadable=true;
      if(failAfterWrite)throw new SheetsGateway.Failure(0,0);
    }
    GoogleExperimentGateway gateway(){return new GoogleExperimentGateway(this,SETTINGS,EXPIRED);}
  }
  static ObjectNode sheet(ObjectNode root,String title){
    for(JsonNode s:root.path("sheets"))if(s.path("properties").path("title").asText().equals(title))return (ObjectNode)s;
    throw new AssertionError(title);
  }
  static ObjectNode sheetById(ObjectNode root,int id){
    for(JsonNode s:root.path("sheets"))if(s.path("properties").path("sheetId").asInt()==id)return (ObjectNode)s;
    throw new AssertionError(id);
  }
  static ObjectNode cell(ObjectNode root,String title,int row,int column){
    ArrayNode rows=(ArrayNode)sheet(root,title).path("data").get(0).path("rowData");
    while(rows.size()<=row)rows.addObject().putArray("values");
    ArrayNode values=(ArrayNode)rows.get(row).path("values");
    while(values.size()<=column)values.addObject();
    return (ObjectNode)values.get(column);
  }
  static Set<String> fields(JsonNode value){var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);return fields;}
  static OperatorAccountErasure engine(SheetsGateway gateway){
    return new OperatorAccountErasure(gateway,(credential,audience,nonce)->{
      if(!credential.equals("synthetic-proof:"+nonce))throw new IllegalArgumentException();
      return new GoogleCredentialVerifier.Principal(SETTINGS.issuer(),SETTINGS.subject());
    },"synthetic-audience",EXPIRED,()->true,true);
  }
  static OperatorAccountErasure.Preview preview(OperatorAccountErasure engine){var c=engine.challenge();return engine.preview("synthetic-proof:"+c.nonce(),c.nonce());}
  static OperatorAccountErasure.Outcome execute(OperatorAccountErasure engine,OperatorAccountErasure.Preview plan){
    var c=engine.challenge();return engine.execute(plan.planId(),plan.beforeDigest(),plan.confirmationText(),"synthetic-proof:"+c.nonce(),c.nonce());
  }

  @Test void autoLinkedIdentityErasureClearsOnlyA2FormatInSameBoundedBatchAndPreservesGuards()throws Exception{
    var wire=new Wire(true);var gateway=wire.gateway();var before=gateway.readComplete();var original=wire.root.deepCopy();
    String oldAccount=before.tables().get("accounts").get(1).get(0);var service=engine(gateway.asMaintenanceGateway());var plan=preview(service);
    assertEquals(1,plan.affectedMatches());assertEquals(1,plan.affectedSeats());assertEquals(0,wire.writes.size());
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED,execute(service,plan));assertEquals(1,wire.writes.size());
    JsonNode requests=wire.writes.get(0).path("requests");assertEquals(6,requests.size());Set<String> changed=new HashSet<>();
    for(JsonNode request:requests){var op=request.path("updateCells");var range=op.path("range");String tab=sheetById(wire.root,range.path("sheetId").asInt()).path("properties").path("title").asText();
      assertEquals(Set.of("sheetId","startRowIndex","endRowIndex","startColumnIndex","endColumnIndex"),fields(range));
      if(op.path("fields").asText().equals("userEnteredFormat")){
        assertEquals("auth_identities",tab);assertEquals(1,range.path("startRowIndex").asInt());assertEquals(2,range.path("endRowIndex").asInt());
        assertEquals(0,range.path("startColumnIndex").asInt());assertEquals(1,range.path("endColumnIndex").asInt());assertTrue(op.path("rows").isEmpty());
      }else{
        assertTrue(changed.add(tab));assertEquals(0,range.path("startRowIndex").asInt());assertEquals(32,range.path("endRowIndex").asInt());
        assertEquals(0,range.path("startColumnIndex").asInt());assertEquals(ExperimentStorage.HEADERS.get(tab).size(),range.path("endColumnIndex").asInt());
      }
    }
    assertEquals(REPLACED,changed);assertTrue(cell(wire.root,"auth_identities",1,0).isEmpty());
    for(String tab:before.tables().keySet())if(!REPLACED.contains(tab))assertEquals(sheet(original,tab),sheet(wire.root,tab));
    assertEquals(cell(original,"accounts",0,1),cell(wire.root,"accounts",0,1));
    var after=gateway.readComplete();assertEquals(1,after.tables().get("accounts").size());assertEquals(1,after.tables().get("auth_identities").size());
    assertFalse(after.tables().toString().contains(oldAccount));assertFalse(after.tables().toString().contains(SETTINGS.subject()));
    var oldPayload=GoogleExperimentGatewayTest.JSON.readTree(before.tables().get("envelopes").get(1).get(4));
    var payload=GoogleExperimentGatewayTest.JSON.readTree(after.tables().get("envelopes").get(1).get(4));
    assertEquals(oldPayload.path("match"),payload.path("match"));assertEquals(oldPayload.path("completed_deals"),payload.path("completed_deals"));
    assertTrue(payload.path("participants").get(0).path("account_id").isNull());assertTrue(payload.path("participants").get(0).has("erased_marker"));
    for(int n=1;n<4;n++)assertEquals(oldPayload.path("participants").get(n),payload.path("participants").get(n));
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED,execute(service,plan));var repeated=preview(service);assertTrue(repeated.alreadyErased());
    assertEquals(OperatorAccountErasure.Outcome.ALREADY_ERASED,execute(service,repeated));assertEquals(1,wire.writes.size());
    try(var restarted=new ExperimentStorage(SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){
      assertThrows(RuntimeException.class,()->ExperimentStorageTest.link(restarted));
      assertThrows(RuntimeException.class,()->restarted.recoverAccount(SETTINGS.issuer(),SETTINGS.subject(),before.tables().get(ExperimentStorage.IDENTITY).get(1).get(2)));
    }
    assertEquals(1,wire.writes.size());
  }
  @Test void erasureBeforeMatchAlsoClearsLinkedIdentityAndPreservesRecoveryGuard()throws Exception{
    var wire=new Wire(false);var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);
    assertEquals(0,plan.affectedMatches());assertEquals(0,plan.affectedSeats());
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED,execute(service,plan));assertTrue(cell(wire.root,"auth_identities",1,0).isEmpty());
    assertTrue(wire.gateway().readComplete().tables().containsKey(ExperimentStorage.RECOVERY));
  }
  @Test void valueOnlyClearThatLeavesLinkMetadataCannotBeCalledVerifiedOrAutomaticallyRetried()throws Exception{
    var wire=new Wire(true);var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);wire.dropFormatClear=true;
    assertEquals(OperatorAccountErasure.Outcome.UNCERTAIN,execute(service,plan));assertEquals(1,wire.writes.size());
    assertTrue(cell(wire.root,"auth_identities",1,0).has("userEnteredFormat"));assertFalse(cell(wire.root,"auth_identities",1,0).has("userEnteredValue"));
    assertThrows(RuntimeException.class,()->execute(service,plan));assertEquals(1,wire.writes.size());
  }
  @Test void metadataOnlyChangeBetweenPreviewAndExecuteRequiresNewPreview()throws Exception{
    var wire=new Wire(true);var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);
    ((ObjectNode)sheet(wire.root,"accounts").path("data").get(0).path("rowMetadata").get(0)).put("pixelSize",25);
    assertDoesNotThrow(()->wire.gateway().readComplete());assertThrows(IllegalStateException.class,()->execute(service,plan));assertEquals(0,wire.writes.size());
    assertEquals(OperatorAccountErasure.Outcome.VERIFIED,execute(service,preview(service)));
  }
  @Test void validValueChangeBetweenPreviewAndExecuteRequiresNewPreview()throws Exception{
    var wire=new Wire(true);var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);
    var nonce=cell(wire.root,ExperimentStorage.RECOVERY,1,2);String changed=UUID.randomUUID().toString();
    nonce.putObject("userEnteredValue").put("stringValue",changed);nonce.putObject("effectiveValue").put("stringValue",changed);nonce.put("formattedValue",changed);
    assertDoesNotThrow(()->wire.gateway().readComplete());assertThrows(IllegalStateException.class,()->execute(service,plan));assertEquals(0,wire.writes.size());
  }
  @Test void issuerRelocationMultipleAccountsAndUnrelatedHiddenMetadataFailBeforeWrite()throws Exception{
    for(String mutation:List.of("moved-link","second-account","note","unrelated-format","extra-column")){
      var wire=new Wire(false);
      switch(mutation){
        case "moved-link" -> {var link=cell(wire.root,"auth_identities",1,0).get("userEnteredFormat").deepCopy();cell(wire.root,"auth_identities",2,0).set("userEnteredFormat",link);}
        case "second-account" -> {var old=sheet(wire.root,"accounts").path("data").get(0).path("rowData").get(1).deepCopy();((ArrayNode)sheet(wire.root,"accounts").path("data").get(0).path("rowData")).add(old);}
        case "note" -> cell(wire.root,"auth_identities",1,0).put("note","synthetic-hidden-data");
        case "unrelated-format" -> cell(wire.root,"accounts",1,0).putObject("userEnteredFormat").putObject("textFormat").putObject("link").put("uri",SETTINGS.issuer());
        case "extra-column" -> {var c=cell(wire.root,"accounts",1,2);c.putObject("userEnteredValue").put("stringValue","unrelated");c.putObject("effectiveValue").put("stringValue","unrelated");c.put("formattedValue","unrelated");}
      }
      assertThrows(RuntimeException.class,()->preview(engine(wire.gateway().asMaintenanceGateway())),mutation);assertEquals(0,wire.writes.size());
    }
  }
  @Test void timeoutAndUnavailableReadbackNeverCauseSecondWrite()throws Exception{
    for(boolean readable:List.of(true,false)){
      var wire=new Wire(true);var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);wire.failAfterWrite=true;wire.failReadback=!readable;
      assertEquals(readable?OperatorAccountErasure.Outcome.VERIFIED:OperatorAccountErasure.Outcome.UNCERTAIN,execute(service,plan));assertEquals(1,wire.writes.size());
      if(!readable){wire.unreadable=false;assertEquals(OperatorAccountErasure.Outcome.VERIFIED,execute(service,plan));assertEquals(1,wire.writes.size());}
    }
  }
  @Test void acceptedGuardOrMetadataDriftAfterWriteRemainsUncertain()throws Exception{
    for(boolean metadata:List.of(true,false)){
      var wire=new Wire(true);var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);
      wire.afterWrite=()->{
        if(metadata)((ObjectNode)sheet(wire.root,"accounts").path("data").get(0).path("rowMetadata").get(0)).put("pixelSize",25);
        else {var nonce=cell(wire.root,ExperimentStorage.RECOVERY,1,2);String changed=UUID.randomUUID().toString();
          nonce.putObject("userEnteredValue").put("stringValue",changed);nonce.putObject("effectiveValue").put("stringValue",changed);nonce.put("formattedValue",changed);}
      };
      assertEquals(OperatorAccountErasure.Outcome.UNCERTAIN,execute(service,plan));assertEquals(1,wire.writes.size());
      assertDoesNotThrow(()->wire.gateway().readComplete());
      assertThrows(RuntimeException.class,()->execute(service,plan));assertEquals(1,wire.writes.size());
    }
  }
  @Test void boundedReplacementRejectsInsufficientGridCapacityBeforeWrite()throws Exception{
    var wire=new Wire(false);var ledger=sheet(wire.root,"erasure_ledger");
    ((ObjectNode)ledger.path("properties").path("gridProperties")).put("rowCount",2);
    ArrayNode metadata=(ArrayNode)ledger.path("data").get(0).path("rowMetadata");while(metadata.size()>2)metadata.remove(metadata.size()-1);
    var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);
    assertThrows(RuntimeException.class,()->execute(service,plan));assertEquals(0,wire.writes.size());
  }
  @Test void nonAppliedWriteIsReadBackWithoutRetry()throws Exception{
    var wire=new Wire(true);var service=engine(wire.gateway().asMaintenanceGateway());var plan=preview(service);wire.failBeforeWrite=true;
    assertEquals(OperatorAccountErasure.Outcome.NOT_APPLIED,execute(service,plan));assertEquals(1,wire.writes.size());
  }
  @Test void fingerprintIsUnavailableUntilCompleteCycleAndDisappearanceInvalidatesPreview()throws Exception{
    var wire=new Wire(false);var facade=wire.gateway().asMaintenanceGateway();assertThrows(RuntimeException.class,facade::snapshotFingerprint);
    facade.read("accounts");assertThrows(RuntimeException.class,facade::snapshotFingerprint);
    for(String tab:List.of("auth_identities","envelopes","commits","erasure_ledger","dataset"))facade.read(tab);
    assertTrue(facade.snapshotFingerprint().orElseThrow().matches("[a-f0-9]{64}"));
    class Toggle implements SheetsGateway{
      boolean available=true;
      public List<List<String>> read(String tab){return facade.read(tab);}
      public void appendAtomic(Map<String,List<List<Object>>> rows){throw new AssertionError();}
      public void replaceAtomic(Map<String,List<List<Object>>> rows){facade.replaceAtomic(rows);}
      public Optional<String> snapshotFingerprint(){return available?facade.snapshotFingerprint():Optional.empty();}
    }
    var toggle=new Toggle();var service=engine(toggle);var plan=preview(service);toggle.available=false;
    assertThrows(IllegalStateException.class,()->execute(service,plan));assertEquals(0,wire.writes.size());
  }
}
