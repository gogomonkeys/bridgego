package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class NativeBackendLifecycleTest {
  @Test void drainedCloseQuarterAndFreshClaimKeepSameBackend()throws Exception {
    var time=new CrossQuarterNativeSettlementTest.Time();var memory=JournaledNativeWriterTest.initial();
    var tx=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);
    var service=new Service(tx,time,JournaledNativeWriterTest.BINDING,"lifecycle-writer",Limits.defaults());service.start();
    var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
    try(var backend=new NativeStoresBackend(service,tx,time,gate)){
      assertThrows(IllegalStateException.class,backend::cleanCloseForQuarter);
      time.now=CrossQuarterNativeSettlementTest.NEW_NOW;gate.beginDrain();assertTrue(gate.beginExclusive());
      var closed=backend.cleanCloseForQuarter();assertEquals("CLOSED",root(new java.util.ArrayList<>(closed.metadata.values())).state());
      backend.retireClosedWriter();
      var maintenance=new JournalQuarterProtocolTest.Memory(memory.b);var protocol=new JournalQuarterProtocol(JournaledNativeWriterTest.BINDING,maintenance,time,"quarter-owner");protocol.start();assertEquals(JournalQuarterProtocol.Status.VERIFIED,protocol.status());memory.b=maintenance.book;
      var reopened=backend.reopenAfterVerifiedQuarter(memory.b,(book,owner)->{var nextTx=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);return new NativeStoresBackend.Session(new Service(nextTx,time,JournaledNativeWriterTest.BINDING,owner,Limits.defaults()),nextTx);});
      assertEquals("OPEN",root(new java.util.ArrayList<>(reopened.metadata.values())).state());assertTrue(backend.lifecycleOpen());assertTrue(service.closed);
      gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));var identity=memory.b.sheet("auth_identities").rows.get(1);
      assertEquals(identity.get(2),backend.findOrCreateAccount(identity.get(0),identity.get(1),"After maintenance","test@example.com"));
      String firstOwner=root(new java.util.ArrayList<>(memory.b.metadata.values())).owner();
      time.now=java.time.Instant.parse("2027-03-31T16:00:02Z");gate.beginDrain();assertTrue(gate.beginExclusive());
      backend.cleanCloseForQuarter();backend.retireClosedWriter();
      var secondMemory=new JournalQuarterProtocolTest.Memory(memory.b);var secondProtocol=new JournalQuarterProtocol(JournaledNativeWriterTest.BINDING,secondMemory,time,"quarter-owner-two");
      secondProtocol.start();assertEquals(JournalQuarterProtocol.Status.VERIFIED,secondProtocol.status());memory.b=secondMemory.book;
      var second=backend.reopenAfterVerifiedQuarter(memory.b,(book,owner)->{var t=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);return new NativeStoresBackend.Session(new Service(t,time,JournaledNativeWriterTest.BINDING,owner,Limits.defaults()),t);});
      assertNotEquals(firstOwner,root(new java.util.ArrayList<>(second.metadata.values())).owner());
      gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
      assertEquals(identity.get(2),backend.findOrCreateAccount(identity.get(0),identity.get(1),"Second quarter","test@example.com"));
    }
  }
  @Test void unknownCleanCloseCannotBeRetriedOrReopened()throws Exception {
    var time=new CrossQuarterNativeSettlementTest.Time();var memory=JournaledNativeWriterTest.initial();var tx=new JournaledNativeTransport(memory,JournaledNativeWriterTest.BINDING,time);
    var service=new Service(tx,time,JournaledNativeWriterTest.BINDING,"unknown-close",Limits.defaults());service.start();
    var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,time);gate.openVerified(QuarterlyRetention.currentQuarterStart(time.instant()));
    try(var backend=new NativeStoresBackend(service,tx,time,gate)){
      gate.beginDrain();assertTrue(gate.beginExclusive());memory.delay=true;int writes=memory.writes;
      assertThrows(RuntimeException.class,backend::cleanCloseForQuarter);assertEquals(writes+1,memory.writes);
      memory.b=NativeSheetsModel.simulate(memory.b,memory.delayed); // Late success never revives the retired local lifecycle.
      assertThrows(RuntimeException.class,backend::cleanCloseForQuarter);
      assertThrows(RuntimeException.class,()->backend.reopenAfterVerifiedQuarter(memory.b,(b,owner)->{throw new AssertionError("must not provision");}));assertEquals(writes+1,memory.writes);
    }
  }
}
