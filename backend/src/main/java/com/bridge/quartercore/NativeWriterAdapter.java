package com.bridge.quartercore;

import java.util.Objects;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Local candidate only. No bean, credentials, startup claim, scheduler or network factory.
 * The supplied native transport must prepare the exact journal before dispatch and
 * atomically persist it with data/root/receipt in the same batch. This adapter never records success
 * in a second write and never retries an unknown operation.
 */
public final class NativeWriterAdapter {
    public enum Outcome { VERIFIED, UNKNOWN }
    public record Result(Outcome outcome,String operationId,String accountId,String commitId) {}
    private final Service service;
    private Ticket pending;
    private Result pendingResult;
    NativeWriterAdapter(Service service){this.service=Objects.requireNonNull(service);}

    public Result identity(String operationId,String issuer,String subject,String nickname,String email) {
        synchronized(service.serial){
            requireReady();
            var result=service.managedIdentity(operationId,issuer,subject,nickname,email,true);
            if(result.ticket()==null)return new Result(Outcome.VERIFIED,operationId,result.account(),null);
            return dispatch(result.ticket(),new Result(Outcome.UNKNOWN,operationId,result.account(),null));
        }
    }
    public Result match(String operationId,com.bridge.persistence.FinalMatchEnvelope envelope) {
        Objects.requireNonNull(envelope);
        // No reserialization: both strict codecs must accept the identical canonical payload/hash.
        var nativeEnvelope=new FinalMatchEnvelope(envelope.commitId(),envelope.matchId(),envelope.revision(),envelope.canonicalJson(),envelope.payloadHash());
        synchronized(service.serial){
            requireReady();
            return dispatch(service.match(operationId,nativeEnvelope),
                new Result(Outcome.UNKNOWN,operationId,null,envelope.commitId()));
        }
    }
    /** Same-owner checkpoint only, not restart takeover. Exact journal readback is required
     * before the existing service adopts the new anchor/layout. Failure retires the writer. */
    public void checkpoint(){synchronized(service.serial){
        requireReady();
        if(!(service.endpoint instanceof JournaledNativeTransport journal))throw new IllegalStateException("Journal transport required");
        try {
            var prior=service.known;
            var after=journal.checkpoint(prior);
            var root=NarrowQuarterPoc.root(new java.util.ArrayList<>(after.metadata.values()));
            if(!root.owner().equals(service.owner)||!root.state().equals("OPEN")||root.epoch()!=prior.epoch()+1
                ||root.anchor()!=prior.nextSheet()||root.next()!=3)throw new NativeSheetsModel.Rejected();
            service.adoptBaseline(after);service.known=root;
        } catch(RuntimeException|Error uncertain){service.close();throw uncertain;}
    }}
    /** Retire this in-process writer before an independently authorized layout rotation.
     * Does not close the remote root, transfer ownership or create a replacement writer. */
    public void retireForRotation(){synchronized(service.serial){requireReady();service.close();}}
    /** Only a live gate-issued permit for a previously registered game may cross the boundary. */
    Result matchDrain(String operationId,com.bridge.persistence.FinalMatchEnvelope envelope,
            com.bridge.persistence.QuarterMaintenanceGate.SettlementGrant grant){
        Objects.requireNonNull(envelope);
        var nativeEnvelope=new FinalMatchEnvelope(envelope.commitId(),envelope.matchId(),envelope.revision(),envelope.canonicalJson(),envelope.payloadHash());
        synchronized(service.serial){requireReady();return dispatch(service.matchDrain(operationId,nativeEnvelope,grant),
            new Result(Outcome.UNKNOWN,operationId,null,envelope.commitId()));}
    }
    private void requireReady(){
        if(pending!=null || service.state()!=State.OPEN || service.quarterInProgress())throw new IllegalStateException("Native writer requires verified OPEN state");
    }
    private Result dispatch(Ticket ticket,Result intent){
        pending=ticket;pendingResult=intent;
        service.send(ticket); // ticket already contains receipt + root + data in ONE wire batch
        return outcome();
    }
    private Result outcome(){
        if(service.state()==State.OPEN && service.pending==null && pending.after().equals(service.known)) {
            var done=new Result(Outcome.VERIFIED,pendingResult.operationId(),pendingResult.accountId(),pendingResult.commitId());
            pending=null;pendingResult=null;return done;
        }
        return pendingResult;
    }
    /** Read-only reconciliation of the exact retained ticket. Does not send a request.
     * Across a JVM restart this object is insufficient: journal recovery must reconstruct
     * and verify the exact retained operation before another writer is admitted. */
    public Result reconcilePending(){synchronized(service.serial){
        if(pending==null)throw new IllegalStateException("No unresolved local ticket");
        service.reconcile();return outcome();
    }}
}
