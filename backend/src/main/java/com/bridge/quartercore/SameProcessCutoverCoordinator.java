package com.bridge.quartercore;
import java.util.*;import java.util.function.Function;
import com.bridge.persistence.StagedNativeBackend;import com.bridge.persistence.QuarterMaintenanceGate;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** In-memory proof lineage only. No reconstruction from HTTP hashes or restart state. */
final class SameProcessCutoverCoordinator {
 @FunctionalInterface interface StartupBuilder {PlannedMaintenanceStartupFactory build(HistoricalMigrationAuthority.VerifiedResult result,Runnable initialLiveGuard);}
 private final GoogleSheetsBinding binding;private final QuarterMaintenanceGate gate;private final StagedNativeBackend staged;
 private InitialCutoverAuthority.VerifiedResult bootstrap;private HistoricalMigrationAuthority.VerifiedResult migration;private boolean busy,failed,migrationAttempted,activationAttempted;
 SameProcessCutoverCoordinator(GoogleSheetsBinding binding,QuarterMaintenanceGate gate,StagedNativeBackend staged){this.binding=Objects.requireNonNull(binding);this.gate=Objects.requireNonNull(gate);this.staged=Objects.requireNonNull(staged);if(!staged.belongsTo(gate)||!staged.inertRoutingSafe())throw new Rejected();}
 synchronized void acceptBootstrap(InitialCutoverAuthority.VerifiedResult proof){if(failed||busy||bootstrap!=null||proof==null||!binding.equals(proof.binding())||!staged.inertRoutingSafe())throw new Rejected();bootstrap=proof;}
 synchronized InitialCutoverAuthority.VerifiedResult bootstrapResult(){if(bootstrap==null)throw new Rejected();return bootstrap;}
 synchronized HistoricalMigrationAuthority.VerifiedResult migrationResult(){if(migration==null)throw new Rejected();return migration;}
 Book executeMigrationOnly(HistoricalMigrationAuthority authority,Function<HistoricalMigrationAuthority,Book> executor){
  synchronized(this){if(failed||busy||migrationAttempted||bootstrap==null||!staged.inertRoutingSafe())throw new Rejected();authority.requireBootstrap(bootstrap);busy=true;migrationAttempted=true;}
  try{Book after=executor.apply(authority);var proof=authority.verifiedResult();synchronized(this){if(failed||proof.bootstrap()!=bootstrap||!binding.equals(proof.binding())||!bootstrap.afterToken().equals(proof.beforeToken())||!proof.afterToken().equals(JournalSessionAuthority.fullToken(after)))throw new Rejected();migration=proof;}return after;}
  catch(RuntimeException|Error e){synchronized(this){failed=true;}throw e;}finally{synchronized(this){busy=false;}}
 }
 void activate(StartupBuilder builder,Runnable initialLiveGuard){
  HistoricalMigrationAuthority.VerifiedResult proof;synchronized(this){if(failed||busy||migration==null||activationAttempted||!staged.inertRoutingSafe())throw new Rejected();busy=true;activationAttempted=true;proof=migration;}
  try{Objects.requireNonNull(initialLiveGuard).run();var factory=Objects.requireNonNull(builder.build(proof,initialLiveGuard));initialLiveGuard.run();factory.requireCompletionBinding(binding,proof.afterToken(),gate);factory.activate(staged);}
  catch(RuntimeException|Error e){synchronized(this){failed=true;}throw e;}finally{synchronized(this){busy=false;}}
 }
}
