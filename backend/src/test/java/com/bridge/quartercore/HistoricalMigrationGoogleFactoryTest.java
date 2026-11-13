package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.time.*;import java.io.*;import java.util.*;import java.util.concurrent.atomic.*;import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class HistoricalMigrationGoogleFactoryTest {
 @TempDir Path dir;byte[] full(Book b)throws Exception{var p=dir.resolve("full.json");JournalRestartProbe.save(p,b);return Files.readAllBytes(p);}
 @Test void boundedFullBeforeAndFreshFullAfterAndUnknownStop()throws Exception{for(int mode=0;mode<3;mode++){
  final int variant=mode;var f=new HistoricalMigrationAuthorityTest.Fixture();var initial=f.book.copy();var b=HistoricalMigrationAuthorityTest.B;var a=f.authority((t,h,e)->{});var posts=new AtomicInteger();var reads=new AtomicInteger();
  var exchange=new BatchedSheetsTransport.Exchange(){GoogleDispatchAuthority.Dispatch ticket;
   public InputStream execute(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight flight)throws Exception{if(!r.write()){reads.incrementAndGet();assertEquals(b.fullTarget(),r.target());return new ByteArrayInputStream(full(f.book));}String wire=new String(r.body(),java.nio.charset.StandardCharsets.UTF_8);ticket=a.beforeDispatch(r.target(),wire);posts.incrementAndGet();if(variant!=1)f.book=simulate(f.book,wire);if(variant==2)throw new IOException("synthetic lost response");int count=JSON.readTree(wire).path("requests").size();return new ByteArrayInputStream(json(Map.of("spreadsheetId",b.spreadsheetId(),"replies",Collections.nCopies(count,Map.of()))).getBytes());}
   public void completed(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight flight,boolean ok){if(r.write()&&ticket!=null){if(ok)ticket.acknowledged();else ticket.unknown();}}
  };
  var tx=new BatchedSheetsTransport(443,new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1)),exchange,b.manualBinding());var h=new HistoricalMigrationGoogleFactory.Handle(tx,()->{},b,a);
  if(mode==0){var after=h.execute();assertEquals(2,reads.get());assertNotEquals(initial.tables().get("commits"),after.tables().get("commits"));assertEquals(JournalSessionAuthority.fullToken(after),a.verifiedResult().afterToken());}else{assertThrows(RuntimeException.class,h::execute);assertEquals(HistoricalMigrationAuthority.State.UNKNOWN,a.state());assertThrows(RuntimeException.class,a::verifiedResult);}assertEquals(1,posts.get());assertThrows(RuntimeException.class,h::execute);
 }}
}
