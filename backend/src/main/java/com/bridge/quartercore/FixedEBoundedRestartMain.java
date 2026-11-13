package com.bridge.quartercore;
import java.util.*;
/** Explicit synthetic E control-only rehearsal. Never enables the formal release. */
public final class FixedEBoundedRestartMain {
 private FixedEBoundedRestartMain(){}
 static void requireConfiguration(Map<String,String> env){
  if(!"true".equals(env.get("BRIDGE_FIXED_E_BOUNDED_RESTART_ENABLED")))throw new IllegalStateException("Fixed E restart disabled");
  if(!"false".equals(env.get("BRIDGE_READONLY_STARTUP_ENABLED")))throw new IllegalStateException("Other startup reader must be disabled");
  for(String k:List.of("BRIDGE_FIXED_E_SPREADSHEET_ID","BRIDGE_FIXED_E_DATASET_ID","BRIDGE_FIXED_E_CLOCK","BRIDGE_FIXED_E_BASELINE"))if(env.containsKey(k))throw new IllegalArgumentException("Fixed E overrides prohibited");
 }
 static NativeRestartContract contract(){var b=FixedERestartClock.binding();String ref="fixed-e-bounded-restart-20261113";return NativeRestartContract.fixedReviewedBuild(new CleanClosedRestartAssembly.Policy(b,NativeRestartContract.PROTOCOL_IDENTITY,ref,new QuarterRetentionPolicy.Configuration(b,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,ref)),"https://fixed-e-maintenance.invalid","fixed-e-login-disabled");}
 public static void main(String[] args){start(args,false);}
 public static void idleWakeMain(String[] args){start(args,true);}
 static void requireIdleConfiguration(Map<String,String> env){if(!"true".equals(env.get("BRIDGE_FIXED_E_IDLE_WAKE_ENABLED")))throw new IllegalStateException("Fixed E idle wake disabled");var copy=new HashMap<>(env);copy.put("BRIDGE_FIXED_E_BOUNDED_RESTART_ENABLED","true");requireConfiguration(copy);}
 private static void start(String[] args,boolean idle){
  if(args.length!=0)throw new IllegalArgumentException("No arguments accepted");var env=System.getenv();if(idle)requireIdleConfiguration(env);else requireConfiguration(env);
  var bundle=idle?FixedEBaselineBundle.compiledIdleWake():FixedEBaselineBundle.compiled();var release=contract();var b=release.binding();
  var config=new JournalGoogleFactory.Configuration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),()->{
   String value=env.get("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");if(value==null||value.length()>65536)throw new java.io.IOException("Credential unavailable");byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);return new java.io.ByteArrayInputStream(bytes){public void close(){Arrays.fill(bytes,(byte)0);}};
  });
  FixedERestartLauncher.run(bundle,release,NativeRestartRuntimeAssembly.googleIO(release,config));
 }
}
