package com.bridge.quartercore;
import org.junit.jupiter.api.Test;import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;import static com.bridge.quartercore.NativeSheetsModel.*;import static com.bridge.quartercore.NarrowQuarterPoc.*;
class FixedECloseWaitTest {
 static Book claim(Book base){Root r=root(new ArrayList<>(base.metadata.values()));String owner="w-"+UUID.randomUUID();Root n=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,owner,"OPEN","CLAIM-"+owner,hash(owner));return NativeCommitJournal.append(FixedERestartClock.binding().manualBinding(),base,QuarterPlan.phaseWire(r,n,List.of()),FixedERestartClockSafetyTest.BUSINESS).expected();}
 static Book close(Book open){Root r=root(new ArrayList<>(open.metadata.values()));Root n=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,r.owner(),"CLOSED","CLOSE-"+r.owner(),hash(r.owner()));return NativeCommitJournal.append(FixedERestartClock.binding().manualBinding(),open,QuarterPlan.phaseWire(r,n,List.of()),FixedERestartClockSafetyTest.BUSINESS).expected();}
 @Test void legalOverlapWaitsOnlyReadsUntilClose()throws Exception{Book base=FixedERestartHttpSafetyTest.baseline(),open=claim(base),closed=close(open);var bundle=FixedEBaselineBundle.isolatedFixture(base);var calls=new AtomicInteger();var sleeps=new AtomicInteger();var nano=new AtomicLong();
  Book result=FixedECloseWait.read(bundle,()->calls.incrementAndGet()==1?open:closed,nano::get,()->{sleeps.incrementAndGet();nano.addAndGet(15_000_000_000L);});assertEquals(2,calls.get());assertEquals(1,sleeps.get());assertEquals(JournalSessionAuthority.fullToken(closed),JournalSessionAuthority.fullToken(result));assertEquals(1,bundle.completedPairs(result));
 }
 @Test void permanentlyOpenIsBoundedAndLateReadCannotExtendBudget()throws Exception{Book base=FixedERestartHttpSafetyTest.baseline(),open=claim(base);var bundle=FixedEBaselineBundle.isolatedFixture(base);var reads=new AtomicInteger();var nano=new AtomicLong();assertThrows(RuntimeException.class,()->FixedECloseWait.read(bundle,()->{reads.incrementAndGet();return open;},nano::get,()->nano.addAndGet(15_000_000_000L)));assertEquals(13,reads.get());assertEquals(180_000_000_000L,nano.get());
  nano.set(0);assertThrows(RuntimeException.class,()->FixedECloseWait.read(bundle,()->{nano.set(181_000_000_000L);return base;},nano::get,()->fail("must not sleep")));
 }
 @Test void invalidIncompleteAndReadUnknownNeverWait()throws Exception{Book base=FixedERestartHttpSafetyTest.baseline(),open=claim(base);var bundle=FixedEBaselineBundle.isolatedFixture(base);Book tamper=open.copy();tamper.sheet("accounts").rows.get(1).set(2,"changed");Root r=root(new ArrayList<>(open.metadata.values()));Root pending=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,r.owner(),"PENDING","local-pending",hash("p"));Book p=NativeCommitJournal.append(FixedERestartClock.binding().manualBinding(),open,QuarterPlan.phaseWire(r,pending,List.of()),FixedERestartClockSafetyTest.BUSINESS).expected();
  for(Book b:List.of(tamper,p))assertThrows(RuntimeException.class,()->FixedECloseWait.read(bundle,()->b,()->0,()->fail("must not sleep")));
  assertThrows(Unknown.class,()->FixedECloseWait.read(bundle,()->{throw new Unknown();},()->0,()->fail("must not sleep")));
 }
}
