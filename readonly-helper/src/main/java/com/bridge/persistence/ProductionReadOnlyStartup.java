package com.bridge.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate provider-free startup process. No Spring classes, background schedule or write capability. */
public final class ProductionReadOnlyStartup {
  private static final String IDENTITY="bridgego-sheets@bridgego-513805.iam.gserviceaccount.com";
  private static final ObjectMapper JSON=new ObjectMapper();
  private ProductionReadOnlyStartup() {}
  private static final AtomicBoolean STARTED=new AtomicBoolean();
  @FunctionalInterface interface Reader {
    ProductionReadOnlyWorkbook.Report read(ProductionReadOnlyPreflight.Settings settings,Supplier<byte[]> credentials);
  }
  record Outcome(String status,ProductionReadOnlyWorkbook.Report summary,ProductionReadOnlyDiagnostics.Snapshot diagnostic,ProductionReadOnlyFormatAudit.Summary formatAudit) {
    @Override public String toString(){return "StartupReadOnlyOutcome["+status+"]";}
  }
  static Outcome run(Function<String,String> env,Clock clock,AtomicBoolean attempted,Reader reader) {
    return run(env,clock,attempted,reader,new ProductionReadOnlyDiagnostics());
  }
  static Outcome run(Function<String,String> env,Clock clock,AtomicBoolean attempted,Reader reader,ProductionReadOnlyDiagnostics diagnostics) {
    diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.CONFIGURATION);
    if(!attempted.compareAndSet(false,true))return result("ALREADY_ATTEMPTED",diagnostics);
    try {
      String enabled=env.apply("BRIDGE_READONLY_STARTUP_ENABLED");
      if(enabled==null || enabled.isEmpty() || enabled.equals("false"))return result("DISABLED",diagnostics);
      if(!enabled.equals("true"))return result("CONFIG_REJECTED",diagnostics);
      String mode=env.apply("BRIDGE_RUNTIME_MODE");
      if(mode!=null && !mode.equals("game") || !"true".equals(env.apply("BRIDGE_SHEETS_ENABLED")))return result("CONFIG_REJECTED",diagnostics);
      var target=ProductionReadOnlyDeployment.confirmedTarget(env.apply("BRIDGE_SHEETS_SPREADSHEET_ID"),
          env.apply("BRIDGE_DATASET_ID"),env.apply("BRIDGE_COLLECTION_MODE"));
      Instant expiry=Instant.parse(env.apply("BRIDGE_READONLY_STARTUP_EXPIRES_AT"));Instant now=clock.instant();
      if(!expiry.isAfter(now) || expiry.isAfter(now.plusSeconds(900)))return result("WINDOW_REJECTED",diagnostics);
      // Each startup permits one read only. A new process may read again; there is no global-once claim.
      try {
        if(!expiry.isAfter(clock.instant()))return result("WINDOW_REJECTED",diagnostics);
        var settings=new ProductionReadOnlyPreflight.Settings(target,IDENTITY,true);
        var report=reader.read(settings,()->{
          String secret=env.apply("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");
          if(secret==null)throw new IllegalStateException("Existing managed credential unavailable");
          return secret.getBytes(StandardCharsets.UTF_8); // Fresh owned copy; facade wipes it.
        });
        if(report==null || report.mutationPermitted() || report.businessSemanticsVerified() || report.writerExclusionVerified())return result("READ_REJECTED",diagnostics);
        diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.COMPLETE);
        return new Outcome("OBSERVED_STRUCTURE_ONLY",report,diagnostics.snapshot(),diagnostics.formatAudit());
      }catch(Exception | LinkageError rejected){diagnostics.fail(rejected);return result("READ_REJECTED",diagnostics);}
    }catch(Exception | LinkageError invalid){diagnostics.fail(invalid);return result("CONFIG_REJECTED",diagnostics);}
  }
  private static Outcome result(String status,ProductionReadOnlyDiagnostics diagnostics){if(status.endsWith("_REJECTED"))diagnostics.fail(ProductionReadOnlyDiagnostics.ErrorType.REJECTED);return new Outcome(status,null,diagnostics.snapshot(),diagnostics.formatAudit());}
  public static void main(String[] ignored) {
    // No persistent marker is created. Deployments/cold starts can repeat this bounded, idempotent read.
    var diagnostics=new ProductionReadOnlyDiagnostics();
    Outcome result=run(System::getenv,Clock.systemUTC(),STARTED,(s,c)->ProductionReadOnlyPreflight.observeDiagnosed(s,c,diagnostics),diagnostics);
    try{System.out.println(JSON.writeValueAsString(result));}
    catch(Exception ignoredFailure){System.out.println("{\"status\":\"SUMMARY_UNAVAILABLE\",\"summary\":null}");}
  }
}
