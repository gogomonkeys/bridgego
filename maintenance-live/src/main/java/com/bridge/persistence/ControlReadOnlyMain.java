package com.bridge.persistence;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** Sole fixed-target read-only public entry. No arguments, mode switch, arm or mutation path. */
public final class ControlReadOnlyMain {
  private ControlReadOnlyMain(){}
  public static void main(String[] args){
    ControlReadOnlyHost host=null;
    try{
      if(args==null||args.length!=0||!Runtime.version().toString().equals("17.0.21+9"))throw ControlReadOnlyHost.rejected();
      var release=MaintenanceRelease.BUNDLED;release.requireLiveBinding();LegacyWriterGuard.denyLegacyForProcess();
      ManagedGoogleServiceAccount.Source credentials=()->{
        String value=System.getenv("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");
        if(value==null||value.length()>65536)throw new java.io.IOException("CREDENTIAL_REJECTED");
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayInputStream(bytes){@Override public void close(){java.util.Arrays.fill(bytes,(byte)0);}};
      };
      host=new ControlReadOnlyHost(()->ManagedGoogleSheetsFactory.openMaintenance(release,credentials,SharedSheetsBudget.HOST,null),ControlReadOnlyHost::requireLiveResources,System.err::println);
      String port=System.getenv("PORT");int number=port==null?8080:Integer.parseInt(port);if(number<1||number>65535)throw ControlReadOnlyHost.rejected();
      host.health(number);var running=host;var stopped=new CountDownLatch(1);
      Runtime.getRuntime().addShutdownHook(new Thread(()->{running.close();stopped.countDown();},"control-readonly-shutdown"));
      host.start();stopped.await();
    }catch(Exception|LinkageError rejected){if(host!=null)host.close();System.err.println("CONTROL_READONLY_UNAVAILABLE");}
  }
}
