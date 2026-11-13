package com.bridge.quartercore;
import java.time.*;import java.util.*;import java.util.function.LongSupplier;
import static com.bridge.quartercore.NativeSheetsModel.*;import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Pre-authority bounded readonly overlap observation. Never clears UNKNOWN or resends writes. */
final class NativeClaimCloseWait {
 @FunctionalInterface interface Pause {void sleep()throws InterruptedException;}
 static Book read(GoogleSheetsBinding b,Clock c,PlannedMaintenanceStartupFactory.Reader r,Runnable live){return read(b,c,r,live,System::nanoTime,()->Thread.sleep(15000));}
 static Book read(GoogleSheetsBinding b,Clock c,PlannedMaintenanceStartupFactory.Reader reader,Runnable live,LongSupplier nano,Pause pause){
  long start=nano.getAsLong(),last=start;Instant wall=c.instant();Book previous=null;
  for(int i=0;i<13;i++){
   notInterrupted();live.run();long now=nano.getAsLong();Instant current=c.instant();if(now<last||now-start>180_000_000_000L||current.isBefore(wall))throw new Rejected();last=now;wall=current;
   notInterrupted();Book fresh=reader.read();notInterrupted();live.run();now=nano.getAsLong();current=c.instant();if(now<last||now-start>180_000_000_000L||current.isBefore(wall))throw new Rejected();last=now;wall=current;
   Root root=root(new ArrayList<>(fresh.metadata.values()));
   if(root.state().equals("CLOSED")){if(previous!=null)progress(b,previous,fresh,c);if(DurableCleanHandoffAssessment.inspect(b.manualBinding(),fresh,c).state()==DurableCleanHandoffAssessment.State.BLOCKED)throw new Rejected();notInterrupted();return fresh;}
   if(!root.state().equals("OPEN"))throw new Rejected();stableOpen(b,fresh,c);if(previous!=null)progress(b,previous,fresh,c);previous=fresh.copy();
   if(i==12)throw new Rejected();try{pause.sleep();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Rejected();}
  }throw new Rejected();
 }
 private static void notInterrupted(){if(Thread.currentThread().isInterrupted())throw new Rejected();}
 private static void stableOpen(GoogleSheetsBinding b,Book book,Clock clock){
  var evidence=NativeCommitJournal.verify(b.manualBinding(),book,clock);Root r=root(new ArrayList<>(book.metadata.values()));
  if(!r.owner().matches("w-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new Rejected();
  Metadata actual=book.metadata.get(r.next()-1);if(actual==null)throw new Rejected();String prior=text(parse(actual.value()),"owner");
  if(r.operation().startsWith("CLAIM-")){if(!r.operation().equals("CLAIM-"+r.owner())||!r.hash().equals(hash(r.owner()))||prior.equals(r.owner()))throw new Rejected();if(prior.isBlank())initialEmptyOwner(book,r,evidence.sequence());}
  else if(!prior.equals(r.owner())||r.operation().isBlank()||r.operation().startsWith("CLOSE-")||r.operation().startsWith("ROTATE-"))throw new Rejected();
  metadataEquals(actual,receipt(new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()-1,prior,"CLOSED","",hash("")),r.operation(),r.hash(),"OPEN"));
 }
 /** Empty predecessor owner is valid only at the exact initial bootstrap/optional admission lineage. */
 private static void initialEmptyOwner(Book book,Root r,long sequence){
  if(r.epoch()!=1||(r.next()!=4&&r.next()!=5)||sequence!=r.next()-2)throw new Rejected();
  Metadata boot=book.metadata.get(2);if(boot==null)throw new Rejected();String op=text(parse(boot.value()),"operation");if(!op.matches("JOURNAL-BOOTSTRAP-[0-9a-f]{16}"))throw new Rejected();
  metadataEquals(boot,receipt(new Root(r.dataset(),1,r.anchor(),r.nextSheet(),2,"","CLOSED","BOOTSTRAP",hash("BOOTSTRAP")),op,hash(op),"CLOSED"));
  if(r.next()==5){Metadata migration=book.metadata.get(3);if(migration==null)throw new Rejected();String operation=text(parse(migration.value()),"operation"),h=text(parse(migration.value()),"hash");
   if(!operation.matches("HISTORICAL-CASUAL-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!h.matches("[0-9a-f]{64}"))throw new Rejected();
   metadataEquals(migration,receipt(new Root(r.dataset(),1,r.anchor(),r.nextSheet(),3,"","CLOSED",op,hash(op)),operation,h,"CLOSED"));
   if(ManualQuarterMaintenance.decoded(book.tables()).values().stream().noneMatch(e->h.equals(e.payloadHash())&&operation.equals("HISTORICAL-CASUAL-"+e.payload().path("historical_admission").path("approval_reference").asText())))throw new Rejected();
  }
 }
 /** Current full evidence only; does not reconstruct overwritten historical journal proofs. */
 private static void progress(GoogleSheetsBinding binding,Book before,Book after,Clock clock){
  var a=NativeCommitJournal.verify(binding.manualBinding(),before,clock);var b=NativeCommitJournal.verify(binding.manualBinding(),after,clock);Root x=root(new ArrayList<>(before.metadata.values())),y=root(new ArrayList<>(after.metadata.values()));
  if(!x.owner().equals(y.owner())||x.epoch()!=y.epoch()||x.anchor()!=y.anchor()||x.nextSheet()!=y.nextSheet()||b.sequence()<a.sequence()||((long)y.next()-x.next())!=b.sequence()-a.sequence())throw new Rejected();
  if(b.sequence()==a.sequence()&&!JournalSessionAuthority.fullToken(before).equals(JournalSessionAuthority.fullToken(after)))throw new Rejected();
 }
 private static void metadataEquals(Metadata a,Metadata b){if(a==null||a.id()!=b.id()||!a.key().equals(b.key())||!Objects.equals(a.sheet(),b.sheet())||!NativeCommitJournal.canonical(parse(a.value())).equals(NativeCommitJournal.canonical(parse(b.value()))))throw new Rejected();}
 private NativeClaimCloseWait(){}
}
