package com.bridge.persistence;

import java.time.*;
import java.util.*;
import static com.bridge.persistence.NativeSheetsModel.*;
import static com.bridge.persistence.NarrowQuarterPoc.*;

/** Final write sink capability. No public constructor, renewal, replay, or recovery.
 * A transport checks readiness before credentials, then consumes before the FIRST POST byte.
 * A decoded ACK is not a whole-dataset proof. Quarter CLOSE is sealed only after that proof.
 * Closing cannot roll back an entered dispatch; no later dispatch is admitted.
 */
final class WritePermit {
  enum Status { READY, IN_FLIGHT, CLOSE_VERIFYING, FINISHED, CLOSED_UNKNOWN, CLOSED_DENIED }
  private final BootArm owner;
  private BootArm.Plan plan;
  private final String nonce,target;
  private final Instant issuedAt,expiresAt;
  private final int writeCount;
  private final long deadline;
  private Status status=Status.READY;
  private int next;
  private Instant observedAt=Instant.now();
  private Dispatch active;
  private QuarterRun quarterRun;
  private String completeWire,closeWire,closeDigest;
  private final boolean quarterMode;
  private WritePermit(BootArm owner,BootArm.Plan plan,String nonce){
    if(owner==null||plan==null||!owner.owns(plan,nonce))throw BootArm.denied();
    this.owner=owner;this.plan=plan;this.nonce=nonce;this.target=plan.mutationPath();
    this.quarterMode=plan.quarter()!=null;
    this.issuedAt=plan.issuedAt();this.expiresAt=plan.expiresAt();this.writeCount=plan.writeCount();
    this.deadline=System.nanoTime()+plan.stageNanos();
  }
  static WritePermit issue(BootArm owner,BootArm.Plan plan,String nonce){
    if(owner==null||!owner.takePermitIssuance(plan,nonce))throw BootArm.denied();
    return new WritePermit(owner,plan,nonce);
  }
  synchronized Status status(){return status;}
  synchronized int dispatched(){return next;}
  synchronized void requireMaintenance(GoogleSheetsBinding binding){
    if(binding==null||!quarterMode||plan==null||plan.quarter()==null||next!=0
        ||!plan.quarter().binding().equals(binding.manualBinding())){deny();throw BootArm.denied();}
    requireReady(binding.path()+":batchUpdate");
  }
  /** Only before startup, and only the actual Service owner/binding/limits authorized by the plan.
   * This is a typed in-process capability, not remote attestation of caller-provided Book objects. */
  synchronized void bindQuarter(Service service){
    requireReady(target);
    if(next!=0||quarterRun!=null||plan.quarter()==null||service==null
        ||!service.owner.equals(plan.quarter().sourceRoot().owner())||!service.binding.equals(plan.quarter().binding())
        ||!service.limits.equals(plan.quarterLimits())){deny();throw BootArm.denied();}
    try{plan.quarter().requireWindow(Instant.now());}catch(RuntimeException invalid){deny();throw BootArm.denied();}
    QuarterRun run=new QuarterRun(this,plan,plan.expectedArm(nonce));
    try{service.bindQuarterRun(run);quarterRun=run;}catch(RuntimeException failure){deny();throw BootArm.denied();}
  }
  synchronized void requireReady(String exactTarget){
    if(status!=Status.READY||plan==null||!owner.owns(plan,nonce)||!target.equals(exactTarget)||!timeValid()){
      deny();throw BootArm.denied();
    }
  }
  synchronized Dispatch beforeDispatch(String exactTarget,String exactWire){
    requireReady(exactTarget);
    String expected=plan.quarter()==null||next<3?next<plan.wiresCount()?plan.wire(next):null:next==3?completeWire:closeWire;
    if(next>=writeCount||expected==null||!expected.equals(exactWire)||plan.quarter()!=null&&quarterRun==null){deny();throw BootArm.denied();}
    if(quarterMode){try{plan.quarter().requireWindow(Instant.now());}catch(RuntimeException invalid){deny();throw BootArm.denied();}}
    active=new Dispatch(this,next++);status=Status.IN_FLIGHT;return active;
  }
  synchronized void closeUnknown(){if(status!=Status.FINISHED&&status!=Status.CLOSED_DENIED){status=Status.CLOSED_UNKNOWN;release();}}
  synchronized void closeDenied(){deny();}
  private void deny(){if(status!=Status.FINISHED&&status!=Status.CLOSED_UNKNOWN){status=Status.CLOSED_DENIED;release();}}
  private void release(){
    if(plan!=null)owner.release(plan,nonce);
    plan=null;quarterRun=null;completeWire=null;closeWire=null;active=null;
    // At most one compact expected-CLOSED digest survives a final-attempt UNKNOWN.
    // It supports read-only classification, never renewed write authority.
    if(!quarterMode||next<writeCount)closeDigest=null;
  }
  private synchronized QuarterPlan.Completion freezeCompletion(QuarterRun run,Book applied,Clock clock){
    requireReady(target);
    if(quarterRun!=run||next!=3||completeWire!=null||run.completion!=null){deny();throw BootArm.denied();}
    try{
      run.intent.requireWindow(clock);
      // New armed mode uses the actual post-read wall clock for verifiedAt. The supplied
      // business clock is still fenced, but cannot invent or predate completion time.
      QuarterPlan.Completion proof=run.execution.completeAfterVerifiedRead(applied,Clock.systemUTC());
      if(proof.ticket().wire().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256*1024)throw BootArm.denied();
      completeWire=proof.ticket().wire();run.completion=proof;return proof;
    }catch(RuntimeException failure){deny();throw failure;}
  }
  private synchronized void verifiedComplete(QuarterRun run,QuarterPlan.Completion proof,Book actual,Clock clock){
    // Read-only core reconciliation may prove an already-applied UNKNOWN. It cannot renew authority.
    if(status==Status.CLOSED_UNKNOWN||status==Status.CLOSED_DENIED||status==Status.FINISHED)return;
    requireReady(target);
    if(quarterRun!=run||next!=4||proof==null||proof!=run.completion||closeWire!=null
        ||!run.execution.expectedMatches(proof.ticket(),proof,actual)){deny();throw BootArm.denied();}
    run.intent.requireWindow(clock);
    Root before=proof.ticket().after();Root closed=new Root(before.dataset(),before.epoch(),before.anchor(),before.nextSheet(),
        before.next()+1,before.owner(),"CLOSED","CLOSE-"+before.owner(),hash(before.owner()));
    closeWire=QuarterPlan.phaseWire(before,closed,List.of());
    // Only the exact native guard + receipt + CLOSED transition can follow the verified proof.
    if(closeWire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256*1024){deny();throw BootArm.denied();}
    Book expected=simulate(actual,closeWire);expected.budgets();closeDigest=QuarterPlan.bookDigest(expected);
  }
  private synchronized void finish(Dispatch call,boolean success){
    if(active!=call||status!=Status.IN_FLIGHT){deny();throw BootArm.denied();}
    active=null;
    if(!success){status=Status.CLOSED_UNKNOWN;release();return;}
    if(!timeValid()){deny();throw BootArm.denied();}
    status=next==writeCount?(quarterMode?Status.CLOSE_VERIFYING:Status.FINISHED):Status.READY;
    if(status==Status.FINISHED||status==Status.CLOSE_VERIFYING)release();
  }
  synchronized boolean verifiedClosed(Book actual){
    if(status==Status.CLOSED_UNKNOWN||status==Status.CLOSED_DENIED)
      return quarterMode&&next==writeCount&&closeDigest!=null&&closeDigest.equals(QuarterPlan.bookDigest(actual));
    if(!quarterMode||status!=Status.CLOSE_VERIFYING||closeDigest==null||!timeValid()
        ||!closeDigest.equals(QuarterPlan.bookDigest(actual))){closeUnknown();return false;}
    status=Status.FINISHED;release();return true;
  }
  private boolean timeValid(){
    Instant now=Instant.now();boolean valid=System.nanoTime()-deadline<0&&!now.isBefore(observedAt)
        &&!now.isBefore(issuedAt)&&now.isBefore(expiresAt);observedAt=now;return valid;
  }
  /** Exact fixed intent plus immutable arm envelope, available only from a consumed permit.
   * All Books must come from Service.full(); this object does not attest a network read by itself. */
  static final class QuarterRun {
    private final WritePermit permit;
    private final BootArm.Plan authority;
    private final QuarterPlan intent;
    private final QuarterPlan.Execution execution;
    private final Metadata arm;
    private QuarterPlan.Completion completion;
    private QuarterRun(WritePermit permit,BootArm.Plan authority,Metadata arm){
      this.permit=permit;this.authority=authority;this.intent=authority.quarter();this.arm=arm;this.execution=intent.bindExactArm(arm);
    }
    QuarterPlan intent(){return intent;}
    boolean closedMatches(Book b){return authority.closedMatches(b,arm);}
    boolean sourceMatches(Book b){return execution.sourceMatches(b);}
    boolean preparedMatches(Book b){return execution.preparedMatches(b);}
    boolean foldedMatches(Book b){return execution.foldedMatches(b);}
    boolean expectedMatches(Ticket t,QuarterPlan.Completion proof,Book b){return execution.expectedMatches(t,proof,b);}
    QuarterPlan.Completion completeAfterVerifiedRead(Book b,Clock clock){return permit.freezeCompletion(this,b,clock);}
    void verifiedComplete(QuarterPlan.Completion proof,Book b,Clock clock){permit.verifiedComplete(this,proof,b,clock);}
    WritePermit permit(){return permit;}
  }
  static final class Dispatch {
    private final WritePermit permit;
    private final int sequence;
    private boolean completed;
    private Dispatch(WritePermit permit,int sequence){this.permit=permit;this.sequence=sequence;}
    synchronized void acknowledged(){if(completed)throw BootArm.denied();completed=true;permit.finish(this,true);}
    synchronized void unknown(){if(completed)return;completed=true;permit.finish(this,false);}
    int sequence(){return sequence;}
  }
}
