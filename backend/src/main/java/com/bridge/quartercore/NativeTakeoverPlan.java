package com.bridge.quartercore;
import java.time.Clock;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Pure one-slot native fencing plan. Does not establish predecessor termination or source permission. */
final class NativeTakeoverPlan {
  static JournalPlan create(ManualQuarterMaintenance.Binding binding,Book before,String newOwner,Clock clock){
    Objects.requireNonNull(binding);Objects.requireNonNull(before);Objects.requireNonNull(clock);ascii(newOwner,40);
    NativeCommitJournal.verify(binding,before,clock);
    ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(),true,before.tables()),binding,clock);
    Root old=root(new ArrayList<>(before.metadata.values()));
    if(newOwner.isBlank()||newOwner.equals(old.owner())||!old.state().equals("OPEN")||!old.dataset().equals(binding.datasetId()))throw new Rejected();
    var quarter=QuarterlyRetention.currentQuarterStart(clock.instant());var ledger=new RetentionLedger(before.tables().get("retention_ledger"));
    if(!ledger.complete()||!ledger.cutoff().equals(quarter))throw new Rejected();
    for(var e:ManualQuarterMaintenance.decoded(before.tables()).values())if(QuarterlyRetention.finishedAt(e).isBefore(quarter))throw new Rejected();
    Root next=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,newOwner,"OPEN","CLAIM-"+newOwner,hash(newOwner));
    JournalPlan plan=NativeCommitJournal.append(binding,before,QuarterPlan.phaseWire(old,next,List.of()),clock);
    if(!before.tables().equals(plan.expected().tables()))throw new Rejected();
    return plan;
  }
}
