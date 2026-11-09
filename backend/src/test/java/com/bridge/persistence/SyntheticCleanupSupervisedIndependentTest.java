package com.bridge.persistence;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent local-only lifecycle review. All transport calls terminate in memory. */
class SyntheticCleanupSupervisedIndependentTest {
  static final class MovingClock extends Clock {
    Instant now=SyntheticCleanupExecutorTest.NOW;
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}
    public Instant instant(){return now;}
  }
  record Setup(SyntheticCleanupSupervisedSession session,SyntheticCleanupHttpWireTest.Fake fake,MovingClock clock){}
  Setup setup(){
    String target=SyntheticCleanupExecutorTest.target();
    byte[] raw=SyntheticCleanupRawProtocol.bytes(SyntheticCleanupRawProtocolTest.rawScaffold(target));
    var fake=new SyntheticCleanupHttpWireTest.Fake(raw);var clock=new MovingClock();
    var wire=new SyntheticCleanupHttpWire(target,raw,fake,clock);
    return new Setup(new SyntheticCleanupSupervisedSession(target,raw,wire,clock,()->true),fake,clock);
  }
  @Test void secondPreviewCannotReplacePendingApproval(){
    var s=setup();var p=s.session.preview();
    assertThrows(IllegalStateException.class,s.session::preview);
    assertEquals(0,s.fake.writes);
    assertEquals("VERIFIED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertEquals(1,s.fake.writes);
  }
  @Test void previousApprovalCannotAuthorizeFollowingPhase(){
    var s=setup();var first=s.session.preview();
    assertEquals("VERIFIED",s.session.confirm(first.confirmation().planId(),first.confirmation().confirmation()).outcome());
    var second=s.session.preview();
    assertEquals("REJECTED",s.session.confirm(first.confirmation().planId(),first.confirmation().confirmation()).outcome());
    assertEquals("REJECTED",s.session.confirm(second.confirmation().planId(),first.confirmation().confirmation()).outcome());
    assertEquals(1,s.fake.writes);
    assertEquals("VERIFIED",s.session.confirm(second.confirmation().planId(),second.confirmation().confirmation()).outcome());
    assertEquals(2,s.fake.writes);
  }
  @Test void expiredApprovalStopsWithoutDispatch(){
    var s=setup();var p=s.session.preview();s.clock.now=s.clock.now.plusSeconds(300);
    assertNotEquals("VERIFIED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertEquals(0,s.fake.writes);assertTrue(s.fake.closed);
    assertThrows(IllegalStateException.class,s.session::preview);
  }
  @Test void baselineChangeAfterPreviewStopsWithoutDispatch(){
    var s=setup();var p=s.session.preview();s.fake.corruptRead=true;
    assertNotEquals("VERIFIED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertEquals(0,s.fake.writes);assertTrue(s.fake.closed);
    s.fake.corruptRead=false;
    assertEquals("REJECTED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertThrows(IllegalStateException.class,s.session::preview);
  }
  @Test void explicitCloseInvalidatesPendingApproval(){
    var s=setup();var p=s.session.preview();s.session.close();
    assertEquals("REJECTED",s.session.confirm(p.confirmation().planId(),p.confirmation().confirmation()).outcome());
    assertThrows(IllegalStateException.class,s.session::preview);
    assertEquals(0,s.fake.writes);assertTrue(s.fake.closed);
  }
}
