package com.bridge.quartercore;
import java.io.*;import java.nio.file.*;import java.time.*;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;import static org.junit.jupiter.api.Assertions.*;
/** Synthetic remote HTTP only: preserves actual bounded transport/parser/exact authority/ACK. */
final class CutoverBoundedHttpFixture {
 final GoogleSheetsBinding binding;final Path directory;private Book actual;int gets,writes;boolean unknownNextWrite;
 CutoverBoundedHttpFixture(GoogleSheetsBinding binding,Book before,Path dir){this.binding=binding;actual=before.copy();directory=dir;}
 synchronized Book snapshot(){return actual.copy();}
 synchronized byte[] resource()throws Exception{Path p=Files.createTempFile(directory,"native-",".json");try{JournalRestartProbe.save(p,actual);return Files.readAllBytes(p);}finally{Files.deleteIfExists(p);}}
 BatchedSheetsTransport transport(GoogleDispatchAuthority authority){return new BatchedSheetsTransport(443,new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1)),new BatchedSheetsTransport.Exchange(){
  GoogleDispatchAuthority.Dispatch receipt;
  public InputStream execute(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f)throws Exception{
   assertTrue(r.deadline()>System.nanoTime());
   if(!r.write()){if(authority!=null)authority.requireReadSession(binding);assertEquals(binding.fullTarget(),r.target());assertEquals(StrictNativeSheetsResource.MAX_RESPONSE_BYTES,r.responseLimit());assertEquals(0,r.body().length);synchronized(CutoverBoundedHttpFixture.this){gets++;return new ByteArrayInputStream(resource());}}
   assertNotNull(authority,"read-only channel");String wire=new String(r.body(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(binding.path()+":batchUpdate",r.target());assertEquals(BatchedSheetsTransport.MAX_BATCH_RESPONSE_BYTES,r.responseLimit());receipt=authority.beforeDispatch(r.target(),wire);
   synchronized(CutoverBoundedHttpFixture.this){actual=simulate(actual,wire);writes++;if(unknownNextWrite){unknownNextWrite=false;throw new IOException("synthetic unknown applied response");}}
   int count=JSON.readTree(wire).path("requests").size();return new ByteArrayInputStream(json(Map.of("spreadsheetId",binding.spreadsheetId(),"replies",Collections.nCopies(count,Map.of()))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  public void completed(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f,boolean ok){if(r.write()&&receipt!=null){if(ok)receipt.acknowledged();else receipt.unknown();}}
  public void abandoned(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f){if(receipt!=null)receipt.unknown();}
 },binding.manualBinding());}
 Book readLegacy(){try(var tx=transport(null);var scope=tx.operation("fixture-readonly-legacy",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){return tx.readLegacyFull();}}
 Book readNative(){try(var tx=transport(null);var scope=tx.operation("fixture-readonly-native",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){return tx.snapshot();}}
 Book bootstrap(InitialCutoverAuthority a){return new InitialCutoverGoogleFactory.Handle(transport(a),()->{},binding,a).execute();}
 Book migrate(HistoricalMigrationAuthority a){return new HistoricalMigrationGoogleFactory.Handle(transport(a),()->{},binding,a).execute();}
 PlannedMaintenanceStartupFactory.Channels channels(){return new PlannedMaintenanceStartupFactory.Channels(){
  public PlannedMaintenanceStartupFactory.Channel ordinary(JournalSessionAuthority a){var slot=new JournalAuthoritySlot(a);var h=new JournalGoogleFactory.Handle(transport(slot),()->{},slot,binding);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
  public PlannedMaintenanceStartupFactory.Channel quarter(JournalQuarterAuthority a){var channel=new QuarterAuthorityChannel(binding,a);var h=new JournalQuarterGoogleFactory.Handle(transport(channel),()->{},channel,binding);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
 };}
}
