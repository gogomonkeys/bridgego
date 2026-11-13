package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.bridge.persistence.QuarterMaintenanceGate;
import com.bridge.persistence.QuarterMaintenanceCoordinator;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Full selected backend cycle with actual final-match envelope and queue, no network. */
class NativeQuarterLifecycleFlowTest {
  @Test void formalAndCasualFinishSaveCleanAndReturnToSameBackend() throws Exception {
    for(String category:List.of("FORMAL","CASUAL")) {
      var time=new CrossQuarterNativeSettlementTest.Time();var memory=JournaledNativeWriterTest.initial();
      var binding=JournaledNativeWriterTest.BINDING;var transport=new JournaledNativeTransport(memory,binding,time);
      var first=new Service(transport,time,binding,"flow-writer",Limits.defaults());first.start();
      var gate=new QuarterMaintenanceGate(true,time);
      try(var backend=new NativeStoresBackend(first,transport,time,gate)) {
        var writer=new NativeQuarterLifecycle.WriterLifecycle(){
          public Book lifecycleSnapshot(){return backend.lifecycleSnapshot();}
          public boolean lifecycleOpen(){return backend.lifecycleOpen();}
          public void retireClosedWriter(){backend.retireClosedWriter();}
          public Book cleanCloseForQuarter(){return backend.cleanCloseForQuarter();}
          public Book reopenAfterVerifiedQuarter(Book closed){return backend.reopenAfterVerifiedQuarter(closed,(b,newOwner)->{
            var nextTransport=new JournaledNativeTransport(memory,binding,time);
            return new NativeStoresBackend.Session(new Service(nextTransport,time,binding,newOwner,Limits.defaults()),nextTransport);
          });}
        };
        var target=new QuarterNativeControl.Target(binding.spreadsheetId(),binding.datasetId(),binding.purpose());
        var lifecycle=new NativeQuarterLifecycle(target,writer,gate,(t,s)->{},(t,b,id)->new NativeQuarterLifecycle.Session(memory,JournalQuarterAuthority.provision(binding,b,"q-"+id,id,(a,c,d,e,f)->{},time,Duration.ofMinutes(5)),()->{}),time);
        var active=new AtomicBoolean(false);var resumed=new AtomicBoolean(false);
        var coordinator=new QuarterMaintenanceCoordinator(gate,lifecycle,time,()->!active.get(),()->resumed.set(true),()->{});
        coordinator.tick();assertEquals(QuarterMaintenanceCoordinator.Status.OPEN,coordinator.status());
        var source=NativeCategoryIdentitySafetyTest.envelope(memory.b,category);
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)source.payload();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("match")).put("started_at","2026-12-31T15:59:50Z").put("finished_at","2026-12-31T16:00:01Z");
        var envelope=com.bridge.persistence.FinalMatchEnvelope.create(source.matchId(),source.revision(),JSON.convertValue(payload,Map.class));
        gate.registerMatch(envelope.matchId());active.set(true);time.now=CrossQuarterNativeSettlementTest.NEW_NOW;
        int before=memory.writes;coordinator.tick();assertEquals(QuarterMaintenanceCoordinator.Status.DRAINING,coordinator.status());
        assertEquals(before,memory.writes);assertFalse(gate.acceptsNewGames());assertTrue(gate.isOpen());
        backend.submit(envelope);assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,NativeHandleBackendCompositionTest.await(backend,envelope.commitId()));
        active.set(false);
        for(int i=0;i<100 && coordinator.status()!=QuarterMaintenanceCoordinator.Status.OPEN;i++){coordinator.tick();Thread.sleep(5);}
        assertEquals(QuarterMaintenanceCoordinator.Status.OPEN,coordinator.status());assertTrue(resumed.get());assertTrue(backend.enabled());assertTrue(gate.acceptsNewGames());
        assertEquals(State.CLOSED,first.state());
        assertTrue(memory.b.sheet("commits").rows.stream().anyMatch(r->!r.isEmpty()&&r.get(0).equals(envelope.commitId())));
        var identity=memory.b.sheet("auth_identities").rows.get(1);
        assertNotNull(backend.findOrCreateAccount(identity.get(0),identity.get(1),"After","after@example.com"));
        int saved=memory.writes;coordinator.tick();assertEquals(saved,memory.writes);
        time.now=Instant.parse("2027-03-31T16:00:02Z");coordinator.tick();
        assertEquals(QuarterMaintenanceCoordinator.Status.OPEN,coordinator.status());assertTrue(backend.enabled());
        assertFalse(memory.b.sheet("commits").rows.stream().anyMatch(r->!r.isEmpty()&&r.get(0).equals(envelope.commitId())));
      }
    }
  }
}
