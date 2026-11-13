package com.bridge.quartercore;
import java.time.*;import java.util.*;
import com.bridge.persistence.QuarterMaintenanceGate;import com.bridge.persistence.StagedNativeBackend;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** One fresh JVM, one preinstalled gate/backend. No initial-cutover fallback or RAM proof import. */
final class NativeRestartRuntimeAssembly implements AutoCloseable {
 enum Status {WAITING,RESUMING,ATTACHED,BLOCKED,CLOSED}
 interface IO {Book readNative();PlannedMaintenanceStartupFactory.Channels channels();}
 private final NativeRestartContract contract;private final IO io;private final Clock clock;private final Clock businessClock;private final boolean isolatedE;private final QuarterMaintenanceGate gate;private final StagedNativeBackend staged;private Status status=Status.WAITING;
 NativeRestartRuntimeAssembly(NativeRestartContract contract,IO io,Clock clock){this(contract,io,clock,clock,false);}
 private NativeRestartRuntimeAssembly(NativeRestartContract contract,IO io,Clock securityClock,Clock businessClock,boolean isolatedE){this.contract=Objects.requireNonNull(contract);this.io=Objects.requireNonNull(io);this.clock=Objects.requireNonNull(securityClock);this.businessClock=Objects.requireNonNull(businessClock);this.isolatedE=isolatedE;if(isolatedE)FixedERestartClock.require(contract.binding(),businessClock);gate=new QuarterMaintenanceGate(true,businessClock);staged=new StagedNativeBackend(gate);}
 /** Future dedicated E composition only; callers cannot override either clock. Not provisioning. */
 static NativeRestartRuntimeAssembly fixedE(NativeRestartContract contract,IO io){return isolatedEClockTest(contract,io,Clock.systemUTC(),Clock.fixed(FixedERestartClock.BUSINESS_INSTANT,ZoneOffset.UTC));}
 static NativeRestartRuntimeAssembly isolatedEClockTest(NativeRestartContract contract,IO io,Clock securityClock,Clock businessClock){FixedERestartClock.require(contract.binding(),businessClock);return new NativeRestartRuntimeAssembly(contract,io,securityClock,businessClock,true);}
 void resume(){synchronized(this){if(status!=Status.WAITING)throw new Rejected();status=Status.RESUMING;}
  try{CleanClosedRestartAssembly.SourcePolicyVerifier source=(policy,close)->{requireLive();contract.requirePolicy(policy);};
   var first=new java.util.concurrent.atomic.AtomicBoolean(true);PlannedMaintenanceStartupFactory.Reader reader=()->first.compareAndSet(true,false)?NativeClaimCloseWait.read(contract.binding(),clock,io::readNative,()->{requireLive();contract.requirePolicy(contract.policy());if(!staged.inertRoutingSafe()||gate.state()!=QuarterMaintenanceGate.State.STARTING)throw new Rejected();}):io.readNative();
   var assembly=isolatedE?CleanClosedRestartAssembly.isolatedEClockTest(contract.policy(),source,io::readNative,io.channels(),gate,staged,clock,businessClock):new CleanClosedRestartAssembly(contract.policy(),source,reader,io.channels(),gate,staged,clock);assembly.resume();synchronized(this){requireLive();status=Status.ATTACHED;}}
  catch(RuntimeException|Error e){synchronized(this){if(status!=Status.CLOSED)status=Status.BLOCKED;}gate.blockManual(QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);throw e;}
 }
 private synchronized void requireLive(){if(isolatedE)FixedERestartClock.require(contract.binding(),businessClock);if(status==Status.BLOCKED||status==Status.CLOSED)throw new Rejected();}
 synchronized Status status(){return status;}NativeRestartContract contract(){return contract;}QuarterMaintenanceGate gate(){return gate;}StagedNativeBackend staged(){return staged;}Clock clock(){return clock;}
 public void close(){synchronized(this){if(status==Status.CLOSED)return;status=Status.CLOSED;}staged.closeAsynchronously();}
 static IO googleIO(NativeRestartContract release,JournalGoogleFactory.Configuration config){
  Objects.requireNonNull(release);var b=release.binding();if(config==null||!config.enabled()||config.credentials()==null||!b.equals(new GoogleSheetsBinding(config.spreadsheetId(),config.datasetId(),config.purpose(),config.issuer())))throw new Rejected();
  var channels=PlannedMaintenanceStartupFactory.googleChannels(new PlannedMaintenanceStartupFactory.Contract(b,"0".repeat(64),release.policy().sourceRevision(),release.policy().approvalReference()),config);
  return new IO(){public Book readNative(){try(var h=QuarterGoogleFactory.openReadOnly(new QuarterGoogleFactory.ReadConfiguration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),config.credentials()))){return h.readVerified();}}public PlannedMaintenanceStartupFactory.Channels channels(){return channels;}};
 }
}
