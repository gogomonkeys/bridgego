package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Full application backend -> journal wrapper -> real Handle -> authority-gated model sink.
 * No Google credentials, requests, or actual service-account capability are used. */
class NativeHandleBackendCompositionTest {
  static final java.time.Clock CLOCK=JournalSessionAuthorityTest.CLOCK;
  static final GoogleSheetsBinding BINDING=JournalSessionAuthorityTest.BINDING;
  static class Sink implements NarrowSheetsTransport {
    Book actual;final JournalSessionAuthority authority;final AtomicInteger writes=new AtomicInteger(),scopes=new AtomicInteger();
    final ThreadLocal<Boolean> scoped=ThreadLocal.withInitial(()->false);volatile boolean timeoutAfter;
    Sink(Book book,JournalSessionAuthority authority){actual=book;this.authority=authority;}
    public Scope operation(String label,Root predecessor,Set<String> tabs,Duration duration){
      assertEquals(ManualQuarterMaintenance.HEADERS.keySet(),tabs);assertTrue(duration.compareTo(Duration.ofSeconds(60))<=0);assertFalse(scoped.get());
      scoped.set(true);scopes.incrementAndGet();return ()->scoped.remove();
    }
    void requireScope(){assertTrue(scoped.get(),"Native operation scope required");}
    public synchronized Book snapshot(){requireScope();return actual.copy();}
    public synchronized List<Metadata> control(){requireScope();return new ArrayList<>(actual.metadata.values());}
    public synchronized List<List<String>> related(String tab){requireScope();return copyRows(actual.sheet(tab).rows);}
    public boolean unsupportedStructure(){requireScope();return false;}
    public synchronized void batch(String wire){requireScope();var dispatch=authority.beforeDispatch(BINDING.path()+":batchUpdate",wire);writes.incrementAndGet();actual=simulate(actual,wire);
      if(timeoutAfter){timeoutAfter=false;dispatch.unknown();throw new Unknown();}dispatch.acknowledged();}
  }
  static com.bridge.persistence.StatsStore.SaveStatus await(NativeStoresBackend backend,String id)throws Exception {
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while(backend.status(id)==com.bridge.persistence.StatsStore.SaveStatus.PENDING&&System.nanoTime()<end)Thread.sleep(5);
    return backend.status(id);
  }
  @Test void applicationProfilesAndBothMatchCategoriesUseOneAuthorizedFinalWireEach()throws Exception {
    Book baseline=JournalSessionAuthorityTest.clean();var authority=JournalSessionAuthorityTest.authority(baseline,CLOCK,Duration.ofMinutes(5));var sink=new Sink(baseline,authority);
    try(var handle=new JournalGoogleFactory.Handle(sink,()->{},authority,BINDING)){
      var transport=new JournaledNativeTransport(handle.transport(),BINDING.manualBinding(),CLOCK);
      try(var service=new Service(transport,CLOCK,BINDING.manualBinding(),JournalSessionAuthorityTest.OWNER,Limits.defaults())){
        try(var scope=transport.operation("claim",null,ManualQuarterMaintenance.HEADERS.keySet(),Duration.ofSeconds(60))){service.start();}
        assertEquals(State.OPEN,service.state());assertEquals(1,sink.writes.get());
        var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,CLOCK);gate.openVerified(QuarterlyRetention.currentQuarterStart(CLOCK.instant()));
        try(var backend=new NativeStoresBackend(service,transport,CLOCK,gate)){
          var mapping=sink.actual.sheet("auth_identities").rows.get(1);String account=mapping.get(2);String lifetime=sink.actual.sheet("accounts").rows.get(1).get(4);
          assertEquals(account,backend.findOrCreateAccount(mapping.get(0),mapping.get(1),"Handle profile","profile@example.com"));assertEquals(2,sink.writes.get());
          assertEquals(lifetime,sink.actual.sheet("accounts").rows.get(1).get(4));
          for(String category:List.of("FORMAL","CASUAL")){
            var match=NativeCategoryIdentitySafetyTest.envelope(sink.actual,category);backend.submit(match);
            assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,await(backend,match.commitId()));
          }
          assertEquals(4,sink.writes.get());var stats=backend.exportStatistics(account);assertNotNull(stats.lifetime());assertEquals(3,stats.current().size());
          assertEquals(1,stats.current().stream().filter(e->"FORMAL".equals(e.statisticsCategory())).count());
          assertEquals(2,stats.current().stream().filter(e->"CASUAL".equals(e.statisticsCategory())).count());
          assertEquals(JournalSessionAuthority.State.READY,authority.state());assertTrue(sink.scopes.get()>=5);
        }
      }
    }
    assertEquals(JournalSessionAuthority.State.CLOSED,authority.state());
  }
  @Test void backendUnknownPoisonsAuthorityAndNeverUsesLegacyOrResends()throws Exception {
    Book baseline=JournalSessionAuthorityTest.clean();var authority=JournalSessionAuthorityTest.authority(baseline,CLOCK,Duration.ofMinutes(5));var sink=new Sink(baseline,authority);
    try(var handle=new JournalGoogleFactory.Handle(sink,()->{},authority,BINDING)){
      var transport=new JournaledNativeTransport(handle.transport(),BINDING.manualBinding(),CLOCK);
      try(var service=new Service(transport,CLOCK,BINDING.manualBinding(),JournalSessionAuthorityTest.OWNER,Limits.defaults())){
        try(var scope=transport.operation("claim",null,ManualQuarterMaintenance.HEADERS.keySet(),Duration.ofSeconds(60))){service.start();}
        var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,CLOCK);gate.openVerified(QuarterlyRetention.currentQuarterStart(CLOCK.instant()));
        try(var backend=new NativeStoresBackend(service,transport,CLOCK,gate)){
          var match=NativeCategoryIdentitySafetyTest.envelope(sink.actual,"FORMAL");sink.timeoutAfter=true;backend.submit(match);
          assertEquals(com.bridge.persistence.StatsStore.SaveStatus.UNKNOWN,await(backend,match.commitId()));assertEquals(2,sink.writes.get());
          assertEquals(com.bridge.persistence.StatsStore.SaveStatus.UNKNOWN,backend.submit(match));assertEquals(2,sink.writes.get());
          assertEquals(JournalSessionAuthority.State.UNKNOWN,authority.state());
          long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);while(gate.isOpen()&&System.nanoTime()<end)Thread.sleep(5);assertFalse(gate.isOpen());
        }
      }
    }
  }
}
