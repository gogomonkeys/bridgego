package com.bridge.quartercore;

import java.time.Clock;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** Local business-writer composition. No bean or live factory.
 * Journal/root/receipt/data are dispatched as one batch, never a follow-up journal write.
 * Full native snapshots retain all metadata. Quarter/rotation protocols are unsupported.
 */
final class JournaledNativeTransport implements NarrowSheetsTransport {
    private final NarrowSheetsTransport delegate;
    private final ManualQuarterMaintenance.Binding binding;
    private final Clock clock;
    private JournalPlan pending;
    JournaledNativeTransport(NarrowSheetsTransport delegate,ManualQuarterMaintenance.Binding binding,Clock clock){
        this.delegate=Objects.requireNonNull(delegate);this.binding=Objects.requireNonNull(binding);this.clock=Objects.requireNonNull(clock);
    }
    /** Full proof reads require all-table coverage within the caller's original deadline. */
    @Override public Scope operation(String label,NarrowQuarterPoc.Root predecessor,Set<String> tabs,java.time.Duration budget){
        Objects.requireNonNull(tabs);Objects.requireNonNull(budget);
        if(budget.isNegative()||budget.isZero())throw new Rejected();
        return delegate.operation(label,predecessor,Set.copyOf(ManualQuarterMaintenance.HEADERS.keySet()),budget);
    }
    /** Operation-boundary renewal only: no remote mutation, no unresolved local plan. */
    synchronized void renewIfDue(){
        if(pending!=null)throw new Unknown();
        if(delegate instanceof JournalPlanTransport plans && plans.renewalDue()){
            snapshot(); // full journal proof remains visible; the Handle rereads before replacement
            plans.renewSession();
        }
    }
    public synchronized Book snapshot(){
        Book observed=delegate.snapshot();
        NativeCommitJournal.verify(binding,observed,clock);
        if(pending!=null){
            if(!pending.matchesAfter(observed))throw new Unknown();
            pending=null;
        }
        return observed;
    }
    public synchronized List<Metadata> control(){return new ArrayList<>(snapshot().metadata.values());}
    public synchronized List<List<String>> related(String tab){return copyRows(snapshot().sheet(tab).rows);}
    public synchronized boolean unsupportedStructure(){return delegate.unsupportedStructure();}
    public synchronized void batch(String protocolWire){
        if(pending!=null)throw new Unknown();
        requireBusinessOnly(protocolWire);
        Book before=snapshot();
        pending=NativeCommitJournal.append(binding,before,protocolWire,clock);
        dispatchPending();
        snapshot(); // Exact applied readback required before returning to Service.
    }
    /** Only the same admitted writer can request rotation; no caller-supplied snapshot/wire. */
    synchronized Book checkpoint(NarrowQuarterPoc.Root expectedRoot){
        if(pending!=null)throw new Unknown();
        Book before=snapshot();
        var observed=NarrowQuarterPoc.root(new ArrayList<>(before.metadata.values()));
        if(expectedRoot==null||!expectedRoot.equals(observed)||!observed.state().equals("OPEN"))throw new Rejected();
        pending=JournalRotation.plan(binding,before,observed.owner(),clock);
        dispatchPending();
        return snapshot();
    }
    private void dispatchPending(){
        // Final-plan aware production sink arms/consumes exactly once itself. Never wrap
        // dispatchPlan in another plan.dispatchOnce, which would double-consume it.
        if(delegate instanceof JournalPlanTransport sink)sink.dispatchPlan(pending);
        else pending.dispatchOnce(delegate::batch);
    }
    private static void requireBusinessOnly(String wire){
        var requests=parse(wire).path("requests");if(!requests.isArray())throw new Rejected();
        for(var request:requests){
            if(request.has("addSheet")||request.has("deleteSheet")||request.has("updateSheetProperties")||request.has("deleteDeveloperMetadata"))throw new Rejected();
            if(request.has("updateDeveloperMetadata")){
                var update=request.get("updateDeveloperMetadata");
                var id=update.path("dataFilters").path(0).path("developerMetadataLookup").path("metadataId");
                if(id.asInt(-1)!=1)throw new Rejected();
                var root=parse(update.path("developerMetadata").path("metadataValue").asText());
                if(!Set.of("OPEN","CLOSED").contains(root.path("state").asText()))throw new Rejected();
            }
        }
    }
}
