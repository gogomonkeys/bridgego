package com.bridge.persistence;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SheetsSocialStoreTest {
 static final Instant NOW=Instant.parse("2026-11-17T00:00:00Z");
 static String uuid(){return UUID.randomUUID().toString();}
 static class Memory implements SheetsGateway {
  Map<String,List<List<String>>> tabs=new HashMap<>();int writes,reads;boolean timeout;int failure;
  String a=uuid(),b=uuid(),c=uuid();
  Memory(){tabs.put("accounts",List.of(AccountProfileRows.HEADER,List.of(a,"2","Alpha",""),List.of(b,"2","Beta",""),List.of(c,"2","Gamma","")));tabs.put("erasure_ledger",List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL));tabs.put("retention_ledger",List.of(RetentionLedger.HEADER,List.of("2026-Q4","2026-09-30T16:00:00Z","COMPLETE","2026-10-01T00:00:00Z","1")));tabs.put("social",List.of(SocialRows.HEADER));}
  public List<List<String>> read(String t){reads++;if(failure!=0)throw new Failure(failure,0);return tabs.get(t);}
  public void appendAtomic(Map<String,List<List<Object>>> ignored){throw new UnsupportedOperationException();}
  public void replaceSocial(List<List<String>> rows){writes++;tabs.put("social",List.copyOf(rows));if(timeout)throw new Failure(0,0);}
  SheetsSocialStore store(){return new SheetsSocialStore(this,Clock.fixed(NOW,ZoneOffset.UTC));}
 }
 @Test void readNeverProvisionsAndCodesPersist(){var m=new Memory();var s=m.store();assertNull(s.snapshot(m.a).ownCode());assertEquals(0,m.writes);String a=s.ensureCode(m.a);assertTrue(a.matches("[A-F0-9]{16}"));assertEquals(a,m.store().ensureCode(m.a));assertEquals(1,m.writes);}
 @Test void mutualAcceptanceAndIdempotencySurviveRestart(){var m=new Memory();var s=m.store();String a=s.ensureCode(m.a),b=s.ensureCode(m.b);String req=uuid(),accept=uuid();s.request(m.a,b,req);assertEquals(1,s.snapshot(m.b).incoming().size());assertEquals(0,s.snapshot(m.a).friends().size());s.respond(m.b,req,true,accept);assertEquals(a,s.snapshot(m.b).friends().get(0).code());int writes=m.writes;m.store().respond(m.b,req,true,accept);assertEquals(writes,m.writes);assertEquals(m.b,s.snapshot(m.a).friends().get(0).accountId());}
 @Test void crossedRequestsDoNotAutoAcceptAndThirdPartyCannotRespond(){var m=new Memory();var s=m.store();String a=s.ensureCode(m.a),b=s.ensureCode(m.b);s.ensureCode(m.c);String req=uuid();s.request(m.a,b,req);s.request(m.b,a,uuid());assertEquals(0,s.snapshot(m.a).friends().size());assertEquals(1,s.snapshot(m.b).incoming().size());assertThrows(IllegalArgumentException.class,()->s.respond(m.c,req,true,uuid()));}
 @Test void rejectRemoveAndOldReceiptCannotResurrect(){var m=new Memory();var s=m.store();s.ensureCode(m.a);String b=s.ensureCode(m.b),req=uuid();s.request(m.a,b,req);s.respond(m.b,req,false,uuid());int writes=m.writes;s.request(m.a,b,req);assertEquals(writes,m.writes);assertTrue(s.snapshot(m.b).incoming().isEmpty());String req2=uuid();s.request(m.a,b,req2);s.respond(m.b,req2,true,uuid());String rm=uuid();s.remove(m.a,b,rm);writes=m.writes;m.store().remove(m.a,b,rm);assertEquals(writes,m.writes);assertTrue(s.snapshot(m.b).friends().isEmpty());}
 @Test void unknownWriteLatchesButReadAndRestartReceiptReconcile(){var m=new Memory();var s=m.store();s.ensureCode(m.a);String b=s.ensureCode(m.b),req=uuid();m.timeout=true;assertThrows(SheetsGateway.Failure.class,()->s.request(m.a,b,req));int writes=m.writes;assertEquals(1,s.snapshot(m.b).incoming().size());assertThrows(IllegalStateException.class,()->s.respond(m.b,req,true,uuid()));assertEquals(writes,m.writes);m.store().request(m.a,b,req);assertEquals(writes,m.writes);}
 @Test void cacheAnd429AvoidReadStorms(){var m=new Memory();var s=m.store();s.snapshot(m.a);int reads=m.reads;for(int i=0;i<20;i++)s.snapshot(m.a);assertEquals(reads,m.reads);m.failure=429;assertThrows(SheetsGateway.Failure.class,()->s.ensureCode(m.a));reads=m.reads;assertThrows(IllegalStateException.class,()->s.snapshot(m.a));assertEquals(reads,m.reads);}
 @Test void deletedAndUnknownAccountsCannotWrite(){var m=new Memory();m.tabs.put("erasure_ledger",List.of(ErasureLedger.HEADER,ErasureLedger.SENTINEL,List.of("ACCOUNT",ErasureLedger.digest("ACCOUNT",m.a),"1")));assertThrows(SheetsGateway.Conflict.class,()->m.store().ensureCode(m.a));assertEquals(0,m.writes);}
 @Test void wrongActionAndSelfAndUnknownCodeReject(){var m=new Memory();var s=m.store();String a=s.ensureCode(m.a),b=s.ensureCode(m.b),id=uuid();assertThrows(IllegalArgumentException.class,()->s.request(m.a,a,id));assertThrows(IllegalArgumentException.class,()->s.request(m.a,"not-a-code",id));s.request(m.a,b,id);assertThrows(IllegalArgumentException.class,()->s.remove(m.a,b,id));}
 @Test void quarterBoundaryInvalidatesOtherwiseFreshCache(){
  var m=new Memory();var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-12-31T15:59:50Z"));
  Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
  var store=new SheetsSocialStore(m,clock);store.snapshot(m.a);int reads=m.reads;
  now.set(Instant.parse("2026-12-31T16:00:01Z"));assertThrows(IllegalStateException.class,()->store.snapshot(m.a));assertTrue(m.reads>reads);
 }
 @Test void missingTableAndSchemaCorruptionFailClosed(){var m=new Memory();m.tabs.remove("social");assertThrows(RuntimeException.class,()->m.store().snapshot(m.a));assertEquals(0,m.writes);m.tabs.put("social",List.of(List.of("wrong")));assertThrows(SheetsGateway.Conflict.class,()->m.store().snapshot(m.a));}
 @Test void erasureDropsCodesEdgesAndOwnReceipts(){var m=new Memory();var s=m.store();s.ensureCode(m.a);String b=s.ensureCode(m.b);s.request(m.a,b,uuid());var rows=SocialRows.erase(m.tabs.get("social"),m.a);assertFalse(rows.toString().contains(m.a));SocialRows.validate(rows,Set.of(m.b,m.c));assertTrue(rows.toString().contains(b));}
}
