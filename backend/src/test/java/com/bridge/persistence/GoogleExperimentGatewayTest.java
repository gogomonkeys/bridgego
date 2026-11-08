package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GoogleExperimentGatewayTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static class Wire implements GoogleExperimentGateway.Transport {
    final ExperimentStorageTest.Fake f=new ExperimentStorageTest.Fake();final Map<String,Integer> ids=new LinkedHashMap<>();
    int posts;boolean failAfter;String comments=ExperimentWorkbook.COMMENTS;String extra;
    Wire(){int i=100;for(String t:f.tables.keySet())ids.put(t,i++);}
    ObjectNode tree(){var s=ExperimentStorageTest.SETTINGS;ObjectNode root=JSON.createObjectNode().put("spreadsheetId",s.spreadsheetId()).put("spreadsheetUrl","https://docs.google.com/spreadsheets/d/"+s.spreadsheetId()+"/edit").put("commentsViewMode",comments);root.putObject("properties").put("title","Fake only").put("locale","en_US").put("timeZone","Etc/UTC");var sheets=root.putArray("sheets");int index=0;for(var e:ids.entrySet()){var sheet=sheets.addObject();var p=sheet.putObject("properties").put("sheetId",e.getValue()).put("title",e.getKey()).put("index",index++).put("sheetType","GRID");int rows=ExperimentStorage.GUARDS.contains(e.getKey())?2:32;int cols=ExperimentStorage.GUARDS.contains(e.getKey())?3:26;p.putObject("gridProperties").put("rowCount",rows).put("columnCount",cols);var block=sheet.putArray("data").addObject();var rm=block.putArray("rowMetadata");var cm=block.putArray("columnMetadata");for(int r=0;r<rows;r++)rm.addObject();for(int c=0;c<cols;c++)cm.addObject();var data=block.putArray("rowData");for(var row:f.tables.get(e.getKey())){var cells=data.addObject().putArray("values");for(String v:row){var cell=cells.addObject();cell.putObject("userEnteredValue").put("stringValue",v);cell.putObject("effectiveValue").put("stringValue",v);cell.put("formattedValue",v);}}}if(extra!=null)root.put(extra,"unknown");return root;}
    public byte[] read(){try{return JSON.writeValueAsBytes(tree());}catch(Exception e){throw new RuntimeException();}}
    public synchronized void batch(Map<String,Object> body){posts++;JsonNode requests=JSON.valueToTree(body).path("requests");var nextIds=new LinkedHashMap<>(ids);var next=new TreeMap<>(f.tables);for(var r:requests){if(r.has("addSheet")){var p=r.path("addSheet").path("properties");String title=p.path("title").asText();int id=p.path("sheetId").asInt();if(nextIds.containsKey(title)||nextIds.containsValue(id))throw new SheetsGateway.Failure(400,0);nextIds.put(title,id);next.put(title,List.of());}else {JsonNode op=r.has("updateCells")?r.path("updateCells"):r.path("appendCells");int id=op.has("sheetId")?op.path("sheetId").asInt():op.path("range").path("sheetId").asInt();String name=nextIds.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();if(op.path("fields").asText().equals("userEnteredFormat")){assertEquals("auth_identities",name);assertEquals(1,op.path("range").path("startRowIndex").asInt());assertEquals(2,op.path("range").path("endRowIndex").asInt());assertEquals(0,op.path("range").path("startColumnIndex").asInt());assertEquals(1,op.path("range").path("endColumnIndex").asInt());assertTrue(op.path("rows").isEmpty());continue;}assertEquals("userEnteredValue",op.path("fields").asText());var rows=new ArrayList<List<String>>();if(r.has("appendCells"))rows.addAll(next.get(name));for(var row:op.path("rows")){var values=new ArrayList<String>();for(var c:row.path("values"))values.add(c.path("userEnteredValue").path("stringValue").asText());rows.add(values);}next.put(name,rows);}}ids.clear();ids.putAll(nextIds);f.tables.clear();f.tables.putAll(next);if(failAfter)throw new SheetsGateway.Failure(0,0);}
    GoogleExperimentGateway gateway(){return new GoogleExperimentGateway(this,ExperimentStorageTest.SETTINGS,ExperimentStorageTest.CLOCK);}
  }
  @Test void exactFullWorkbookAndThreeAtomicClaims()throws Exception{var wire=new Wire();try(var s=new ExperimentStorage(ExperimentStorageTest.SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){ExperimentStorageTest.bootstrap(s);String account=ExperimentStorageTest.link(s);var e=ExperimentStorageTest.envelope(account,"wired");s.submit(e);assertEquals(StatsStore.SaveStatus.CONFIRMED,ExperimentStorageTest.waitStatus(s,e));assertEquals(3,wire.posts);assertTrue(wire.ids.keySet().containsAll(Set.of(ExperimentStorage.BOOT,ExperimentStorage.IDENTITY,ExperimentStorage.RESULT)));}}
  @Test void omittedCommentsUnknownFieldsFormulaAndExtraColumnsFailBeforePost(){var wire=new Wire();wire.comments="COMMENTS_VIEW_MODE_OMITTED";assertThrows(RuntimeException.class,()->wire.gateway().readComplete());wire.comments=ExperimentWorkbook.COMMENTS;wire.extra="unknown";assertThrows(RuntimeException.class,()->wire.gateway().readComplete());wire.extra=null;ObjectNode tree=wire.tree();((ObjectNode)tree.path("sheets").get(0).path("data").get(0).path("rowData").get(0).path("values").get(0).path("userEnteredValue")).removeAll().put("formulaValue","=1");assertThrows(RuntimeException.class,()->ExperimentWorkbook.parse(bytes(tree),ExperimentStorageTest.SETTINGS,ExperimentStorageTest.CLOCK));assertEquals(0,wire.posts);}
  @Test void reservedIdCollisionAndUnknownGuardAreRefused(){var wire=new Wire();wire.ids.put("accounts",1900000001);var collision=wire;assertThrows(RuntimeException.class,()->collision.gateway().readComplete());wire=new Wire();wire.ids.put("unknown_guard",777);wire.f.tables.put("unknown_guard",List.of(List.of("x")));var bad=wire;assertThrows(RuntimeException.class,()->bad.gateway().readComplete());assertEquals(0,wire.posts);}
  @Test void changedMetadataImmediatelyBeforeClaimStops(){var wire=new Wire();var g=wire.gateway();var s=g.readComplete();wire.ids.put("accounts",555);assertThrows(RuntimeException.class,()->g.claimAtomic(ExperimentStorage.BOOT,List.of(ExperimentStorage.GUARD_HEADER,List.of(ExperimentStorageTest.SETTINGS.datasetId(),ExperimentStorageTest.SETTINGS.expiresAt().toString(),UUID.randomUUID().toString())),Map.of("retention_ledger",List.of())));assertEquals(0,wire.posts);}
  @Test void expiredMaintenancePreservesGuardAndOnlyReplacesFiveTables(){var wire=new Wire();try(var s=new ExperimentStorage(ExperimentStorageTest.SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){ExperimentStorageTest.bootstrap(s);ExperimentStorageTest.link(s);}var expired=Clock.fixed(ExperimentStorageTest.SETTINGS.expiresAt().plusSeconds(1),ZoneOffset.UTC);var g=new GoogleExperimentGateway(wire,ExperimentStorageTest.SETTINGS,expired);var facade=g.asMaintenanceGateway();var before=g.readComplete();var rows=new TreeMap<String,List<List<Object>>>();for(String t:Set.of("accounts","auth_identities","envelopes","commits","erasure_ledger"))rows.put(t,before.tables().get(t).stream().map(r->r.stream().map(x->(Object)x).toList()).toList());for(String t:Set.of("accounts","auth_identities","envelopes","commits","erasure_ledger","dataset"))facade.read(t);facade.replaceAtomic(rows);assertEquals(before.tables(),g.readComplete().tables());assertEquals(3,wire.posts);assertThrows(RuntimeException.class,()->facade.read(ExperimentStorage.BOOT));}
  @Test void endToEndFakeErasureAfterExpiryKeepsPermanentGuardsAndDeniesReregistration()throws Exception {
    var wire=new Wire();var settings=ExperimentStorageTest.SETTINGS;
    try(var store=new ExperimentStorage(settings,wire.gateway(),ExperimentStorageTest.CLOCK)) {
      ExperimentStorageTest.bootstrap(store);String account=ExperimentStorageTest.link(store);
      var envelope=ExperimentStorageTest.envelope(account,"erasure-flow");store.submit(envelope);
      assertEquals(StatsStore.SaveStatus.CONFIRMED,ExperimentStorageTest.waitStatus(store,envelope));
    }
    var guards=new TreeMap<String,List<List<String>>>();for(String g:ExperimentStorage.GUARDS)guards.put(g,wire.f.tables.get(g));
    var expired=Clock.fixed(settings.expiresAt().plusSeconds(1),ZoneOffset.UTC);
    var adapter=new GoogleExperimentGateway(wire,settings,expired);
    var engine=new OperatorAccountErasure(adapter.asMaintenanceGateway(),(credential,audience,nonce)-> {
      if(!credential.equals("fake-proof:"+nonce))throw new IllegalArgumentException();
      return new com.bridge.room.GoogleCredentialVerifier.Principal(settings.issuer(),settings.subject());
    },"fake-audience",expired,()->true,true);
    var challenge=engine.challenge();var plan=engine.preview("fake-proof:"+challenge.nonce(),challenge.nonce());
    assertEquals(1,plan.affectedSeats());assertEquals(1,plan.affectedMatches());
    var fresh=engine.challenge();assertEquals(OperatorAccountErasure.Outcome.VERIFIED,
        engine.execute(plan.planId(),plan.beforeDigest(),plan.confirmationText(),"fake-proof:"+fresh.nonce(),fresh.nonce()));
    for(String g:ExperimentStorage.GUARDS)assertEquals(guards.get(g),wire.f.tables.get(g));
    assertEquals(1,wire.f.tables.get("accounts").size());assertEquals(1,wire.f.tables.get("auth_identities").size());
    assertEquals(4,wire.posts);try(var restarted=new ExperimentStorage(settings,wire.gateway(),ExperimentStorageTest.CLOCK)) {
      assertThrows(RuntimeException.class,()->ExperimentStorageTest.link(restarted));
    }
    assertEquals(4,wire.posts);
  }
  @Test void maintenanceReadsOneCoherentCycleAndRejectsConcurrentChangeBeforeReplace(){
    var wire=new Wire();try(var store=new ExperimentStorage(ExperimentStorageTest.SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){ExperimentStorageTest.bootstrap(store);ExperimentStorageTest.link(store);}
    var gateway=wire.gateway();var before=gateway.readComplete();var facade=gateway.asMaintenanceGateway();
    assertEquals(before.tables().get("accounts"),facade.read("accounts"));
    String changed=UUID.randomUUID().toString();wire.f.tables.put("accounts",List.of(ExperimentStorage.HEADERS.get("accounts"),List.of(changed,"1")));
    wire.f.tables.put("auth_identities",List.of(ExperimentStorage.HEADERS.get("auth_identities"),List.of(ExperimentStorageTest.SETTINGS.issuer(),ExperimentStorageTest.SETTINGS.subject(),changed,"1")));
    assertEquals(before.tables().get("auth_identities"),facade.read("auth_identities"));
    for(String tab:List.of("envelopes","commits","erasure_ledger","dataset"))facade.read(tab);
    var rows=new TreeMap<String,List<List<Object>>>();for(String tab:Set.of("accounts","auth_identities","envelopes","commits","erasure_ledger"))rows.put(tab,before.tables().get(tab).stream().map(r->r.stream().map(v->(Object)v).toList()).toList());
    assertThrows(RuntimeException.class,()->facade.replaceAtomic(rows));assertEquals(2,wire.posts);
  }
  @Test void validHashUnknownPayloadFieldsAndOverflowedSchemaAreRejected()throws Exception{
    var wire=new Wire();FinalMatchEnvelope envelope;
    try(var store=new ExperimentStorage(ExperimentStorageTest.SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){
      ExperimentStorageTest.bootstrap(store);String account=ExperimentStorageTest.link(store);envelope=ExperimentStorageTest.envelope(account,"unknown-fields");store.submit(envelope);assertEquals(StatsStore.SaveStatus.CONFIRMED,ExperimentStorageTest.waitStatus(store,envelope));
    }
    for(String location:List.of("root","match","person","deal","overflow")) {
      ObjectNode root=envelope.payload().deepCopy();
      if(location.equals("root"))root.put("unexpected","do-not-delete");
      if(location.equals("match"))((ObjectNode)root.path("match")).put("unexpected","do-not-delete");
      if(location.equals("person"))((ObjectNode)root.path("participants").get(0)).put("email","fake@example.invalid");
      if(location.equals("deal"))((ObjectNode)root.path("completed_deals").get(0)).put("unexpected","do-not-delete");
      if(location.equals("overflow"))((ObjectNode)root.path("match")).put("schema_version",4294967297L);
      var altered=FinalMatchEnvelope.create(envelope.matchId(),1,JSON.convertValue(root,Map.class));
      wire.f.tables.put("envelopes",List.of(ExperimentStorage.HEADERS.get("envelopes"),List.of(altered.commitId(),altered.payloadHash(),"0","1",altered.canonicalJson())));
      wire.f.tables.put("commits",List.of(ExperimentStorage.HEADERS.get("commits"),List.of(altered.commitId(),altered.payloadHash(),"1",altered.matchId(),"1")));
      assertThrows(RuntimeException.class,()->wire.gateway().readComplete());assertEquals(3,wire.posts);
    }
  }
  @Test void unknownSubmissionFieldsAreNotWrittenEvenWithCorrectOwnerAndHash()throws Exception{
    var wire=new Wire();try(var store=new ExperimentStorage(ExperimentStorageTest.SETTINGS,wire.gateway(),ExperimentStorageTest.CLOCK)){
      ExperimentStorageTest.bootstrap(store);String account=ExperimentStorageTest.link(store);var envelope=ExperimentStorageTest.envelope(account,"bad-submission");ObjectNode root=envelope.payload().deepCopy();root.put("extra","fake");var e=FinalMatchEnvelope.create(envelope.matchId(),1,JSON.convertValue(root,Map.class));store.submit(e);assertEquals(StatsStore.SaveStatus.UNKNOWN,ExperimentStorageTest.waitStatus(store,e));assertEquals(2,wire.posts);
    }
  }
  static byte[] bytes(Object o){try{return JSON.writeValueAsBytes(o);}catch(Exception e){throw new RuntimeException();}}
}
