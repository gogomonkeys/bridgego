package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.time.*;import java.util.*;import java.net.*;import java.net.http.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Local synthetic E model only. NOT the remotely observed 391e... baseline. */
class FixedERestartHttpSafetyTest {
 @TempDir Path dir;
 static Book baseline()throws Exception{
  var m=FixedERestartClockSafetyTest.closed();var clock=FixedERestartClockSafetyTest.BUSINESS;
  var phases=new QuarterPhasePlans(FixedERestartClock.binding().manualBinding(),"local-E-future",clock);Book b=m.b;
  for(int i=0;i<5;i++)b=simulate(b,phases.next(b,i).wire());return b;
 }
 static Book pair(Book b,String owner){
  var m=new JournaledNativeWriterTest.Memory(b);var tx=new JournaledNativeTransport(m,FixedERestartClock.binding().manualBinding(),FixedERestartClockSafetyTest.BUSINESS);
  try(var s=new Service(tx,FixedERestartClockSafetyTest.BUSINESS,FixedERestartClock.binding().manualBinding(),owner,Limits.defaults())){s.start();s.cleanClose();assertEquals(State.CLOSED,s.state());}return m.b;
 }
 static void save(Path p,Book b)throws Exception{JournalRestartProbe.save(p,b);Files.writeString(p,Files.readString(p).replace(RecoveredFixture.ID,FixedERestartClock.binding().spreadsheetId()));}
 @Test void boundedPairsAndFreshJvmValidReceiptOrderingButTamperedPrefixDataAndFourthClaimReject()throws Exception{
  Book base=baseline(),b=base;var bundle=FixedEBaselineBundle.isolatedFixture(base);assertEquals(0,bundle.completedPairs(base));
  for(int n=1;n<=3;n++){b=pair(b,"w-"+UUID.randomUUID());assertEquals(n,bundle.completedPairs(b));if(n<3)bundle.requireNextClaim(b);}
  Book three=b;assertThrows(RuntimeException.class,()->bundle.requireNextClaim(three));Book four=pair(three,"w-"+UUID.randomUUID());assertThrows(RuntimeException.class,()->bundle.completedPairs(four));
  Book bad=three.copy();bad.sheet("accounts").rows.get(1).set(2,"tampered");assertThrows(RuntimeException.class,()->bundle.completedPairs(bad));
  Book prefix=three.copy();var meta=prefix.metadata.get(2);prefix.metadata.put(2,new Metadata(2,meta.key(),meta.value()+" ",meta.sheet()));assertThrows(RuntimeException.class,()->bundle.completedPairs(prefix));
  Path before=dir.resolve("base.json"),after=dir.resolve("after.json"),out=dir.resolve("child.log");save(before,base);save(after,three);
  var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),FixedEBundleProbe.class.getName(),before.toString(),after.toString()).redirectErrorStream(true).redirectOutput(out.toFile()).start();
  if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();fail("child timeout");}assertEquals(0,p.exitValue(),Files.readString(out));assertNotEquals(ProcessHandle.current().pid(),p.pid());
 }
 @Test void trueHttpNeverExposesGameAndPreflightFailureHasZeroWrites()throws Exception{
  var prior=new HashMap<String,String>();for(String key:List.of("server.port","server.address"))prior.put(key,System.getProperty(key));
  System.setProperty("server.port","0");System.setProperty("server.address","127.0.0.1");
  try{
   for(boolean tamper:List.of(false,true)){
    Book base=baseline();var bundle=FixedEBaselineBundle.isolatedFixture(base);var m=new JournaledNativeWriterTest.Memory(base.copy());if(tamper)m.b.sheet("accounts").rows.get(1).set(2,"changed");
    var grant=new AtomicReference<JournalSessionAuthority>();var channels=FixedERestartClockSafetyTest.channels(FixedERestartClock.binding(),m,grant,q->{throw new AssertionError("Fixed Q1 must not run another quarterly deletion");});
    var io=FixedERestartClockSafetyTest.io(m,channels);
    try(var context=FixedERestartLauncher.run(bundle,FixedERestartClockSafetyTest.contract(FixedERestartClock.binding()),io)){
     context.getBean(com.bridge.persistence.QuarterMaintenanceCoordinator.class).tick();int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();var client=HttpClient.newHttpClient();
     for(String path:List.of("/api/rooms","/api/auth/google","/api/statistics","/api/profile","/ws","/","/health/ready","/health/route?x=1"))assertEquals(503,get(client,port,path,"GET").statusCode(),path);
     assertEquals(503,get(client,port,"/health/route","POST").statusCode());
     for(String path:List.of("/health/live","/health/route")){var r=get(client,port,path,"GET");assertEquals(200,r.statusCode());assertTrue(r.body().contains("\"writeReady\":false"));assertEquals("no-store",r.headers().firstValue("Cache-Control").orElse(""));}
     assertEquals(tamper?503:200,get(client,port,"/health/synthetic-ready","GET").statusCode());assertEquals(tamper?0:1,m.writes);
    }
    if(!tamper){assertEquals(1,bundle.completedPairs(m.b));assertEquals(base.tables(),m.b.tables());}else assertEquals(0,m.writes);
   }
  }finally{prior.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
 }
 static HttpResponse<String> get(HttpClient c,int port,String path,String method)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());}
 @Test void absentBundleWrongBindingAndOverrideCannotInstallWriter()throws Exception{
  var calls=new AtomicInteger();var noIO=new NativeRestartRuntimeAssembly.IO(){public Book readNative(){calls.incrementAndGet();throw new AssertionError();}public PlannedMaintenanceStartupFactory.Channels channels(){calls.incrementAndGet();throw new AssertionError();}};
  assertThrows(RuntimeException.class,()->FixedERestartLauncher.run(null,FixedERestartClockSafetyTest.contract(FixedERestartClock.binding()),noIO));
  var bundle=FixedEBaselineBundle.isolatedFixture(baseline());assertThrows(RuntimeException.class,()->FixedERestartLauncher.run(bundle,NativeRestartRuntimeAssemblyTest.contract(),noIO));assertEquals(0,calls.get());
  for(String k:List.of("BRIDGE_FIXED_E_SPREADSHEET_ID","BRIDGE_FIXED_E_DATASET_ID","BRIDGE_FIXED_E_CLOCK"))assertThrows(RuntimeException.class,()->FixedERestartLauncher.requireReadOnlyConfiguration(Map.of("BRIDGE_FIXED_E_READONLY_PREFLIGHT","true",k,"anything")));
  assertNotNull(FixedEBaselineBundle.compiled());assertNull(NativeRestartCompiledRelease.current());
 }
}
