package com.bridge.quartercore;

import java.time.Clock;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Explicit local-only journal-v1 epoch rotation. No ownership acquisition or live sink.
 * Original anchor/receipt arbitration and all data/proof changes share one atomic batch.
 */
final class JournalRotation {
    private JournalRotation(){}
    static JournalPlan plan(ManualQuarterMaintenance.Binding binding,Book before,String expectedOwner,Clock clock){
        var prior=NativeCommitJournal.verify(binding,before,clock);
        var all=new ArrayList<>(before.metadata.values());Root old=root(all);
        if(expectedOwner==null||expectedOwner.isBlank()||!expectedOwner.equals(old.owner())||!old.state().equals("OPEN")
            ||old.epoch()>=1_000_000||old.nextSheet()==Integer.MAX_VALUE
            ||all.stream().anyMatch(m->Objects.equals(m.sheet(),old.anchor())))throw new Rejected();
        var rows=copyRows(before.sheet("retention_ledger").rows);
        int newId=old.nextSheet();String operation="JOURNAL-ROTATE-"+old.epoch();
        Root next=new Root(old.dataset(),old.epoch()+1,newId,newId+1,3,old.owner(),"OPEN",operation,hash(rows));
        var requests=new ArrayList<Object>();
        requests.add(guard(old.anchor(),"retention_ledger"));
        // Occupy the exact old next slot before deleting anything. A competing old batch
        // cannot erase this arbitration: old batches always begin with the deleted anchor.
        requests.add(create(receipt(old,operation,next.hash(),"ROTATE")));
        requests.add(Map.of("addSheet",Map.of("properties",Map.of("sheetId",newId,"title","nq_rotation_temporary",
            "gridProperties",Map.of("rowCount",1000,"columnCount",26)))));
        requests.add(update(newId,0,rows.size(),0,5,rows));
        requests.add(Map.of("deleteSheet",Map.of("sheetId",old.anchor())));
        requests.add(rename(newId,"retention_ledger"));
        for(Metadata m:all)if(m.key().equals(RECEIPT_KEY))requests.add(deleteMetadata(m.id()));
        requests.add(deleteMetadata(old.next()));
        requests.add(create(new Metadata(2,RECEIPT_KEY,json(Map.of("epoch",next.epoch(),"owner",next.owner(),
            "operation",operation,"hash",next.hash(),"phase","OPEN")),null)));
        requests.add(updateRoot(next));
        if(spreadsheetChars(all)+CONTROL_MAX>METADATA_LIMIT)throw new Rejected();
        Book rotated=simulate(before,wire(requests));
        // Rotation may only change the ledger's sheet identity and protocol metadata.
        if(!before.tables().equals(rotated.tables()))throw new Rejected();
        for(Metadata m:all)if(!m.key().equals(ROOT_KEY)&&!m.key().equals(RECEIPT_KEY)
            &&!Objects.equals(m,rotated.metadata.get(m.id())))throw new Rejected();
        var priorProof=parse(before.metadata.get(NativeCommitJournal.ID).value());
        String beforeDigest=NativeCommitJournal.digest(binding,before),afterDigest=NativeCommitJournal.digest(binding,rotated);
        var proof=new NativeCommitJournal.Proof(1,Math.addExact(prior.sequence(),1),priorProof.path("targetHash").asText(),
            next.epoch(),2,operation,prior.proofHash(),beforeDigest,afterDigest);
        var marker=new Metadata(NativeCommitJournal.ID,NativeCommitJournal.KEY,NativeCommitJournal.canonical(proof),null);
        if(marker.characters()>2048)throw new Rejected();
        requests.add(Map.of("updateDeveloperMetadata",Map.of("dataFilters",List.of(Map.of("developerMetadataLookup",Map.of("metadataId",NativeCommitJournal.ID))),
            "developerMetadata",Map.of("metadataValue",marker.value()),"fields","metadataValue")));
        String combined=wire(requests);
        if(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>Limits.defaults().requestBytes())throw new Rejected();
        Book after=simulate(before,combined);
        var verified=NativeCommitJournal.verify(binding,after,clock);
        if(!afterDigest.equals(verified.snapshotDigest())||!next.equals(root(new ArrayList<>(after.metadata.values()))))throw new Rejected();
        return new JournalPlan(binding,beforeDigest,combined,after,verified,clock);
    }
}
