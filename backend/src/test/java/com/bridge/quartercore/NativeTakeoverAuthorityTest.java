package com.bridge.quartercore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.JournalSessionAuthorityTest.*;
class NativeTakeoverAuthorityTest {
  static Book openBook()throws Exception{return claim(clean()).expected();}
  NativeTakeoverAuthority authority(Book before){return NativeTakeoverAuthority.provision(BINDING,before,"next-owner",(b,t,o,n)->{},CLOCK,Duration.ofMinutes(5));}
  @Test void exactControlOnlyClaimThroughBoundedChannel()throws Exception{
    Book before=openBook();var a=authority(before);var closed=new AtomicInteger();
    var memory=new JournalQuarterProtocolTest.Memory(before){@Override public void batch(String wire){var d=a.beforeDispatch(BINDING.path()+":batchUpdate",wire);super.batch(wire);d.acknowledged();}};
    try(var handle=new JournalQuarterGoogleFactory.Handle(memory,closed::incrementAndGet,a,BINDING)){
      a.execute(handle.transport());assertEquals(NativeTakeoverAuthority.State.VERIFIED,a.state());assertEquals(before.tables(),a.verifiedBook().tables());assertEquals(1,memory.writes);
      assertThrows(RuntimeException.class,()->a.execute(handle.transport()));assertEquals(1,memory.writes);
    }assertEquals(1,closed.get());assertEquals(JournalSessionAuthority.fullToken(memory.book),a.verifiedOwnershipToken());
  }
  @Test void appliedUnknownRemainsTerminalNoRetry()throws Exception{
    var before=openBook();var a=authority(before);var m=new JournalQuarterProtocolTest.Memory(before);m.failAt=1;m.applyUnknown=true;
    assertThrows(RuntimeException.class,()->a.execute(m));assertEquals(NativeTakeoverAuthority.State.UNKNOWN,a.state());assertEquals(1,m.writes);
    assertThrows(RuntimeException.class,a::verifiedBook);assertThrows(RuntimeException.class,a::verifiedOwnershipToken);assertThrows(RuntimeException.class,()->a.execute(m));assertEquals(1,m.writes);
  }
  @Test void unsupportedOrMissingEvidenceRejectsBeforeCredentials()throws Exception{
    Book before=openBook();assertThrows(RuntimeException.class,()->NativeTakeoverAuthority.provision(BINDING,before,"next-owner",(b,t,o,n)->{throw new Rejected();},CLOCK,Duration.ofMinutes(5)));
    var reads=new AtomicInteger();var cfg=new JournalGoogleFactory.Configuration(true,BINDING.spreadsheetId(),BINDING.datasetId(),BINDING.purpose(),BINDING.serviceAccountIssuer(),()->{reads.incrementAndGet();throw new AssertionError();});
    assertThrows(RuntimeException.class,()->NativeTakeoverGoogleFactory.open(cfg,null));assertEquals(0,reads.get());
    assertThrows(RuntimeException.class,()->authority(clean()));
  }
  @Test void lateWriterWinsThenFixedTakeoverRefusesWithoutPost()throws Exception{
    Book before=openBook();var a=authority(before);Book late=profile(before,"late").expected();var m=new JournalQuarterProtocolTest.Memory(late);
    assertThrows(RuntimeException.class,()->a.execute(m));assertEquals(0,m.writes);assertEquals(NativeTakeoverAuthority.State.UNKNOWN,a.state());
  }
}
