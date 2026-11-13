package com.bridge.quartercore;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Test subprocess only. Synthetic full native serialization, NOT captured Google evidence or a trust source.
 * No credentials, HTTP client or production factory is instantiated. */
public final class JournalRestartProbe {
    // Object-key ordering is JVM-dependent; preserve all metadata/values while canonicalizing keys.
    static String fullDigest(Book book) {
        return FinalMatchEnvelope.hash(NativeCommitJournal.canonical(book.nativeValue()));
    }
    static void save(Path file, Book book) throws Exception {
        // Synthetic full native response fixture, not captured Google evidence.
        var nativeJson=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(book.nativeValue());
        nativeJson.put("spreadsheetId",RecoveredFixture.ID);
        nativeJson.put("spreadsheetUrl","https://docs.google.com/spreadsheets/d/"+RecoveredFixture.ID+"/edit");
        nativeJson.put("commentsViewMode","COMMENTS_VIEW_MODE_INCLUDED");
        nativeJson.putObject("properties").put("title","Synthetic restart fixture").put("locale","en_US").put("timeZone","Asia/Taipei");
        int index=0;
        for(var sheet:nativeJson.path("sheets")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode)sheet.path("properties")).put("index",index++).put("sheetType","GRID");
            var block=(com.fasterxml.jackson.databind.node.ObjectNode)sheet.path("data").get(0);
            var rows=block.putArray("rowMetadata");for(int i=0;i<1000;i++)rows.addObject();
            var columns=block.putArray("columnMetadata");for(int i=0;i<26;i++)columns.addObject();
            for(var row:block.path("rowData"))for(var cell:row.path("values")) {
                if(cell.has("userEnteredValue"))((com.fasterxml.jackson.databind.node.ObjectNode)cell).set("effectiveValue",cell.get("userEnteredValue").deepCopy());
            }
        }
        Files.writeString(file,JSON.writeValueAsString(nativeJson));
    }
    static Book load(Path file) throws Exception {
        var binding=new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,RecoveredFixture.DATASET);
        try(var stream=Files.newInputStream(file)) {
            return StrictNativeSheetsResource.decodeFull(stream,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20),binding);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=7)throw new IllegalArgumentException();
        Book book=load(Path.of(args[0])); Clock clock=Clock.fixed(Instant.parse(args[1]),ZoneOffset.UTC);
        var binding=new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,RecoveredFixture.DATASET);
        var evidence=NativeCommitJournal.verify(binding,book,clock);
        var root=root(new ArrayList<>(book.metadata.values()));
        if(!root.state().equals(args[2]) || evidence.sequence()!=Long.parseLong(args[3]))throw new AssertionError("snapshot mismatch");
        if(!args[4].equals("-")) {
            boolean rejected=false;
            try{simulate(book,Files.readString(Path.of(args[4])));}catch(RuntimeException expected){rejected=true;}
            if(!rejected)throw new AssertionError("replay accepted");
        }
        // A fresh process lacks independent authority proving no delayed old request can arrive.
        // Even a valid current journal must not turn this absence into permission to resume.
        var target=new QuarterNativeControl.Target(binding.spreadsheetId(),binding.datasetId(),binding.purpose());
        var control=new QuarterNativeControl(target,()->book.copy(),QuarterNativeControl.OwnershipVerifier.unavailable(),
                NativeCommitJournal.verifier(target,()->book.copy(),clock),null,clock);
        if(control.inspect(QuarterlyRetention.currentQuarterStart(clock.instant())).phase()
                !=com.bridge.persistence.QuarterMaintenanceCoordinator.Phase.UNKNOWN)throw new AssertionError("ownership bypass");
        if(!args[5].equals(fullDigest(book)))throw new AssertionError("serialization mismatch");
        if(!args[6].equals("-")&&!JournalQuarterProtocol.inspect(binding,book,clock).state().name().equals(args[6]))
            throw new AssertionError("quarter recovery mismatch");
        System.out.println("CHILD_OK pid="+ProcessHandle.current().pid()+" journal="+evidence.sequence()+" state="+root.state()+" ownership=UNKNOWN");
    }
}
