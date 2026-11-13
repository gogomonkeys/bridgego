package com.bridge.quartercore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
class ExtractedCoreParityTest {
  static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"),ZoneOffset.UTC);
  static class Memory implements NarrowSheetsTransport {
    Book b;int writes;boolean fail;int failAt;List<String> phases=new ArrayList<>();Memory(Book b){this.b=b;}
    public Book snapshot(){return b.copy();}public List<Metadata> control(){return new ArrayList<>(b.metadata.values());}
    public List<List<String>> related(String t){return copyRows(b.sheet(t).rows);}
    public boolean unsupportedStructure(){return false;}
    public void batch(String wire){writes++;if(fail||writes==failAt)throw new Unknown();b=simulate(b,wire);phases.add(root(control()).state());}
  }
  static Service service(Memory m){return new Service(m,CLOCK,new ManualQuarterMaintenance.Binding(RecoveredFixture.ID,RecoveredFixture.DATASET),"local-test-B",Limits.defaults());}
  static void reply(String body){BatchedSheetsTransport.validateBatchReply(body.getBytes(StandardCharsets.UTF_8),RecoveredFixture.ID,1);}
  static String good(){return "{\"spreadsheetId\":\""+RecoveredFixture.ID+"\",\"replies\":[{}]";}
  @Test void legalBatchReplies(){assertDoesNotThrow(()->reply(good()+"}"));assertDoesNotThrow(()->reply(good()+",\"commentUpdateState\":\"NO_UPDATES_REQUESTED\",\"updatedSpreadsheet\":null}"));}
  @Test void illegalBatchReplies(){for(String bad:List.of(good()+",\"commentUpdateState\":\"UPDATED\"}",good()+",\"updatedSpreadsheet\":{}}",good()+",\"extra\":true}",good().replace(RecoveredFixture.ID,"wrong")+"}",good().replace("[{}]","[]")+"}",good().replace("[{}]","[null]")+"}","{}","null"))assertThrows(RuntimeException.class,()->reply(bad));}
  @Test void completeFiveBatchesPreserveAccountsAndCurrent()throws Exception{Memory m=new Memory(RecoveredFixture.book());var before=m.b.tables();try(Service s=service(m)){s.start();assertEquals(State.OPEN,s.state());assertNotNull(s.quarterSummary());assertEquals(4,m.writes);s.cleanClose();assertEquals(State.CLOSED,s.state());assertEquals(5,m.writes);assertEquals(2,m.b.sheet("commits").rows.size());assertEquals(before.get("auth_identities"),m.b.tables().get("auth_identities"));assertEquals(before.get("accounts").size(),m.b.sheet("accounts").rows.size());assertEquals("COMPLETE",m.b.sheet("retention_ledger").rows.get(1).get(2));for(int i=1;i<=2;i++){assertEquals(before.get("accounts").get(i).subList(0,4),m.b.sheet("accounts").rows.get(i).subList(0,4));var carry=LifetimeStatistics.decode(m.b.sheet("accounts").rows.get(i).get(4));assertEquals(Instant.parse("2026-09-30T16:00:00Z"),carry.through());assertEquals("2026-Q3",carry.trackedFromQuarter());assertEquals(new LifetimeStatistics.Counts(1,1,i==1?1:0,i==2?1:0,0,i==1?7:-3,1,0,0),carry.formal());assertEquals(LifetimeStatistics.Counts.zero(),carry.casual());}assertEquals(List.of(before.get("commits").get(0),before.get("commits").get(2)),m.b.sheet("commits").rows);assertEquals(List.of(before.get("envelopes").get(0),before.get("envelopes").get(2)),m.b.sheet("envelopes").rows);assertEquals(before.get("erasure_ledger"),m.b.sheet("erasure_ledger").rows);}}
  @Test void unknownClaimNeverAutomaticallyResends()throws Exception{Memory m=new Memory(RecoveredFixture.book());m.fail=true;try(Service s=service(m)){s.start();assertEquals(State.UNKNOWN,s.state());assertEquals(1,m.writes);s.tick();s.start();assertFalse(s.reconcile());assertEquals(1,m.writes);}}
  @Test void unknownEveryPhaseStops()throws Exception{for(int at=1;at<=5;at++){Memory m=new Memory(RecoveredFixture.book());m.failAt=at;try(Service s=service(m)){s.start();if(at==5)s.cleanClose();assertEquals(State.UNKNOWN,s.state());assertEquals(at,m.writes);s.tick();assertFalse(s.reconcile());assertEquals(at,m.writes);}}}
  @Test void extractedReleaseCannotAuthorizeLiveTarget(){assertThrows(RuntimeException.class,()->MaintenanceRelease.BUNDLED.requireProvisionedBinding());assertThrows(RuntimeException.class,()->MaintenanceRelease.BUNDLED.requireLiveBinding());}
}
