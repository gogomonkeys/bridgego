package com.bridge.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Offline-only retention. No scheduler, endpoint or default destructive execution. */
public final class QuarterlyRetention {
    public static final ZoneId TAIPEI=ZoneId.of("Asia/Taipei");
    public static final List<String> AGGREGATE_HEADER=List.of("quarter","publication","matches_rounded","deals_rounded","schema_version");
    private static final Set<String> TABS=Set.of("envelopes","commits","accounts","auth_identities","erasure_ledger","retention_ledger","quarterly_aggregates");
    public enum State { DRY_RUN, PENDING, COMPLETE, FAILED, UNCERTAIN }
    /** Thresholds are candidate policy, not anonymity guarantees; disabled by default. */
    public record AggregatePolicy(boolean approved,int minimumMatches,int minimumHumans,int rounding) {
        public AggregatePolicy {if(minimumMatches<50||minimumHumans<20||rounding<10||rounding%10!=0)throw new IllegalArgumentException("Conservative minimum required");}
        public static AggregatePolicy suppressAll(){return new AggregatePolicy(false,50,20,10);}
    }
    public record Preview(String planId,String beforeDigest,Instant cutoff,int expiredCommits,String confirmationText) {}
    public record Result(State state,Instant cutoff,Instant completedAt) {}
    private static final class Plan {
        final Preview preview;final Map<String,List<List<String>>> before,after;final Instant expiresAt,alreadyCompletedAt;
        boolean attempted;
        Plan(Preview preview,Map<String,List<List<String>>> before,Map<String,List<List<String>>> after,Instant expiresAt,Instant alreadyCompletedAt){
            this.preview=preview;this.before=before;this.after=after;this.expiresAt=expiresAt;this.alreadyCompletedAt=alreadyCompletedAt;
        }
    }
    private final SheetsGateway gateway;private final Clock clock;private final BooleanSupplier exclusiveOffline;
    private final boolean executionEnabled;private final AggregatePolicy policy;private Plan plan;
    public QuarterlyRetention(SheetsGateway gateway,Clock clock,BooleanSupplier exclusiveOffline){this(gateway,clock,exclusiveOffline,false,AggregatePolicy.suppressAll());}
    public QuarterlyRetention(SheetsGateway gateway,Clock clock,BooleanSupplier exclusiveOffline,boolean executionEnabled,AggregatePolicy policy){this.gateway=Objects.requireNonNull(gateway);this.clock=Objects.requireNonNull(clock);this.exclusiveOffline=Objects.requireNonNull(exclusiveOffline);this.executionEnabled=executionEnabled;this.policy=Objects.requireNonNull(policy);}
    public static Instant currentQuarterStart(Instant now){ZonedDateTime local=now.atZone(TAIPEI);return LocalDate.of(local.getYear(),((local.getMonthValue()-1)/3)*3+1,1).atStartOfDay(TAIPEI).toInstant();}
    public static String quarter(Instant now){var local=now.atZone(TAIPEI);return local.getYear()+"-Q"+((local.getMonthValue()-1)/3+1);}
    public static Instant finishedAt(FinalMatchEnvelope envelope){try{return Instant.parse(envelope.payload().path("match").path("finished_at").asText());}catch(RuntimeException bad){throw new SheetsGateway.Conflict();}}
    public static void requireCurrent(FinalMatchEnvelope envelope,Clock clock,RetentionLedger ledger){
        Instant now=clock.instant(),boundary=currentQuarterStart(now),finish=finishedAt(envelope),start;
        try{start=Instant.parse(envelope.payload().path("match").path("started_at").asText());}catch(RuntimeException bad){throw new SheetsGateway.Conflict();}
        if(finish.isBefore(boundary)||finish.isAfter(now)||start.isAfter(finish))throw new SheetsGateway.Conflict();ledger.requireAllowed(envelope);
    }
    public static void requireCollectionReady(SheetsGateway gateway,Clock clock){RetentionLedger ledger=new RetentionLedger(gateway.read("retention_ledger"));if(!ledger.complete()||!ledger.cutoff().equals(currentQuarterStart(clock.instant())))throw new IllegalStateException("Quarter cleanup pending; collection blocked");}
    private void requireOffline(){if(!exclusiveOffline.getAsBoolean())throw new IllegalStateException("All game, identity and maintenance writers must be stopped");}
    public synchronized Preview preview(){
        if(plan!=null&&plan.attempted)throw new IllegalStateException("Prior attempt requires review; use a new maintenance session");
        requireOffline();Instant boundary=currentQuarterStart(clock.instant());var before=snapshot();var after=copy(before);
        RetentionLedger ledger=new RetentionLedger(before.get("retention_ledger"));if(ledger.cutoff().isAfter(boundary))throw new SheetsGateway.Conflict();
        var raw=SheetsStatsStore.readVerified(memory(before));Set<String> expired=new HashSet<>();Map<String,List<FinalMatchEnvelope>> grouped=new TreeMap<>();
        for(var envelope:raw.values()){
            ledger.requireAllowed(envelope);Instant finished=finishedAt(envelope);
            if(finished.isAfter(clock.instant()))throw new SheetsGateway.Conflict();
            if(finished.isBefore(boundary)){expired.add(envelope.commitId());grouped.computeIfAbsent(quarter(finished),k->new ArrayList<>()).add(envelope);}
        }
        List<FinalMatchEnvelope> expiredRecords = raw.values().stream().filter(e -> expired.contains(e.commitId())).toList();
        after.put("accounts", new ArrayList<>(LifetimeStatistics.fold(before.get("accounts"), expiredRecords, boundary)));
        after.get("envelopes").removeIf(r->!r.isEmpty()&&expired.contains(r.get(0)));after.get("commits").removeIf(r->!r.isEmpty()&&expired.contains(r.get(0)));
        for(var group:grouped.entrySet()){
            if(after.get("quarterly_aggregates").stream().skip(1).anyMatch(r->r.get(0).equals(group.getKey())))throw new SheetsGateway.Conflict();
            after.get("quarterly_aggregates").add(aggregate(group.getKey(),group.getValue()));
        }
        Instant alreadyCompletedAt=null;
        if(ledger.complete()&&ledger.cutoff().equals(boundary)){
            // A fresh verified COMPLETE snapshot is a no-op, not another PENDING/COMPLETE rewrite.
            alreadyCompletedAt=before.get("retention_ledger").stream().skip(1)
                .filter(r->r.get(0).equals(quarter(boundary))).map(r->Instant.parse(r.get(3))).findFirst().orElseThrow(SheetsGateway.Conflict::new);
            if(alreadyCompletedAt.isAfter(clock.instant())||!expired.isEmpty())throw new SheetsGateway.Conflict();
        }else{
            after.get("retention_ledger").removeIf(r->!r.isEmpty()&&r.get(0).equals(quarter(boundary)));
            after.get("retention_ledger").add(List.of(quarter(boundary),boundary.toString(),"PENDING","","1"));
        }
        String id=UUID.randomUUID().toString(),digest=digest(before);Preview preview=new Preview(id,digest,boundary,expired.size(),"PURGE QUARTER "+id+" "+digest+" ALL WRITERS STOPPED");
        plan=new Plan(preview,before,after,clock.instant().plus(Duration.ofMinutes(5)),alreadyCompletedAt);return preview;
    }
    public synchronized Result execute(String planId,String confirmation){
        if(!executionEnabled)return new Result(State.DRY_RUN,currentQuarterStart(clock.instant()),null);
        requireOffline();Plan p=plan;
        if(p==null||!p.preview.planId().equals(planId)||!p.preview.confirmationText().equals(confirmation)||!p.expiresAt.isAfter(clock.instant())||!p.preview.cutoff().equals(currentQuarterStart(clock.instant())))throw new IllegalArgumentException("Fresh plan-bound confirmation required");
        if(p.attempted)throw new IllegalStateException("Prior attempt requires read-only review; execution is never replayed");
        p.attempted=true; // Even an unavailable readback must not make the same execution replayable.
        if(!digest(snapshot()).equals(p.preview.beforeDigest()))throw new IllegalStateException("Dataset changed; fresh preview required");
        requireOffline();
        if(!p.expiresAt.isAfter(clock.instant())||!p.preview.cutoff().equals(currentQuarterStart(clock.instant())))throw new IllegalArgumentException("Fresh plan-bound confirmation required");
        if(p.alreadyCompletedAt!=null)return new Result(State.COMPLETE,p.preview.cutoff(),p.alreadyCompletedAt);
        requireOffline();try{gateway.replaceAtomic(objects(p.after));}catch(RuntimeException failure){return reconcile(p);}
        return reconcile(p);
    }
    private Result reconcile(Plan p){
        try{
            requireOffline();var observed=snapshot();String actual=digest(observed);
            if(!actual.equals(digest(p.after))){
                if(actual.equals(digest(p.before)))return new Result(State.FAILED,p.preview.cutoff(),null);
                return new Result(State.UNCERTAIN,p.preview.cutoff(),null);
            }
            if(!withinWindow(p))return new Result(State.UNCERTAIN,p.preview.cutoff(),null);
            // Raw deletion is now positively observed. Only now record an actual verification time.
            Instant completed=clock.instant();var done=copy(p.after);var rows=done.get("retention_ledger");rows.removeIf(r->!r.isEmpty()&&r.get(0).equals(quarter(p.preview.cutoff())));rows.add(List.of(quarter(p.preview.cutoff()),p.preview.cutoff().toString(),"COMPLETE",completed.toString(),"1"));
            requireOffline();if(!withinWindow(p))return new Result(State.UNCERTAIN,p.preview.cutoff(),null);
            try{gateway.replaceAtomic(objects(done));}catch(RuntimeException unknown){/* Read back, never blindly replay. */}
            boolean verified=digest(snapshot()).equals(digest(done));
            requireOffline();if(!withinWindow(p))return new Result(State.UNCERTAIN,p.preview.cutoff(),null);
            if(verified)return new Result(State.COMPLETE,p.preview.cutoff(),completed);
            return new Result(State.PENDING,p.preview.cutoff(),null);
        }catch(RuntimeException unavailable){return new Result(State.UNCERTAIN,p.preview.cutoff(),null);}
    }
    private boolean withinWindow(Plan p){Instant now=clock.instant();return p.expiresAt.isAfter(now)&&p.preview.cutoff().equals(currentQuarterStart(now));}
    private List<String> aggregate(String quarter,List<FinalMatchEnvelope> envelopes){
        Set<String> humans=new HashSet<>(),matches=new HashSet<>();long deals=0;boolean eligible=true;
        for(var e:envelopes){
            // Multiple revisions cannot double count; finished_at assigns the quarter.
            if(!matches.add(e.matchId()))eligible=false;
            try{if(Instant.parse(e.payload().path("match").path("started_at").asText()).isAfter(finishedAt(e)))eligible=false;}catch(RuntimeException bad){eligible=false;}
            deals+=e.payload().path("completed_deals").size();
            for(var person:e.payload().path("participants")){for(String field:List.of("account_id","guest_id")){String id=person.path(field).asText("");if(!id.isBlank())humans.add(field+":"+id);}}
        }
        if(!policy.approved||!eligible||matches.size()<policy.minimumMatches||humans.size()<policy.minimumHumans)return List.of(quarter,"SUPPRESSED","","","1");
        return List.of(quarter,"CANDIDATE_COUNTS",Long.toString(matches.size()/policy.rounding*policy.rounding),Long.toString(deals/policy.rounding*policy.rounding),"1");
    }
    private Map<String,List<List<String>>> snapshot(){var result=new TreeMap<String,List<List<String>>>();for(String tab:TABS)result.put(tab,gateway.read(tab));result.put("dataset",gateway.read("dataset"));
        for(var rows:result.values())if(rows==null||rows.isEmpty())throw new SheetsGateway.Conflict();
        new RetentionLedger(result.get("retention_ledger"));var aggregate=result.get("quarterly_aggregates");if(!aggregate.get(0).equals(AGGREGATE_HEADER))throw new SheetsGateway.Conflict();Set<String> seen=new HashSet<>();for(var row:aggregate.subList(1,aggregate.size())){
            if(row.size()!=5||!seen.add(row.get(0))||!row.get(0).matches("[0-9]{4}-Q[1-4]")||!row.get(4).equals("1"))throw new SheetsGateway.Conflict();
            if(row.get(1).equals("SUPPRESSED")){if(!row.get(2).isEmpty()||!row.get(3).isEmpty())throw new SheetsGateway.Conflict();}
            else if(row.get(1).equals("CANDIDATE_COUNTS")){for(int i=2;i<=3;i++)try{if(!row.get(i).matches("[0-9]+")||Long.parseLong(row.get(i))%10!=0)throw new IllegalArgumentException();}catch(RuntimeException bad){throw new SheetsGateway.Conflict();}}
            else throw new SheetsGateway.Conflict();
        }return copy(result);}
    private static SheetsGateway memory(Map<String,List<List<String>>> snapshot){return new SheetsGateway(){public List<List<String>> read(String tab){return snapshot.get(tab);}public void appendAtomic(Map<String,List<List<Object>>> rows){throw new UnsupportedOperationException();}};}
    private static Map<String,List<List<String>>> copy(Map<String,List<List<String>>> source){var result=new TreeMap<String,List<List<String>>>();source.forEach((k,v)->result.put(k,new ArrayList<>(v.stream().map(List::copyOf).toList())));return result;}
    private static Map<String,List<List<Object>>> objects(Map<String,List<List<String>>> snapshot){var result=new TreeMap<String,List<List<Object>>>();for(String tab:TABS)result.put(tab,snapshot.get(tab).stream().map(r->(List<Object>)new ArrayList<Object>(r)).toList());return result;}
    private static String digest(Map<String,List<List<String>>> snapshot){try{return FinalMatchEnvelope.hash(new ObjectMapper().writeValueAsString(new TreeMap<>(snapshot)));}catch(Exception e){throw new IllegalStateException(e);}}
}
