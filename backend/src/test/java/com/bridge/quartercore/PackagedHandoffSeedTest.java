package com.bridge.quartercore;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
/** Prepares local synthetic input only; this test class is excluded from the fixture JAR. */
class PackagedHandoffSeedTest {
 @Test void exportSyntheticDurableClose()throws Exception {
  Path dir=Path.of(System.getProperty("packaged.handoff.fixtureDir","/tmp/bridgego-packaged-handoff"));Files.createDirectories(dir);
  var b=DurableCleanHandoffAssessmentTest.closed();PackagedHandoffCodec.save(dir.resolve("seed.json"),b);
  Files.writeString(dir.resolve("time.txt"),JournaledNativeWriterTest.CLOCK.instant().toString());
  var time=java.time.Clock.fixed(QuarterlyRetention.currentQuarterStart(JournaledNativeWriterTest.CLOCK.instant()).atZone(java.time.ZoneId.of("Asia/Taipei")).plusMonths(3).toInstant().plusSeconds(1),java.time.ZoneOffset.UTC);
  var phases=new QuarterPhasePlans(JournaledNativeWriterTest.BINDING,"packaged-maintenance",time);
  var claimed=NativeSheetsModel.simulate(b,phases.next(b,0).wire());
  var pending=NativeSheetsModel.simulate(claimed,phases.next(claimed,1).wire());
  PackagedHandoffCodec.save(dir.resolve("pending.json"),pending);
  var expected=pending;
  for(int phase=2;phase<5;phase++)expected=NativeSheetsModel.simulate(expected,phases.next(expected,phase).wire());
  Files.writeString(dir.resolve("expected-tables.json"),NativeSheetsModel.JSON.writeValueAsString(java.util.Map.of("same",b.tables(),"due",expected.tables())));
 }
}
