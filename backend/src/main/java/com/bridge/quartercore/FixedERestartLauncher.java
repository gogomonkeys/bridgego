package com.bridge.quartercore;
import java.util.*;
/** Fixed-E maintenance composition plus independent readonly entry.
 * Readonly success never grants writes; the separate bounded-restart main uses the reviewed E bundle. */
public final class FixedERestartLauncher {
 private FixedERestartLauncher(){}
 static org.springframework.context.ConfigurableApplicationContext run(FixedEBaselineBundle bundle,NativeRestartContract contract,NativeRestartRuntimeAssembly.IO io){
  if(bundle==null||!FixedERestartClock.binding().equals(contract.binding()))throw new NativeSheetsModel.Rejected();
  var checked=new NativeRestartRuntimeAssembly.IO(){private final java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);public NativeSheetsModel.Book readNative(){var book=first.compareAndSet(true,false)?FixedECloseWait.read(bundle,io::readNative):io.readNative();bundle.requireNextClaim(book);return book;}public PlannedMaintenanceStartupFactory.Channels channels(){return io.channels();}};
  var runtime=NativeRestartRuntimeAssembly.fixedE(contract,checked);
  return PlannedCutoverLauncher.runRestart(runtime,context->{
   var b=contract.binding();var filter=new com.bridge.room.FixedSyntheticMaintenanceFilter(new com.bridge.persistence.DatasetBinding(b.spreadsheetId(),b.datasetId(),b.purpose()),runtime.gate(),runtime.staged());
   context.getBeanFactory().registerSingleton("fixedEMaintenanceFilter",filter);
   var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<com.bridge.room.FixedSyntheticMaintenanceFilter>(filter);
   registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);registration.addUrlPatterns("/*");registration.setDispatcherTypes(java.util.EnumSet.allOf(jakarta.servlet.DispatcherType.class));
   context.getBeanFactory().registerSingleton("fixedEMaintenanceRegistration",registration);
  });
 }
 static void requireReadOnlyConfiguration(Map<String,String> env){
  if(!"true".equals(env.get("BRIDGE_FIXED_E_READONLY_PREFLIGHT")))throw new IllegalStateException("Fixed E preflight disabled");
  // Reject ambiguity before a credential supplier can be invoked. Target is never imported.
  for(String k:List.of("BRIDGE_FIXED_E_SPREADSHEET_ID","BRIDGE_FIXED_E_DATASET_ID","BRIDGE_FIXED_E_CLOCK"))
   if(env.containsKey(k))throw new IllegalArgumentException("Target/boot overrides are not accepted by fixed E preflight");
 }
 public static void main(String[] args)throws java.io.IOException{startReadOnly(args,false);}
 public static void completedReadOnlyMain(String[] args)throws java.io.IOException{startReadOnly(args,true);}
 private static void startReadOnly(String[] args,boolean completed)throws java.io.IOException{
  if(args.length!=0)throw new IllegalArgumentException("No arguments accepted");
  var env=System.getenv();requireReadOnlyConfiguration(env);if(completed&&(!"true".equals(env.get("BRIDGE_FIXED_E_COMPLETION_READONLY_ENABLED"))||!"false".equals(env.get("BRIDGE_READONLY_STARTUP_ENABLED"))))throw new IllegalStateException("Completion readonly disabled");var b=FixedERestartClock.binding();
  var config=new QuarterGoogleFactory.ReadConfiguration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),()->{
   String value=env.get("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");if(value==null||value.length()>65536)throw new java.io.IOException("Credential unavailable");
   byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);return new java.io.ByteArrayInputStream(bytes){public void close(){Arrays.fill(bytes,(byte)0);}};
  });
  // Separate maintenance-only server: never starts RoomApplication or a legacy writer.
  int port=Integer.parseInt(env.getOrDefault("PORT","8080"));if(port<1||port>65535)throw new IllegalArgumentException("Invalid port");
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port),16);
  var artifact=completed?FixedEVerifiedArtifact.completedRestart():FixedEVerifiedArtifact.initial();
  server.createContext("/",exchange->{
   String path=exchange.getRequestURI().getRawPath();boolean health=exchange.getRequestMethod().equals("GET")&&exchange.getRequestURI().getRawQuery()==null&&(path.equals("/health/live")||path.equals("/health/route"));
   byte[] body="{\"maintenance\":true,\"writeReady\":false,\"restartProvisioned\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   byte[] verified=artifact.response(exchange.getRequestMethod(),path,exchange.getRequestURI().getRawQuery());if(verified!=null){body=verified;exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\"fixed-e-verified-baseline.json\"");}
   exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(verified!=null||health?200:503,body.length);try(var out=exchange.getResponseBody()){out.write(body);}finally{exchange.close();}
  });
  server.start();Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0),"fixed-e-readonly-stop"));
  Thread preflight=new Thread(()->{try(var channel=QuarterGoogleFactory.openReadOnly(config)){
   artifact.verifyOnce(completed?()->FixedECloseWait.read(FixedEBaselineBundle.compiled(),channel::readVerified):channel::readVerified);
   System.out.println(completed?"FIXED_E_COMPLETION_VERIFIED; WRITES_DISABLED":"FIXED_E_INITIAL_PREFLIGHT_VERIFIED; WRITES_DISABLED");
  }catch(RuntimeException failure){System.out.println("FIXED_E_PREFLIGHT_UNVERIFIED; WRITES_DISABLED");}},"fixed-e-readonly-preflight");preflight.setDaemon(true);preflight.start();
 }
}
