package com.bridge.persistence;

import com.bridge.room.GoogleCredentialVerifier;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCleanupDiagnosticTest {
  static final String PRIVATE_SUBJECT="PRIVATE_TEST_ID_NEVER_LOG";
  record Setup(SyntheticCleanupSupervisedHost host,SyntheticCleanupHttpWireTest.Fake fake,List<SyntheticCleanupSupervisedHost.Diagnostic> events){}
  Setup setup(boolean denied,boolean factoryFailure){
    String target=SyntheticCleanupExecutorTest.target();var clock=Clock.fixed(SyntheticCleanupExecutorTest.NOW,ZoneOffset.UTC);
    byte[] raw=SyntheticCleanupRawProtocol.bytes(SyntheticCleanupRawProtocolTest.rawScaffold(target));
    var fake=new SyntheticCleanupHttpWireTest.Fake(raw);List<SyntheticCleanupSupervisedHost.Diagnostic> events=new ArrayList<>();
    var settings=new SyntheticCleanupSupervisedHost.Settings(true,"https://example.test",SyntheticCleanupSupervisedHostTest.CLIENT,target,
        SyntheticCleanupWorkbook.sha256("https://accounts.google.com\n"+PRIVATE_SUBJECT));
    var host=new SyntheticCleanupSupervisedHost(settings,(token,audience,nonce)->{
      if(denied)throw new IllegalStateException("secret "+PRIVATE_SUBJECT);
      return new GoogleCredentialVerifier.Principal("https://accounts.google.com",PRIVATE_SUBJECT);},
      ()->{if(factoryFailure)throw new IllegalStateException("secret "+PRIVATE_SUBJECT);
        return new SyntheticCleanupSupervisedSession(target,raw,new SyntheticCleanupHttpWire(target,raw,fake,clock),clock,()->true);},clock,events::add);
    return new Setup(host,fake,events);
  }
  @Test void previewEventsHaveNoExecutionOrIdentifiers(){
    var s=setup(false,false);s.host.start(SyntheticCleanupSupervisedHostTest.proof(s.host));
    assertEquals(List.of("AUTHENTICATION_STARTED","AUTHENTICATION_SUCCESS","FACTORY_STARTED","FACTORY_SUCCESS","PREVIEW_STARTED","PREVIEW_SUCCESS"),s.events.stream().map(Enum::name).toList());
    assertEquals(0,s.fake.writes);assertFalse(s.events.toString().contains(PRIVATE_SUBJECT));
  }
  @Test void authAndFactoryFailureHaveSeparateRedactedStages(){
    var denied=setup(true,false);var e=assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->denied.host.start(SyntheticCleanupSupervisedHostTest.proof(denied.host)));
    assertFalse(e.toString().contains(PRIVATE_SUBJECT));assertEquals(List.of("AUTHENTICATION_STARTED","AUTHENTICATION_FAILED"),denied.events.stream().map(Enum::name).toList());
    var failed=setup(false,true);assertThrows(SyntheticCleanupSupervisedHost.Failure.class,()->failed.host.start(SyntheticCleanupSupervisedHostTest.proof(failed.host)));
    assertTrue(failed.events.contains(SyntheticCleanupSupervisedHost.Diagnostic.FACTORY_FAILED));assertFalse(failed.events.contains(SyntheticCleanupSupervisedHost.Diagnostic.PREVIEW_STARTED));
  }
  @Test void wrongConfirmationDoesNotClaimDispatchAndAmbiguousResultCannotClaimSuccess(){
    var s=setup(false,false);var p=s.host.start(SyntheticCleanupSupervisedHostTest.proof(s.host));
    s.host.confirm(SyntheticCleanupSupervisedHostTest.proof(s.host),p.planId(),"wrong");
    assertTrue(s.events.contains(SyntheticCleanupSupervisedHost.Diagnostic.CONFIRMATION_REJECTED));
    assertFalse(s.events.contains(SyntheticCleanupSupervisedHost.Diagnostic.EXECUTION_STARTED));assertEquals(0,s.fake.writes);
    s.fake.server.unknown=true;s.host.confirm(SyntheticCleanupSupervisedHostTest.proof(s.host),p.planId(),p.confirmation());
    assertTrue(s.events.contains(SyntheticCleanupSupervisedHost.Diagnostic.EXECUTION_UNVERIFIED));
    assertFalse(s.events.contains(SyntheticCleanupSupervisedHost.Diagnostic.PHASE_VERIFIED));assertEquals(1,s.fake.writes);
  }
}
