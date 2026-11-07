package com.bridge.persistence;

import java.io.Console;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Reconstructed local test operator; no Spring/game runtime or background writers. */
public final class QuarterRetentionOperator {
    public static final String SYNTHETIC_APP_VERSION="synthetic-transport-test-not-gameplay";
    public static final String STOPPED_ACK="ALL GAME IDENTITY IMPORT AND MAINTENANCE WRITERS STOPPED";
    public interface Terminal { void print(String text); String readLine(); }
    public record Settings(boolean executeEnabled,boolean policyVerified,boolean writersStopped) {}
    private QuarterRetentionOperator() {}

    /** Prints bounded summaries only, never credentials, identities, raw rows or exception details. */
    public static int run(Terminal terminal,Supplier<SheetsGateway> gatewayFactory,Settings settings,Clock clock) {
        terminal.print("Synthetic-only Taiwan-quarter test (at most two BOT matches). Accounts must be empty; aggregates are suppressed. Backups, revision history and other copies are outside scope. No game writer or scheduler is started.");
        if(!settings.writersStopped()) {terminal.print("BLOCKED: independently stop all writers to this isolated dataset before acknowledging exclusive maintenance. A flag is not proof of exclusivity.");return 2;}
        terminal.print("Type exactly: "+STOPPED_ACK);
        if(!STOPPED_ACK.equals(terminal.readLine())) {terminal.print("CANCELLED: no storage access.");return 2;}
        try {
            SheetsGateway gateway=gatewayFactory.get();preflight(gateway);
            boolean enabled=settings.executeEnabled()&&settings.policyVerified();
            QuarterlyRetention engine=new QuarterlyRetention(gateway,clock,()->settings.writersStopped(),enabled,QuarterlyRetention.AggregatePolicy.suppressAll());
            var preview=engine.preview();
            terminal.print("Preview: cutoff="+preview.cutoff()+"; expired commits="+preview.expiredCommits()+"; before digest="+preview.beforeDigest()+". Only active A:E schema ranges are in scope; accounts/identities remain.");
            if(!enabled) {terminal.print("DRY_RUN: no writes. Execution and verified-policy flags are both required. No cleanup completion asserted.");return 0;}
            terminal.print("Irreversible active-schema rewrite. Verify the isolated dataset and independent shutdown evidence. Type this exact fresh five-minute confirmation: "+preview.confirmationText());
            String confirmation=terminal.readLine();
            if(!preview.confirmationText().equals(confirmation)) {terminal.print("CANCELLED: no writes.");return 2;}
            var result=engine.execute(preview.planId(),confirmation);
            terminal.print("Result="+result.state()+"; cutoff="+result.cutoff()+"; verified completion="+(result.completedAt()==null?"NOT ESTABLISHED":result.completedAt())+". Keep writers stopped unless COMPLETE is independently verified.");
            return result.state()==QuarterlyRetention.State.COMPLETE?0:3;
        }catch(RuntimeException failure) {terminal.print("BLOCKED_OR_UNCERTAIN: storage, schema, policy or readback verification failed. Do not blindly retry or restart writers. No completion asserted; investigate and obtain a fresh preview.");return 3;}
    }
    static void preflight(SheetsGateway gateway) {
        Map<String,List<String>> headers=Map.of(
            "envelopes",List.of("commit_id","payload_hash","chunk_index","chunk_count","canonical_json_chunk"),
            "commits",List.of("commit_id","payload_hash","chunk_count","match_id","revision"),
            "accounts",List.of("account_id","schema_version"),
            "auth_identities",List.of("issuer","subject","account_id","schema_version"),
            "erasure_ledger",ErasureLedger.HEADER,"retention_ledger",RetentionLedger.HEADER,
            "quarterly_aggregates",QuarterlyRetention.AGGREGATE_HEADER);
        Map<String,List<List<String>>> snapshot=new TreeMap<>();
        for(var entry:headers.entrySet()) {
            var rows=gateway.read(entry.getKey());if(rows==null||rows.isEmpty()||!rows.get(0).equals(entry.getValue()))throw new SheetsGateway.Conflict();
            for(var row:rows)if(row.size()!=entry.getValue().size())throw new SheetsGateway.Conflict();snapshot.put(entry.getKey(),rows);
        }
        var marker=gateway.read("dataset");
        if(marker==null||marker.size()!=2||!marker.get(0).equals(List.of("purpose","dataset_id"))||marker.get(1).size()!=2||!marker.get(1).get(0).equals("bridgego-restricted-test"))throw new SheetsGateway.Conflict();
        String dataset=marker.get(1).get(1);if(!UUID.fromString(dataset).toString().equals(dataset))throw new SheetsGateway.Conflict();
        if(snapshot.get("accounts").size()!=1||snapshot.get("auth_identities").size()!=1||snapshot.get("erasure_ledger").size()!=2)throw new SheetsGateway.Conflict();
        ErasureLedger erasures=new ErasureLedger(snapshot.get("erasure_ledger"));Set<String> accounts=new HashSet<>();Map<String,String> identities=new HashMap<>();
        for(var row:snapshot.get("accounts").subList(1,snapshot.get("accounts").size())) {
            if(!row.get(1).equals("1")||!UUID.fromString(row.get(0)).toString().equals(row.get(0))||erasures.contains("ACCOUNT",row.get(0)))throw new SheetsGateway.Conflict();accounts.add(row.get(0));
        }
        for(var row:snapshot.get("auth_identities").subList(1,snapshot.get("auth_identities").size())) {
            String identity=ErasureLedger.identityKey(row.get(0),row.get(1));
            if(!row.get(0).equals("https://accounts.google.com")||row.get(1).isBlank()||!row.get(3).equals("1")||!accounts.contains(row.get(2))||erasures.contains("IDENTITY",identity))throw new SheetsGateway.Conflict();
            String old=identities.putIfAbsent(identity,row.get(2));if(old!=null&&!old.equals(row.get(2)))throw new SheetsGateway.Conflict();
        }
        SheetsGateway memory=new SheetsGateway(){public List<List<String>> read(String tab){return snapshot.get(tab);}public void appendAtomic(Map<String,List<List<Object>>> rows){throw new UnsupportedOperationException();}};
        var raw=SheetsStatsStore.readVerified(memory);if(raw.size()>2)throw new SheetsGateway.Conflict();
        for(var envelope:raw.values()) {
            if(!envelope.payload().path("match").path("app_version").asText().equals(SYNTHETIC_APP_VERSION))throw new SheetsGateway.Conflict();
            for(var person:envelope.payload().path("participants"))if(!person.path("player_type").asText().equals("BOT")||!person.path("control_type").asText().equals("BOT")||!person.path("account_id").isNull()||!person.path("guest_id").isNull())throw new SheetsGateway.Conflict();
            Instant finished=QuarterlyRetention.finishedAt(envelope),started;
            try{started=Instant.parse(envelope.payload().path("match").path("started_at").asText());}catch(RuntimeException missing){throw new SheetsGateway.Conflict();}
            // Approved rule: finished_at assigns the quarter, even if the game began earlier.
            if(started.isAfter(finished))throw new SheetsGateway.Conflict();
        }
    }
    public static void main(String[] args) {
        if(args.length!=0){System.err.println("No command-line arguments accepted. Never put credentials in arguments.");System.exit(2);return;}
        Console console=System.console();if(console==null){System.err.println("Interactive operator console required; redirected input is refused.");System.exit(2);return;}
        Map<String,String> env=System.getenv();
        Settings settings=new Settings(Boolean.parseBoolean(env.getOrDefault("BRIDGE_QUARTER_EXECUTION_ENABLED","false")),Boolean.parseBoolean(env.getOrDefault("BRIDGE_QUARTER_POLICY_VERIFIED","false")),Boolean.parseBoolean(env.getOrDefault("BRIDGE_QUARTER_WRITER_STOPPED_ACK","false")));
        int result=run(new Terminal(){public void print(String text){console.printf("%s%n",text);}public String readLine(){return console.readLine();}},()-> {
            String sheet=env.get("BRIDGE_TEST_SPREADSHEET_ID"),production=env.get("BRIDGE_SHEETS_SPREADSHEET_ID");
            if(sheet==null||sheet.isBlank()||sheet.equals(production))throw new IllegalArgumentException("Separate test dataset required");
            return new TestDatasetGateway(new GoogleSheetsGateway(sheet,env.get("BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON")),env.get("BRIDGE_TEST_DATASET_ID"));
        },settings,Clock.systemUTC());
        if(result!=0)System.exit(result);
    }
}
