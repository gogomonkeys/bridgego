package com.bridge.quartercore;
import java.time.*;import java.util.*;import java.util.function.BooleanSupplier;
import com.bridge.persistence.StatsStore;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** No component, HTTP endpoint, env parser, identity API or caller-supplied match API.
 * Compiled synthetic fixture only. A new process is inert and requires a new exact-baseline arm.
 * This is a test runner, not a claim that normal gameplay clocks are independently configurable. */
final class SyntheticQuarterRunner implements AutoCloseable {
 enum Status { UNARMED,ARMED,STARTED,FIXTURES_SUBMITTED,ADVANCED,NEW_FIXTURES_SUBMITTED,VERIFIED,REVIEW,CLOSED }
 static final Release BUNDLED=SyntheticECompiledRelease.load(); // Fixed synthetic E only; explicit runner mode required.
 record Release(GoogleSheetsBinding binding,String baselineToken,String revision,String approval,
     Instant businessStart,Instant nextQuarter,List<com.bridge.persistence.FinalMatchEnvelope> envelopes,List<com.bridge.persistence.FinalMatchEnvelope> newQuarterEnvelopes,String fixtureHash) {
  private static final Set<String> EXCLUDED_SHEET_HASHES=Set.of("2cb7e2b584982dbca014d681a91105f49a718b396bd72c38f41cbbb61a81cc0a","210fc8ddd190a97a4474c1df49adbb4cb5966dbc239ad4e372c70e6d2022f312","76b02d08ba9c730cebf2e0fa8159f554f10ce4d7fd36b01586354e8afee2af36");
  static void requireAllowedSpreadsheetHash(String hash){if(EXCLUDED_SHEET_HASHES.contains(hash))throw new Rejected();}
  Release {
   Objects.requireNonNull(binding);requireAllowedSpreadsheetHash(FinalMatchEnvelope.hash(binding.spreadsheetId()));
   Objects.requireNonNull(binding);Objects.requireNonNull(businessStart);Objects.requireNonNull(nextQuarter);envelopes=List.copyOf(envelopes);newQuarterEnvelopes=List.copyOf(newQuarterEnvelopes);
   if(!"bridgego-restricted-test".equals(binding.purpose())||baselineToken==null||!baselineToken.matches("[a-f0-9]{64}")
       ||revision==null||revision.isBlank()||approval==null||approval.isBlank()||envelopes.isEmpty()||envelopes.size()>4||newQuarterEnvelopes.isEmpty()||newQuarterEnvelopes.size()>4
       ||!nextQuarter.equals(QuarterlyRetention.currentQuarterStart(businessStart).atZone(ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1))
       ||!manifest(all(envelopes,newQuarterEnvelopes)).equals(fixtureHash))throw new Rejected();
   Set<String> ids=new HashSet<>();for(var e:envelopes){if(!ids.add(e.commitId()))throw new Rejected();var n=new FinalMatchEnvelope(e.commitId(),e.matchId(),e.revision(),e.canonicalJson(),e.payloadHash());
    Instant finish=QuarterlyRetention.finishedAt(n);if(finish.isBefore(QuarterlyRetention.currentQuarterStart(businessStart))||finish.isAfter(businessStart))throw new Rejected();}
   Set<String> allIds=new HashSet<>();for(var e:all(envelopes,newQuarterEnvelopes))if(!allIds.add(e.commitId()))throw new Rejected();
   for(var e:newQuarterEnvelopes){var n=new FinalMatchEnvelope(e.commitId(),e.matchId(),e.revision(),e.canonicalJson(),e.payloadHash());Instant finish=QuarterlyRetention.finishedAt(n);if(finish.isBefore(QuarterlyRetention.currentQuarterStart(nextQuarter))||finish.isAfter(nextQuarter))throw new Rejected();}
  }
  static List<com.bridge.persistence.FinalMatchEnvelope> all(List<com.bridge.persistence.FinalMatchEnvelope>a,List<com.bridge.persistence.FinalMatchEnvelope>b){var out=new ArrayList<com.bridge.persistence.FinalMatchEnvelope>(a);out.addAll(b);return List.copyOf(out);}
  static String manifest(List<com.bridge.persistence.FinalMatchEnvelope> values){return FinalMatchEnvelope.hash(NativeCommitJournal.canonical(values.stream().map(e->List.of(e.commitId(),e.payloadHash(),e.canonicalJson())).toList()));}
 }
 private static final class BusinessClock extends Clock {
  private Instant now;BusinessClock(Instant start){now=start;}public synchronized Instant instant(){return now;}
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){if(!ZoneOffset.UTC.equals(z))throw new Rejected();return this;}
  synchronized void advance(Instant expected,Instant next){if(!now.equals(expected)||!next.isAfter(now))throw new Rejected();now=next;}
 }
 private final Release release;private final Clock security;private final BusinessClock business;private final BooleanSupplier domain;
 private final PlannedMaintenanceStartupFactory.Reader reader;private final com.bridge.persistence.QuarterMaintenanceGate gate;
 private final com.bridge.persistence.StagedNativeBackend staged;private final PlannedMaintenanceStartupFactory factory;
 private final com.bridge.persistence.QuarterMaintenanceCoordinator coordinator;private Map<String,List<List<String>>> unchangedTables;private List<List<String>> expectedAccounts;private String finalToken;private final Object revocationLock=new Object();private volatile boolean revoked;private final java.util.concurrent.atomic.AtomicBoolean releasing=new java.util.concurrent.atomic.AtomicBoolean();private volatile Status status=Status.UNARMED;private boolean advanced;
 SyntheticQuarterRunner(Release release,PlannedMaintenanceStartupFactory.Reader reader,PlannedMaintenanceStartupFactory.Channels channels,Clock security,BooleanSupplier domain){
  this.release=Objects.requireNonNull(release);this.reader=Objects.requireNonNull(reader);this.security=Objects.requireNonNull(security);this.domain=Objects.requireNonNull(domain);business=new BusinessClock(release.businessStart());
  gate=new com.bridge.persistence.QuarterMaintenanceGate(true,business);staged=new com.bridge.persistence.StagedNativeBackend(gate);
  var policy=QuarterRetentionPolicy.explicitlyApproved(new QuarterRetentionPolicy.Configuration(release.binding(),QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,release.approval()),business);
  var contract=new PlannedMaintenanceStartupFactory.Contract(release.binding(),release.baselineToken(),release.revision(),release.approval());
  factory=PlannedMaintenanceStartupFactory.isolatedClockTest(contract,c->{if(revoked||!contract.equals(c)||status==Status.UNARMED||status==Status.REVIEW||status==Status.CLOSED)throw new Rejected();},reader,channels,policy,gate,security,business,release.binding());
  coordinator=new com.bridge.persistence.QuarterMaintenanceCoordinator(gate,staged.lifecycle(),business,domain,()->{});
 }
 Status status(){return status;}
 synchronized Instant businessTime(){return business.instant();}
 synchronized void arm(String expectedBaseline){
  if(revoked||status!=Status.UNARMED)throw new Rejected();try{if(!release.baselineToken().equals(expectedBaseline)||!domain.getAsBoolean())throw new Rejected();var b=reader.read();checkRevoked();
   if(!release.baselineToken().equals(JournalSessionAuthority.fullToken(b))||JournalQuarterProtocol.inspect(release.binding().manualBinding(),b,business).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();publish(Status.ARMED);
  }catch(RuntimeException e){stop();throw e;}
 }
 synchronized void start(){if(revoked||status!=Status.ARMED)throw new Rejected();publish(Status.STARTED);try{factory.activate(staged);checkRevoked();coordinator.tick();checkRevoked();if(!gate.acceptsNewGames())throw new Rejected();}catch(RuntimeException e){stop();throw e;}}
 synchronized void submitFixedFixtures(){if(revoked||status!=Status.STARTED)throw new Rejected();publish(Status.FIXTURES_SUBMITTED);try{
  for(var e:release.envelopes()){checkRevoked();gate.registerMatch(e.matchId());if(!Set.of(StatsStore.SaveStatus.PENDING,StatsStore.SaveStatus.CONFIRMED).contains(staged.submit(e)))throw new Rejected();}
 }catch(RuntimeException e){stop();throw e;}}
 synchronized boolean fixturesConfirmed(){if(status!=Status.FIXTURES_SUBMITTED&&status!=Status.NEW_FIXTURES_SUBMITTED)return false;for(var e:status==Status.FIXTURES_SUBMITTED?release.envelopes():release.newQuarterEnvelopes()){var s=staged.status(e.commitId());if(s==StatsStore.SaveStatus.UNKNOWN||s==StatsStore.SaveStatus.REJECTED){stop();return false;}if(s!=StatsStore.SaveStatus.CONFIRMED)return false;}try{factory.requireSyntheticQuiescent();return true;}catch(IllegalStateException notDrained){return false;}}
 synchronized void advanceOnce(){
  if(revoked||status!=Status.FIXTURES_SUBMITTED||advanced)throw new Rejected();
  try{if(!fixturesConfirmed()||!domain.getAsBoolean())throw new Rejected();factory.requireSyntheticQuiescent();
   // Runner owns the only submit surface. No external game/identity API or mutable clock escapes.
   var before=factory.syntheticSnapshot();checkRevoked();var expired=ManualQuarterMaintenance.decoded(before.tables()).values().stream().filter(e->QuarterlyRetention.finishedAt(e).isBefore(QuarterlyRetention.currentQuarterStart(release.nextQuarter()))).toList();
   unchangedTables=new HashMap<>();for(String tab:List.of("dataset","auth_identities","erasure_ledger"))unchangedTables.put(tab,copyRows(before.tables().get(tab)));
   expectedAccounts=LifetimeStatistics.fold(before.tables().get("accounts"),expired,QuarterlyRetention.currentQuarterStart(release.nextQuarter()));
   checkRevoked();advanced=true;business.advance(release.businessStart(),release.nextQuarter());coordinator.tick();
   if(coordinator.status()!=com.bridge.persistence.QuarterMaintenanceCoordinator.Status.OPEN||!gate.acceptsNewGames())throw new Rejected();
   publish(Status.ADVANCED);
  }catch(RuntimeException e){stop();throw e;}
 }
 synchronized void submitNewQuarterFixtures(){if(revoked||status!=Status.ADVANCED)throw new Rejected();publish(Status.NEW_FIXTURES_SUBMITTED);try{for(var e:release.newQuarterEnvelopes()){checkRevoked();gate.registerMatch(e.matchId());if(!Set.of(StatsStore.SaveStatus.PENDING,StatsStore.SaveStatus.CONFIRMED).contains(staged.submit(e)))throw new Rejected();}}catch(RuntimeException e){stop();throw e;}}
 synchronized void finishClosed(){if(revoked||status!=Status.NEW_FIXTURES_SUBMITTED)throw new Rejected();try{
  if(!fixturesConfirmed()||!domain.getAsBoolean())throw new Rejected();factory.requireSyntheticQuiescent();gate.beginDrain();if(!gate.beginExclusive())throw new Rejected();
  checkRevoked();var after=factory.closeSynthetic();checkRevoked();for(var entry:unchangedTables.entrySet())if(!after.tables().get(entry.getKey()).equals(entry.getValue()))throw new Rejected();if(!after.tables().get("accounts").equals(expectedAccounts))throw new Rejected();
  var records=ManualQuarterMaintenance.decoded(after.tables());Set<String> expected=new HashSet<>();for(var e:release.newQuarterEnvelopes()){expected.add(e.commitId());var actual=records.get(e.commitId());if(actual==null||!actual.payloadHash().equals(e.payloadHash()))throw new Rejected();}
  if(!records.keySet().equals(expected)||JournalQuarterProtocol.inspect(release.binding().manualBinding(),after,business).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();
  finalToken=JournalSessionAuthority.fullToken(after);publish(Status.VERIFIED);
 }catch(RuntimeException e){stop();throw e;}}
 synchronized String verifiedClosedToken(){if(revoked||status!=Status.VERIFIED)throw new Rejected();return finalToken;}
 private void checkRevoked(){if(revoked)throw new Rejected();}
 private void publish(Status next){synchronized(revocationLock){checkRevoked();status=next;}}
 private void releaseAsync(){if(releasing.compareAndSet(false,true)){var t=new Thread(()->{try{staged.close();}catch(RuntimeException ignored){}} ,"synthetic-runner-release");t.setDaemon(true);t.start();}}
 private void stop(){synchronized(revocationLock){if(!revoked)status=Status.REVIEW;revoked=true;}gate.blockManual(com.bridge.persistence.QuarterMaintenanceGate.Reason.VERIFICATION_FAILED);releaseAsync();}
 /** Revocation is immediate and never waits for a worker holding the runner/Sheets I/O monitor.
  * Already-dispatched requests can still arrive; no later dispatch or success publication is allowed. */
 @Override public void close(){synchronized(revocationLock){revoked=true;status=Status.CLOSED;}releaseAsync();}
}
