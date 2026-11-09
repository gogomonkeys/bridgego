package com.bridge.persistence;

import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupSupervisedSessionTest {
  record Setup(SyntheticCleanupSupervisedSession session,SyntheticCleanupHttpWireTest.Fake transport,
      AtomicBoolean freeze){}
  Setup setup(){
    String target=SyntheticCleanupExecutorTest.target();
    byte[] raw=SyntheticCleanupRawProtocol.bytes(SyntheticCleanupRawProtocolTest.rawScaffold(target));
    var clock=Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC);
    var fake=new SyntheticCleanupHttpWireTest.Fake(raw);var freeze=new AtomicBoolean(true);
    var wire=new SyntheticCleanupHttpWire(target,raw,fake,clock);
    return new Setup(new SyntheticCleanupSupervisedSession(target,raw,wire,clock,freeze::get),fake,freeze);
  }
  @Test void fourStagesHaveSeparatePrecisePreviewsAndNeverCertifyProduction(){
    var s=setup();assertEquals(0,s.transport.reads);assertEquals(0,s.transport.writes);
    for(int i=0;i<4;i++){
      var p=s.session.preview();assertEquals(i,s.transport.writes);assertEquals(8,p.tables().size());
      assertFalse(p.changes().isEmpty());assertEquals(p.changes().size(),p.tables().stream().mapToInt(x->x.changedCells()).sum());
      assertEquals(10,p.exactBatchPreview().path("requestsTemplate").size());
      var result=s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation());
      assertEquals("VERIFIED",result.outcome());assertEquals(i+1,result.verifiedPhases());
      assertEquals(i==3,result.syntheticFunctionalRunVerified());assertFalse(result.productionCleanupCertified());
      assertEquals(i+1,s.transport.writes);
    }
    assertThrows(IllegalStateException.class,s.session::preview);assertTrue(s.transport.closed);
  }
  @Test void noConfirmationWrongConfirmationAndRepeatedConfirmationDoNotWrite(){
    var s=setup();assertEquals("REJECTED",s.session.confirm("x","x").outcome());
    var p=s.session.preview();assertEquals("REJECTED",s.session.confirm(p.confirmation().planId(),"yes").outcome());assertEquals(0,s.transport.writes);
    assertEquals("VERIFIED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertEquals("REJECTED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());assertEquals(1,s.transport.writes);
  }
  @Test void lostResponseAfterApplyCannotRetryOrContinue(){
    var s=setup();var p=s.session.preview();s.transport.server.unknown=true;
    assertEquals("STOPPED_UNCERTAIN",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertEquals("REJECTED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertThrows(IllegalStateException.class,s.session::preview);assertEquals(1,s.transport.writes);assertTrue(s.transport.closed);
  }
  @Test void lostManualFreezePreventsWrite(){
    var s=setup();var p=s.session.preview();s.freeze.set(false);
    assertEquals("REJECTED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertEquals(0,s.transport.writes);assertThrows(IllegalStateException.class,s.session::preview);
  }
  @Test void previewReturnedTreeCannotMutatePendingRequest(){
    var s=setup();var p=s.session.preview();((com.fasterxml.jackson.databind.node.ObjectNode)p.exactBatchPreview()).removeAll();
    assertEquals(10,p.exactBatchPreview().path("requestsTemplate").size());
    assertEquals("VERIFIED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
  }
  @Test void priorMarkerRefusesFreshSessionInsteadOfReset(){
    var s=setup();var p=s.session.preview();s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation());s.session.close();
    String target=SyntheticCleanupExecutorTest.target();byte[] raw=s.transport.server.raw;
    assertThrows(IllegalStateException.class,()->new SyntheticCleanupHttpWire(target,raw,s.transport,Clock.systemUTC()));
    assertEquals(1,s.transport.writes);
  }
}
