package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
class SyntheticCOrdinaryCheckTest {
  static final Clock CLOCK=JournalQuarterProtocolTest.CLOCK;
  static final GoogleSheetsBinding C=new GoogleSheetsBinding(SyntheticCOrdinaryCheck.SHEET,SyntheticCOrdinaryCheck.DATASET,"bridgego-restricted-test",SyntheticCRelease.ISSUER);
  record Prepared(JournalQuarterProtocolTest.Memory memory,JournalQuarterAuthority completed){}
  static Prepared prepare()throws Exception {
    Book b=RecoveredFixture.book();b.sheet("dataset").rows.set(1,new ArrayList<>(List.of(C.purpose(),C.datasetId())));
    Root old=root(new ArrayList<>(b.metadata.values()));Root next=new Root(C.datasetId(),old.epoch(),old.anchor(),old.nextSheet(),old.next(),old.owner(),old.state(),old.operation(),old.hash());b.metadata.put(1,new Metadata(1,ROOT_KEY,rootText(next),null));
    b.sheet("retention_ledger").rows.add(new ArrayList<>(List.of("2026-Q3","2026-06-30T16:00:00Z","COMPLETE","2026-06-30T16:00:01Z","1")));
    b=NativeCommitJournal.bootstrap(C.manualBinding(),b,NativeCommitJournal.digest(C.manualBinding(),b),CLOCK).expected();
    var memory=new JournalQuarterProtocolTest.Memory(b);String id=UUID.randomUUID().toString(),owner="c-"+UUID.randomUUID();
    var grant=JournalQuarterAuthority.provision(C.manualBinding(),b,owner,id,(t,q,token,o,op)->{},CLOCK,Duration.ofMinutes(5));
    var protocol=new JournalQuarterProtocol(C.manualBinding(),memory,CLOCK,owner,grant);protocol.start();assertEquals(JournalQuarterProtocol.Status.VERIFIED,protocol.status());return new Prepared(memory,grant);
  }
  @Test void realHandleSessionWritesIdentityProfileBothCategoriesAndCloses()throws Exception {
    var p=prepare();Book before=p.memory.book.copy();int writes=p.memory.writes;
    Book after=SyntheticCOrdinaryCheck.execute(C,before,p.completed,CLOCK,authority->{
      var memory=p.memory;
      var endpoint=new NarrowSheetsTransport(){
        public Scope operation(String label,Root root,Set<String> tabs,Duration duration){return memory.operation(label,root,tabs,duration);}
        public Book snapshot(){return memory.snapshot();}public List<Metadata> control(){return memory.control();}public List<List<String>> related(String t){return memory.related(t);}public boolean unsupportedStructure(){return false;}
        public void batch(String wire){var dispatch=authority.beforeDispatch(C.path()+":batchUpdate",wire);memory.batch(wire);dispatch.acknowledged();}
      };
      var handle=new JournalGoogleFactory.Handle(endpoint,()->{},authority,C);return new SyntheticCLauncher.Session(handle.transport(),handle::close);
    });
    assertEquals(6,p.memory.writes-writes);assertEquals("CLOSED",root(new ArrayList<>(after.metadata.values())).state());
    assertEquals(before.sheet("accounts").rows.size()+1,after.sheet("accounts").rows.size());
    assertEquals(before.sheet("accounts").rows,after.sheet("accounts").rows.subList(0,before.sheet("accounts").rows.size()));
    String account=after.sheet("accounts").rows.get(after.sheet("accounts").rows.size()-1).get(0);
    var counts=LifetimeStatistics.fromEnvelopes(account,ManualQuarterMaintenance.decoded(after.tables()).values());
    assertEquals(1,counts.formal().matches());assertEquals(1,counts.casual().matches());assertEquals(7,counts.formal().totalScore());assertEquals(7,counts.casual().totalScore());
    var originalCommits=ManualQuarterMaintenance.decoded(before.tables()).keySet();
    var added=ManualQuarterMaintenance.decoded(after.tables()).values().stream().filter(e->!originalCommits.contains(e.commitId())).toList();
    assertTrue(added.stream().allMatch(e->e.payload().path("match").path("app_version").asText().equals("0.13.2")));
    assertEquals(2,added.size());assertEquals(Set.of("FORMAL","CASUAL"),new HashSet<>(added.stream().map(FinalMatchEnvelope::statisticsCategory).toList()));
  }
  @Test void wrongTargetOrMissingQuarterProofCannotReachHandle()throws Exception {
    var p=prepare();assertThrows(RuntimeException.class,()->SyntheticCOrdinaryCheck.execute(JournalSessionAuthorityTest.BINDING,p.memory.book,p.completed,CLOCK,a->{throw new AssertionError();}));
    assertThrows(RuntimeException.class,()->SyntheticCOrdinaryCheck.execute(C,p.memory.book,null,CLOCK,a->{throw new AssertionError();}));
  }
  @Test void unknownClaimStopsBeforeAnyIdentityOrMatch()throws Exception {
    var p=prepare();int writes=p.memory.writes;p.memory.failAt=writes+1;p.memory.applyUnknown=true;
    assertThrows(RuntimeException.class,()->SyntheticCOrdinaryCheck.execute(C,p.memory.book,p.completed,CLOCK,a->new SyntheticCLauncher.Session(p.memory,()->{})));
    assertEquals(writes+1,p.memory.writes);
  }
}
