package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.util.*;
class JournaledNativeWriterTest {
  static final java.time.Clock CLOCK=ExtractedCoreParityTest.CLOCK;
  static final ManualQuarterMaintenance.Binding BINDING=new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,RecoveredFixture.DATASET);
  static class Memory extends NativeWriterAdapterTest.WireMemory {
    boolean delay;String delayed;
    Memory(Book b){super(b);}
    @Override public void batch(String wire){if(delay){delay=false;delayed=wire;writes++;throw new Unknown();}super.batch(wire);}
  }
  static Memory initial()throws Exception {
    var m=new Memory(RecoveredFixture.book());try(var s=ExtractedCoreParityTest.service(m)){s.start();s.cleanClose();}
    var bootstrap=NativeCommitJournal.bootstrap(BINDING,m.b,NativeCommitJournal.digest(BINDING,m.b),CLOCK);bootstrap.dispatchOnce(m::batch);return m;
  }
  static Service service(JournaledNativeTransport t){return new Service(t,CLOCK,BINDING,"journal-business",Limits.defaults());}
  @Test void oneCombinedBatchPreservesLifetimeAndCarriesJournal()throws Exception {
    var m=initial();var transport=new JournaledNativeTransport(m,BINDING,CLOCK);try(var s=service(transport)){
      s.start();assertEquals(State.OPEN,s.state());var identity=m.b.sheet("auth_identities").rows.get(1);String lifetime=m.b.sheet("accounts").rows.get(1).get(4);int count=m.writes;
      var result=new NativeWriterAdapter(s).identity("JOURNAL-PROFILE-1",identity.get(0),identity.get(1),"Journal nickname","test@example.com");
      assertEquals(NativeWriterAdapter.Outcome.VERIFIED,result.outcome());assertEquals(count+1,m.writes);assertEquals(lifetime,m.b.sheet("accounts").rows.get(1).get(4));
      String wire=m.wires.get(m.wires.size()-1);assertTrue(wire.contains("20001"));assertTrue(wire.contains("createDeveloperMetadata"));assertTrue(wire.contains("updateCells"));
      assertNotNull(transport.snapshot().metadata.get(20001));NativeCommitJournal.verify(BINDING,m.b,CLOCK);
    }
  }
  @Test void beforeSnapshotRemainsBlockedUntilExactLateCombinedApplyWithoutResend()throws Exception {
    var m=initial();var transport=new JournaledNativeTransport(m,BINDING,CLOCK);try(var s=service(transport)){
      s.start();var adapter=new NativeWriterAdapter(s);var identity=m.b.sheet("auth_identities").rows.get(1);m.delay=true;int count=m.writes;
      assertEquals(NativeWriterAdapter.Outcome.UNKNOWN,adapter.identity("JOURNAL-LATE-1",identity.get(0),identity.get(1),"Late nickname","test@example.com").outcome());
      assertEquals(NativeWriterAdapter.Outcome.UNKNOWN,adapter.reconcilePending().outcome());assertEquals(count+1,m.writes);assertThrows(Unknown.class,transport::snapshot);
      m.b=simulate(m.b,m.delayed);assertEquals(NativeWriterAdapter.Outcome.VERIFIED,adapter.reconcilePending().outcome());assertEquals(count+1,m.writes);
    }
  }
  @Test void missingOrStaleJournalBlocksReadAndNoQuarterWireAllowed()throws Exception {
    var m=initial();var transport=new JournaledNativeTransport(m,BINDING,CLOCK);int count=m.writes;
    assertThrows(Rejected.class,()->transport.batch("{\"requests\":[{\"deleteSheet\":{\"sheetId\":1}}]}"));assertEquals(count,m.writes);
    m.b.sheet("accounts").rows.get(1).set(2,"manual legacy change");assertThrows(Rejected.class,transport::snapshot);
  }
}
