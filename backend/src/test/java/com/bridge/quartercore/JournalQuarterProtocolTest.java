package com.bridge.quartercore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.time.*;
import java.util.*;

class JournalQuarterProtocolTest {
  static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"),ZoneOffset.UTC);
  static final ManualQuarterMaintenance.Binding BINDING=new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,RecoveredFixture.DATASET);
  static Book baseline()throws Exception {
    Book before=RecoveredFixture.book();before.sheet("retention_ledger").rows.add(new ArrayList<>(List.of("2026-Q3","2026-06-30T16:00:00Z","COMPLETE","2026-06-30T16:00:01Z","1")));
    return NativeCommitJournal.bootstrap(BINDING,before,NativeCommitJournal.digest(BINDING,before),CLOCK).expected();
  }
  static class Memory implements NarrowSheetsTransport {
    Book book;int writes,failAt;boolean scopeRequired,inScope;int scopes;boolean applyUnknown,tamperAfterWrite,unavailableRead;
    Runnable afterBatch=()->{};
    final List<String> wires=new ArrayList<>();final List<Book> states=new ArrayList<>();
    Memory(Book book){this.book=book;}
    public Scope operation(String label,Root predecessor,Set<String> tabs,Duration budget) {
      if(inScope||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofSeconds(60))>0)throw new Rejected();
      inScope=true;scopes++;return ()->inScope=false;
    }
    public Book snapshot(){if(scopeRequired&&!inScope)throw new Rejected();if(unavailableRead)throw new Unknown();return book.copy();}
    public List<Metadata> control(){return new ArrayList<>(book.metadata.values());}
    public List<List<String>> related(String t){return copyRows(book.sheet(t).rows);}
    public boolean unsupportedStructure(){return false;}
    public void batch(String wire){
      if(scopeRequired&&!inScope)throw new Rejected();writes++;wires.add(wire);
      if(writes==failAt&&!applyUnknown)throw new Unknown();
      book=simulate(book,wire);states.add(book.copy());afterBatch.run();
      if(tamperAfterWrite)book.sheet("accounts").rows.get(1).set(2,"Unjournaled mutation");
      if(writes==failAt)throw new Unknown();
    }
  }
  static JournalQuarterProtocol protocol(Memory memory){return new JournalQuarterProtocol(BINDING,memory,CLOCK,"quarter-test");}
  @Test void fiveJournaledPhasesPreserveBusinessAndEveryNativeExpectedIncludesJournal()throws Exception {
    Memory m=new Memory(baseline());var before=m.book.tables();var p=protocol(m);p.start();
    assertEquals(JournalQuarterProtocol.Status.VERIFIED,p.status());assertEquals(5,m.writes);
    assertEquals(List.of("OPEN","PENDING","APPLIED","OPEN","CLOSED"),m.states.stream().map(b->root(new ArrayList<>(b.metadata.values())).state()).toList());
    assertEquals(6,NativeCommitJournal.verify(BINDING,m.book,CLOCK).sequence());
    for(var state:m.states)assertDoesNotThrow(()->NativeCommitJournal.verify(BINDING,state,CLOCK));
    assertEquals(before.get("auth_identities"),m.book.tables().get("auth_identities"));
    assertEquals(before.get("erasure_ledger"),m.book.tables().get("erasure_ledger"));
    assertEquals(List.of(before.get("commits").get(0),before.get("commits").get(2)),m.book.tables().get("commits"));
    for(int i=1;i<=2;i++){
      var row=m.book.sheet("accounts").rows.get(i);assertEquals(before.get("accounts").get(i).subList(0,4),row.subList(0,4));
      var carry=LifetimeStatistics.decode(row.get(4));assertEquals(i==1?7:-3,carry.formal().totalScore());assertEquals(1,carry.formal().matches());
    }
    assertEquals("2026-11-12T08:00:00Z",m.book.sheet("retention_ledger").rows.get(2).get(3));
    assertEquals(JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT,p.inspect().state());
    assertThrows(RuntimeException.class,p::start);assertEquals(5,m.writes);
  }
  @Test void unknownEveryPhaseBeforeAndAfterApplyStopsWithoutSendingFollowingPhase()throws Exception {
    for(int at=1;at<=5;at++)for(boolean apply:new boolean[]{false,true}) {
      Memory m=new Memory(baseline());m.failAt=at;m.applyUnknown=apply;var p=protocol(m);p.start();
      assertEquals(JournalQuarterProtocol.Status.REVIEW,p.status());assertEquals(at,m.writes);
      assertEquals(apply,p.lastReadMatchesExpected());assertThrows(RuntimeException.class,p::start);assertEquals(at,m.writes);
      var restarted=protocol(new Memory(m.book.copy()));var recovery=restarted.inspect();
      if(at==1&&!apply)assertEquals(JournalQuarterProtocol.RecoveryState.READY_DUE,recovery.state());
      else if(at==5&&apply)assertEquals(JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT,recovery.state());
      else assertEquals(JournalQuarterProtocol.RecoveryState.REVIEW,recovery.state());
      // Inspection does not renew write authority or automatically resume any phase.
      assertEquals(0,restarted.dispatched());
    }
  }
  @Test void staleDuplicatePhaseCannotReplaceWinningNativeState()throws Exception {
    Memory m=new Memory(baseline());Book before=m.book.copy();var p=protocol(m);p.start();
    assertEquals(JournalQuarterProtocol.Status.VERIFIED,p.status());String finalDigest=NativeCommitJournal.digest(BINDING,m.book);
    for(String wire:m.wires)assertThrows(RuntimeException.class,()->simulate(m.book,wire));
    assertEquals(finalDigest,NativeCommitJournal.digest(BINDING,m.book));
    Book claimed=simulate(before,m.wires.get(0));
    assertThrows(RuntimeException.class,()->simulate(claimed,m.wires.get(0)));
  }
  @Test void changedOrMissingJournalNativeDataStopsBeforeNextPhase()throws Exception {
    Memory m=new Memory(baseline());m.tamperAfterWrite=true;var p=protocol(m);p.start();
    assertEquals(1,m.writes);assertEquals(JournalQuarterProtocol.Status.REVIEW,p.status());assertFalse(p.lastReadMatchesExpected());
    assertEquals(JournalQuarterProtocol.RecoveryState.UNVERIFIED,p.inspect().state());
    Memory missing=new Memory(baseline());missing.book.metadata.remove(NativeCommitJournal.ID);var rejected=protocol(missing);rejected.start();assertEquals(0,missing.writes);
  }
  @Test void cleanCurrentSnapshotIsReadOnlyNoSecondQuarterExecution()throws Exception {
    Memory m=new Memory(baseline());protocol(m).start();var fresh=protocol(m);fresh.start();
    assertEquals(JournalQuarterProtocol.Status.REVIEW,fresh.status());assertEquals(0,fresh.dispatched());assertEquals(5,m.writes);
  }
  @Test void scopesBoundEveryFullReadAndDispatchWithoutNesting()throws Exception {
    Memory m=new Memory(baseline());m.scopeRequired=true;var p=protocol(m);
    assertEquals(JournalQuarterProtocol.RecoveryState.READY_DUE,p.inspect().state());p.start();
    assertEquals(JournalQuarterProtocol.Status.VERIFIED,p.status());assertEquals(7,m.scopes);assertFalse(m.inScope);
    assertEquals(JournalQuarterProtocol.RecoveryState.CLEAN_CURRENT,p.inspect().state());assertEquals(8,m.scopes);
  }
  @Test void quarterBoundaryOrClockRollbackStopsFollowingPhase()throws Exception {
    class Time extends Clock {
      Instant value=CLOCK.instant();public ZoneId getZone(){return ZoneOffset.UTC;}
      public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
    }
    for(boolean rollback:new boolean[]{false,true}) {
      var time=new Time();Memory m=new Memory(baseline());
      m.afterBatch=()->time.value=rollback?CLOCK.instant().minusSeconds(1):Instant.parse("2026-12-31T16:00:00Z");
      var p=new JournalQuarterProtocol(BINDING,m,time,"time-test");p.start();
      assertEquals(JournalQuarterProtocol.Status.REVIEW,p.status());assertEquals(1,m.writes);
    }
  }
  @Test void expiredRecordsWithoutLifetimeRuleAreNotDeletedOrClaimed()throws Exception {
    Book book=RecoveredFixture.book();book.sheet("retention_ledger").rows.add(new ArrayList<>(List.of("2026-Q3","2026-06-30T16:00:00Z","COMPLETE","2026-06-30T16:00:01Z","1")));
    var row=book.sheet("envelopes").rows.get(1);var value=(com.fasterxml.jackson.databind.node.ObjectNode)parse(row.get(4));
    ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("match")).remove("lifetime_rule");
    String raw=value.toString(),hash=FinalMatchEnvelope.hash(raw);row.set(4,raw);row.set(1,hash);book.sheet("commits").rows.get(1).set(1,hash);
    book=NativeCommitJournal.bootstrap(BINDING,book,NativeCommitJournal.digest(BINDING,book),CLOCK).expected();
    Memory m=new Memory(book);var p=protocol(m);p.start();assertEquals(0,m.writes);assertEquals(JournalQuarterProtocol.Status.REVIEW,p.status());
  }
  @Test void wrongTargetOrUnavailableReadCannotDispatch()throws Exception {
    Memory m=new Memory(baseline());var wrong=new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,UUID.randomUUID().toString());
    var p=new JournalQuarterProtocol(wrong,m,CLOCK,"other");p.start();assertEquals(0,m.writes);assertEquals(JournalQuarterProtocol.Status.REVIEW,p.status());
    m.unavailableRead=true;var unavailable=protocol(m);unavailable.start();assertEquals(0,m.writes);
  }
}
