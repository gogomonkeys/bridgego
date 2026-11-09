package com.bridge.persistence;
import java.time.*;
import java.util.concurrent.atomic.*;
import com.bridge.room.GoogleCredentialVerifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SyntheticCleanupManualQuarterHostTest {
  static final String CLIENT="123456789-abcdefgh.apps.googleusercontent.com",SUBJECT="LOCAL-OPERATOR";
  record Setup(SyntheticCleanupManualQuarterHost host,SyntheticCleanupManualQuarterTrialTest.Wire wire,AtomicInteger opened,AtomicReference<Instant> now){}
  Setup setup(){
    var wire=new SyntheticCleanupManualQuarterTrialTest.Wire();var opened=new AtomicInteger();var now=new AtomicReference<>(wire.fixture.clock.instant());
    Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
    var settings=new SyntheticCleanupManualQuarterHost.Settings(true,"https://example.test",CLIENT,SyntheticCleanupExecutorTest.target(),SyntheticCleanupWorkbook.sha256("https://accounts.google.com\n"+SUBJECT));
    var host=new SyntheticCleanupManualQuarterHost(settings,(token,aud,nonce)->new GoogleCredentialVerifier.Principal("https://accounts.google.com",token.equals("allowed")?SUBJECT:"other"),()->{opened.incrementAndGet();return new SyntheticCleanupManualQuarterTrial(wire.input(),wire,clock,()->true);},clock);
    return new Setup(host,wire,opened,now);
  }
  static SyntheticCleanupManualQuarterHost.Proof proof(SyntheticCleanupManualQuarterHost h){return new SyntheticCleanupManualQuarterHost.Proof(h.challenge().nonce(),"allowed",true,true);}
  @Test void freshOperatorProofAndPlanBoundCheckboxExecuteActualCoreExactlyOnce(){
    var s=setup();var p=proof(s.host);var view=s.host.preview(p);assertEquals(1,view.removeMatches());assertEquals(2,view.keepMatches());
    assertThrows(SyntheticCleanupManualQuarterHost.Failure.class,()->s.host.confirm(p,view.planId(),true));assertEquals(0,s.wire.posts);
    assertEquals("REJECTED_NO_WRITE",s.host.confirm(proof(s.host),view.planId(),false).outcome());
    assertEquals("REJECTED_NO_WRITE",s.host.confirm(proof(s.host),"wrong",true).outcome());assertEquals(0,s.wire.posts);
    var result=s.host.confirm(proof(s.host),view.planId(),true);assertEquals("SYNTHETIC_QUARTER_VERIFIED",result.outcome());assertTrue(result.syntheticQuarterVerified());assertFalse(result.productionCleanupCertified());assertEquals(2,s.wire.posts);s.wire.fixture.verify();
    assertThrows(SyntheticCleanupManualQuarterHost.Failure.class,s.host::challenge);
  }
  @Test void expiredNeverExecutedPlanCanRefreshWithoutReopeningSession(){
    var s=setup();var old=s.host.preview(proof(s.host));s.now.set(old.expiresAt());
    assertEquals("PREVIEW_EXPIRED_NO_WRITE",s.host.confirm(proof(s.host),old.planId(),true).outcome());assertEquals(0,s.wire.posts);
    var fresh=s.host.preview(proof(s.host));assertNotEquals(old.planId(),fresh.planId());assertEquals(1,s.opened.get());
    assertEquals("REJECTED_NO_WRITE",s.host.confirm(proof(s.host),old.planId(),true).outcome());
    assertEquals("SYNTHETIC_QUARTER_VERIFIED",s.host.confirm(proof(s.host),fresh.planId(),true).outcome());
  }
  @Test void unknownWriteCannotRefreshOrRetry(){var s=setup();var p=s.host.preview(proof(s.host));s.wire.unavailableAfterWrite=true;
    assertEquals("STOPPED_RESULT_REQUIRES_REVIEW",s.host.confirm(proof(s.host),p.planId(),true).outcome());assertEquals(1,s.wire.posts);assertThrows(SyntheticCleanupManualQuarterHost.Failure.class,s.host::challenge);assertEquals(1,s.opened.get());}
  @Test void unauthenticatedOrNoFreezeNeverOpensCredentials(){var s=setup();var c=s.host.challenge();
    assertThrows(SyntheticCleanupManualQuarterHost.Failure.class,()->s.host.preview(new SyntheticCleanupManualQuarterHost.Proof(c.nonce(),"bad",true,true)));
    var d=s.host.challenge();assertThrows(SyntheticCleanupManualQuarterHost.Failure.class,()->s.host.preview(new SyntheticCleanupManualQuarterHost.Proof(d.nonce(),"allowed",true,false)));assertEquals(0,s.opened.get());assertEquals(0,s.wire.reads);}
}
