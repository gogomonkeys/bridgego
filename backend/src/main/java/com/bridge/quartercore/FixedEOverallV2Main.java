package com.bridge.quartercore;
import java.time.*;import java.util.*;
/** Explicit fixed synthetic E once-only maintenance executable. */
public final class FixedEOverallV2Main {
 private FixedEOverallV2Main(){}
 static void requireConfiguration(Map<String,String> env){if(!"true".equals(env.get("BRIDGE_FIXED_E_OVERALL_V2_ENABLED"))||!"false".equals(env.get("BRIDGE_READONLY_STARTUP_ENABLED")))throw new IllegalStateException("Fixed E overall runner disabled");for(String key:List.of("BRIDGE_FIXED_E_SPREADSHEET_ID","BRIDGE_FIXED_E_DATASET_ID","BRIDGE_FIXED_E_CLOCK","BRIDGE_FIXED_E_BASELINE"))if(env.containsKey(key))throw new IllegalArgumentException("No fixed E overrides");}
 public static void main(String[] args)throws Exception{if(args.length!=0)throw new IllegalArgumentException("No arguments");var env=System.getenv();requireConfiguration(env);FixedEOverallV2Runner.baseline();var b=FixedERestartClock.binding();var real=Clock.systemUTC();
  var config=new JournalGoogleFactory.Configuration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),()->{String value=env.get("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");if(value==null||value.length()>65536)throw new java.io.IOException("Credential unavailable");byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);return new java.io.ByteArrayInputStream(bytes){public void close(){Arrays.fill(bytes,(byte)0);}};});
  var channels=PlannedMaintenanceStartupFactory.googleChannels(new PlannedMaintenanceStartupFactory.Contract(b,FixedEOverallV2Runner.TOKEN,"fixed-e-overall-v2-once","fixed-e-overall-v2-20261113"),config);
  PlannedMaintenanceStartupFactory.Reader reader=()->{try(var h=QuarterGoogleFactory.openReadOnly(new QuarterGoogleFactory.ReadConfiguration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),config.credentials()))){return h.readVerified();}};
  var runner=new FixedEOverallV2Runner(reader,channels,real);var scope=Map.<String,Object>of("kind","FIXED_E_Q2_OVERALL_V2_ONCE","baseline",FixedEOverallV2Runner.TOKEN,"businessTime",FixedEOverallV2Runner.BUSINESS.toString(),"scope","fixed E only; five phases; remove two synthetic Q1 records; retain lifetime and formal/casual v2 counts; no ordinary reopen");
  var host=new SyntheticQuarterRunnerLauncher(scope,runner,real,System.err::println);int port=Integer.parseInt(env.getOrDefault("PORT","8080"));if(port<1||port>65535)throw new IllegalArgumentException("Invalid port");host.health(port);Runtime.getRuntime().addShutdownHook(new Thread(host::close));host.start();new java.util.concurrent.CountDownLatch(1).await();
 }
}
