package com.bridge.persistence;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Trusted-host summary facade. No public raw-response, credential, mutation, URL or header interface. */
public final class ProductionReadOnlyPreflight {
  private static final AtomicBoolean ACTIVE=new AtomicBoolean();
  private ProductionReadOnlyPreflight() {}
  /** Construct only from independently verified server configuration, never web request parameters. */
  public record Settings(ProductionReadOnlyTarget target,String expectedServiceAccountEmail,boolean enabled) {
    public Settings(ProductionReadOnlyTarget target,String expectedServiceAccountEmail){this(target,expectedServiceAccountEmail,false);}
    @Override public String toString(){return "ProductionReadOnlySettings[REDACTED, enabled="+enabled+"]";}
  }
  /** Explicit observation only. Does not approve or save its own returned layout hash.
   * The credential supplier must return a fresh owned copy, which is wiped before returning. */
  public static ProductionReadOnlyWorkbook.Report observeOnce(Settings settings,Supplier<byte[]> existingCredentials) {
    return run(settings,existingCredentials,null,false,ProductionReadOnlyHttp::open);
  }
  static ProductionReadOnlyWorkbook.Report observeDiagnosed(Settings settings,Supplier<byte[]> credentials,ProductionReadOnlyDiagnostics diagnostics) {
    return run(settings,credentials,null,false,(t,c,e,enabled)->ProductionReadOnlyHttp.open(t,c,e,enabled,diagnostics),diagnostics);
  }
  /** Independent reviewed layout required; there is no fallback to observation on mismatch.
   * The credential supplier must return a fresh owned copy, which is wiped before returning. */
  public static ProductionReadOnlyWorkbook.Report verifyOnce(Settings settings,Supplier<byte[]> existingCredentials,String reviewedLayoutHash) {
    return run(settings,existingCredentials,reviewedLayoutHash,true,ProductionReadOnlyHttp::open);
  }
  @FunctionalInterface interface Factory {
    ProductionReadOnlyHttp open(ProductionReadOnlyTarget target,byte[] credentials,String email,boolean enabled);
  }
  /** Test seam only. Caller supplies a fresh owned credential copy; it is wiped on every exit path. */
  static ProductionReadOnlyWorkbook.Report run(Settings settings,Supplier<byte[]> credentials,String layout,
      boolean verify,Factory factory) {
    return run(settings,credentials,layout,verify,factory,new ProductionReadOnlyDiagnostics());
  }
  static ProductionReadOnlyWorkbook.Report run(Settings settings,Supplier<byte[]> credentials,String layout,
      boolean verify,Factory factory,ProductionReadOnlyDiagnostics diagnostics) {
    byte[] ownedCredentials=null,resource=null;boolean acquired=false;
    try {
      if(settings==null || !settings.enabled() || settings.target()==null || credentials==null || factory==null)throw rejected();
      ProductionReadOnlyHttp.validateExpectedEmail(settings.expectedServiceAccountEmail());
      if(verify && (layout==null || !layout.matches("[a-f0-9]{64}")))throw rejected();
      if(!ACTIVE.compareAndSet(false,true))throw rejected();acquired=true;
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.CREDENTIAL_LOAD);
      ownedCredentials=credentials.get();
      diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.CREDENTIAL_VALIDATION);
      try(var http=factory.open(settings.target(),ownedCredentials,settings.expectedServiceAccountEmail(),true)) {
        clear(ownedCredentials);ownedCredentials=null;
        resource=http.withDiagnostics(diagnostics).readFullResource();
        diagnostics.mark(ProductionReadOnlyDiagnostics.Stage.WORKBOOK_PARSE);
        return verify?ProductionReadOnlyWorkbook.verify(resource,settings.target(),layout):
            ProductionReadOnlyWorkbook.inspectDiagnosed(resource,settings.target(),diagnostics);
      }
    }catch(Exception | LinkageError failure){diagnostics.fail(failure);throw rejected();}
    finally{clear(ownedCredentials);clear(resource);if(acquired)ACTIVE.set(false);}
  }
  private static void clear(byte[] bytes){if(bytes!=null)Arrays.fill(bytes,(byte)0);}
  private static IllegalStateException rejected(){return new IllegalStateException("Production read-only preflight unavailable or rejected");}
}
