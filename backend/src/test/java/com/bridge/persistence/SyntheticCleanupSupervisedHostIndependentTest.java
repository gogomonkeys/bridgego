package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent host checks: fake identity verifier and fake transport only. */
class SyntheticCleanupSupervisedHostIndependentTest {
  static final class MovingClock extends Clock {
    Instant now=SyntheticCleanupExecutorTest.NOW;
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}
    public Instant instant(){return now;}
  }
  static SyntheticCleanupSupervisedHost.Proof proof(SyntheticCleanupSupervisedHost h){return SyntheticCleanupSupervisedHostTest.proof(h);}
  @Test void rejectedConfirmationConsumesNonceButCannotWrite(){
    var s=new SyntheticCleanupSupervisedHostTest().setup();var v=s.host().start(proof(s.host()));
    var p=proof(s.host());
    assertEquals("REJECTED",s.host().confirm(p,v.planId(),"yes").outcome());
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->s.host().confirm(p,v.planId(),v.confirmation()));
    assertEquals(0,s.transport().writes);
    assertEquals("VERIFIED",s.host().confirm(proof(s.host()),v.planId(),v.confirmation()).outcome());
    assertEquals(1,s.transport().writes);
  }
  @Test void missingFreezeCannotConfirmPendingPlan(){
    var s=new SyntheticCleanupSupervisedHostTest().setup();var v=s.host().start(proof(s.host()));
    var nonce=s.host().challenge().nonce();
    var p=new SyntheticCleanupSupervisedHost.Proof(nonce,"allowed",true,false);
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->s.host().confirm(p,v.planId(),v.confirmation()));
    assertEquals(0,s.transport().writes);
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->s.host().confirm(new SyntheticCleanupSupervisedHost.Proof(nonce,"allowed",true,true),v.planId(),v.confirmation()));
    assertEquals(0,s.transport().writes);
  }
  @Test void expiryDuringVerificationNeverOpensFactory(){
    var clock=new MovingClock();var opens=new AtomicInteger();
    var settings=settings();
    var h=new SyntheticCleanupSupervisedHost(settings,(token,aud,nonce)->{
      clock.now=clock.now.plusSeconds(120);
      return new GoogleCredentialVerifier.Principal("https://accounts.google.com",SyntheticCleanupSupervisedHostTest.SUBJECT);
    },()->{opens.incrementAndGet();throw new AssertionError("must not open");},clock);
    var p=proof(h);assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->h.start(p));assertEquals(0,opens.get());
  }
  @Test void failedInitialFactoryCannotBeRetriedOrExposeUnderlyingException(){
    var opens=new AtomicInteger();
    var h=new SyntheticCleanupSupervisedHost(settings(),(token,aud,nonce)->new GoogleCredentialVerifier.Principal("https://accounts.google.com",SyntheticCleanupSupervisedHostTest.SUBJECT),
      ()->{opens.incrementAndGet();throw new IllegalStateException("PRIVATE_SECRET_SENTINEL");},Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC));
    var p=proof(h);var failure=assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->h.start(p));
    assertFalse(failure.toString().contains("PRIVATE_SECRET_SENTINEL"));assertNull(failure.getCause());
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,h::challenge);
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->h.start(p));assertEquals(1,opens.get());
  }
  @Test void enabledSettingsRejectArbitraryOriginComponents(){
    for(String origin:new String[]{"http://example.test","https://user@example.test","https://example.test/path","https://example.test?x=1","https://example.test#x","https://example.test:0"}){
      assertThrows(RuntimeException.class,()->new SyntheticCleanupSupervisedHost.Settings(true,origin,SyntheticCleanupSupervisedHostTest.CLIENT,SyntheticCleanupExecutorTest.target(),"a".repeat(64)));
    }
  }
  private static SyntheticCleanupSupervisedHost.Settings settings(){
    return new SyntheticCleanupSupervisedHost.Settings(true,"https://example.test",SyntheticCleanupSupervisedHostTest.CLIENT,SyntheticCleanupExecutorTest.target(),
      SyntheticCleanupWorkbook.sha256("https://accounts.google.com\n"+SyntheticCleanupSupervisedHostTest.SUBJECT));
  }
}
