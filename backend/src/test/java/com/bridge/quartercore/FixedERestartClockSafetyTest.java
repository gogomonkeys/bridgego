package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
/** Offline only: pinned E resource, no live Sheet, credentials or Google exchange. */
class FixedERestartClockSafetyTest {
 static final class Time extends Clock {Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 static final Clock BUSINESS=Clock.fixed(Instant.parse("2026-12-31T16:00:01Z"),ZoneOffset.UTC);
 static GoogleSheetsBinding binding(){return SyntheticECompiledRelease.load().binding();}
 static NativeRestartContract contract(GoogleSheetsBinding b){return NativeRestartContract.fixedReviewedBuild(new CleanClosedRestartAssembly.Policy(b,NativeRestartContract.PROTOCOL_IDENTITY,"offline-E-restart",new QuarterRetentionPolicy.Configuration(b,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"offline-E-retention")),"https://fixture.invalid","fixture-audience");}
 static JournaledNativeWriterTest.Memory closed()throws Exception{
  var b=binding();Book book;
  try(var in=FixedERestartClockSafetyTest.class.getResourceAsStream("/synthetic-e/before-native.json")){book=StrictNativeSheetsResource.decodeFull(in,System.nanoTime()+20_000_000_000L,b.manualBinding());}
  var memory=new JournaledNativeWriterTest.Memory(book);Clock old=Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"),ZoneOffset.UTC);
  var tx=new JournaledNativeTransport(memory,b.manualBinding(),old);
  try(var service=new NarrowQuarterPoc.Service(tx,old,b.manualBinding(),"offline-E-close",NarrowQuarterPoc.Limits.defaults())){service.start();service.cleanClose();assertEquals(NarrowQuarterPoc.State.CLOSED,service.state());}
  memory.writes=0;return memory;
 }
 static PlannedMaintenanceStartupFactory.Channels channels(GoogleSheetsBinding b,JournaledNativeWriterTest.Memory m,AtomicReference<JournalSessionAuthority> ordinary,java.util.function.Consumer<JournalQuarterAuthority> onQuarter){return new PlannedMaintenanceStartupFactory.Channels(){
  NarrowSheetsTransport endpoint(GoogleDispatchAuthority a){return new NarrowSheetsTransport(){public Book snapshot(){return m.snapshot();}public List<Metadata> control(){return m.control();}public List<List<String>> related(String tab){return m.related(tab);}public boolean unsupportedStructure(){return false;}public void batch(String wire){var d=a.beforeDispatch(b.path()+":batchUpdate",wire);try{m.batch(wire);d.acknowledged();}catch(RuntimeException e){d.unknown();throw e;}}};}
  public PlannedMaintenanceStartupFactory.Channel ordinary(JournalSessionAuthority a){ordinary.set(a);var slot=new JournalAuthoritySlot(a);var h=new JournalGoogleFactory.Handle(endpoint(slot),()->{},slot,b);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
  public PlannedMaintenanceStartupFactory.Channel quarter(JournalQuarterAuthority a){onQuarter.accept(a);var cap=new QuarterAuthorityChannel(b,a);var h=new JournalQuarterGoogleFactory.Handle(endpoint(cap),()->{},cap,b);return new PlannedMaintenanceStartupFactory.Channel(h.transport(),h::close);}
 };}
 static NativeRestartRuntimeAssembly.IO io(JournaledNativeWriterTest.Memory m,PlannedMaintenanceStartupFactory.Channels channels){return new NativeRestartRuntimeAssembly.IO(){public Book readNative(){return m.snapshot();}public PlannedMaintenanceStartupFactory.Channels channels(){return channels;}};}
 @Test void futureBusinessQuarterUsesCurrentSecurityAndOrdinaryExpiresOnSecurityOnly()throws Exception{
  var security=new Time();Instant actual=security.instant();var m=closed();var ordinary=new AtomicReference<JournalSessionAuthority>();var ch=channels(binding(),m,ordinary,q->assertSame(security,q.securityClock()));
  try(var runtime=NativeRestartRuntimeAssembly.isolatedEClockTest(contract(binding()),io(m,ch),security,BUSINESS)){
   runtime.resume();assertEquals(NativeRestartRuntimeAssembly.Status.ATTACHED,runtime.status());assertEquals(6,m.writes);assertEquals(actual,security.instant());
   var grant=ordinary.get();grant.requireSession(binding());security.now=actual.plusSeconds(3601);
   assertThrows(RuntimeException.class,()->grant.requireSession(binding()));assertEquals(JournalSessionAuthority.State.EXPIRED_CLEAN,grant.state());
  }
 }
 @Test void quarterSecurityExpiryOrRollbackBeforeDispatchCannotWriteOrRetry()throws Exception{
  for(boolean rollback:List.of(false,true)){
   var security=new Time();var m=closed();var ordinary=new AtomicReference<JournalSessionAuthority>();var quarter=new AtomicReference<JournalQuarterAuthority>();
   var ch=channels(binding(),m,ordinary,q->{quarter.set(q);security.now=security.now.plusSeconds(rollback?-1:301);});
   try(var runtime=NativeRestartRuntimeAssembly.isolatedEClockTest(contract(binding()),io(m,ch),security,BUSINESS)){
    assertThrows(RuntimeException.class,runtime::resume);assertEquals(0,m.writes);assertFalse(runtime.staged().enabled());assertNull(ordinary.get());assertThrows(RuntimeException.class,runtime::resume);assertEquals(JournalQuarterAuthority.State.UNKNOWN,quarter.get().state());
   }
  }
 }
 @Test void wrongTargetOrArbitraryBusinessTimeRejectsBeforeReadAndProductionConstructorRemainsSingleClock()throws Exception{
  var security=new Time();var reads=new AtomicInteger();var noIO=new NativeRestartRuntimeAssembly.IO(){public Book readNative(){reads.incrementAndGet();throw new AssertionError();}public PlannedMaintenanceStartupFactory.Channels channels(){throw new AssertionError();}};
  assertThrows(RuntimeException.class,()->NativeRestartRuntimeAssembly.isolatedEClockTest(NativeRestartRuntimeAssemblyTest.contract(),noIO,security,BUSINESS));
  assertThrows(RuntimeException.class,()->NativeRestartRuntimeAssembly.isolatedEClockTest(contract(binding()),noIO,security,Clock.fixed(BUSINESS.instant().plusSeconds(1),ZoneOffset.UTC)));
  assertEquals(0,reads.get());
  try(var normal=new NativeRestartRuntimeAssembly(contract(binding()),noIO,security)){assertSame(security,normal.clock());assertEquals(NativeRestartRuntimeAssembly.Status.WAITING,normal.status());assertEquals(0,reads.get());}
 }
}
