package com.bridge.persistence;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.net.*;import java.net.http.*;import java.time.Duration;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Actual production main on a fresh JVM classpath without test @Configuration classes. */
class InertNativeApplicationProcessTest {
 @TempDir Path temp;
 @Test void productionBootWithoutCapabilityServesOnlyMaintenance()throws Exception{
  int port;try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
  String sep=System.getProperty("path.separator");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
  cp=String.join(sep,Arrays.stream(cp.split(java.util.regex.Pattern.quote(sep))).filter(p->!Path.of(p).getFileName().toString().equals("test-classes")).toList());
  Path poison=temp.resolve("poison.json");Files.writeString(poison,"NOT_A_SERVICE_ACCOUNT");Path log=temp.resolve("application.log");
  var b=new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,"com.bridge.room.RoomApplication",
   "--server.address=127.0.0.1","--server.port="+port,"--BRIDGE_PERSISTENCE_DRIVER=native-journal-v1","--BRIDGE_QUARTER_GATE_ENABLED=true",
   "--BRIDGE_SHEETS_ENABLED=true","--BRIDGE_COLLECTION_MODE=restricted","--BRIDGE_TEST_SPREADSHEET_ID=process-inert-synthetic-sheet",
   "--BRIDGE_TEST_DATASET_ID=b5fc0d0a-8e4a-45e1-b223-1c1b18b2434b","--BRIDGE_NATIVE_CUTOVER_BASELINE_SHA256=invalid","--spring.main.banner-mode=off"));
  b.environment().clear();b.environment().put("GOOGLE_APPLICATION_CREDENTIALS",poison.toString());b.environment().put("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON","not-json");
  Process process=b.redirectErrorStream(true).redirectOutput(log.toFile()).start();
  try{
   var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();HttpResponse<String> route=null;
   while(System.nanoTime()<deadline&&process.isAlive()){try{route=get(client,port,"/health/route");if(route.statusCode()==200)break;}catch(java.io.IOException unavailable){}Thread.sleep(100);}
   assertTrue(process.isAlive(),()->read(log));assertNotNull(route,()->read(log));assertEquals(200,route.statusCode(),()->read(log));assertTrue(route.body().contains("maintenance-serving"));
   assertEquals(200,get(client,port,"/health/live").statusCode());assertEquals(503,get(client,port,"/health/ready").statusCode());assertTrue(get(client,port,"/").body().contains("系統維護中"));
   for(String path:List.of("/","/api/rooms","/api/auth/google","/api/statistics","/ws"))assertEquals(503,get(client,port,path).statusCode(),path);
   Thread.sleep(31_000); // Includes another actual scheduled tick, not a manually invoked fake.
   assertEquals(200,get(client,port,"/health/route").statusCode());assertEquals(503,get(client,port,"/health/ready").statusCode());
  }finally{process.destroy();if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();}}
 }
 static HttpResponse<String> get(HttpClient c,int port,String path)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());}
 static String read(Path path){try{return Files.readString(path);}catch(Exception e){return "log unavailable";}}
}
