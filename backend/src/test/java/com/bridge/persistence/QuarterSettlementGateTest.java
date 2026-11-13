package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class QuarterSettlementGateTest {
  @Test void queueCancellationRetainsRegistrationButConfirmedSettlementRetiresIt() {
    var clock=new QuarterMaintenanceCoordinatorTest.Time();var gate=new QuarterMaintenanceGate(true,clock);
    gate.openVerified(QuarterlyRetention.currentQuarterStart(clock.instant()));gate.registerMatch("existing");
    clock.now=Instant.parse("2026-12-31T16:00:00Z");
    var cancelled=gate.enterSettlement("existing");cancelled.success();cancelled.close();
    assertTrue(gate.settlementAllowed("existing"));
    var confirmed=gate.enterSettlement("existing");
    try(var scope=confirmed.attach()){confirmed.settlementRecorded();confirmed.success();}confirmed.close();
    assertFalse(gate.settlementAllowed("existing"));
    assertThrows(QuarterMaintenanceGate.Unavailable.class,()->gate.enterSettlement("existing"));
  }
  @Test void boundaryAllowsOnlyRegisteredSettlementAndDrainsQueuedPermit() {
    var clock=new QuarterMaintenanceCoordinatorTest.Time();var gate=new QuarterMaintenanceGate(true,clock);
    var old=QuarterlyRetention.currentQuarterStart(clock.instant());gate.openVerified(old);gate.registerMatch("existing");
    clock.now=Instant.parse("2026-12-31T16:00:00Z");
    assertTrue(gate.isOpen());assertFalse(gate.acceptsNewGames());
    assertThrows(QuarterMaintenanceGate.Unavailable.class,gate::enterWrite);
    assertThrows(QuarterMaintenanceGate.Unavailable.class,()->gate.registerMatch("new"));
    assertThrows(QuarterMaintenanceGate.Unavailable.class,()->gate.enterSettlement("new"));
    assertEquals(QuarterMaintenanceGate.State.FINISHING,gate.state());
    var p=gate.enterSettlement("existing");var grant=p.settlementGrant();
    assertThrows(QuarterMaintenanceGate.Unavailable.class,()->grant.require("existing",clock.now));
    gate.beginDrain();assertFalse(gate.beginExclusive());
    try(var scope=p.attach()) {
      assertEquals(old,grant.require("existing",clock.now));
      assertThrows(QuarterMaintenanceGate.Unavailable.class,()->grant.require("wrong",clock.now));p.success();
    }
    p.close();assertTrue(gate.beginExclusive());
    assertThrows(QuarterMaintenanceGate.Unavailable.class,()->grant.require("existing",clock.now));
    assertThrows(QuarterMaintenanceGate.Unavailable.class,()->gate.enterSettlement("existing"));
    assertThrows(QuarterMaintenanceGate.Unavailable.class,gate::enterWrite);
  }
}
