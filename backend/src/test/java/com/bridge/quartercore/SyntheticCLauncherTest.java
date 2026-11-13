package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
class SyntheticCLauncherTest {
  static final GoogleSheetsBinding BINDING=new GoogleSheetsBinding("synthetic-c-local-test",RecoveredFixture.DATASET,"bridgego-restricted-test",SyntheticCRelease.ISSUER);
  static Book baseline()throws Exception {
    Book b=RecoveredFixture.book();b.sheet("retention_ledger").rows.add(new ArrayList<>(List.of("2026-Q3","2026-06-30T16:00:00Z","COMPLETE","2026-06-30T16:00:01Z","1")));
    return NativeCommitJournal.bootstrap(BINDING.manualBinding(),b,NativeCommitJournal.digest(BINDING.manualBinding(),b),JournalQuarterProtocolTest.CLOCK).expected();
  }
  static void await(SyntheticCLauncher l,SyntheticCLauncher.Status desired)throws Exception {
    long end=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();while(l.status()!=desired&&System.nanoTime()<end)Thread.sleep(5);assertEquals(desired,l.status());
  }
  static SyntheticCLauncher make(JournalQuarterProtocolTest.Memory memory,List<String> logs,String token){
    return new SyntheticCLauncher(new SyntheticCRelease(BINDING,token,"local-revision","C-local"),new SyntheticCLauncher.Handles(){
      public SyntheticCLauncher.Session readOnly(){return new SyntheticCLauncher.Session(memory,()->{});}
      public SyntheticCLauncher.Session quarter(JournalQuarterAuthority grant){return new SyntheticCLauncher.Session(memory,()->{});}
    },JournalQuarterProtocolTest.CLOCK,logs::add,()->{},(b,g)->{assertEquals(g.verifiedClosedToken(),JournalSessionAuthority.fullToken(b));return b;});
  }
  @Test void privateApprovalOnceFivePhasesAndRestartBaselineRejects()throws Exception {
    var memory=new JournalQuarterProtocolTest.Memory(baseline());String original=JournalSessionAuthority.fullToken(memory.book);var logs=new CopyOnWriteArrayList<String>();
    try(var l=make(memory,logs,original)){
      l.start();await(l,SyntheticCLauncher.Status.WAITING_APPROVAL);assertEquals(0,memory.writes);
      var message=NativeSheetsModel.JSON.readTree(logs.get(0));String nonce=message.path("privateNonce").asText(),digest=message.path("previewDigest").asText();assertEquals(64,nonce.length());
      for(int i=0;i<20;i++)assertFalse(l.approve("wrong",digest));assertEquals(SyntheticCLauncher.Status.WAITING_APPROVAL,l.status());
      assertTrue(l.approve(nonce,digest));assertFalse(l.approve(nonce,digest));await(l,SyntheticCLauncher.Status.VERIFIED);assertEquals(5,memory.writes);
      assertThrows(RuntimeException.class,l::start);assertFalse(l.approve(nonce,digest));
    }
    try(var restarted=make(memory,new CopyOnWriteArrayList<>(),original)){restarted.start();await(restarted,SyntheticCLauncher.Status.REVIEW);assertEquals(5,memory.writes);}
  }
  @Test void unknownNeverRetriesEvenIfAppliedAndPrivateNonceNeverInHttp()throws Exception {
    var memory=new JournalQuarterProtocolTest.Memory(baseline());memory.failAt=2;memory.applyUnknown=true;var logs=new CopyOnWriteArrayList<String>();
    try(var l=make(memory,logs,JournalSessionAuthority.fullToken(memory.book))){
      l.health(0);l.start();await(l,SyntheticCLauncher.Status.WAITING_APPROVAL);var info=JSON.readTree(logs.get(0));String nonce=info.path("privateNonce").asText(),digest=info.path("previewDigest").asText();
      var client=java.net.http.HttpClient.newHttpClient();String base="http://127.0.0.1:"+l.port();
      var route=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/health/route")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,route.statusCode());assertFalse(route.body().contains(nonce));
      var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/execute")).header("Authorization","Bearer "+nonce).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"previewDigest\":\""+digest+"\"}")).build();
      assertEquals(202,client.send(req,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());await(l,SyntheticCLauncher.Status.REVIEW);assertEquals(2,memory.writes);
      assertEquals(409,client.send(req,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(2,memory.writes);
    }
  }
  @Test void defaultReleaseAndMissingEquivalentBeforePreventAnyRead(){
    var calls=new java.util.concurrent.atomic.AtomicInteger();var handles=new SyntheticCLauncher.Handles(){public SyntheticCLauncher.Session readOnly(){calls.incrementAndGet();throw new AssertionError();}public SyntheticCLauncher.Session quarter(JournalQuarterAuthority g){throw new AssertionError();}};
    try(var l=new SyntheticCLauncher(SyntheticCRelease.BUNDLED,handles,JournalQuarterProtocolTest.CLOCK,s->{},()->{},(b,g)->b)){assertThrows(RuntimeException.class,l::start);assertEquals(0,calls.get());}
    var r=new SyntheticCRelease(BINDING,"a".repeat(64),"revision","C");
    try(var l=new SyntheticCLauncher(r,handles,JournalQuarterProtocolTest.CLOCK,s->{},()->{throw new Rejected();},(b,g)->b)){assertThrows(RuntimeException.class,l::start);assertEquals(0,calls.get());}
  }
}
