package com.bridge.quartercore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.JournalQuarterProtocolTest.*;

class JournalQuarterAuthorityTest {
  JournalQuarterAuthority grant(NativeSheetsModel.Book b){return JournalQuarterAuthority.provision(BINDING,b,"quarter-test",UUID.randomUUID().toString(),(target,q,t,o,id)->{
    assertEquals(BINDING,target);assertEquals(JournalSessionAuthority.fullToken(b),t);
  },CLOCK,Duration.ofMinutes(5));}
  @Test void exactFivePhasesAndNoReplay()throws Exception{
    var m=new Memory(baseline());var a=grant(m.book);var p=new JournalQuarterProtocol(BINDING,m,CLOCK,"quarter-test",a);p.start();
    assertEquals(JournalQuarterProtocol.Status.VERIFIED,p.status());assertEquals(JournalQuarterAuthority.State.COMPLETE,a.state());assertEquals(5,a.verifiedPhases());assertEquals(5,m.writes);
    assertThrows(RuntimeException.class,a::nextPlan);assertEquals(5,m.writes);
  }
  @Test void missingApprovalAndUnrelatedPlanCannotDispatch()throws Exception{
    var b=baseline();assertThrows(RuntimeException.class,()->JournalQuarterAuthority.provision(BINDING,b,"quarter-test",UUID.randomUUID().toString(),(t,q,s,o,id)->{throw new NativeSheetsModel.Rejected();},CLOCK,Duration.ofMinutes(5)));
    var a=grant(b);var own=a.nextPlan();var other=grant(b).nextPlan();assertThrows(RuntimeException.class,()->a.beforeDispatch(other));
    a.beforeDispatch(own);assertThrows(RuntimeException.class,()->a.beforeDispatch(own));
  }
  @Test void UnknownEveryPhaseNeverProceedsEvenExactApplied()throws Exception{
    for(int phase=1;phase<=5;phase++)for(boolean applied:new boolean[]{false,true}){
      var m=new Memory(baseline());m.failAt=phase;m.applyUnknown=applied;var a=grant(m.book);
      var p=new JournalQuarterProtocol(BINDING,m,CLOCK,"quarter-test",a);p.start();
      assertEquals(JournalQuarterProtocol.Status.REVIEW,p.status());assertEquals(JournalQuarterAuthority.State.UNKNOWN,a.state());assertEquals(phase,m.writes);assertThrows(RuntimeException.class,a::nextPlan);
    }
  }
  @Test void durationCannotExceedPerOperationWindow()throws Exception{
    var b=baseline();assertThrows(RuntimeException.class,()->JournalQuarterAuthority.provision(BINDING,b,"quarter-test",UUID.randomUUID().toString(),(t,q,s,o,id)->{},CLOCK,Duration.ofMinutes(6)));
  }
  @Test void expiryAndBackwardClockCannotReviveGrant()throws Exception{
    for(boolean backward:new boolean[]{false,true}){
      var b=baseline();var time=new java.util.concurrent.atomic.AtomicReference<>(CLOCK.instant());
      Clock moving=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}};
      var a=JournalQuarterAuthority.provision(BINDING,b,"quarter-test",UUID.randomUUID().toString(),(t,q,s,o,id)->{},moving,Duration.ofMinutes(5));
      var plan=a.nextPlan();time.set(backward?CLOCK.instant().minusSeconds(1):CLOCK.instant().plusSeconds(301));
      assertThrows(RuntimeException.class,()->a.beforeDispatch(plan));assertEquals(JournalQuarterAuthority.State.UNKNOWN,a.state());
      time.set(CLOCK.instant());assertThrows(RuntimeException.class,a::nextPlan);
    }
  }

  @Test void wrongOperationOrOwnerRejectedBeforeDispatch()throws Exception{
    var b=baseline();var a=grant(b);assertThrows(RuntimeException.class,()->a.requireContext(BINDING,b,"quarter-test",UUID.randomUUID().toString()));
    assertEquals(JournalQuarterAuthority.State.UNKNOWN,a.state());
    var other=grant(b);assertThrows(RuntimeException.class,()->new JournalQuarterProtocol(BINDING,new Memory(b),CLOCK,"other-owner",other));
    assertEquals(JournalQuarterAuthority.State.UNKNOWN,other.state());
  }

}
