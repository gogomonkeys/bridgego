package com.bridge.quartercore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
/** Dedicated maintenance executable only. No game/legacy fallback, target env override or automatic write. */
public final class SyntheticCMain {
  private SyntheticCMain(){}
  public static void main(String[] args){SyntheticCLauncher launcher=null;try{
    if(args==null||args.length!=0||!Runtime.version().toString().equals("17.0.21+9"))throw new IllegalStateException();
    healthLimits();var release=SyntheticCRelease.BUNDLED;
    ManagedGoogleServiceAccount.Source credentials=()->{
      String value=System.getenv("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");if(value==null||value.length()>65536)throw new IOException("CREDENTIAL_REJECTED");
      byte[] bytes=value.getBytes(StandardCharsets.UTF_8);return new ByteArrayInputStream(bytes){public void close(){Arrays.fill(bytes,(byte)0);}};
    };
    var handles=new SyntheticCLauncher.Handles(){
      public SyntheticCLauncher.Session readOnly(){
        var binding=release.require();var handle=ManagedGoogleSheetsFactory.open(new ManagedGoogleSheetsFactory.Configuration(true,binding,credentials,null),SharedSheetsBudget.HOST);
        return new SyntheticCLauncher.Session(handle.transport,handle::close);
      }
      public SyntheticCLauncher.Session quarter(JournalQuarterAuthority grant){
        var b=release.require();var handle=JournalQuarterGoogleFactory.open(new JournalGoogleFactory.Configuration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),credentials::open),grant);
        return new SyntheticCLauncher.Session(handle.transport(),handle::close);
      }
    };
    var hook=new SyntheticCLauncher.PostVerification(){
      private final java.util.concurrent.atomic.AtomicReference<SyntheticCOrdinaryCheck> current=new java.util.concurrent.atomic.AtomicReference<>();
      private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
      public NativeSheetsModel.Book run(NativeSheetsModel.Book before,JournalQuarterAuthority completed){
        if(closed.get())throw new NativeSheetsModel.Rejected();var next=new SyntheticCOrdinaryCheck(release.require(),credentials,Clock.systemUTC());
        if(!current.compareAndSet(null,next)||closed.get()){next.close();throw new NativeSheetsModel.Rejected();}
        return next.run(before,completed);
      }
      public void close(){closed.set(true);var active=current.get();if(active!=null)active.close();}
    };
    launcher=new SyntheticCLauncher(release,handles,Clock.systemUTC(),System.err::println,release::requireSavedBefore,hook);
    String port=System.getenv("PORT");int p=port==null?8080:Integer.parseInt(port);if(p<1||p>65535)throw new IllegalStateException();launcher.health(p);
    var running=launcher;var stopped=new java.util.concurrent.CountDownLatch(1);Runtime.getRuntime().addShutdownHook(new Thread(()->{running.close();stopped.countDown();},"synthetic-c-stop"));
    try{launcher.start();}catch(RuntimeException unprovisioned){System.err.println("C_UNPROVISIONED_OR_BASELINE_UNAVAILABLE");}
    stopped.await();
  }catch(Exception|LinkageError failure){if(launcher!=null)launcher.close();System.err.println("C_UNAVAILABLE");}}
  static void healthLimits(){
    Map.ofEntries(Map.entry("jdk.httpserver.maxConnections","16"),Map.entry("sun.net.httpserver.maxIdleConnections","0"),Map.entry("sun.net.httpserver.idleInterval","2"),Map.entry("sun.net.httpserver.clockTick","250"),Map.entry("sun.net.httpserver.maxReqTime","2"),Map.entry("sun.net.httpserver.maxRspTime","2"),Map.entry("sun.net.httpserver.timerMillis","250"),Map.entry("sun.net.httpserver.maxReqHeaders","64"),Map.entry("sun.net.httpserver.maxReqHeaderSize","8192"),Map.entry("sun.net.httpserver.drainAmount","0"),Map.entry("sun.net.httpserver.debug","false"),Map.entry("sun.net.httpserver.disableHostValidation","false")).forEach(System::setProperty);
    java.util.logging.Logger.getLogger("com.sun.net.httpserver").setLevel(java.util.logging.Level.OFF);
  }
}
