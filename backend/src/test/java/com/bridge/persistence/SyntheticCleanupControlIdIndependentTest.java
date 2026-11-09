package com.bridge.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Local-only independent regression coverage for the narrowed reserved-ID range. */
class SyntheticCleanupControlIdIndependentTest {
  @Test void zeroIdRemainsValid(){
    byte[] raw=SyntheticCleanupControlIdBoundaryTest.scaffoldWithId(0);
    var fake=new SyntheticCleanupHttpWireTest.Fake(raw);
    try(var wire=new SyntheticCleanupHttpWire(SyntheticCleanupExecutorTest.target(),raw,fake,Clock.systemUTC())){
      assertEquals(0,fake.reads);assertEquals(0,fake.writes);
    }
  }
  @Test void duplicatedHighIdAndOutOfIntRangeStillRejectBeforeTransport(){
    for(boolean overflow:new boolean[]{false,true}){
      var raw=SyntheticCleanupRawProtocolTest.rawScaffold(SyntheticCleanupExecutorTest.target());
      ((ObjectNode)raw.path("sheets").get(0).path("properties")).put("sheetId",overflow?2147483648L:2147483647L);
      if(!overflow)((ObjectNode)raw.path("sheets").get(1).path("properties")).put("sheetId",Integer.MAX_VALUE);
      byte[] bytes=SyntheticCleanupRawProtocol.bytes(raw);var fake=new SyntheticCleanupHttpWireTest.Fake(bytes);
      assertThrows(IllegalStateException.class,()->new SyntheticCleanupHttpWire(SyntheticCleanupExecutorTest.target(),bytes,fake,Clock.systemUTC()));
      assertEquals(0,fake.reads);assertEquals(0,fake.writes);
    }
  }
  @Test void highIdUnknownWriteStillStopsWithoutReplay(){
    byte[] raw=SyntheticCleanupControlIdBoundaryTest.scaffoldWithId(Integer.MAX_VALUE);
    var fake=new SyntheticCleanupHttpWireTest.Fake(raw);var clock=Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC);
    var wire=new SyntheticCleanupHttpWire(SyntheticCleanupExecutorTest.target(),raw,fake,clock);
    var session=new SyntheticCleanupSupervisedSession(SyntheticCleanupExecutorTest.target(),raw,wire,clock,()->true);
    var p=session.preview();fake.server.unknown=true;
    assertEquals("STOPPED_UNCERTAIN",session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertEquals("REJECTED",session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertThrows(IllegalStateException.class,session::preview);
    assertEquals(1,fake.writes);assertTrue(fake.closed);
  }
}
