package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.time.*;import java.io.*;import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;
class InitialCutoverGoogleFactoryTest {
 @TempDir Path dir;
 byte[] full(Book book)throws Exception{Path file=dir.resolve("full.json");JournalRestartProbe.save(file,book);return Files.readAllBytes(file);}
 @Test void disabledOrWrongTargetRejectedBeforeCredentials()throws Exception{
  var b=InitialCutoverAuthorityTest.B;AtomicInteger reads=new AtomicInteger();QuarterGoogleFactory.CredentialSource source=()->{reads.incrementAndGet();throw new IOException();};
  var a=InitialCutoverAuthorityTest.authority(InitialCutoverAuthorityTest.legacy());
  assertThrows(RuntimeException.class,()->InitialCutoverGoogleFactory.open(new JournalGoogleFactory.Configuration(false,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),source),a));
  assertThrows(RuntimeException.class,()->InitialCutoverGoogleFactory.open(new JournalGoogleFactory.Configuration(true,"wrong-spreadsheet",b.datasetId(),b.purpose(),b.serviceAccountIssuer(),source),a));assertEquals(0,reads.get());
 }
 @Test void originalPlanExpiryIsCheckedBeforeConstructionAndCredentialRead()throws Exception{
  var b=InitialCutoverAuthorityTest.B;Book legacy=InitialCutoverAuthorityTest.legacy();
  var time=new CrossQuarterNativeSettlementTest.Time();time.now=Clock.systemUTC().instant();
  var p=CutoverAssessment.plan(b.manualBinding(),legacy,CutoverAssessment.token(b.manualBinding(),legacy),new CutoverAssessment.FreezeEvidence("approval","source",time.instant().plusSeconds(2)),(t,token,e)->{},time);
  var a=new InitialCutoverAuthority(b,p,time);time.now=time.now.plusSeconds(3);
  AtomicInteger reads=new AtomicInteger();
  assertThrows(RuntimeException.class,()->new InitialCutoverAuthority(b,p,time));
  assertThrows(RuntimeException.class,()->InitialCutoverGoogleFactory.open(new JournalGoogleFactory.Configuration(true,b.spreadsheetId(),b.datasetId(),b.purpose(),b.serviceAccountIssuer(),()->{reads.incrementAndGet();throw new IOException();}),a));
  assertEquals(0,reads.get());
 }
 @Test void boundedWorkerExchangeReadsLegacyThenExactMetadataThenNative()throws Exception{
  var b=InitialCutoverAuthorityTest.B;Book initial=InitialCutoverAuthorityTest.legacy();Book[] remote={initial};var a=InitialCutoverAuthorityTest.authority(initial);AtomicInteger posts=new AtomicInteger();
  var exchange=new BatchedSheetsTransport.Exchange(){InitialCutoverAuthority.Dispatch dispatch;
   public InputStream execute(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f)throws Exception{
    if(!r.write()){assertEquals(b.fullTarget(),r.target());return new ByteArrayInputStream(full(remote[0]));}
    String wire=new String(r.body(),java.nio.charset.StandardCharsets.UTF_8);dispatch=a.beforeDispatch(r.target(),wire);posts.incrementAndGet();remote[0]=simulate(remote[0],wire);
    int count=JSON.readTree(wire).path("requests").size();return new ByteArrayInputStream(json(Map.of("spreadsheetId",b.spreadsheetId(),"replies",Collections.nCopies(count,Map.of()))).getBytes());
   }
   public void completed(BatchedSheetsTransport.Request r,BatchedSheetsTransport.Flight f,boolean ok){if(r.write()){if(ok)dispatch.acknowledged();else dispatch.unknown();}}
  };
  var tx=new BatchedSheetsTransport(443,new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1)),exchange,b.manualBinding());AtomicBoolean closed=new AtomicBoolean();
  var h=new InitialCutoverGoogleFactory.Handle(tx,()->closed.set(true),b,a);assertEquals(initial.tables(),h.execute().tables());assertEquals(1,posts.get());assertTrue(closed.get());assertNotNull(a.verifiedResult());assertThrows(RuntimeException.class,h::execute);
 }
 @Test void explicitLegacyReadDoesNotRelaxNativeSnapshotAndReadOnlyErrorsClose()throws Exception{
  var b=InitialCutoverAuthorityTest.B;Book initial=InitialCutoverAuthorityTest.legacy();byte[] bytes=full(initial);
  var tx=new BatchedSheetsTransport(443,new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1)),(r,f)->{assertFalse(r.write());assertEquals(b.fullTarget(),r.target());return new ByteArrayInputStream(bytes);},b.manualBinding());
  try(var scope=tx.operation("test",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){assertEquals(initial.tables(),tx.readLegacyFull().tables());assertThrows(RuntimeException.class,tx::snapshot);}tx.close();
  AtomicBoolean closed=new AtomicBoolean();var bad=new BatchedSheetsTransport(443,new SharedSheetsBudget(System::nanoTime,54,54,Duration.ofMinutes(1)),(r,f)->new ByteArrayInputStream("{}".getBytes()),b.manualBinding());
  var view=new QuarterGoogleFactory.LegacyReadOnly(bad,()->{closed.set(true);bad.close();},b);assertThrows(RuntimeException.class,view::readVerified);assertTrue(closed.get());assertThrows(RuntimeException.class,view::readVerified);
 }
}
