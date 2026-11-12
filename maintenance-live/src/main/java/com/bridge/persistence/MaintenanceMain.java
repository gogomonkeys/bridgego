package com.bridge.persistence;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** Public maintenance-only entry. Immutable synthetic target; no game fallback or target override. */
public final class MaintenanceMain {
  private MaintenanceMain(){}
  public static void main(String[] args){
    MaintenanceLauncher launcher=null;
    try{
      if(args==null||args.length!=0||!Runtime.version().toString().equals("17.0.21+9"))throw BootArm.denied();
      var release=MaintenanceRelease.BUNDLED;
      LegacyWriterGuard.denyLegacyForProcess();
      // Credentials are read lazily inside the host, after the release and resource gates.
      ManagedGoogleServiceAccount.Source credentials=()->{
        String value=System.getenv("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");
        if(value==null||value.length()>65536)throw new java.io.IOException("CREDENTIAL_REJECTED");
        // Environment strings cannot be erased; this owned UTF-8 copy is wiped on close.
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayInputStream(bytes){@Override public void close(){java.util.Arrays.fill(bytes,(byte)0);}};
      };
      var budget=SharedSheetsBudget.HOST;
      launcher=new MaintenanceLauncher(release,p->ManagedGoogleSheetsFactory.openMaintenance(release,credentials,budget,p),MaintenanceLauncher::requireLiveResources,System.err::println);
      String port=System.getenv("PORT");int number=port==null?8080:Integer.parseInt(port);
      if(number<1||number>65535)throw BootArm.denied();launcher.health(number);
      var running=launcher;var stopped=new CountDownLatch(1);
      Runtime.getRuntime().addShutdownHook(new Thread(()->{running.close();stopped.countDown();},"maintenance-shutdown"));
      try{release.requireLiveBinding();launcher.start();}
      catch(RuntimeException unprovisioned){System.err.println("MAINTENANCE_UNAVAILABLE");}
      stopped.await();
    }catch(Exception|LinkageError rejected){if(launcher!=null)launcher.close();System.err.println("MAINTENANCE_UNAVAILABLE");}
  }
}
