package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Fixed isolated C-only post-cleanup check, never a formal-source ownership provider.
 * One ordinary session: CLAIM, fake identity, profile update, FORMAL, CASUAL, CLOSE.
 * No retries, rotation, clock substitution, target env override or unbounded workload. */
final class SyntheticCOrdinaryCheck implements SyntheticCLauncher.PostVerification,AutoCloseable {
  static final String SHEET="10AX1NfXIKoiu5k3JPUxFSiz20rYjcMm-7SqOkVars2o",DATASET="5b3abd1e-55f7-438f-a820-250c637324e3";
  @FunctionalInterface interface Handles { SyntheticCLauncher.Session open(JournalSessionAuthority authority); }
  private final GoogleSheetsBinding binding;private final ManagedGoogleServiceAccount.Source credentials;private final Clock clock;
  private final java.util.concurrent.atomic.AtomicReference<SyntheticCLauncher.Session> current=new java.util.concurrent.atomic.AtomicReference<>();
  private final java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean(),used=new java.util.concurrent.atomic.AtomicBoolean();
  SyntheticCOrdinaryCheck(GoogleSheetsBinding binding,ManagedGoogleServiceAccount.Source credentials,Clock clock){requireTarget(binding);this.binding=binding;this.credentials=Objects.requireNonNull(credentials);this.clock=Objects.requireNonNull(clock);}
  @Override public Book run(Book closed,JournalQuarterAuthority completed) {
    if(stopped.get()||!used.compareAndSet(false,true))throw new Rejected();
    return execute(binding,closed,completed,clock,authority->{
      if(stopped.get())throw new Rejected();
      var handle=JournalGoogleFactory.open(new JournalGoogleFactory.Configuration(true,binding.spreadsheetId(),binding.datasetId(),binding.purpose(),binding.serviceAccountIssuer(),credentials::open),authority);
      var session=new SyntheticCLauncher.Session(handle.transport(),handle::close);current.set(session);
      if(stopped.get()){session.close();current.compareAndSet(session,null);throw new Rejected();}
      return new SyntheticCLauncher.Session(session.transport(),()->{try{session.close();}finally{current.compareAndSet(session,null);}});
    });
  }
  @Override public void close(){stopped.set(true);var active=current.getAndSet(null);if(active!=null)active.close();}
  static Book run(GoogleSheetsBinding binding,Book closed,JournalQuarterAuthority completed,
      ManagedGoogleServiceAccount.Source credentials,Clock clock) {
    return execute(binding,closed,completed,clock,authority->{
      var handle=JournalGoogleFactory.open(new JournalGoogleFactory.Configuration(true,binding.spreadsheetId(),binding.datasetId(),binding.purpose(),binding.serviceAccountIssuer(),credentials::open),authority);
      return new SyntheticCLauncher.Session(handle.transport(),handle::close);
    });
  }
  static Book execute(GoogleSheetsBinding binding,Book closed,JournalQuarterAuthority completed,Clock clock,Handles handles) {
    requireTarget(binding);Objects.requireNonNull(clock);Objects.requireNonNull(handles);
    String baseline=JournalSessionAuthority.fullToken(closed);
    if(!Objects.requireNonNull(completed).verifiedClosedToken().equals(baseline)
        ||JournalQuarterProtocol.inspect(binding.manualBinding(),closed,clock).state()!=JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT)throw new Rejected();
    String owner="co-"+UUID.randomUUID();Instant started=clock.instant(),expires=started.plusSeconds(180);long deadline=System.nanoTime()+Duration.ofSeconds(180).toNanos();
    String scope="synthetic-c-ordinary-v1";
    var authority=JournalSessionAuthority.provision(binding,closed,owner,scope,scope,(actual,o,revision,approval,token)->{
      requireTarget(actual);
      if(!actual.equals(binding)||!owner.equals(o)||!scope.equals(revision)||!scope.equals(approval)||!baseline.equals(token)
          ||clock.instant().isBefore(started)||!clock.instant().isBefore(expires)||System.nanoTime()>=deadline)throw new Rejected();
    },clock,Duration.ofMinutes(3));
    try(SyntheticCLauncher.Session session=handles.open(authority)) {
      var transport=new JournaledNativeTransport(session.transport(),binding.manualBinding(),clock);
      try(Service service=new Service(transport,clock,binding.manualBinding(),owner,Limits.defaults())) {
        try(var scopeRead=transport.operation("c-ordinary-start",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))) {
          if(!baseline.equals(JournalSessionAuthority.fullToken(transport.snapshot())))throw new Rejected();service.start();
        }
        if(service.state()!=State.OPEN)throw new Rejected();NativeWriterAdapter writer=new NativeWriterAdapter(service);
        String subject="SYNTHETIC-C-"+UUID.randomUUID();String account;
        try(var op=operation(transport,service,"c-fake-identity")) {
          var result=writer.identity("C-IDENTITY-"+UUID.randomUUID(),"https://accounts.google.com",subject,"C Synthetic","c-initial@synthetic.test");
          if(result.outcome()!=NativeWriterAdapter.Outcome.VERIFIED)throw new Rejected();account=result.accountId();
        }
        try(var op=operation(transport,service,"c-fake-profile")) {
          var result=writer.identity("C-PROFILE-"+UUID.randomUUID(),"https://accounts.google.com",subject,"C Updated","c-updated@synthetic.test");
          if(result.outcome()!=NativeWriterAdapter.Outcome.VERIFIED||!account.equals(result.accountId()))throw new Rejected();
        }
        var envelopes=new ArrayList<com.bridge.persistence.FinalMatchEnvelope>();
        for(String category:List.of("FORMAL","CASUAL")) {
          var envelope=envelope(account,category,clock.instant());envelopes.add(envelope);
          try(var op=operation(transport,service,"c-fake-match")) {
            if(writer.match("C-MATCH-"+UUID.randomUUID(),envelope).outcome()!=NativeWriterAdapter.Outcome.VERIFIED)throw new Rejected();
          }
        }
        Book result;
        try(var op=operation(transport,service,"c-ordinary-close")) {
          service.cleanClose();if(service.state()!=State.CLOSED)throw new Rejected();result=transport.snapshot();
          NativeCommitJournal.verify(binding.manualBinding(),result,clock);
          if(!root(new ArrayList<>(result.metadata.values())).equals(service.known))throw new Rejected();
        }
        verifyContents(closed,result,account,subject,envelopes);return result;
      }
    } finally {authority.close();}
  }
  private static NarrowSheetsTransport.Scope operation(NarrowSheetsTransport tx,Service service,String label){return tx.operation(label,service.known,BatchedSheetsTransport.ALL,Duration.ofSeconds(60));}
  static void requireTarget(GoogleSheetsBinding binding){
    if(binding==null||!SHEET.equals(binding.spreadsheetId())||!DATASET.equals(binding.datasetId())||!"bridgego-restricted-test".equals(binding.purpose())||!SyntheticCRelease.ISSUER.equals(binding.serviceAccountIssuer()))throw new Rejected();
  }
  private static void verifyContents(Book before,Book after,String account,String subject,List<com.bridge.persistence.FinalMatchEnvelope> envelopes) {
    for(String table:List.of("dataset","retention_ledger","quarterly_aggregates","erasure_ledger"))if(!before.sheet(table).rows.equals(after.sheet(table).rows))throw new Rejected();
    for(String table:List.of("accounts","auth_identities","commits","envelopes")) {
      var old=before.sheet(table).rows;var rows=after.sheet(table).rows;
      if(rows.size()<old.size()||!rows.subList(0,old.size()).equals(old))throw new Rejected();
    }
    if(after.sheet("accounts").rows.size()!=before.sheet("accounts").rows.size()+1||after.sheet("auth_identities").rows.size()!=before.sheet("auth_identities").rows.size()+1||after.sheet("commits").rows.size()!=before.sheet("commits").rows.size()+2)throw new Rejected();
    var row=after.sheet("accounts").rows.get(after.sheet("accounts").rows.size()-1);
    if(!row.subList(0,4).equals(List.of(account,"2","C Updated","c-updated@synthetic.test")))throw new Rejected();
    var identity=after.sheet("auth_identities").rows.get(after.sheet("auth_identities").rows.size()-1);
    if(!identity.equals(List.of("https://accounts.google.com",subject,account,"1")))throw new Rejected();
    var decoded=ManualQuarterMaintenance.decoded(after.tables());
    for(var e:envelopes){var stored=decoded.get(e.commitId());if(stored==null||!stored.canonicalJson().equals(e.canonicalJson())||!stored.payloadHash().equals(e.payloadHash()))throw new Rejected();}
    var statistics=LifetimeStatistics.fromEnvelopes(account,decoded.values());
    if(statistics.formal().matches()!=1||statistics.casual().matches()!=1||statistics.formal().wins()!=1||statistics.casual().wins()!=1
        ||statistics.formal().totalScore()!=7||statistics.casual().totalScore()!=7)throw new Rejected();
  }
  static com.bridge.persistence.FinalMatchEnvelope envelope(String account,String category,Instant finish) {
    if(!Set.of("FORMAL","CASUAL").contains(category))throw new Rejected();String id=UUID.randomUUID().toString();Instant start=finish.minusSeconds(2);
    if(start.isBefore(QuarterlyRetention.currentQuarterStart(finish)))throw new Rejected();
    var match=row("match_id",id,"revision",1,"schema_version",1,"app_version","0.13.2","score_version",LifetimeStatistics.SCORE_VERSION,
      "started_at",start.toString(),"finished_at",finish.toString(),"statistics_rule",FinalMatchEnvelope.STATISTICS_RULE,"lifetime_rule",LifetimeStatistics.RULE,
      "deal_timing_rule",FinalMatchEnvelope.DEAL_TIMING_RULE,"match_category",category,"room_type",category,"outcome","SCORED","planned_deals",1,"completed_deals",1,
      "winner_team_id","NS","reason","PLANNED_DEALS_COMPLETE","ns_total",7,"ew_total",-3);
    var people=new ArrayList<Map<String,Object>>();for(int seat=0;seat<4;seat++){String type=category.equals("CASUAL")&&seat==3?"BOT":"HUMAN";
      people.add(row("match_id",id,"seat",seat,"team_id",seat%2==0?"NS":"EW","player_type",type,"control_type",type,"settlement_control",type,"account_id",seat==0?account:null,"guest_id",null));}
    return com.bridge.persistence.FinalMatchEnvelope.create(id,1,row("match",match,"participants",people,"completed_deals",List.of(row("match_id",id,"deal_id",UUID.randomUUID().toString(),"deal_index",1,"elapsed_ms",1000,"ns_total",7,"ew_total",-3))));
  }
  private static Map<String,Object> row(Object... values){var row=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row;}
}
