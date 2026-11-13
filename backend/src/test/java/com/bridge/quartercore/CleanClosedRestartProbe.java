package com.bridge.quartercore;
import java.nio.file.*;import java.time.*;
/** Test-only separate JVM. Synthetic transport, no credentials, API, or production policy. */
public final class CleanClosedRestartProbe {
 public static void main(String[] args)throws Exception{
  var book=JournalRestartProbe.load(Path.of(args[0]));var clock=Clock.fixed(Instant.parse(args[1]),ZoneOffset.UTC);var b=PlannedMaintenanceStartupFactoryTest.B;
  var m=new JournaledNativeWriterTest.Memory(book);var gate=new com.bridge.persistence.QuarterMaintenanceGate(true,clock);var stage=new com.bridge.persistence.StagedNativeBackend(gate);
  var policy=new CleanClosedRestartAssembly.Policy(b,"test-native-source","test-explicit-restart",new QuarterRetentionPolicy.Configuration(b,QuarterRetentionPolicy.VERSION,"Asia/Taipei",QuarterRetentionPolicy.RULES,"test-explicit-quarter"));
  var restart=new CleanClosedRestartAssembly(policy,(p,c)->{if(!p.equals(policy)||c.state()==DurableCleanHandoffAssessment.State.BLOCKED)throw new AssertionError();},m::snapshot,PlannedMaintenanceStartupFactoryTest.channels(m),gate,stage,clock);
  restart.resume();if(stage.lifecycle().startupWaiting()||gate.isOpen())throw new AssertionError("admission opened before coordinator verification");
  var coordinator=new com.bridge.persistence.QuarterMaintenanceCoordinator(gate,stage.lifecycle(),clock,()->true,()->{});coordinator.tick();if(!gate.isOpen()||!stage.enabled())throw new AssertionError("not resumed");
  int expected=Integer.parseInt(args[2]);if(m.writes!=expected)throw new AssertionError("unexpected batch count "+m.writes);stage.close();System.out.println("SYNTHETIC_RESTART_VERIFIED");
 }
}
