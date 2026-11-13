package com.bridge.quartercore;
import java.util.*;import java.time.Clock;import java.security.*;import java.security.spec.X509EncodedKeySpec;
import com.bridge.persistence.*;import org.springframework.boot.SpringApplication;import org.springframework.context.ConfigurableApplicationContext;
/** Explicit opt-in main; ordinary settings do not bypass fresh operator/signed evidence checks. */
public final class PlannedCutoverLauncher {
 private PlannedCutoverLauncher(){}
 static String required(Map<String,String> e,String key){String v=e.get(key);if(v==null||v.isBlank()||v.length()>2048)throw new IllegalArgumentException("Missing or invalid setting: "+key);return v;}
 private static Set<String> list(Map<String,String> e,String key){var parts=required(e,key).split(",",-1);var out=new HashSet<>(Arrays.asList(parts));if(out.size()!=parts.length||out.size()>32||out.stream().anyMatch(s->s.isBlank()||!s.equals(s.trim())))throw new IllegalArgumentException("Invalid cutover list");return Set.copyOf(out);}
 static PlannedCutoverRuntimeAssembly.Settings settings(Map<String,String> e){
  if(!"true".equals(e.get("BRIDGE_PLANNED_CUTOVER_ENABLED")))throw new IllegalStateException("Planned cutover disabled");
  var b=new GoogleSheetsBinding(required(e,"BRIDGE_CUTOVER_SPREADSHEET_ID"),required(e,"BRIDGE_CUTOVER_DATASET_ID"),required(e,"BRIDGE_CUTOVER_PURPOSE"),required(e,"BRIDGE_CUTOVER_SERVICE_ACCOUNT_ISSUER"));
  var policy=new QuarterRetentionPolicy.Configuration(b,required(e,"BRIDGE_CUTOVER_POLICY_VERSION"),"Asia/Taipei",required(e,"BRIDGE_CUTOVER_POLICY_RULES"),required(e,"BRIDGE_CUTOVER_APPROVAL_REFERENCE"));
  String[] fields=required(e,"BRIDGE_CUTOVER_HISTORY_COUNTS").split(",",-1);if(fields.length!=9)throw new IllegalArgumentException("Nine historical counts required");long[] n=Arrays.stream(fields).mapToLong(Long::parseLong).toArray();
  var history=new PlannedCutoverRuntimeAssembly.History(required(e,"BRIDGE_CUTOVER_HISTORY_COMMIT"),required(e,"BRIDGE_CUTOVER_HISTORY_HASH"),required(e,"BRIDGE_CUTOVER_HISTORY_APPROVAL"),new LifetimeStatistics.Counts(n[0],n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8]));
  return new PlannedCutoverRuntimeAssembly.Settings(required(e,"BRIDGE_CUTOVER_ORIGIN"),required(e,"BRIDGE_GOOGLE_CLIENT_ID"),list(e,"BRIDGE_CUTOVER_OPERATOR_SUBJECTS"),b,required(e,"BRIDGE_CUTOVER_WORKSPACE"),required(e,"BRIDGE_CUTOVER_SERVICE"),required(e,"BRIDGE_CUTOVER_REVISION"),list(e,"BRIDGE_CUTOVER_LEGACY_DEPLOYMENTS"),list(e,"BRIDGE_CUTOVER_MANUAL_SOURCES"),required(e,"BRIDGE_CUTOVER_APPROVAL_REFERENCE"),history,policy);
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=0)throw new IllegalArgumentException("No cutover command-line overrides accepted");var env=System.getenv();var s=settings(env);Clock clock=Clock.systemUTC();
  byte[] key=Base64.getDecoder().decode(required(env,"BRIDGE_CUTOVER_OBSERVATION_PUBLIC_KEY"));if(key.length>128)throw new IllegalArgumentException("Invalid public key");var pin=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key));var relay=new CurrentSignedObservationRelay(pin,clock);
  var b=s.binding();var google=new JournalGoogleFactory.Configuration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),()->{
   String value=System.getenv("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");if(value==null||value.length()>65536)throw new java.io.IOException("Credential unavailable");byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);return new java.io.ByteArrayInputStream(bytes){public void close(){Arrays.fill(bytes,(byte)0);}};
  });
  var runtime=new PlannedCutoverRuntimeAssembly(s,PlannedCutoverRuntimeAssembly.googleIO(s,google),new com.bridge.room.GoogleJwtVerifier(new com.fasterxml.jackson.databind.ObjectMapper()),relay,clock);
  try{run(runtime);}catch(RuntimeException|Error e){runtime.close();throw e;}
 }
 static ConfigurableApplicationContext run(PlannedCutoverRuntimeAssembly runtime){
  var app=new SpringApplication(com.bridge.room.RoomApplication.class);
  app.addInitializers(context->{
   var b=runtime.settings().binding();boolean managed=b.purpose().equals(DatasetBinding.MANAGED_PURPOSE);var fixed=new HashMap<String,Object>();
   fixed.put("BRIDGE_PERSISTENCE_DRIVER","native-journal-v1");fixed.put("BRIDGE_SHEETS_ENABLED","true");fixed.put("BRIDGE_QUARTER_GATE_ENABLED","true");fixed.put("BRIDGE_COLLECTION_MODE",managed?"managed":"restricted");
   fixed.put(managed?"BRIDGE_SHEETS_SPREADSHEET_ID":"BRIDGE_TEST_SPREADSHEET_ID",b.spreadsheetId());fixed.put(managed?"BRIDGE_DATASET_ID":"BRIDGE_TEST_DATASET_ID",b.datasetId());
   fixed.put("GOOGLE_CLIENT_ID",runtime.settings().audience());fixed.put("PUBLIC_ORIGIN",runtime.settings().origin());
   fixed.put("spring.web.resources.cache.cachecontrol.no-store","true");
   context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixed-planned-runtime",fixed));
   var beans=context.getBeanFactory();beans.registerSingleton("preinstalledNativeRuntime",new PreinstalledNativeRuntime(runtime.gate(),runtime.staged(),new DatasetBinding(b.spreadsheetId(),b.datasetId(),b.purpose())));
   registerOperator(beans,runtime);
   context.addApplicationListener(event->{if(event instanceof org.springframework.context.event.ContextClosedEvent)runtime.close();});
  });return app.run();
 }
 private static void registerOperator(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans,PlannedCutoverRuntimeAssembly runtime){
  var controller=new InitialCutoverOperatorController(runtime.settings().origin(),runtime.settings().audience(),runtime.settings().manualSources(),runtime.operations(),runtime.clock());
  beans.registerSingleton("initialCutoverHttpAdapter",new InitialCutoverHttpAdapter(controller));
  beans.registerSingleton("signedObservationHttpAdapter",new SignedObservationHttpAdapter(runtime.settings().origin(),()->runtime.relay().current()));
 }
}
