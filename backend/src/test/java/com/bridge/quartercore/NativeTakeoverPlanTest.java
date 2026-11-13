package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class NativeTakeoverPlanTest {
  static Book open()throws Exception {var m=JournaledNativeWriterTest.initial();var t=new JournaledNativeTransport(m,JournaledNativeWriterTest.BINDING,JournaledNativeWriterTest.CLOCK);try(var s=JournaledNativeWriterTest.service(t)){s.start();return m.b.copy();}}
  @Test void fixedSlotLetsOnlyOldWriteOrNewClaimWinNotBoth()throws Exception {
    Book before=open();var binding=JournaledNativeWriterTest.BINDING;var clock=JournaledNativeWriterTest.CLOCK;
    var takeover=NativeTakeoverPlan.create(binding,before,"new-takeover-owner",clock);Root old=root(new ArrayList<>(before.metadata.values()));
    Root next=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,old.owner(),"OPEN","OLD-PROFILE-LATE",hash("old"));
    var oldPlan=NativeCommitJournal.append(binding,before,QuarterPlan.phaseWire(old,next,List.of(update(before.sheet("accounts").id,1,2,2,3,List.of(List.of("Old profile"))))),clock);
    Book newFirst=simulate(before,takeover.wire());assertThrows(Rejected.class,()->simulate(newFirst,oldPlan.wire()));assertThrows(Rejected.class,()->simulate(newFirst,takeover.wire()));
    Book oldFirst=simulate(before,oldPlan.wire());assertThrows(Rejected.class,()->simulate(oldFirst,takeover.wire()));
    assertEquals(before.tables(),newFirst.tables());assertEquals("new-takeover-owner",root(new ArrayList<>(newFirst.metadata.values())).owner());
    NativeCommitJournal.verify(binding,newFirst,clock);NativeCommitJournal.verify(binding,oldFirst,clock);
  }
  @Test void refusesClosedSameOwnerAndOverdueWithoutMutations()throws Exception {
    Book before=open();var binding=JournaledNativeWriterTest.BINDING;var clock=JournaledNativeWriterTest.CLOCK;Root old=root(new ArrayList<>(before.metadata.values()));
    assertThrows(Rejected.class,()->NativeTakeoverPlan.create(binding,before,old.owner(),clock));
    assertThrows(Rejected.class,()->NativeTakeoverPlan.create(binding,JournaledNativeWriterTest.initial().b,"new-owner",clock));
    var future=java.time.Clock.fixed(java.time.Instant.parse("2027-01-01T00:00:00Z"),java.time.ZoneOffset.UTC);
    assertThrows(Rejected.class,()->NativeTakeoverPlan.create(binding,before,"new-owner",future));
  }
}
