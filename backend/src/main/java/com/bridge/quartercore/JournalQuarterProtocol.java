package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Journal-quarter-v1 local protocol driver. No Spring registration, Google sink,
 * credential access, old WritePermit reuse, or source-ownership authorization.
 * Every expected full Book includes the current journal. Original QuarterPlan is used
 * ONLY for its immutable business before/pending intent, not its old metadata proof.
 */
public final class JournalQuarterProtocol {
  public enum Status { READY, RUNNING, VERIFIED, REVIEW }
  public enum RecoveryState { CLEAN_CURRENT, READY_DUE, REVIEW, UNVERIFIED }
  public record Recovery(RecoveryState state,Instant cutoff,String operation,long sequence) {}
  private final ManualQuarterMaintenance.Binding binding;private final NarrowSheetsTransport transport;
  private final Clock clock;private final String owner;
  private JournalQuarterAuthority authority;
  private Status status=Status.READY;private int dispatched;private boolean started,lastReadMatchesExpected;
  private Instant quarter,deadline,lastObserved;private long monotonicDeadline;private Book current;
  JournalQuarterProtocol(ManualQuarterMaintenance.Binding binding,NarrowSheetsTransport transport,Clock clock,String owner) {
    this.binding=Objects.requireNonNull(binding);this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);
    ascii(owner,40);if(owner.isBlank())throw new Rejected();this.owner=owner;
  }
  JournalQuarterProtocol(ManualQuarterMaintenance.Binding binding,NarrowSheetsTransport transport,Clock clock,String owner,JournalQuarterAuthority authority){
    this(binding,transport,clock,owner);this.authority=Objects.requireNonNull(authority);authority.requireContext(binding,owner);
  }
  public synchronized Status status(){return status;}
  public synchronized int dispatched(){return dispatched;}
  public synchronized boolean lastReadMatchesExpected(){return lastReadMatchesExpected;}
  /** Classification only: even CLEAN_CURRENT does not prove source cutover, exclusive
   * ownership, absence of late requests, or grant permission to resume gameplay. */
  static Recovery inspect(ManualQuarterMaintenance.Binding binding,Book book,Clock clock) {
    try {
      var evidence=NativeCommitJournal.verify(binding,book,clock);
      Root root=root(new ArrayList<>(book.metadata.values()));Instant quarter=QuarterlyRetention.currentQuarterStart(clock.instant());
      RetentionLedger ledger=new RetentionLedger(book.tables().get("retention_ledger"));
      if(!root.state().equals("CLOSED"))return new Recovery(RecoveryState.REVIEW,ledger.cutoff(),evidence.operation(),evidence.sequence());
      if(!ledger.complete()||ledger.cutoff().isAfter(quarter))throw new Rejected();
      boolean expired=false,compatible=true;
      for(var e:ManualQuarterMaintenance.decoded(book.tables()).values())if(QuarterlyRetention.finishedAt(e).isBefore(quarter)) {
        expired=true;var match=e.payload().path("match");compatible&=LifetimeStatistics.eligibleForRetention(e);
      }
      if(ledger.cutoff().equals(quarter)) {
        if(expired)throw new Rejected();return new Recovery(RecoveryState.CLEAN_CURRENT,quarter,evidence.operation(),evidence.sequence());
      }
      return new Recovery(compatible?RecoveryState.READY_DUE:RecoveryState.REVIEW,ledger.cutoff(),evidence.operation(),evidence.sequence());
    }catch(RuntimeException invalid){return new Recovery(RecoveryState.UNVERIFIED,null,"",0);}
  }
  public synchronized Recovery inspect() {
    try(var scope=transport.operation("journal-quarter-inspect",null,BatchedSheetsTransport.ALL,Duration.ofSeconds(60))){return inspect(binding,read(),clock);}catch(RuntimeException invalid){return new Recovery(RecoveryState.UNVERIFIED,null,"",0);}
  }
  /** Single local execution. Caller must separately authorize the sink and stop all other
   * writers. Unknown (including applied-but-unacknowledged) halts; no phase is resent. */
  synchronized void start() {
    if(started)throw new Rejected();started=true;status=Status.RUNNING;
    try {
      lastObserved=clock.instant();quarter=QuarterlyRetention.currentQuarterStart(lastObserved);deadline=lastObserved.plusSeconds(300);monotonicDeadline=System.nanoTime()+Duration.ofSeconds(300).toNanos();
      try(var scope=transport.operation("journal-quarter-baseline",null,BatchedSheetsTransport.ALL,phaseBudget())){current=read();}if(inspect(binding,current,clock).state()!=RecoveryState.READY_DUE)throw new Rejected();
      QuarterPhasePlans plans=new QuarterPhasePlans(binding,owner,clock);
      for(int index=0;index<5;index++)if(!phase(authority==null?plans.next(current,index):authority.nextPlan()))return;
      if(inspect(binding,current,clock).state()!=RecoveryState.CLEAN_CURRENT)throw new Rejected();status=Status.VERIFIED;
    }catch(RuntimeException rejected){if(authority!=null)authority.unknown();status=Status.REVIEW;}
  }
  private Book read(){Book book=transport.snapshot();if(transport.unsupportedStructure())throw new Rejected();NativeCommitJournal.verify(binding,book,clock);return book;}
  private void requireWindow() {
    Instant now=clock.instant();if(System.nanoTime()>=monotonicDeadline||now.isBefore(lastObserved)||!now.isBefore(deadline)||!QuarterlyRetention.currentQuarterStart(now).equals(quarter))throw new Rejected();lastObserved=now;
  }
  private Duration phaseBudget() {
    long remaining=monotonicDeadline-System.nanoTime();if(remaining<=0)throw new Rejected();
    return Duration.ofNanos(Math.min(Duration.ofSeconds(60).toNanos(),remaining));
  }
  private boolean phase(JournalPlan plan) {
    requireWindow();
    try(var scope=transport.operation("journal-quarter-phase",root(new ArrayList<>(current.metadata.values())),BatchedSheetsTransport.ALL,phaseBudget())) {
    Book fresh=read();
    if(!NativeCommitJournal.canonical(fresh.nativeValue()).equals(NativeCommitJournal.canonical(current.nativeValue())))throw new Rejected();
    if(!plan.beforeDigest().equals(NativeCommitJournal.digest(binding,current)))throw new Rejected();
    boolean acknowledged=true;dispatched++;lastReadMatchesExpected=false;
    try{if(authority!=null)authority.beforeDispatch(plan);if(transport instanceof JournalPlanTransport sink)sink.dispatchPlan(plan);else plan.dispatchOnce(transport::batch);}catch(RuntimeException unknown){acknowledged=false;}
    try{Book after=read();lastReadMatchesExpected=plan.matchesAfter(after);if(lastReadMatchesExpected)current=after;}
    catch(RuntimeException unknown){lastReadMatchesExpected=false;}
    if(!acknowledged||!lastReadMatchesExpected){if(authority!=null)authority.unknown();status=Status.REVIEW;return false;}
    if(authority!=null)authority.verified(current);
    requireWindow();return true;
    }
  }
}
