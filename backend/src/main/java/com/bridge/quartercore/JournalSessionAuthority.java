package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Process/session capability for delegated ordinary writes, not permission to delete data.
 * No public constructor, environment parser, unverified renewal or serialized approval flag.
 * Trusted integration must provision only after explicit source-cutover authorization and
 * independently verified exclusive ownership. That provider is not installed by default.
 */
public final class JournalSessionAuthority implements AutoCloseable {
  public enum State { READY, PREPARED, IN_FLIGHT, VERIFYING, EXPIRED_CLEAN, UNKNOWN, CLOSED }
  @FunctionalInterface interface ApprovedCutoverVerifier {
    /** Verify actual authorization+source cutover+ownership, bound to these exact inputs.
     * Must not infer approval from an environment flag, Sheet row or CLOSED marker. */
    void verify(GoogleSheetsBinding binding,String owner,String sourceVersion,String approvalReference,String fullSnapshotToken);
  }
  private final GoogleSheetsBinding binding;private final String owner,sourceVersion,approvalReference,approvedBaselineToken;
  private final ApprovedCutoverVerifier cutover;private final Clock clock,businessClock;private final Instant expiresAt;private final Duration sessionDuration;
  private final long monotonicDeadline;private Instant lastTime;private Book confirmed;private JournalPlan pending;
  private State state=State.READY;private int writes;private Dispatch active;
  private JournalSessionAuthority(GoogleSheetsBinding binding,Book baseline,String owner,String sourceVersion,String approvalReference,
      ApprovedCutoverVerifier cutover,Clock clock,Duration duration) {
    this(binding,baseline,owner,sourceVersion,approvalReference,cutover,clock,clock,duration,null);
  }
  private JournalSessionAuthority(GoogleSheetsBinding binding,Book baseline,String owner,String sourceVersion,String approvalReference,
      ApprovedCutoverVerifier cutover,Clock clock,Clock businessClock,Duration duration,String originalApprovalBaseline) {
    this.binding=Objects.requireNonNull(binding);this.cutover=Objects.requireNonNull(cutover);this.clock=Objects.requireNonNull(clock);this.businessClock=Objects.requireNonNull(businessClock);
    ascii(owner,40);ascii(sourceVersion,96);ascii(approvalReference,128);
    if(owner.isBlank()||sourceVersion.isBlank()||approvalReference.isBlank()||duration==null||duration.isNegative()
        ||duration.isZero()||duration.compareTo(Duration.ofHours(1))>0)throw new Rejected();
    NativeCommitJournal.verify(binding.manualBinding(),baseline,businessClock);
    this.owner=owner;this.sourceVersion=sourceVersion;this.approvalReference=approvalReference;confirmed=baseline.copy();approvedBaselineToken=originalApprovalBaseline==null?fullToken(baseline):originalApprovalBaseline;
    sessionDuration=duration;lastTime=clock.instant();expiresAt=lastTime.plus(duration);monotonicDeadline=System.nanoTime()+duration.toNanos();
    verifyCutover();
  }
  /** Package-private trusted provisioning seam; no production provider invokes it by default. */
  static JournalSessionAuthority provision(GoogleSheetsBinding binding,Book baseline,String owner,String sourceVersion,String approvalReference,
      ApprovedCutoverVerifier verifier,Clock clock,Duration duration) {
    return new JournalSessionAuthority(binding,baseline,owner,sourceVersion,approvalReference,verifier,clock,duration);
  }
  /** Explicit package-local assembly only; security expiry never follows simulated business time. */
  static JournalSessionAuthority provision(GoogleSheetsBinding binding,Book baseline,String owner,String sourceVersion,String approvalReference,
      ApprovedCutoverVerifier verifier,Clock securityClock,Clock businessClock,Duration duration) {
    return new JournalSessionAuthority(binding,baseline,owner,sourceVersion,approvalReference,verifier,securityClock,businessClock,duration,null);
  }
  /** Same-source renewal only; clean idle expiry may revalidate, UNKNOWN may not.
   * Fresh snapshot must equal the last positively confirmed journal state. No remote write. */
  synchronized JournalSessionAuthority renew(Book fresh,Duration duration) {
    requireReadSession(binding);
    if((state!=State.READY&&state!=State.EXPIRED_CLEAN)||pending!=null||active!=null||fresh==null||duration==null||duration.compareTo(sessionDuration)>0)throw new Rejected();
    try {
      NativeCommitJournal.verify(binding.manualBinding(),fresh,businessClock);
      var root=root(new ArrayList<>(fresh.metadata.values()));
      if(!fullToken(fresh).equals(fullToken(confirmed))||!root.owner().equals(owner)
          ||!Set.of("OPEN","CLOSED").contains(root.state()))throw new Rejected();
      verifyCutover();
      var replacement=new JournalSessionAuthority(binding,fresh,owner,sourceVersion,approvalReference,cutover,clock,businessClock,duration,approvedBaselineToken);
      close();return replacement;
    }catch(RuntimeException failure){closeUnknown();throw failure;}
  }
  synchronized Duration sessionDuration(){return sessionDuration;}
  synchronized boolean renewalDue(){
    requireReadSession(binding);return state==State.EXPIRED_CLEAN||state==State.READY&&(writes>=128||!clock.instant().plusSeconds(60).isBefore(expiresAt));
  }
  public synchronized State state(){return state;}
  private boolean observeTime(){
    Instant now=clock.instant();
    if(now.isBefore(lastTime)){closeUnknown();return false;}
    lastTime=now;
    if(!now.isBefore(expiresAt)||System.nanoTime()>=monotonicDeadline){
      if((state==State.READY||state==State.EXPIRED_CLEAN)&&pending==null&&active==null)state=State.EXPIRED_CLEAN;
      else if(state!=State.CLOSED)closeUnknown();
      return false;
    }
    return true;
  }
  private void validTime(){if(!observeTime())throw new Rejected();}
  /** Original fixed-target read channel only. Clean expiry cannot arm or dispatch writes. */
  synchronized void requireReadSession(GoogleSheetsBinding requested){
    boolean timely=observeTime();
    if((!timely&&state!=State.EXPIRED_CLEAN)||!binding.equals(requested)||state==State.UNKNOWN||state==State.CLOSED)throw new Rejected();
    verifyCutover();
  }
  static String fullToken(Book book){return FinalMatchEnvelope.hash(NativeCommitJournal.canonical(book.nativeValue()));}
  private void verifyCutover(){
    try{cutover.verify(binding,owner,sourceVersion,approvalReference,approvedBaselineToken);}
    catch(RuntimeException denied){closeUnknown();throw new Rejected();}
  }
  synchronized void requireSession(GoogleSheetsBinding requested) {
    validTime();if(!binding.equals(requested)||state==State.UNKNOWN||state==State.CLOSED)throw new Rejected();verifyCutover();
  }
  synchronized void arm(JournalPlan plan) {
    requireSession(binding);if(state!=State.READY||plan==null||writes>=128)throw new Rejected();
    if(!plan.beforeDigest().equals(NativeCommitJournal.digest(binding.manualBinding(),confirmed)))throw new Rejected();
    Book expected=plan.expected();NativeCommitJournal.verify(binding.manualBinding(),expected,businessClock);
    if(!NativeCommitJournal.canonical(simulate(confirmed,plan.wire()).nativeValue()).equals(NativeCommitJournal.canonical(expected.nativeValue())))throw new Rejected();
    // Re-derive the allowed atomic request sequence, not just its simulated final state.
    Root beforeRoot=root(new ArrayList<>(confirmed.metadata.values())),afterRoot=root(new ArrayList<>(expected.metadata.values()));
    JournalPlan rebuilt;
    if(afterRoot.epoch()!=beforeRoot.epoch())rebuilt=JournalRotation.plan(binding.manualBinding(),confirmed,owner,businessClock);
    else {
      var requests=parse(plan.wire()).path("requests");if(!requests.isArray()||requests.size()<4)throw new Rejected();
      var business=new ArrayList<Object>();for(int i=0;i<requests.size()-1;i++)business.add(JSON.convertValue(requests.get(i),Object.class));
      rebuilt=NativeCommitJournal.append(binding.manualBinding(),confirmed,wire(business),businessClock);
    }
    if(!rebuilt.wire().equals(plan.wire()))throw new Rejected();
    requireOrdinary(confirmed,expected);pending=plan;state=State.PREPARED;
  }
  private void requireOrdinary(Book before,Book after) {
    Root a=root(new ArrayList<>(before.metadata.values())),b=root(new ArrayList<>(after.metadata.values()));
    if(!b.owner().equals(owner)||!Set.of("OPEN","CLOSED").contains(b.state()))throw new Rejected();
    var old=before.tables();var next=after.tables();
    for(String tab:List.of("dataset","retention_ledger","quarterly_aggregates","erasure_ledger"))if(!old.get(tab).equals(next.get(tab)))throw new Rejected();
    if(a.state().equals("CLOSED")) {
      if(!b.state().equals("OPEN")||!b.operation().equals("CLAIM-"+owner)||!b.hash().equals(hash(owner))||!old.equals(next))throw new Rejected();
      return;
    }
    if(!a.state().equals("OPEN")||!a.owner().equals(owner))throw new Rejected();
    if(b.state().equals("CLOSED")&&(!b.operation().equals("CLOSE-"+owner)||!b.hash().equals(hash(owner))||!old.equals(next)))throw new Rejected();
    for(String tab:List.of("auth_identities","commits","envelopes")) {
      var previous=old.get(tab);var updated=next.get(tab);
      if(updated.size()<previous.size()||!updated.subList(0,previous.size()).equals(previous))throw new Rejected();
    }
    var accounts=old.get("accounts");var updated=next.get("accounts");
    if(updated.size()<accounts.size()||!accounts.get(0).equals(updated.get(0)))throw new Rejected();
    int profileChanges=0;
    for(int i=1;i<accounts.size();i++) {
      var prior=accounts.get(i);var now=updated.get(i);
      if(!prior.equals(now)){profileChanges++;if(now.size()<4||!"2".equals(now.get(1)))throw new Rejected();}
      if(!prior.get(0).equals(now.get(0))||!Objects.equals(prior.size()==5?prior.get(4):"",now.size()==5?now.get(4):""))throw new Rejected();
    }
    int identityAdds=next.get("auth_identities").size()-old.get("auth_identities").size();
    int accountAdds=updated.size()-accounts.size(),commitAdds=next.get("commits").size()-old.get("commits").size();
    int chunkAdds=next.get("envelopes").size()-old.get("envelopes").size();
    if(profileChanges>1||accountAdds>1||identityAdds>1||accountAdds!=identityAdds||commitAdds>1
        ||(commitAdds==0)!=(chunkAdds==0))throw new Rejected();
    int categories=(profileChanges>0?1:0)+(identityAdds>0?1:0)+(commitAdds>0?1:0);
    if(categories>1)throw new Rejected();
    if(b.epoch()!=a.epoch()&&!old.equals(next))throw new Rejected(); // Layout-only rotation, no erasure.
    if(categories==0&&b.state().equals("OPEN")&&b.epoch()==a.epoch())throw new Rejected();
  }
  synchronized void requireReady(String exactTarget){
    requireSession(binding);if(state!=State.PREPARED||pending==null||!(binding.path()+":batchUpdate").equals(exactTarget))throw new Rejected();
  }
  synchronized Dispatch beforeDispatch(String exactTarget,String exactWire) {
    requireReady(exactTarget);if(!pending.wire().equals(exactWire))throw new Rejected();
    writes++;state=State.IN_FLIGHT;active=new Dispatch(this);return active;
  }
  synchronized void verified(Book fresh) {
    validTime();if(state!=State.VERIFYING||pending==null||!pending.matchesAfter(fresh)){closeUnknown();throw new Rejected();}
    // Verify new committed state and source ownership before admitting another plan.
    try{NativeCommitJournal.verify(binding.manualBinding(),fresh,businessClock);confirmed=fresh.copy();verifyCutover();pending=null;state=State.READY;}
    catch(RuntimeException invalid){closeUnknown();throw new Rejected();}
  }
  synchronized void closeUnknown(){if(state!=State.CLOSED){state=State.UNKNOWN;pending=null;active=null;}}
  @Override public synchronized void close(){state=State.CLOSED;pending=null;active=null;}
  static final class Dispatch implements GoogleDispatchAuthority.Dispatch {
    private final JournalSessionAuthority owner;private boolean ended;
    private Dispatch(JournalSessionAuthority owner){this.owner=owner;}
    public void acknowledged(){synchronized(owner){if(ended||owner.active!=this||owner.state!=State.IN_FLIGHT)throw new Rejected();ended=true;owner.validTime();owner.active=null;owner.state=State.VERIFYING;}}
    public void unknown(){synchronized(owner){if(ended)return;ended=true;owner.closeUnknown();}}
  }
  @Override public String toString(){return "JournalSessionAuthority[REDACTED]";}
}
