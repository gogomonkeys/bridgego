package com.bridge.quartercore;
import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Shared trusted ordered business-plan builder; completion time is generated only after APPLIED readback. */
final class QuarterPhasePlans {
 private final ManualQuarterMaintenance.Binding binding;private final String owner;private final Clock clock;private final Instant quarter;
 private QuarterPlan intent;private int next;
 QuarterPhasePlans(ManualQuarterMaintenance.Binding binding,String owner,Clock clock){this.binding=binding;this.owner=owner;this.clock=clock;quarter=QuarterlyRetention.currentQuarterStart(clock.instant());}
 JournalPlan next(Book confirmed,int phase){
 if(phase!=next||!quarter.equals(QuarterlyRetention.currentQuarterStart(clock.instant())))throw new Rejected();
      Root r=root(new ArrayList<>(confirmed.metadata.values()));String wire;
      switch(phase){
        case 0 -> {Root n=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,owner,"OPEN","CLAIM-"+owner,hash(owner));wire=QuarterPlan.phaseWire(r,n,List.of());}
        case 1 -> {intent=QuarterPlan.create(binding,confirmed,owner,clock,Limits.defaults());if(!intent.cutoff().equals(quarter))throw new Rejected();wire=intent.prepareTicket().wire();}
        case 2 -> {if(!confirmed.tables().equals(intent.beforeTables()))throw new Rejected();wire=intent.foldTicket().wire();}
        case 3 -> {
          if(!r.state().equals("APPLIED")||!r.operation().equals(intent.planId())||!confirmed.tables().equals(intent.pendingTables()))throw new Rejected();
          Instant verifiedAt=clock.instant();var completed=new TreeMap<String,List<List<String>>>(intent.pendingTables());
          var retention=new ArrayList<>(completed.get("retention_ledger"));String name=QuarterlyRetention.quarter(quarter);
          retention.removeIf(row->!row.isEmpty()&&row.get(0).equals(name));retention.add(List.of(name,quarter.toString(),"COMPLETE",verifiedAt.toString(),"1"));completed.put("retention_ledger",List.copyOf(retention));
          String proof=FinalMatchEnvelope.hash(NativeCommitJournal.canonical(List.of("journal-quarter-complete-v1",intent.planId(),intent.digest(),NativeCommitJournal.canonical(confirmed.nativeValue()),quarter.toString(),verifiedAt.toString(),completed)));
          Root n=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,owner,"OPEN",intent.planId()+"-VERIFIED",proof);
          var sheet=confirmed.sheet("retention_ledger");wire=QuarterPlan.phaseWire(r,n,List.of(update(sheet.id,0,Math.max(sheet.rows.size(),retention.size()),0,5,retention)));
        }
        case 4 -> {Root n=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()+1,owner,"CLOSED","CLOSE-"+owner,hash(owner));wire=QuarterPlan.phaseWire(r,n,List.of());}
        default -> throw new Rejected();
      }
 next++;return NativeCommitJournal.append(binding,confirmed,wire,clock);
 }
}
