package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupSupervisedHostTest {
  static final String CLIENT="123456789-abcdefgh.apps.googleusercontent.com";
  static final String SUBJECT="LOCAL-TEST-OPERATOR-NOT-REAL";
  record Setup(SyntheticCleanupSupervisedHost host,SyntheticCleanupHttpWireTest.Fake transport,AtomicInteger opens){}
  Setup setup(){
    String target=SyntheticCleanupExecutorTest.target();var clock=Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC);
    byte[] raw=SyntheticCleanupRawProtocol.bytes(SyntheticCleanupRawProtocolTest.rawScaffold(target));
    var fake=new SyntheticCleanupHttpWireTest.Fake(raw);var count=new AtomicInteger();
    var settings=new SyntheticCleanupSupervisedHost.Settings(true,"https://example.test",CLIENT,target,
        SyntheticCleanupWorkbook.sha256("https://accounts.google.com\n"+SUBJECT));
    var host=new SyntheticCleanupSupervisedHost(settings,(token,audience,nonce)->{
      assertEquals(CLIENT,audience);return new GoogleCredentialVerifier.Principal("https://accounts.google.com",token.equals("allowed")?SUBJECT:"OTHER");},
      ()->{count.incrementAndGet();return new SyntheticCleanupSupervisedSession(target,raw,new SyntheticCleanupHttpWire(target,raw,fake,clock),clock,()->true);},clock);
    return new Setup(host,fake,count);
  }
  static SyntheticCleanupSupervisedHost.Proof proof(SyntheticCleanupSupervisedHost host){
    return new SyntheticCleanupSupervisedHost.Proof(host.challenge().nonce(),"allowed",true,true);
  }
  @Test void freshAuthenticatedPreviewAndConfirmationForEveryPhase() throws Exception {
    var s=setup();assertEquals(0,s.opens.get());assertEquals(0,s.transport.reads);
    var view=s.host.start(proof(s.host));assertEquals(1,s.opens.get());assertEquals(0,s.transport.writes);
    for(int i=0;i<4;i++){
      String body=new ObjectMapper().findAndRegisterModules().writeValueAsString(view);
      assertFalse(body.contains(SUBJECT));assertFalse(body.contains(SyntheticCleanupExecutorTest.target()));
      assertTrue(body.contains("scopeWarning"));assertEquals(8,view.tables().size());assertFalse(view.changes().isEmpty());
      var result=s.host.confirm(proof(s.host),view.planId(),view.confirmation());
      assertEquals("VERIFIED",result.outcome());assertEquals(i+1,result.verifiedPhases());assertFalse(result.productionCleanupCertified());
      assertEquals(i==3,result.syntheticFunctionalRunVerified());
      if(i<3)view=s.host.next(proof(s.host));
    }
    assertEquals(4,s.transport.writes);assertThrows(SyntheticCleanupSupervisedHost.Failure.class,s.host::challenge);
  }
  @Test void deniedOperatorAndAbsentConsentNeverOpenCredentialFactory(){
    var s=setup();var c=s.host.challenge();
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->s.host.start(new SyntheticCleanupSupervisedHost.Proof(c.nonce(),"bad",true,true)));
    var d=s.host.challenge();assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->s.host.start(new SyntheticCleanupSupervisedHost.Proof(d.nonce(),"allowed",false,true)));
    var e=s.host.challenge();assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->s.host.start(new SyntheticCleanupSupervisedHost.Proof(e.nonce(),"allowed",true,false)));
    assertEquals(0,s.opens.get());assertEquals(0,s.transport.reads);
  }
  @Test void nonceConsumedOnDeniedProofAndCannotReplayStart(){
    var s=setup();var c=proof(s.host);s.host.start(c);
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->s.host.next(c));assertEquals(1,s.opens.get());assertEquals(0,s.transport.writes);
  }
  @Test void genericYesCannotWriteAndPreviousPlanCannotAuthorizeNext(){
    var s=setup();var p=s.host.start(proof(s.host));
    assertEquals("REJECTED",s.host.confirm(proof(s.host),p.planId(),"yes").outcome());assertEquals(0,s.transport.writes);
    s.host.confirm(proof(s.host),p.planId(),p.confirmation());s.host.next(proof(s.host));
    assertEquals("REJECTED",s.host.confirm(proof(s.host),p.planId(),p.confirmation()).outcome());assertEquals(1,s.transport.writes);
  }
  @Test void ambiguousWriteStopsHostAndDoesNotExposeTransportError(){
    var s=setup();var p=s.host.start(proof(s.host));s.transport.server.unknown=true;
    var result=s.host.confirm(proof(s.host),p.planId(),p.confirmation());assertEquals("STOPPED_UNCERTAIN",result.outcome());
    assertFalse(result.toString().contains("private"));assertThrows(SyntheticCleanupSupervisedHost.Failure.class,s.host::challenge);assertEquals(1,s.transport.writes);
  }
  @Test void disabledHostDoesNotLoadCredentialsAndRejectsUnpinnedTarget(){
    var settings=SyntheticCleanupSupervisedHost.Settings.read(n->null);assertFalse(settings.enabled());
    var factory=SyntheticCleanupSupervisedHost.realFactory(settings,()->{fail("must not read credentials");return null;},Clock.systemUTC());
    assertThrows(SyntheticCleanupSupervisedHost.Failure.class,factory::open);
    assertThrows(RuntimeException.class,()->new SyntheticCleanupSupervisedHost.Settings(true,"https://example.test",CLIENT,"unapprovedTarget123","a".repeat(64)));
  }
}
