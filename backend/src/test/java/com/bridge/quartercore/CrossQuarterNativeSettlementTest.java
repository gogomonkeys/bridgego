package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class CrossQuarterNativeSettlementTest {
  static final Instant OLD_NOW=Instant.parse("2026-12-31T15:59:58Z"),NEW_NOW=Instant.parse("2026-12-31T16:00:02Z");
  static class Time extends Clock {volatile Instant now=OLD_NOW;public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}}
  static com.bridge.persistence.FinalMatchEnvelope envelope(Book book,boolean newFinish){
    var e=NativeCategoryIdentitySafetyTest.envelope(book,"FORMAL");var payload=(com.fasterxml.jackson.databind.node.ObjectNode)e.payload();
    var match=(com.fasterxml.jackson.databind.node.ObjectNode)payload.path("match");match.put("started_at","2026-12-31T15:59:50Z").put("finished_at",newFinish?"2026-12-31T16:00:01Z":"2026-12-31T15:59:57Z");
    return com.bridge.persistence.FinalMatchEnvelope.create(e.matchId(),e.revision(),JSON.convertValue(payload,Map.class));
  }
  static void cleanupAndCheck(JournaledNativeWriterTest.Memory memory,Service service,Time time,com.bridge.persistence.FinalMatchEnvelope e,boolean kept){
    service.cleanClose();assertEquals(State.CLOSED,service.state());var maintenance=new JournalQuarterProtocolTest.Memory(memory.b);
    var protocol=new JournalQuarterProtocol(JournaledNativeWriterTest.BINDING,maintenance,time,"after-finishing");protocol.start();assertEquals(JournalQuarterProtocol.Status.VERIFIED,protocol.status());
    assertEquals(kept,maintenance.book.sheet("commits").rows.stream().anyMatch(r->!r.isEmpty()&&r.get(0).equals(e.commitId())));
    var carry=LifetimeStatistics.decode(maintenance.book.sheet("accounts").rows.get(1).get(4));assertEquals(kept?1:2,carry.formal().matches());
    assertEquals(QuarterlyRetention.currentQuarterStart(time.instant()),carry.through());
  }
  @Test void registeredActiveGameFinishesNewQuarterThenCleanupKeepsItsRawRecord()throws Exception {
    var time=new Time();var memory=JournaledNativeWriterTest.initial();var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);
    try(var service=new Service(transport,time,JournaledNativeWriterTest.BINDING,"boundary-writer",Limits.defaults())){
      service.start();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
      try(var backend=new NativeStoresBackend(service,transport,time,gate)){
        var e=envelope(memory.b,true);gate.registerMatch(e.matchId());time.now=NEW_NOW;int before=memory.writes;
        assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.FINISHING,gate.state());
        var identity=memory.b.sheet("auth_identities").rows.get(1);
        assertThrows(com.bridge.persistence.QuarterMaintenanceGate.Unavailable.class,()->backend.findOrCreateAccount(identity.get(0),identity.get(1),"Blocked","blocked@example.com"));
        assertEquals(State.OPEN,service.state());assertEquals(before,memory.writes);
        var unrelated=envelope(memory.b,true);assertEquals(com.bridge.persistence.StatsStore.SaveStatus.REJECTED,backend.submit(unrelated));
        backend.submit(e);assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,NativeHandleBackendCompositionTest.await(backend,e.commitId()));assertEquals(before+1,memory.writes);
        assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,backend.submit(e));assertEquals(before+1,memory.writes);
        cleanupAndCheck(memory,service,time,e,true);
      }
    }
  }
  @Test void preAdmittedOldFinishedQueueDrainsThenFoldsIntoLifetime()throws Exception {
    var time=new Time();var memory=JournaledNativeWriterTest.initial();var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);
    try(var service=new Service(transport,time,JournaledNativeWriterTest.BINDING,"queued-boundary",Limits.defaults())){
      service.start();var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
      try(var backend=new NativeStoresBackend(service,transport,time,gate)){
        var e=envelope(memory.b,false);gate.registerMatch(e.matchId());int before=memory.writes;
        try(var pause=gate.enterRead();var scope=pause.attach()){
          assertEquals(com.bridge.persistence.StatsStore.SaveStatus.PENDING,backend.submit(e));assertEquals(2,gate.activeOperations());
          time.now=NEW_NOW;assertEquals(com.bridge.persistence.QuarterMaintenanceGate.State.FINISHING,gate.state());
        }
        assertEquals(com.bridge.persistence.StatsStore.SaveStatus.CONFIRMED,NativeHandleBackendCompositionTest.await(backend,e.commitId()));assertEquals(before+1,memory.writes);
        cleanupAndCheck(memory,service,time,e,false);
      }
    }
  }
  @Test void normalMatchStillRejectsOldLedgerWithoutDrainGrant()throws Exception {
    var time=new Time();var memory=JournaledNativeWriterTest.initial();var transport=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);
    try(var service=new Service(transport,time,JournaledNativeWriterTest.BINDING,"normal-boundary",Limits.defaults())){
      service.start();var e=envelope(memory.b,true);time.now=NEW_NOW;int writes=memory.writes;
      assertThrows(RuntimeException.class,()->new NativeWriterAdapter(service).match("NOT-A-DRAIN",e));assertEquals(writes,memory.writes);
    }
  }
}
