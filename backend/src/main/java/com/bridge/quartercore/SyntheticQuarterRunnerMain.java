package com.bridge.quartercore;
import java.time.*;import java.util.*;import java.io.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Dedicated opt-in executable; missing exact mode or release rejects before credentials, network or HTTP approval. */
public final class SyntheticQuarterRunnerMain {
 private SyntheticQuarterRunnerMain(){}
 public static void main(String[] args)throws Exception{
  if(args==null||args.length!=0)throw new Rejected();requireMode(System.getenv("BRIDGE_RUNTIME_MODE"));var release=requireProvisioned(SyntheticQuarterRunner.BUNDLED);
  Clock real=Clock.systemUTC();var b=release.binding();var config=new JournalGoogleFactory.Configuration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),()->{String value=System.getenv("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON");if(value==null||value.length()>65536)throw new IOException("Credential unavailable");byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);return new ByteArrayInputStream(bytes){public void close(){Arrays.fill(bytes,(byte)0);}};});
  var channels=PlannedMaintenanceStartupFactory.googleChannels(new PlannedMaintenanceStartupFactory.Contract(b,release.baselineToken(),release.revision(),release.approval()),config);
  PlannedMaintenanceStartupFactory.Reader reader=()->{try(var h=QuarterGoogleFactory.openReadOnly(new QuarterGoogleFactory.ReadConfiguration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),config.credentials()::open))){return h.readVerified();}};
  var runner=new SyntheticQuarterRunner(release,reader,channels,real,()->true); // No game/domain services exist in this executable.
  var driver=driver(runner,release);
  var scope=Map.<String,Object>of("kind","FIXED_SYNTHETIC_CROSS_QUARTER","spreadsheet",b.spreadsheetId(),"dataset",b.datasetId(),"purpose",b.purpose(),"baseline",release.baselineToken(),"revision",release.revision(),"fixtureHash",release.fixtureHash(),"businessStart",release.businessStart().toString(),"nextQuarter",release.nextQuarter().toString(),"scope","fixed fixtures; one business advance; fold/reopen/new fixtures/clean close; no public game");
  var launcher=new SyntheticQuarterRunnerLauncher(scope,driver,real,System.err::println);String port=System.getenv("PORT");int p=port==null?8080:Integer.parseInt(port);if(p<1||p>65535)throw new Rejected();launcher.health(p);Runtime.getRuntime().addShutdownHook(new Thread(launcher::close));launcher.start();new java.util.concurrent.CountDownLatch(1).await();
 }
 static SyntheticQuarterRunnerLauncher.Driver driver(SyntheticQuarterRunner runner,SyntheticQuarterRunner.Release release){return new SyntheticQuarterRunnerLauncher.Driver(){public void arm(){runner.arm(release.baselineToken());}public void run(){long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();checkDeadline(deadline);runner.start();checkDeadline(deadline);runner.submitFixedFixtures();waitConfirmed(runner,deadline);checkDeadline(deadline);runner.advanceOnce();checkDeadline(deadline);runner.submitNewQuarterFixtures();waitConfirmed(runner,deadline);checkDeadline(deadline);runner.finishClosed();checkDeadline(deadline);}public String verifiedToken(){return runner.verifiedClosedToken();}public void close(){runner.close();}};}
 static void requireMode(String mode){if(!"synthetic-quarter-runner".equals(mode))throw new IllegalStateException("SYNTHETIC_RUNNER_DISABLED");}
 static SyntheticQuarterRunner.Release requireProvisioned(SyntheticQuarterRunner.Release release){if(release==null)throw new IllegalStateException("SYNTHETIC_RUNNER_UNPROVISIONED");return release;}
 private static void checkDeadline(long deadline){if(System.nanoTime()>=deadline||Thread.currentThread().isInterrupted())throw new Rejected();}
 private static void waitConfirmed(SyntheticQuarterRunner runner,long deadline){while(!runner.fixturesConfirmed()){if(System.nanoTime()>=deadline||runner.status()==SyntheticQuarterRunner.Status.REVIEW||Thread.currentThread().isInterrupted())throw new Rejected();try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Rejected();}}}
}
