package com.bridge.quartercore;

import java.time.Clock;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Versioned bounded latest-commit evidence, not a lock, signature, or historical audit log.
 * Exactly one metadata record is updated in the SAME atomic batch as data/root/receipt.
 * Existing receipt-slot uniqueness supplies predecessor CAS. Manual/legacy writers that
 * change data without this protocol invalidate the digest; coordinated malicious editing
 * of data and proof is outside this mechanism (no secret signing key is introduced).
 * predecessorProofHash/beforeDigest bind the exact predecessor while PLANNING; because
 * only the latest proof is retained, restart cannot independently reconstruct their history.
 * Restart verifies current afterDigest/root/receipt only, not a complete historical chain.
 */
public final class NativeCommitJournal {
  static final int ID=20001;static final String KEY="bg.commit.v1";static final String ZERO="0".repeat(64);
  record Proof(int version,long sequence,String targetHash,int epoch,int receiptSlot,String operation,
      String predecessorProofHash,String beforeDigest,String afterDigest) {}
  public record Evidence(long sequence,String operation,String snapshotDigest,String proofHash) {}
  private NativeCommitJournal() {}
  /** Real persisted-proof verifier for QuarterNativeControl. Reads afresh, validates the
   * stored journal against all current content, and binds the caller's full snapshot token.
   * Ownership remains an independent requirement and is not supplied by this verifier. */
  static QuarterNativeControl.CommittedSnapshotVerifier verifier(QuarterNativeControl.Target fixedTarget,
      QuarterNativeControl.NativeReader fullNativeReader,Clock clock) {
    Objects.requireNonNull(fixedTarget);Objects.requireNonNull(fullNativeReader);Objects.requireNonNull(clock);
    return (requested,callerToken)->{
      if(!fixedTarget.equals(requested))throw new Rejected();
      Book fresh=fullNativeReader.readVerified();verify(fixedTarget.binding(),fresh,clock);
      if(!QuarterPlan.bookDigest(fresh).equals(callerToken))throw new Rejected();
    };
  }
  static String canonical(Object value) { return json(sorted(JSON.valueToTree(value))); }
  private static Object sorted(com.fasterxml.jackson.databind.JsonNode n) {
    if(n.isObject()){var m=new TreeMap<String,Object>();n.fields().forEachRemaining(e->m.put(e.getKey(),sorted(e.getValue())));return m;}
    if(n.isArray()){var a=new ArrayList<Object>();n.forEach(v->a.add(sorted(v)));return a;}
    if(n.isTextual())return n.textValue();if(n.isIntegralNumber())return n.bigIntegerValue();
    if(n.isBoolean())return n.booleanValue();if(n.isNull())return null;throw new Rejected();
  }
  private static String sha(Object value){return FinalMatchEnvelope.hash(canonical(value));}
  private static String target(ManualQuarterMaintenance.Binding binding) {
    return sha(List.of("journal-target-v1",binding.spreadsheetId(),binding.datasetId(),binding.purpose()));
  }
  /** Excludes only the single journal record itself. Includes all other metadata, root,
   * receipts, eight tables, sheet IDs/layout and explicit configured target binding. */
  static String digest(ManualQuarterMaintenance.Binding binding,Book book) {
    Book projected=book.copy();
    for(var m:book.metadata.values())if(m.id()==ID||m.key().equals(KEY)) {
      if(m.id()!=ID||!m.key().equals(KEY)||m.sheet()!=null)throw new Rejected();projected.metadata.remove(ID);
    }
    return sha(List.of("journal-content-v1",target(binding),projected.nativeValue()));
  }
  private static Proof proof(ManualQuarterMaintenance.Binding binding,Book book) {
    var m=book.metadata.get(ID);if(m==null||!m.key().equals(KEY)||m.sheet()!=null||m.characters()>2048)throw new Rejected();
    try {
      Proof p=BoundedSheetsProjection.MAPPER.readValue(m.value(),Proof.class);
      if(p.version()!=1||p.sequence()<1||p.sequence()>9_007_199_254_740_991L||!p.targetHash().equals(target(binding))
          ||!p.predecessorProofHash().matches("[0-9a-f]{64}")||!p.beforeDigest().matches("[0-9a-f]{64}")
          ||!p.afterDigest().matches("[0-9a-f]{64}")||p.sequence()==1&&!p.predecessorProofHash().equals(ZERO)
          ||p.sequence()>1&&p.predecessorProofHash().equals(ZERO)||!m.value().equals(canonical(p)))throw new Rejected();
      ascii(p.operation(),96);return p;
    }catch(Exception bad){throw new Rejected();}
  }
  private static void structure(ManualQuarterMaintenance.Binding binding,Book book,Clock clock) {
    ManualQuarterMaintenance.validate(new ManualQuarterMaintenance.Snapshot(binding.spreadsheetId(),true,book.tables()),binding,clock);
    BoundedSheetsProjection.validateNarrowControl(book);book.budgets();
    if(book.sheets.size()!=8||bytes(book.nativeValue())>Limits.defaults().futureReadBytes())throw new Rejected();
    for(var s:book.sheets.values())if(s.rowCount!=1000||s.columnCount!=26||s.rows.size()>1000)throw new Rejected();
    for(var m:book.metadata.values())BoundedSheetsProjection.strictControlValue(m);
  }
  static Evidence verify(ManualQuarterMaintenance.Binding binding,Book book,Clock clock) {
    structure(binding,book,clock);Proof p=proof(binding,book);Root root=root(new ArrayList<>(book.metadata.values()));
    if(p.epoch()!=root.epoch()||p.receiptSlot()!=root.next()-1||!p.operation().equals(root.operation())
        ||!p.afterDigest().equals(digest(binding,book)))throw new Rejected();
    return new Evidence(p.sequence(),p.operation(),p.afterDigest(),sha(p));
  }
  /** Explicit one-time migration only, with separately approved exact baseline and writer
   * cutover. Never inferred from an empty/missing journal. No data values are changed. */
  static JournalPlan bootstrap(ManualQuarterMaintenance.Binding binding,Book before,String approvedBaselineDigest,Clock clock) {
    structure(binding,before,clock);
    if(before.metadata.values().stream().anyMatch(m->m.id()==ID||m.key().equals(KEY))
        ||!digest(binding,before).equals(approvedBaselineDigest))throw new Rejected();
    Root old=root(new ArrayList<>(before.metadata.values()));if(!old.state().equals("CLOSED"))throw new Rejected();
    String operation="JOURNAL-BOOTSTRAP-"+approvedBaselineDigest.substring(0,16);
    Root next=new Root(old.dataset(),old.epoch(),old.anchor(),old.nextSheet(),old.next()+1,old.owner(),"CLOSED",operation,hash(operation));
    String wire=QuarterPlan.phaseWire(old,next,List.of());
    return combine(binding,before,wire,1,ZERO,true,clock);
  }
  static JournalPlan append(ManualQuarterMaintenance.Binding binding,Book before,String protocolWire,Clock clock) {
    Evidence prior=verify(binding,before,clock);
    return combine(binding,before,protocolWire,Math.addExact(prior.sequence(),1),prior.proofHash(),false,clock);
  }
  private static JournalPlan combine(ManualQuarterMaintenance.Binding binding,Book before,String protocolWire,
      long sequence,String predecessorProof,boolean initial,Clock clock) {
    Root old=root(new ArrayList<>(before.metadata.values()));Book business=simulate(before,protocolWire);
    if(!Objects.equals(before.metadata.get(ID),business.metadata.get(ID)))throw new Rejected();
    Root next=root(new ArrayList<>(business.metadata.values()));
    // First journal version intentionally rejects anchor/epoch rotation. Do not silently
    // drop evidence or rewrite receipt history; add a separately tested rotation protocol.
    if(!next.dataset().equals(old.dataset())||next.epoch()!=old.epoch()||next.anchor()!=old.anchor()
        ||next.nextSheet()!=old.nextSheet()||next.next()!=old.next()+1)throw new Rejected();
    for(var m:before.metadata.values())if(m.id()!=1&&!Objects.equals(m,business.metadata.get(m.id())))throw new Rejected();
    if(business.metadata.size()!=before.metadata.size()+1
        ||!Objects.equals(business.metadata.get(old.next()),receipt(old,next.operation(),next.hash(),next.state())))throw new Rejected();
    for(var m:before.metadata.values())if(m.key().equals(RECEIPT_KEY)
        &&parse(m.value()).path("operation").asText().equals(next.operation())) {
      // PREPARE and FOLD deliberately share one immutable intent ID/hash. Only the
      // immediately preceding PENDING receipt may advance once to APPLIED; no ordinary
      // operation duplicate, earlier receipt reuse or repeated APPLIED is accepted.
      if(!(old.state().equals("PENDING")&&next.state().equals("APPLIED")
          &&old.operation().equals(next.operation())&&old.hash().equals(next.hash())
          &&m.id()==old.next()-1&&parse(m.value()).path("phase").asText().equals("PENDING")))throw new Rejected();
    }
    // Validate the request sequence, not only its simulated end state. A delete of an
    // absent future receipt followed by create would look identical before dispatch but
    // could erase a competitor's receipt and defeat the uniqueness/CAS on a late request.
    var frame=parse(protocolWire).path("requests");
    if(frame.size()<3||!canonical(frame.get(0)).equals(canonical(guard(old.anchor(),"retention_ledger")))
        ||!canonical(frame.get(1)).equals(canonical(create(receipt(old,next.operation(),next.hash(),next.state()))))
        ||!canonical(frame.get(frame.size()-1)).equals(canonical(updateRoot(next))))throw new Rejected();
    for(int i=2;i<frame.size()-1;i++) {
      var request=frame.get(i);if(request.size()!=1||!(request.has("appendCells")||request.has("updateCells")))throw new Rejected();
    }
    structure(binding,business,clock);
    String beforeDigest=digest(binding,before),afterDigest=digest(binding,business);
    Proof proof=new Proof(1,sequence,target(binding),next.epoch(),old.next(),next.operation(),predecessorProof,beforeDigest,afterDigest);
    String encoded=canonical(proof);Metadata marker=new Metadata(ID,KEY,encoded,null);
    var requests=new ArrayList<Object>();for(var request:parse(protocolWire).path("requests"))requests.add(JSON.convertValue(request,Object.class));
    if(initial)requests.add(create(marker));
    else requests.add(Map.of("updateDeveloperMetadata",Map.of("dataFilters",List.of(Map.of("developerMetadataLookup",Map.of("metadataId",ID))),
        "developerMetadata",Map.of("metadataValue",encoded),"fields","metadataValue")));
    String combined=wire(requests);if(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>Limits.defaults().requestBytes())throw new Rejected();
    Book after=simulate(before,combined);Evidence checked=verify(binding,after,clock);
    if(!checked.snapshotDigest().equals(afterDigest)||marker.characters()>2048)throw new Rejected();
    return new JournalPlan(binding,beforeDigest,combined,after,checked,clock);
  }
}
