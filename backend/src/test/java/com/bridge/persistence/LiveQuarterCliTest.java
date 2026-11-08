package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class LiveQuarterCliTest {
  static class Scenario implements LiveQuarterCli.Terminal, LiveQuarterCli.FilesPort {
    final LiveQuarterStorageTest.FakeTransport http = new LiveQuarterStorageTest.FakeTransport();
    final List<String> output = new ArrayList<>(), events = http.events;
    String mode="", wrong="";
    boolean attempt, failMarker, badManifest, partialMarkerFailure;
    int keyReads, factories, markerCalls;
    byte[] suppliedKey;
    public void print(String text){output.add(text);}
    public String path(String prompt){return prompt.startsWith("Approved")?"manifest.json":"private-key-path-not-to-echo";}
    public String line(String prompt){
      if(prompt.startsWith("Stopped"))return wrong.equals("stop")?"yes":http.fixture.binding.stoppedConfirmation();
      if(prompt.startsWith("Mode"))return mode;
      if(prompt.startsWith("Local key"))return wrong.equals("key")?"yes":"READ KEY FOR SHEET "+http.fixture.binding.spreadsheetId()+" DATASET "+http.fixture.binding.datasetId();
      if(prompt.startsWith("Execute"))return wrong.equals("execute")?"yes":output.get(output.size()-1).substring("Fresh exact confirmation: ".length());
      if(prompt.startsWith("Type INSPECT"))return "INSPECT";
      throw new AssertionError("Unexpected prompt");
    }
    public byte[] manifest(String path){
      events.add("MANIFEST");
      if(badManifest)return "{\"endpoint\":\"https://wrong.example\"}".getBytes(StandardCharsets.UTF_8);
      try{return new ObjectMapper().writeValueAsBytes(Map.of("manifest_version",1,"purpose","bridgego-manual-quarter-maintenance-v1",
          "spreadsheet_id",http.fixture.binding.spreadsheetId(),"dataset_id",http.fixture.binding.datasetId()));}catch(Exception e){throw new AssertionError(e);}
    }
    public byte[] credentials(String path){events.add("KEY");keyReads++;suppliedKey="SYNTHETIC-NOT-A-KEY".getBytes(StandardCharsets.UTF_8);return suppliedKey;}
    public boolean hasAttempt(String path){return attempt;}
    public void createAttempt(String path,byte[] receipt){
      events.add("MARKER");markerCalls++;
      if(attempt)throw new IllegalStateException("already recorded");
      if(partialMarkerFailure)attempt=true;
      if(failMarker)throw new IllegalStateException("DO-NOT-ECHO-PATH-OR-KEY");
      attempt=true;
      String text=new String(receipt,StandardCharsets.UTF_8);assertFalse(text.contains("SYNTHETIC-NOT-A-KEY"));assertTrue(text.contains("workbook_sha256"));
    }
    int run(){return LiveQuarterCli.run(this,this,(binding,key)->{factories++;events.add("FACTORY");assertEquals(http.fixture.binding,binding);return http;},http.fixture.clock);}
  }

  @Test void defaultPreviewReadsOnlyAfterBothTargetAndKeyConsentsAndNeverCreatesMarker(){
    var s=new Scenario();assertEquals(0,s.run());assertEquals(1,s.keyReads);assertEquals(0,s.http.posts);assertEquals(0,s.markerCalls);
    assertTrue(s.events.indexOf("MANIFEST")<s.events.indexOf("KEY"));assertTrue(s.http.closed);
    for(byte b:s.suppliedKey)assertEquals(0,b);assertTrue(s.output.toString().contains("token POST"));
    assertFalse(s.output.toString().contains("private-key-path-not-to-echo"));
  }
  @Test void malformedManifestOrUnconfirmedTargetOrKeyNeverReadsCredentialOrCreatesHttp(){
    for(String failure:List.of("manifest","stop","key")){
      var s=new Scenario();s.badManifest=failure.equals("manifest");s.wrong=failure;
      assertNotEquals(0,s.run());assertEquals(0,s.keyReads);assertEquals(0,s.factories);assertEquals(0,s.http.posts);
    }
  }
  @Test void exactExecutionRecordsAttemptBeforeFirstPostAndPreservesFixture(){
    var s=new Scenario();s.mode="EXECUTE";assertEquals(0,s.run());assertEquals(2,s.http.posts);s.http.fixture.verify();
    assertTrue(s.attempt);assertEquals(1,s.markerCalls);assertTrue(s.events.indexOf("MARKER")<s.events.indexOf("POST"));
  }
  @Test void alreadyCompleteQuarterUsesFreshReadbackWithoutCellWritesOrAttemptMarker(){
    var s=new Scenario();completeInMemory(s);s.mode="EXECUTE";
    var before=ManualQuarterMaintenance.immutable(s.http.fixture.tables);
    assertEquals(0,s.run());assertEquals(0,s.http.posts);assertEquals(0,s.markerCalls);assertFalse(s.attempt);
    assertTrue(s.http.reads>=2);assertEquals(before,s.http.fixture.tables);
    assertTrue(s.output.toString().contains("READ-ONLY RESULT=COMPLETE"));
  }
  @Test void changedOrUnavailableNoopReadbackCannotFallThroughToMarkerOrWrite(){
    for(String fault:List.of("unavailable","changed-data","changed-metadata")){
      var s=new Scenario();completeInMemory(s);s.mode="EXECUTE";
      s.http.beforeRead=count->{if(count==2){
        if(fault.equals("unavailable"))throw new IllegalStateException("SYNTHETIC-READ-FAILURE");
        if(fault.equals("changed-metadata"))s.http.mutate=root->((com.fasterxml.jackson.databind.node.ObjectNode)
            LiveQuarterWorkbookTest.properties(root,"accounts").path("gridProperties")).put("rowGroupControlAfter",true);
        else s.http.fixture.tables.get("quarterly_aggregates").add(List.of("2025-Q1","SUPPRESSED","","","1"));
      }};
      assertEquals(3,s.run());assertEquals(0,s.http.posts);assertEquals(0,s.markerCalls);assertFalse(s.attempt);
      assertTrue(s.output.toString().contains("READ-ONLY RESULT=UNCERTAIN"));
    }
  }
  static void completeInMemory(Scenario s){
    var f=s.http.fixture;var op=new ManualQuarterMaintenance(f.binding,f,f.clock,()->true,true);
    var p=op.preview(f.binding.stoppedConfirmation());
    assertEquals(ManualQuarterMaintenance.Outcome.COMPLETE,op.execute(p.planId(),p.confirmationText()).outcome());
  }
  @Test void wrongPlanConfirmationCancelsBeforeMarkerOrCellWrite(){
    var s=new Scenario();s.mode="EXECUTE";s.wrong="execute";assertEquals(2,s.run());assertEquals(0,s.markerCalls);assertEquals(0,s.http.posts);
  }
  @Test void markerFailureOrRaceBlocksEveryPostAndKeepsAnyPartialMarker(){
    for(boolean partial:List.of(false,true)){
      var s=new Scenario();s.mode="EXECUTE";s.failMarker=true;s.partialMarkerFailure=partial;
      assertEquals(3,s.run());assertEquals(0,s.http.posts);assertEquals(partial,s.attempt);
      assertFalse(s.output.toString().contains("DO-NOT-ECHO"));assertTrue(s.http.closed);
    }
  }
  @Test void existingAttemptForcesPreviewEvenWhenOperatorTypesExecute(){
    var s=new Scenario();s.attempt=true;s.mode="EXECUTE";assertEquals(0,s.run());assertEquals(0,s.http.posts);assertEquals(0,s.markerCalls);
    assertTrue(s.output.toString().contains("prior attempt marker"));assertTrue(s.output.toString().contains("not a verification of any prior attempt"));
  }
  @Test void uncertainOutcomeOnlyInspectsAndLeavesMarkerForRestart(){
    var s=new Scenario();s.mode="EXECUTE";s.http.unavailableAfterWrite=true;assertEquals(3,s.run());
    assertEquals(1,s.http.posts);assertTrue(s.attempt);assertTrue(s.output.toString().contains("READ-ONLY RESULT=UNCERTAIN"));
  }
  @Test void manifestIsExactBoundedAndCannotSupplyEndpointScopeCredentialOrApproval(){
    var s=new Scenario();byte[] valid=s.manifest("test");assertEquals(s.http.fixture.binding,LiveQuarterCli.parseManifest(valid));
    for(String invalid:List.of("{}","null",new String(valid,StandardCharsets.UTF_8)+"{}",
        new String(valid,StandardCharsets.UTF_8).replace("\"manifest_version\":1","\"manifest_version\":4294967297"),
        new String(valid,StandardCharsets.UTF_8).replace("\"manifest_version\":1","\"manifest_version\":1,\"manifest_version\":1"),
        "{\"approved\":true,"+new String(valid,StandardCharsets.UTF_8).substring(1)))
      assertThrows(IllegalStateException.class,()->LiveQuarterCli.parseManifest(invalid.getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalStateException.class,()->LiveQuarterCli.parseManifest(new byte[8193]));
    assertThrows(IllegalStateException.class,()->LiveQuarterCli.parseManifest(null));
  }
  @Test void launchRefusesArgumentsAndEveryExternalConfigurationCategory(){
    assertTrue(LiveQuarterCli.launchAllowed(new String[0],Set.of("PATH"),Set.of("java.version")));
    assertFalse(LiveQuarterCli.launchAllowed(new String[]{"anything"},Set.of(),Set.of()));
    for(String key:List.of("BRIDGE_SHEETS_ENABLED","GOOGLE_APPLICATION_CREDENTIALS","HTTPS_PROXY","JAVA_TOOL_OPTIONS","spring.config.import","javax.net.ssl.trustStore"))
      assertFalse(LiveQuarterCli.launchAllowed(new String[0],Set.of(key),Set.of()));
  }
}
