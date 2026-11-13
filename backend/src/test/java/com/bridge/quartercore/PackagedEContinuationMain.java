package com.bridge.quartercore;
import java.nio.file.*;import java.time.*;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Test-only derived entry. The locally generated first pair is NOT an observed online snapshot. */
public final class PackagedEContinuationMain {
 public static void main(String[] args)throws Exception{
  if(args.length!=4)throw new IllegalArgumentException();Book b=PackagedEHandoffMain.load(Path.of(args[0]));
  if(!JournalSessionAuthority.fullToken(b).equals("2c8db9559c124f1d9d34ffe79c8d6413bb282382772554be55b9abbda33834a7"))throw new Rejected();
  if(args[3].equals("prepare-local-one-pair")){
   var clock=Clock.fixed(FixedERestartClock.BUSINESS_INSTANT,ZoneOffset.UTC);String owner="w-"+UUID.randomUUID();
   for(String phase:List.of("OPEN","CLOSED")){Root r=root(new ArrayList<>(b.metadata.values()));String op=(phase.equals("OPEN")?"CLAIM-":"CLOSE-")+owner;Root n=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,owner,phase,op,hash(owner));b=NativeCommitJournal.append(FixedERestartClock.binding().manualBinding(),b,QuarterPlan.phaseWire(r,n,List.of()),clock).expected();}
   Files.writeString(Path.of(args[1]),json(b.nativeValue()));System.out.println("LOCAL_SYNTHETIC_FIRST_PAIR_ONLY_NOT_ONLINE_EVIDENCE");return;
  }
  if(args[3].equals("completion-check")){
   var completion=FixedEVerifiedArtifact.completedIdleContinuation();completion.verifyOnce(()->{try{return PackagedEHandoffMain.load(Path.of(args[1]));}catch(Exception e){throw new Rejected();}});
   if(completion.response("GET",FixedEVerifiedArtifact.PATH,null)==null)throw new Rejected();System.out.println("LOCAL_EXACT_THREE_PAIR_COMPLETION_VERIFIED");return;
  }
  PackagedEHandoffMain.run(args,FixedEBaselineBundle.compiledIdleContinuation());
 }
}
