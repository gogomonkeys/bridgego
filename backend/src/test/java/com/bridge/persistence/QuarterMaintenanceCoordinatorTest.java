package com.bridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static com.bridge.persistence.QuarterMaintenanceCoordinator.*;

class QuarterMaintenanceCoordinatorTest {
  static final Instant Q3=Instant.parse("2026-06-30T16:00:00Z"), Q4=Instant.parse("2026-09-30T16:00:00Z");
  static class Time extends Clock {
    Instant now=Q4.plusSeconds(1);public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
  }
  static Observation value(Phase phase,Instant through,String operation,boolean compatible) {
    return new Observation(phase,through,"verified-native-token",operation,true,true,true,compatible);
  }
  static class Control implements DurableControl {
    Observation state=value(Phase.DUE,Q3,"",true);int writes,reads;boolean unknown,throwAfterApplied;Time time;
    public Observation inspect(Instant q){reads++;return state;}
    public void executeOnce(Instant q,Observation expected,String operation) {
      writes++;
      if(unknown){state=value(Phase.APPLIED,Q3,operation,true);throw new IllegalStateException();}
      state=value(Phase.COMPLETE,q,operation,true);
      if(time!=null)time.now=Instant.parse("2026-12-31T16:00:00Z");
      if(throwAfterApplied)throw new IllegalStateException();
    }
  }
  @Test void noTransportFailsClosedAndDisabledNeverTouchesStorage() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);
    var c=new QuarterMaintenanceCoordinator(gate,DurableControl.unavailable(),clock,()->true);c.tick();
    assertEquals(Status.BLOCKED_MANUAL,c.status());assertFalse(gate.isOpen());
    var disabled=new QuarterMaintenanceGate(false,clock);var control=new Control();
    var off=new QuarterMaintenanceCoordinator(disabled,control,clock,()->true);off.tick();
    assertEquals(0,control.reads);assertEquals(0,control.writes);
  }
  @Test void completeAtStartupOpensButUnknownFlagsNeverDo() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
    control.state=value(Phase.COMPLETE,Q4,"prior",true);
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true);c.tick();
    assertTrue(gate.isOpen());assertEquals(0,control.writes);
    control.state=new Observation(Phase.COMPLETE,Q4,"token","prior",true,true,false,true);gate.reconciling();c.tick();
    assertFalse(gate.isOpen());assertEquals(Status.BLOCKED_MANUAL,c.status());
  }
  @Test void fullDueFlowWritesOnceAndRequiresFreshMatchingCompletion() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true);c.tick();c.tick();
    assertTrue(gate.isOpen());assertEquals(1,control.writes);assertTrue(control.reads>=3);
  }
  @Test void unknownExecutionNeverRetriesOrReopensEvenIfLaterFlagChanges() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();control.unknown=true;
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true);c.tick();
    control.state=value(Phase.COMPLETE,Q4,"other",true);c.tick();
    assertEquals(Status.BLOCKED_MANUAL,c.status());assertFalse(gate.isOpen());assertEquals(1,control.writes);
  }
  @Test void exceptionAfterAppliedMayOpenOnlyOnIndependentExactReceipt() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();control.throwAfterApplied=true;
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true);c.tick();
    assertTrue(gate.isOpen());assertEquals(1,control.writes);
  }
  @Test void legacyAndUnresolvedStatesNeverDispatch() {
    for(var phase:new Phase[]{Phase.DUE,Phase.PENDING,Phase.APPLIED,Phase.UNKNOWN}) {
      var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
      control.state=value(phase,Q3,"",false);
      var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true);c.tick();
      assertEquals(0,control.writes);assertEquals(Status.BLOCKED_MANUAL,c.status());
    }
  }
  @Test void queuedWorkAndDomainQuiescenceMustBothDrainWithoutTimeoutOverride() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);gate.openVerified(Q4);
    var permit=gate.enterWrite();var control=new Control();var quiet=new AtomicBoolean(false);
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,quiet::get);c.tick();
    assertEquals(Status.DRAINING,c.status());assertEquals(0,control.writes);
    quiet.set(true);c.tick();assertEquals(0,control.writes);
    permit.success();permit.close();c.tick();assertEquals(1,control.writes);assertTrue(gate.isOpen());
  }
  @Test void defaultDomainProofNeverAllowsExecutionAndWakeDetectsQuarterChange() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
    control.state=value(Phase.COMPLETE,Q4,"prior",true);
    var c=new QuarterMaintenanceCoordinator(gate,control,clock);c.tick();assertTrue(gate.isOpen());
    clock.now=Instant.parse("2026-12-31T16:00:00Z");control.state=value(Phase.DUE,Q4,"",true);c.tick();
    assertEquals(Status.DRAINING,c.status());assertEquals(0,control.writes);assertFalse(gate.acceptsNewGames());
  }
  @Test void domainResumesOnlyAfterVerifiedSuccessAndNeverOnUnknown() {
    for(boolean unknown:new boolean[]{false,true}) {
      var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();control.unknown=unknown;
      var frozen=new AtomicBoolean(false);var resumed=new AtomicBoolean(false);
      var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->{frozen.set(true);return true;},()->{resumed.set(true);frozen.set(false);});
      c.tick();assertEquals(!unknown,resumed.get());assertEquals(unknown,frozen.get());
    }
  }
  @Test void unknownReadWhileOpenAlsoFreezesDomainWithoutDispatch() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
    var frozen=new AtomicBoolean(false);control.state=value(Phase.COMPLETE,Q4,"prior",true);
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->{frozen.set(true);return true;},()->frozen.set(false));
    c.tick();assertTrue(gate.isOpen());control.state=Observation.unavailable();gate.reconciling();c.tick();
    assertTrue(frozen.get());assertFalse(gate.isOpen());assertEquals(0,control.writes);
  }
  @Test void failedDomainResumeDoesNotOpenGate() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true,()->{throw new IllegalStateException();});
    c.tick();assertFalse(gate.isOpen());assertEquals(Status.BLOCKED_MANUAL,c.status());
  }
  @Test void explicitInvalidationFreshCompleteReopensThenReturnsToIdleCache() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
    control.state=value(Phase.COMPLETE,Q4,"prior",true);
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true);c.tick();
    assertEquals(1,control.reads);gate.reconciling();assertFalse(gate.isOpen());
    c.tick();assertTrue(gate.isOpen());assertEquals(Status.OPEN,c.status());assertEquals(2,control.reads);
    c.tick();c.tick();assertEquals(2,control.reads);assertEquals(0,control.writes);
  }
  @Test void idleAndWaitingDoNotPollWholeSheetAndRollbackBlocks() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();
    control.state=value(Phase.COMPLETE,Q4,"prior",true);var quiet=new AtomicBoolean(false);
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,quiet::get);c.tick();int reads=control.reads;
    for(int i=0;i<20;i++){clock.now=clock.now.plusSeconds(30);c.tick();}assertEquals(reads,control.reads);
    clock.now=Instant.parse("2026-12-31T16:00:00Z");control.state=value(Phase.DUE,Q4,"",true);c.tick();reads=control.reads;
    for(int i=0;i<20;i++){clock.now=clock.now.plusSeconds(30);c.tick();}assertEquals(reads,control.reads);assertEquals(0,control.writes);
    clock.now=clock.now.minusSeconds(1);c.tick();assertEquals(Status.BLOCKED_MANUAL,c.status());
  }
  @Test void boundaryChangesDuringExecutionCannotReopen() {
    var clock=new Time();var gate=new QuarterMaintenanceGate(true,clock);var control=new Control();control.time=clock;
    var c=new QuarterMaintenanceCoordinator(gate,control,clock,()->true);c.tick();
    assertEquals(Status.BLOCKED_MANUAL,c.status());assertFalse(gate.isOpen());assertEquals(1,control.writes);
  }
}
