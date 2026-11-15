package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;

class TutorialServiceTest {
  @Test void sessionOwnershipRevisionAndExitAreIsolated() {
    var service = new TutorialService();
    var a = service.start("a");
    assertEquals(a, service.start("a"));
    var b = service.start("b");
    assertNotEquals(a.sessionId(), b.sessionId());
    assertThrows(RoomService.Failure.class, () -> service.play("b", a.sessionId(), 0L, a.legalCards().get(0)));
    var next = service.play("a", a.sessionId(), 0L, a.legalCards().get(0));
    assertEquals(1, next.revision());
    assertThrows(RoomService.Failure.class, () -> service.play("a", a.sessionId(), 0L, a.legalCards().get(0)));
    assertEquals(next, service.view("a"));
    assertEquals(b, service.view("b"));
    assertThrows(RoomService.Failure.class, () -> service.exit("a", b.sessionId()));
    service.exit("a", a.sessionId());
    service.exit("a", a.sessionId());
    assertThrows(RoomService.Failure.class, () -> service.view("a"));
    var restarted = service.start("a");
    assertNotEquals(a.sessionId(), restarted.sessionId());
    assertThrows(RoomService.Failure.class, () -> service.exit("a", a.sessionId()));
  }
  @Test void badPlayDoesNotConsumeRevision() {
    var service = new TutorialService();
    var a = service.start("a");
    assertThrows(RoomService.Failure.class, () -> service.play("a", a.sessionId(), 0L, new GameState.Card("SPADE", 2)));
    assertEquals(a, service.view("a"));
  }
  @Test void timePassingNeverAutoPlaysOrAdvancesTutorialRevision() {
    var time = new AtomicLong(1000);
    var service = new TutorialService(time::get);
    var before = service.start("learner");
    time.addAndGet(600_000);
    assertEquals(before, service.current("learner"));
    var after = service.play("learner", before.sessionId(), before.revision(), before.legalCards().get(0));
    assertEquals(1, after.revision());
    time.addAndGet(600_000);
    assertEquals(after, service.view("learner"));
  }
  @Test void capacityAndExpiryBoundMemory() {
    var time = new AtomicLong(9_000_000);
    var service = new TutorialService(time::get);
    for (int i=0;i<128;i++) service.start("owner"+i);
    assertThrows(RoomService.Failure.class, () -> service.start("overflow"));
    time.addAndGet(3_600_001);
    assertThrows(RoomService.Failure.class, () -> service.view("owner0"));
    assertNotNull(service.start("new"));
  }
}
