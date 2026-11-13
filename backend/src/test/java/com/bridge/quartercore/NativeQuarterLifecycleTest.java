package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.time.*;
import java.util.*;
import com.bridge.persistence.*;
import com.bridge.persistence.QuarterMaintenanceGate;
class NativeQuarterLifecycleTest {
  static final Clock CLOCK=JournalQuarterProtocolTest.CLOCK;
  static final ManualQuarterMaintenance.Binding B=JournalQuarterProtocolTest.BINDING;
  static final QuarterNativeControl.Target T=new QuarterNativeControl.Target(B.spreadsheetId(),B.datasetId(),B.purpose());
  static final Instant Q=QuarterlyRetention.currentQuarterStart(CLOCK.instant());
  static final class Writer implements NativeQuarterLifecycle.WriterLifecycle {
    final JournalQuarterProtocolTest.Memory memory;boolean open=true,retired,maintenanceClosed;int closes,reopens,reads;String fail="";
    Writer()throws Exception {memory=new JournalQuarterProtocolTest.Memory(JournalQuarterProtocolTest.baseline());claim("writer");}
    void claim(String owner) {Root r=root(new ArrayList<>(memory.book.metadata.values()));Root next=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,owner,"OPEN","CLAIM-"+owner,hash(owner));memory.book=NativeCommitJournal.append(B,memory.book,QuarterPlan.phaseWire(r,next,List.of()),CLOCK).expected();}
    public Book lifecycleSnapshot(){reads++;return memory.book.copy();}
    public boolean lifecycleOpen(){return open;}
    public Book cleanCloseForQuarter(){closes++;if(fail.equals("close"))throw new Unknown();Root r=root(new ArrayList<>(memory.book.metadata.values()));Root next=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,r.owner(),"CLOSED","CLOSE-"+r.owner(),hash(r.owner()));memory.book=NativeCommitJournal.append(B,memory.book,QuarterPlan.phaseWire(r,next,List.of()),CLOCK).expected();open=false;return memory.book.copy();}
    public void retireClosedWriter(){retired=true;}
    public Book reopenAfterVerifiedQuarter(Book exact){reopens++;assertTrue(retired);assertTrue(maintenanceClosed);assertEquals(NativeQuarterLifecycle.token(exact),NativeQuarterLifecycle.token(memory.book));if(fail.equals("reopen"))throw new Unknown();claim("writer-next");open=true;return memory.book.copy();}
  }
  static QuarterMaintenanceGate gate(){var g=new QuarterMaintenanceGate(true,CLOCK);g.openVerified(Q);return g;}
  NativeQuarterLifecycle provider(Writer w,QuarterMaintenanceGate g){return new NativeQuarterLifecycle(T,w,g,(t,token)->{},(t,b,op)->{
    assertTrue(w.retired);assertEquals(NativeQuarterLifecycle.token(b),NativeQuarterLifecycle.token(w.memory.book));
    return new NativeQuarterLifecycle.Session(w.memory,JournalQuarterAuthority.provision(B,b,"q-"+op,op,(binding,quarter,baseline,owner,id)->{},CLOCK,Duration.ofMinutes(5)),()->w.maintenanceClosed=true);
  },CLOCK);}
  @Test void openInspectionDoesNotCloseAndLifecycleUsesSameSourceAcrossSevenWrites()throws Exception {
    Writer w=new Writer();var g=gate();var p=provider(w,g);var before=p.inspect(Q);
    assertEquals(QuarterMaintenanceCoordinator.Phase.DUE,before.phase());assertEquals(0,w.closes);assertEquals(1,w.reads);
    g.beginDrain();assertTrue(g.beginExclusive());String op=UUID.randomUUID().toString();p.executeOnce(Q,before,op);
    assertEquals(1,w.closes);assertEquals(1,w.reopens);assertEquals(5,w.memory.writes);
    var after=p.inspect(Q);assertEquals(QuarterMaintenanceCoordinator.Phase.COMPLETE,after.phase());assertEquals(op,after.completedOperation());
    assertThrows(RuntimeException.class,()->p.executeOnce(Q,before,op));
  }
  @Test void unknownCloseEveryPhaseAndReopenNeverReportsCompleteOrRetries()throws Exception {
    for(int failure=0;failure<7;failure++) {
      Writer w=new Writer();if(failure==0)w.fail="close";else if(failure==6)w.fail="reopen";else {w.memory.failAt=failure;w.memory.applyUnknown=true;}
      var g=gate();var p=provider(w,g);var expected=p.inspect(Q);g.beginDrain();g.beginExclusive();String op=UUID.randomUUID().toString();
      assertThrows(RuntimeException.class,()->p.executeOnce(Q,expected,op));
      assertEquals(QuarterMaintenanceCoordinator.Phase.UNKNOWN,p.inspect(Q).phase());int count=w.memory.writes;
      assertThrows(RuntimeException.class,()->p.executeOnce(Q,expected,op));assertEquals(count,w.memory.writes);
      assertFalse(g.isOpen());
    }
  }
  @Test void missingAuthorityAndNonexclusiveStateDoNotCloseWriter()throws Exception {
    Writer w=new Writer();var g=gate();var p=provider(w,g);var observation=p.inspect(Q);
    assertThrows(RuntimeException.class,()->p.executeOnce(Q,observation,UUID.randomUUID().toString()));assertEquals(0,w.closes);
    var denied=new NativeQuarterLifecycle(T,w,g,QuarterNativeControl.OwnershipVerifier.unavailable(),null,CLOCK);
    assertEquals(QuarterMaintenanceCoordinator.Phase.UNKNOWN,denied.inspect(Q).phase());assertEquals(0,w.closes);
  }
}
