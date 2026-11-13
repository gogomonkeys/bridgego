package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.util.*;
class NativeWriterAdapterTest {
  static class WireMemory extends ExtractedCoreParityTest.Memory {
    final List<String> wires=new ArrayList<>();boolean timeoutAfter;
    WireMemory(Book b){super(b);}
    @Override public void batch(String wire){wires.add(wire);super.batch(wire);if(timeoutAfter){timeoutAfter=false;throw new Unknown();}}
  }
  @Test void nicknameChangeKeepsSameAccountAndLifetimeInAtomicReceiptBatch()throws Exception {
    var mem=new WireMemory(RecoveredFixture.book());try(var service=ExtractedCoreParityTest.service(mem)){
      service.start();var before=mem.b.sheet("accounts").rows.get(1);var identity=mem.b.sheet("auth_identities").rows.get(1);String account=before.get(0),lifetime=before.get(4);int count=mem.writes;
      var adapter=new NativeWriterAdapter(service);var result=adapter.identity("PROFILE-LOCAL-1",identity.get(0),identity.get(1),"新暱稱","example@example.com");
      assertEquals(NativeWriterAdapter.Outcome.VERIFIED,result.outcome());assertEquals(account,result.accountId());assertEquals(count+1,mem.writes);
      assertEquals(lifetime,mem.b.sheet("accounts").rows.get(1).get(4));assertEquals("新暱稱",mem.b.sheet("accounts").rows.get(1).get(2));
      var wire=NativeSheetsModel.JSON.readTree(mem.wires.get(mem.wires.size()-1));assertTrue(wire.toString().contains("createDeveloperMetadata"));assertTrue(wire.toString().contains("updateDeveloperMetadata"));assertTrue(wire.toString().contains("updateCells"));
      var lookup=adapter.identity("LOOKUP-LOCAL-1",identity.get(0),identity.get(1),"新暱稱","example@example.com");assertEquals(account,lookup.accountId());assertEquals(count+1,mem.writes);
    }
  }
  @Test void unknownOutcomeOnlyReconcilesOriginalWithoutResend()throws Exception {
    var mem=new WireMemory(RecoveredFixture.book());try(var service=ExtractedCoreParityTest.service(mem)){
      service.start();var identity=mem.b.sheet("auth_identities").rows.get(1);var adapter=new NativeWriterAdapter(service);mem.timeoutAfter=true;int count=mem.writes;
      assertEquals(NativeWriterAdapter.Outcome.UNKNOWN,adapter.identity("UNKNOWN-PROFILE-1",identity.get(0),identity.get(1),"Changed","test@example.com").outcome());
      assertThrows(IllegalStateException.class,()->adapter.identity("OTHER-1",identity.get(0),identity.get(1),"Other","test@example.com"));
      assertEquals(count+1,mem.writes);assertEquals(NativeWriterAdapter.Outcome.VERIFIED,adapter.reconcilePending().outcome());assertEquals(count+1,mem.writes);
    }
  }
  @Test void currentStrictMatchCanonicalHashCompatibleAndAtomicallyCommitted()throws Exception {
    var mem=new WireMemory(RecoveredFixture.book());try(var service=ExtractedCoreParityTest.service(mem)){
      service.start();var old=mem.b.sheet("commits").rows.get(1);String id=UUID.randomUUID().toString();String json=mem.b.sheet("envelopes").rows.get(1).get(4).replace(old.get(3),id);
      var envelope=new com.bridge.persistence.FinalMatchEnvelope(id+":1",id,1,json,FinalMatchEnvelope.hash(json));int count=mem.writes;var profiles=copyRows(mem.b.sheet("accounts").rows);
      var result=new NativeWriterAdapter(service).match("MATCH-LOCAL-1",envelope);assertEquals(NativeWriterAdapter.Outcome.VERIFIED,result.outcome());assertEquals(count+1,mem.writes);
      assertEquals(profiles,mem.b.sheet("accounts").rows);assertEquals(envelope.payloadHash(),mem.b.sheet("commits").rows.get(2).get(1));
      String wire=mem.wires.get(mem.wires.size()-1);assertTrue(wire.contains("createDeveloperMetadata"));assertTrue(wire.contains("appendCells"));assertTrue(wire.contains("updateDeveloperMetadata"));
    }
  }
}
