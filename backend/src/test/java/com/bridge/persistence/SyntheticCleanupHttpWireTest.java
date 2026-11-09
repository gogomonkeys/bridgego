package com.bridge.persistence;

import com.bridge.persistence.SyntheticCleanupExecutor.*;
import com.bridge.persistence.SyntheticCleanupRawProtocol.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupHttpWireTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static class Fake implements LiveQuarterStorage.Transport {
    final SyntheticCleanupRawProtocolTest.FakeWire server;
    int reads,writes; boolean failRead,corruptRead,closed; byte[] captured;
    Fake(byte[] raw){server=new SyntheticCleanupRawProtocolTest.FakeWire(raw);}
    public byte[] readWorkbook(){reads++;if(failRead)throw new IllegalStateException("secret");return corruptRead?"{}".getBytes():server.raw.clone();}
    public void replaceWorkbook(byte[] body){writes++;captured=body.clone();try{server.sendOnce("ignored",new Batch(JSON.readTree(body).path("requests"),JSON.createObjectNode()));}catch(Exception e){throw new IllegalStateException("secret");}}
    public void close(){closed=true;}
  }
  record Setup(String target,Fake transport,SyntheticCleanupHttpWire wire,SyntheticCleanupRawProtocol protocol,SyntheticCleanupExecutor executor){}
  Setup setup(){
    String target=SyntheticCleanupExecutorTest.target();byte[] raw=SyntheticCleanupRawProtocol.bytes(SyntheticCleanupRawProtocolTest.rawScaffold(target));
    var fake=new Fake(raw);var clock=Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC);
    var wire=new SyntheticCleanupHttpWire(target,raw,fake,clock);var fixtures=SyntheticCleanupExecutor.generate(target,SyntheticCleanupExecutorTest.NOW);
    var protocol=new SyntheticCleanupRawProtocol(fixtures,wire,raw);var executor=new SyntheticCleanupExecutor(fixtures,protocol,clock,()->true);
    return new Setup(target,fake,wire,protocol,executor);
  }
  PhaseManifest arm(Setup s){var preview=s.executor.preview(Phase.SEED_ERASURE);var manifest=s.protocol.manifest(preview);s.wire.arm(manifest);return manifest;}
  @Test void fourPhasesPassThroughBoundedRealSerializationWithoutNetwork() throws Exception {
    var s=setup();for(var phase:Phase.values()){
      var preview=s.executor.preview(phase);s.wire.arm(s.protocol.manifest(preview));
      assertEquals(Outcome.VERIFIED,s.executor.execute(preview.planId(),preview.confirmation()).outcome());
      JsonNode body=JSON.readTree(s.transport.captured);assertEquals(1,body.size());assertEquals(10,body.path("requests").size());
    }assertEquals(4,s.transport.writes);s.wire.close();assertTrue(s.transport.closed);
  }
  @Test void noArmMeansNoDispatchAndPermanentStop(){var s=setup();assertThrows(IllegalStateException.class,()->s.wire.sendOnce(s.target,new Batch(JSON.createArrayNode(),JSON.createObjectNode())));assertThrows(IllegalStateException.class,()->s.wire.readWhole(s.target));assertEquals(0,s.transport.writes);assertEquals(0,s.transport.reads);}
  @Test void alteredBatchCannotDispatchOrRetry(){var s=setup();var manifest=arm(s);ArrayNode requests=(ArrayNode)manifest.requests();requests.addObject().putObject("deleteSheet").put("sheetId",100);assertThrows(IllegalStateException.class,()->s.wire.sendOnce(s.target,new Batch(requests,JSON.createObjectNode())));assertThrows(IllegalStateException.class,()->s.wire.sendOnce(s.target,new Batch(manifest.requests(),JSON.createObjectNode())));assertEquals(0,s.transport.writes);}
  @Test void lostResponseLatchesAfterExactlyOneAttempt(){var s=setup();var m=arm(s);s.transport.server.unknown=true;var error=assertThrows(IllegalStateException.class,()->s.wire.sendOnce(s.target,new Batch(m.requests(),JSON.createObjectNode())));assertFalse(error.toString().contains("secret"));assertThrows(IllegalStateException.class,()->s.wire.sendOnce(s.target,new Batch(m.requests(),JSON.createObjectNode())));int before=s.transport.reads;assertThrows(IllegalStateException.class,()->s.wire.readWhole(s.target));assertEquals(before,s.transport.reads);assertEquals(1,s.transport.writes);}
  @Test void wrongTargetStopsBeforeTransport(){var s=setup();assertThrows(IllegalStateException.class,()->s.wire.readWhole("other"));assertThrows(IllegalStateException.class,()->s.wire.readWhole(s.target));assertEquals(0,s.transport.reads);}
  @Test void readFailureAndPostWriteMismatchLatch(){var s=setup();s.transport.failRead=true;assertThrows(IllegalStateException.class,()->s.wire.readWhole(s.target));s.transport.failRead=false;assertThrows(IllegalStateException.class,()->s.wire.readWhole(s.target));assertEquals(1,s.transport.reads);
    var q=setup();var m=arm(q);q.wire.sendOnce(q.target,new Batch(m.requests(),JSON.createObjectNode()));q.transport.corruptRead=true;assertThrows(IllegalStateException.class,()->q.wire.readWhole(q.target));assertThrows(IllegalStateException.class,()->q.wire.arm(m));assertEquals(1,q.transport.writes);
  }
  @Test void factoryRejectsOtherTargetBeforeCredentialValidation(){assertThrows(IllegalStateException.class,()->SyntheticCleanupHttpWire.open("not-approved",new byte[0],new byte[0],Clock.systemUTC()));}
}
