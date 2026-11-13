package com.bridge.quartercore;
import java.time.*;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Immutable reviewed full starting snapshot and bounded receipt lineage. Not a historical journal hash-chain reconstruction. */
final class FixedEBaselineBundle {
 private final Book baseline;private final Clock business=Clock.fixed(FixedERestartClock.BUSINESS_INSTANT,ZoneOffset.UTC);
 private FixedEBaselineBundle(Book full,String token){FixedERestartPreflight.isolatedFixture(()->full,token);if(DurableCleanHandoffAssessment.inspect(FixedERestartClock.binding().manualBinding(),full,business).state()!=DurableCleanHandoffAssessment.State.CLEAN_CURRENT_RESUMABLE)throw new Rejected();baseline=full.copy();}
 static FixedEBaselineBundle reviewedSnapshot(Book full){return new FixedEBaselineBundle(full,FixedERestartPreflight.INITIAL_TOKEN);}
 static FixedEBaselineBundle isolatedFixture(Book full){return new FixedEBaselineBundle(full,JournalSessionAuthority.fullToken(full));}
 /** No actual E full snapshot has been obtained and reviewed for this release. */
 static FixedEBaselineBundle compiled(){return null;}
 int completedPairs(Book fresh){
  if(DurableCleanHandoffAssessment.inspect(FixedERestartClock.binding().manualBinding(),fresh,business).state()!=DurableCleanHandoffAssessment.State.CLEAN_CURRENT_RESUMABLE)throw new Rejected();
  Root start=root(new ArrayList<>(baseline.metadata.values())),end=root(new ArrayList<>(fresh.metadata.values()));int distance=end.next()-start.next();
  if(distance<0||distance>6||distance%2!=0)throw new Rejected();if(distance==0){if(!JournalSessionAuthority.fullToken(baseline).equals(JournalSessionAuthority.fullToken(fresh)))throw new Rejected();return 0;}Book expected=baseline.copy();Set<String> owners=new HashSet<>();owners.add(start.owner());
  for(int i=0;i<distance/2;i++){
   Root old=root(new ArrayList<>(expected.metadata.values()));Metadata claim=fresh.metadata.get(old.next());if(claim==null)throw new Rejected();String operation=text(parse(claim.value()),"operation");if(!operation.startsWith("CLAIM-"))throw new Rejected();String owner=operation.substring(6);ascii(owner,40);if(!owner.matches("w-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!owners.add(owner))throw new Rejected();
   expected=transition(expected,owner,"OPEN","CLAIM-"+owner);expected=transition(expected,owner,"CLOSED","CLOSE-"+owner);
  }
  // The latest journal is verified above against the actual complete snapshot. Historical
  // journal records are overwritten, so do not claim to reconstruct their hash chain.
  var beforeEvidence=NativeCommitJournal.verify(FixedERestartClock.binding().manualBinding(),baseline,business);
  var afterEvidence=NativeCommitJournal.verify(FixedERestartClock.binding().manualBinding(),fresh,business);
  if(afterEvidence.sequence()!=beforeEvidence.sequence()+distance)throw new Rejected();
  Book observed=fresh.copy();expected.metadata.remove(NativeCommitJournal.ID);observed.metadata.remove(NativeCommitJournal.ID);
  // Only newly created, expected control records may vary JSON key order. All original
  // prefix values, tables, shapes, IDs and unrelated metadata remain byte-for-byte pinned.
  for(int id=start.next();id<end.next();id++){var a=expected.metadata.get(id);var b=observed.metadata.get(id);if(a==null||b==null||!a.key().equals(b.key())||!Objects.equals(a.sheet(),b.sheet())||!NativeCommitJournal.canonical(parse(a.value())).equals(NativeCommitJournal.canonical(parse(b.value()))))throw new Rejected();observed.metadata.put(id,a);}
  var er=expected.metadata.get(1);var or=observed.metadata.get(1);if(or==null||!er.key().equals(or.key())||!Objects.equals(er.sheet(),or.sheet())||!NativeCommitJournal.canonical(parse(er.value())).equals(NativeCommitJournal.canonical(parse(or.value()))))throw new Rejected();observed.metadata.put(1,er);
  if(!NativeCommitJournal.canonical(expected.nativeValue()).equals(NativeCommitJournal.canonical(observed.nativeValue())))throw new Rejected();return distance/2;
 }
 void requireNextClaim(Book fresh){if(completedPairs(fresh)>=3)throw new Rejected();}
 private Book transition(Book before,String owner,String state,String operation){Root old=root(new ArrayList<>(before.metadata.values()));Root next=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,owner,state,operation,hash(owner));return simulate(before,QuarterPlan.phaseWire(old,next,List.of()));}
}
