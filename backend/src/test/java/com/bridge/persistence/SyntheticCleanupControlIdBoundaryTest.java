package com.bridge.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupControlIdBoundaryTest {
  static byte[] scaffoldWithId(int id){
    var raw=SyntheticCleanupRawProtocolTest.rawScaffold(SyntheticCleanupExecutorTest.target());
    ((ObjectNode)raw.path("sheets").get(0).path("properties")).put("sheetId",id);
    return SyntheticCleanupRawProtocol.bytes(raw);
  }
  @Test void highNonreservedNativeIdsAreValidAndExecuteFourPhasesLocally(){
    for(int id:new int[]{SyntheticCleanupExecutor.CONTROL_ID_BASE-1,SyntheticCleanupExecutor.CONTROL_ID_BASE+4,2_008_928_427,Integer.MAX_VALUE}){
      byte[] raw=scaffoldWithId(id);var fake=new SyntheticCleanupHttpWireTest.Fake(raw);var clock=Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC);
      var wire=new SyntheticCleanupHttpWire(SyntheticCleanupExecutorTest.target(),raw,fake,clock);
      var session=new SyntheticCleanupSupervisedSession(SyntheticCleanupExecutorTest.target(),raw,wire,clock,()->true);
      for(int phase=0;phase<4;phase++){
        var p=session.preview();assertEquals("VERIFIED",session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
      }
      assertEquals(4,fake.writes);
    }
  }
  @Test void eachOfTheFourActualReservedIdsRejectedBeforeAnyTransportCall(){
    for(int offset=0;offset<4;offset++){
      byte[] raw=scaffoldWithId(SyntheticCleanupExecutor.CONTROL_ID_BASE+offset);var fake=new SyntheticCleanupHttpWireTest.Fake(raw);
      assertThrows(IllegalStateException.class,()->new SyntheticCleanupHttpWire(SyntheticCleanupExecutorTest.target(),raw,fake,Clock.systemUTC()));
      assertEquals(0,fake.reads);assertEquals(0,fake.writes);
    }
  }
  @Test void negativeIdRejectedBeforeTransport(){
    byte[] raw=scaffoldWithId(-1);var fake=new SyntheticCleanupHttpWireTest.Fake(raw);
    assertThrows(IllegalStateException.class,()->new SyntheticCleanupHttpWire(SyntheticCleanupExecutorTest.target(),raw,fake,Clock.systemUTC()));
    assertEquals(0,fake.reads);assertEquals(0,fake.writes);
  }
}
