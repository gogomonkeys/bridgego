package com.bridge.quartercore;
import java.nio.file.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
final class PackagedHandoffCodec {
    static void save(Path file, Book book) throws Exception {
        // Synthetic full native response fixture, not captured Google evidence.
        var nativeJson=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(book.nativeValue());
        nativeJson.put("spreadsheetId",PackagedHandoffMain.B.spreadsheetId());
        nativeJson.put("spreadsheetUrl","https://docs.google.com/spreadsheets/d/"+PackagedHandoffMain.B.spreadsheetId()+"/edit");
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
        var binding=new ManualQuarterMaintenance.Binding(PackagedHandoffMain.B.spreadsheetId(),PackagedHandoffMain.B.datasetId());
        try(var stream=Files.newInputStream(file)) {
            return StrictNativeSheetsResource.decodeFull(stream,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20),binding);
        }
    }
}
