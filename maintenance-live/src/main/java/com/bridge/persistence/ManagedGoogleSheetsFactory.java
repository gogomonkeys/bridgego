package com.bridge.persistence;

import javax.net.ssl.SSLContext;
import java.time.Clock;
import java.util.function.LongSupplier;

/** Package-private factory. No environment discovery or implicit production enable.
 * Default open rejects before reading credentials, constructing TLS, resolving DNS or opening sockets.
 * Even explicitly enabled production is READ ONLY: a supplied write permit is rejected.
 * Maintenance writes require an immutable provisioned release and consumed quarter capability; this build has no live release.
 * OAuth scope is account-wide; target/issuer and final write permit are additional application boundaries. */
final class ManagedGoogleSheetsFactory {
  private ManagedGoogleSheetsFactory(){}
  record Configuration(boolean explicitlyEnabled,GoogleSheetsBinding binding,ManagedGoogleServiceAccount.Source credentials,WritePermit writePermit){
    static Configuration disabled(GoogleSheetsBinding binding,ManagedGoogleServiceAccount.Source source){return new Configuration(false,binding,source,null);}
    @Override public String toString(){return "ManagedGoogleConfiguration[REDACTED]";}
  }
  static Handle open(GoogleSheetsBinding binding,ManagedGoogleServiceAccount.Source source,SharedSheetsBudget budget){
    return open(Configuration.disabled(binding,source),budget);
  }
  static Handle open(Configuration config,SharedSheetsBudget budget){
    if(config==null||!config.explicitlyEnabled())throw new ManagedGoogleServiceAccount.Rejected();
    if(config.binding()==null||budget==null||config.writePermit()!=null)throw new ManagedGoogleServiceAccount.Rejected();
    ManagedGoogleOAuthExchange.requireSilentTls();
    ManagedGoogleServiceAccount identity=ManagedGoogleServiceAccount.load(config.credentials(),config.binding());
    try{return handle(config.binding(),budget,ManagedGoogleOAuthExchange.production(config.binding(),identity,budget));}
    catch(Exception|LinkageError rejected){identity.close();throw new ManagedGoogleServiceAccount.Rejected();}
  }
  /** Explicit test seam keeps Google claims/binding but cannot route outside numeric loopback. */
  static Handle openSynthetic(GoogleSheetsBinding binding,ManagedGoogleServiceAccount.Source source,SharedSheetsBudget budget,
      WritePermit permit,SSLContext localTls,int localPort){
    return openSynthetic(binding,source,budget,permit,localTls,localPort,Clock.systemUTC(),System::nanoTime);
  }
  static Handle openSynthetic(GoogleSheetsBinding binding,ManagedGoogleServiceAccount.Source source,SharedSheetsBudget budget,
      WritePermit permit,SSLContext localTls,int localPort,Clock realUtc,LongSupplier nanos){
    if(binding==null||localPort<1||localPort>65535||localTls==null||budget==null||realUtc==null||nanos==null)throw new ManagedGoogleServiceAccount.Rejected();
    if(permit!=null)permit.requireReady(binding.path()+":batchUpdate");
    ManagedGoogleOAuthExchange.requireSilentTls();ManagedGoogleServiceAccount identity=ManagedGoogleServiceAccount.load(source,binding);
    try{return handle(binding,budget,ManagedGoogleOAuthExchange.synthetic(binding,identity,budget,permit,localTls,localPort,realUtc,nanos));}
    catch(Exception|LinkageError rejected){identity.close();throw new ManagedGoogleServiceAccount.Rejected();}
  }
  /** Release-restricted path. Bundled UNPROVISIONED rejects before all credential or network work. */
  static Handle openMaintenance(MaintenanceRelease release,ManagedGoogleServiceAccount.Source source,SharedSheetsBudget budget,WritePermit permit){
    if(release==null)throw new ManagedGoogleServiceAccount.Rejected();
    return maintenance(release.requireLiveBinding(),source,budget,permit,null,0);
  }
  /** Package-private local-only seam; not selectable by the executable or environment. */
  static Handle openMaintenanceSynthetic(MaintenanceRelease release,ManagedGoogleServiceAccount.Source source,SharedSheetsBudget budget,WritePermit permit,SSLContext tls,int port){
    if(release==null||tls==null||port<1||port>65535)throw new ManagedGoogleServiceAccount.Rejected();
    return maintenance(release.requireProvisionedBinding(),source,budget,permit,tls,port);
  }
  private static Handle maintenance(GoogleSheetsBinding binding,ManagedGoogleServiceAccount.Source source,SharedSheetsBudget budget,WritePermit permit,SSLContext tls,int port){
    if(binding==null||budget==null)throw new ManagedGoogleServiceAccount.Rejected();
    if(permit!=null)permit.requireMaintenance(binding);
    ManagedGoogleOAuthExchange.requireSilentTls();
    ManagedGoogleServiceAccount identity=ManagedGoogleServiceAccount.load(source,binding);
    try{return handle(binding,budget,ManagedGoogleOAuthExchange.maintenance(binding,identity,budget,permit,tls,port));}
    catch(Exception|LinkageError rejected){identity.close();throw new ManagedGoogleServiceAccount.Rejected();}
  }
  private static Handle handle(GoogleSheetsBinding binding,SharedSheetsBudget budget,ManagedGoogleOAuthExchange provider){
    try{return new Handle(new BatchedSheetsTransport(443,budget,provider,binding.manualBinding()),provider);}
    catch(Exception|LinkageError rejected){provider.close();throw new ManagedGoogleServiceAccount.Rejected();}
  }
  static final class Handle implements AutoCloseable {
    final BatchedSheetsTransport transport;final ManagedGoogleOAuthExchange provider;
    private Handle(BatchedSheetsTransport transport,ManagedGoogleOAuthExchange provider){this.transport=transport;this.provider=provider;}
    @Override public void close(){transport.close();provider.close();}
    @Override public String toString(){return "ManagedGoogleSheetsHandle[REDACTED]";}
  }
}
