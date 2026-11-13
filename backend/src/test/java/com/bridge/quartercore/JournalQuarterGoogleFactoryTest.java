package com.bridge.quartercore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.JournalQuarterProtocolTest.*;

class JournalQuarterGoogleFactoryTest {
  static final GoogleSheetsBinding GOOGLE=JournalSessionAuthorityTest.BINDING;
  JournalQuarterAuthority grant(Book baseline){return JournalQuarterAuthority.provision(GOOGLE.manualBinding(),baseline,"quarter-test",UUID.randomUUID().toString(),(b,q,t,o,id)->{},CLOCK,Duration.ofMinutes(5));}
  static class FakeHttp extends Memory {
    final QuarterAuthorityChannel channel;boolean loseAck;
    FakeHttp(Book book,QuarterAuthorityChannel channel){super(book);this.channel=channel;}
    @Override public void batch(String wire){
      var dispatch=channel.beforeDispatch(GOOGLE.path()+":batchUpdate",wire);
      super.batch(wire);
      if(loseAck){dispatch.unknown();throw new Unknown();}dispatch.acknowledged();
    }
  }
  @Test void fiveExactPlansUseSharedChannelAndFinalReadonlyRemainsAvailable()throws Exception{
    Book b=baseline();var grant=grant(b);var channel=new QuarterAuthorityChannel(GOOGLE,grant);var fake=new FakeHttp(b,channel);var closed=new AtomicInteger();
    try(var handle=new JournalQuarterGoogleFactory.Handle(fake,closed::incrementAndGet,channel,GOOGLE)){
      var protocol=new JournalQuarterProtocol(GOOGLE.manualBinding(),handle.transport(),CLOCK,"quarter-test",grant);protocol.start();
      assertEquals(JournalQuarterProtocol.Status.VERIFIED,protocol.status());assertEquals(5,fake.writes);assertEquals(JournalQuarterAuthority.State.COMPLETE,grant.state());
      assertNotNull(handle.transport().snapshot());assertThrows(RuntimeException.class,()->handle.transport().batch("{}"));assertEquals(5,fake.writes);
    }
    assertEquals(1,closed.get());assertEquals(JournalQuarterAuthority.State.COMPLETE,grant.state());
  }
  @Test void wrongTargetAndWireRejectedBeforeFirstPostAndAckAloneDoesNotAdvance()throws Exception{
    Book b=baseline();var a=grant(b);var c=new QuarterAuthorityChannel(GOOGLE,a);var plan=a.nextPlan();a.beforeDispatch(plan);c.arm(plan);
    assertThrows(RuntimeException.class,()->c.beforeDispatch("/v4/spreadsheets/foreign:batchUpdate",plan.wire()));
    assertThrows(RuntimeException.class,()->c.beforeDispatch(GOOGLE.path()+":batchUpdate",plan.wire()+" "));
    var d=c.beforeDispatch(GOOGLE.path()+":batchUpdate",plan.wire());d.acknowledged();assertEquals(0,a.verifiedPhases());assertEquals(JournalQuarterAuthority.State.IN_FLIGHT,a.state());
    assertThrows(RuntimeException.class,()->c.beforeDispatch(GOOGLE.path()+":batchUpdate",plan.wire()));
  }
  @Test void appliedUnknownClosesProviderNoRetry()throws Exception{
    Book b=baseline();var a=grant(b);var c=new QuarterAuthorityChannel(GOOGLE,a);var fake=new FakeHttp(b,c);fake.loseAck=true;var closed=new AtomicInteger();
    var handle=new JournalQuarterGoogleFactory.Handle(fake,closed::incrementAndGet,c,GOOGLE);
    var p=new JournalQuarterProtocol(GOOGLE.manualBinding(),handle.transport(),CLOCK,"quarter-test",a);p.start();
    assertEquals(JournalQuarterProtocol.Status.REVIEW,p.status());assertEquals(1,fake.writes);assertEquals(1,closed.get());assertEquals(JournalQuarterAuthority.State.UNKNOWN,a.state());
    assertThrows(RuntimeException.class,handle::transport);assertThrows(RuntimeException.class,p::start);handle.close();assertEquals(1,closed.get());
  }
  @Test void missingDisabledOrWrongTargetGrantBeforeCredentialRead()throws Exception{
    var reads=new AtomicInteger();QuarterGoogleFactory.CredentialSource source=()->{reads.incrementAndGet();throw new AssertionError();};
    var off=new JournalGoogleFactory.Configuration(GOOGLE.spreadsheetId(),GOOGLE.datasetId(),GOOGLE.purpose(),GOOGLE.serviceAccountIssuer(),source);
    assertThrows(RuntimeException.class,()->JournalQuarterGoogleFactory.open(off,grant(baseline())));
    var on=new JournalGoogleFactory.Configuration(true,GOOGLE.spreadsheetId(),GOOGLE.datasetId(),GOOGLE.purpose(),GOOGLE.serviceAccountIssuer(),source);
    assertThrows(RuntimeException.class,()->JournalQuarterGoogleFactory.open(on,null));
    var wrong=new JournalGoogleFactory.Configuration(true,GOOGLE.spreadsheetId(),UUID.randomUUID().toString(),GOOGLE.purpose(),GOOGLE.serviceAccountIssuer(),source);
    assertThrows(RuntimeException.class,()->JournalQuarterGoogleFactory.open(wrong,grant(baseline())));assertEquals(0,reads.get());
  }
}
