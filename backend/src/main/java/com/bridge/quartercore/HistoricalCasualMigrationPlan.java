package com.bridge.quartercore;
import java.time.*;import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;
/** Pure exact-baseline plan. No transport, credential, ordinary grant or automatic enablement. */
final class HistoricalCasualMigrationPlan {
 record Approval(ManualQuarterMaintenance.Binding target,String baselineDigest,String sourceCommit,String sourceHash,String approvalReference,LifetimeStatistics.Counts expectedContribution){}
 static JournalPlan prepare(ManualQuarterMaintenance.Binding target,Book before,Approval a,Clock clock){
  if(a==null||!target.equals(a.target())||!NativeCommitJournal.digest(target,before).equals(a.baselineDigest()))throw new Rejected();
  NativeCommitJournal.verify(target,before,clock);Root old=root(new ArrayList<>(before.metadata.values()));if(!old.state().equals("CLOSED"))throw new Rejected();
  var records=ManualQuarterMaintenance.decoded(before.tables());var source=records.values().stream().filter(e->e.commitId().equals(a.sourceCommit())).findFirst().orElseThrow(Rejected::new);
  if(!source.payloadHash().equals(a.sourceHash())||source.revision()!=1||records.values().stream().filter(e->e.matchId().equals(source.matchId())).count()!=1)throw new Rejected();
  var replacement=HistoricalCasualAdmission.migrate(source,a.approvalReference());String account=source.payload().path("participants").findValues("account_id").stream().filter(n->n.isTextual()).findFirst().orElseThrow(Rejected::new).asText();
  var contribution=LifetimeStatistics.fromEnvelopes(account,List.of(replacement));if(!contribution.formal().equals(LifetimeStatistics.Counts.zero())||!contribution.casual().equals(a.expectedContribution()))throw new Rejected();
  var tables=before.tables();var commits=new ArrayList<List<String>>();var envelopes=new ArrayList<List<String>>();String text=replacement.canonicalJson();int count=(text.length()+9999)/10000;
  for(var row:tables.get("commits"))commits.add(row.get(0).equals(source.commitId())?List.of(replacement.commitId(),replacement.payloadHash(),Integer.toString(count),replacement.matchId(),"2"):row);
  boolean added=false;for(var row:tables.get("envelopes")){if(!row.get(0).equals(source.commitId()))envelopes.add(row);else if(!added){for(int i=0;i<count;i++)envelopes.add(List.of(replacement.commitId(),replacement.payloadHash(),Integer.toString(i),Integer.toString(count),text.substring(i*10000,Math.min(text.length(),(i+1)*10000))));added=true;}}
  if(!added)throw new Rejected();Root next=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,old.owner(),"CLOSED","HISTORICAL-CASUAL-"+a.approvalReference(),replacement.payloadHash());
  var changes=List.<Object>of(update(before.sheet("commits").id,0,Math.max(commits.size(),tables.get("commits").size()),0,5,commits),update(before.sheet("envelopes").id,0,Math.max(envelopes.size(),tables.get("envelopes").size()),0,5,envelopes));
  var plan=NativeCommitJournal.append(target,before,QuarterPlan.phaseWire(old,next,changes),clock);var after=plan.expected().tables();
  for(var tab:tables.keySet())if(!Set.of("commits","envelopes").contains(tab)&&!tables.get(tab).equals(after.get(tab)))throw new Rejected();
  var decoded=ManualQuarterMaintenance.decoded(after);if(decoded.size()!=records.size()||decoded.values().stream().noneMatch(replacement::equals))throw new Rejected();for(var e:records.values())if(!e.equals(source)&&decoded.values().stream().noneMatch(e::equals))throw new Rejected();return plan;
 }
}
