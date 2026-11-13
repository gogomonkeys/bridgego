package com.bridge.quartercore;
import java.time.Clock;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Pure typed-full-snapshot planner, NOT a parser or live migration endpoint. Supports
 * only existing exact native-compatible eight-table schema/layout, no data conversion.
 * Caller must obtain a genuinely complete legacy read; runtime strict parser is unchanged. */
final class LegacyToNativeCutoverPlan {
  static JournalPlan prepare(ManualQuarterMaintenance.Binding binding,Book before,Clock clock) {
    if(before.metadata.values().stream().anyMatch(m->m.id()==1||m.id()==2||m.id()==NativeCommitJournal.ID
        ||m.key().startsWith("nq.")||m.key().equals(NativeCommitJournal.KEY)))throw new Rejected();
    int max=before.sheets.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow(Rejected::new);
    Root initial=new Root(binding.datasetId(),1,before.sheet("retention_ledger").id,Math.addExact(max,1),2,"","CLOSED","BOOTSTRAP",hash("BOOTSTRAP"));
    Metadata rootMetadata=new Metadata(1,ROOT_KEY,rootText(initial),null);
    Book staged=simulate(before,wire(List.of(create(rootMetadata))));
    // This strict existing validator rejects unsupported historical/table formats rather
    // than inventing lifetime rules, truncating rows, changing IDs or resizing sheets.
    JournalPlan bootstrap=NativeCommitJournal.bootstrap(binding,staged,NativeCommitJournal.digest(binding,staged),clock);
    Book expected=bootstrap.expected();
    NativeCommitJournal.Proof original;
    try{original=BoundedSheetsProjection.MAPPER.readValue(expected.metadata.get(NativeCommitJournal.ID).value(),NativeCommitJournal.Proof.class);}
    catch(Exception bad){throw new Rejected();}
    var proof=new NativeCommitJournal.Proof(original.version(),original.sequence(),original.targetHash(),original.epoch(),original.receiptSlot(),original.operation(),original.predecessorProofHash(),NativeCommitJournal.digest(binding,before),original.afterDigest());
    Metadata journal=new Metadata(NativeCommitJournal.ID,NativeCommitJournal.KEY,NativeCommitJournal.canonical(proof),null);
    var requests=new ArrayList<Object>();requests.add(create(rootMetadata));
    var frame=parse(bootstrap.wire()).path("requests");
    for(int i=0;i<frame.size()-1;i++)requests.add(JSON.convertValue(frame.get(i),Object.class));
    requests.add(create(journal));String combined=wire(requests);
    if(bytes(parse(combined))>Limits.defaults().requestBytes())throw new Rejected();
    Book after=simulate(before,combined);
    if(!after.tables().equals(before.tables()))throw new Rejected();
    var evidence=NativeCommitJournal.verify(binding,after,clock);
    return new JournalPlan(binding,NativeCommitJournal.digest(binding,before),combined,after,evidence,clock);
  }
}
