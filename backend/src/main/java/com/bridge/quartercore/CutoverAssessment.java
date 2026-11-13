package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Read-only cutover planning. No credentials, transport, lease or automatic migration.
 * Only the exact known eight-table legacy model can produce a metadata-only plan. */
public final class CutoverAssessment {
  public enum Status { NATIVE_BOOTSTRAP_READY, LEGACY_BOOTSTRAP_READY, ALREADY_JOURNALED, BLOCKED_SCHEMA, BLOCKED_CONTROL }
  public record Report(Status status,String baselineToken,String reason) {}
  public record FreezeEvidence(String reference,String sourceVersion,Instant expiresAt) {
    public FreezeEvidence { Objects.requireNonNull(reference);Objects.requireNonNull(sourceVersion);Objects.requireNonNull(expiresAt);
      if(reference.isBlank()||sourceVersion.isBlank())throw new IllegalArgumentException(); }
  }
  /** Trusted external boundary, not an environment flag or a spreadsheet marker.
   * Must confirm explicit initialization approval, all legacy/manual/import writers stopped,
   * restart disabled and no outstanding requests, for this exact source/target/full baseline.
   * Rechecked before dispatch. Returning means attested; unavailable implementations throw.
   * Cannot establish a distributed lease or prevent an uncooperative external writer. */
  @FunctionalInterface public interface FreezeVerifier {
    void verify(ManualQuarterMaintenance.Binding target,String baselineToken,FreezeEvidence evidence);
    static FreezeVerifier unavailable(){return (t,b,e)->{throw new Rejected();};}
  }
  private CutoverAssessment() {}
  static String token(ManualQuarterMaintenance.Binding target,Book b) {
    return hash(NativeCommitJournal.canonical(List.of("cutover-baseline-v1",target.spreadsheetId(),target.datasetId(),target.purpose(),b.nativeValue())));
  }
  static Report inspect(ManualQuarterMaintenance.Binding target,Book b,Clock clock) {
    String token=token(target,b);
    if(!b.metadata.containsKey(1)) {
      try { LegacyToNativeCutoverPlan.prepare(target,b,clock);return new Report(Status.LEGACY_BOOTSTRAP_READY,token,"Known eight-table schema; metadata-only plan available subject to external freeze approval."); }
      catch(RuntimeException rejected){return new Report(Status.BLOCKED_SCHEMA,token,"Legacy full snapshot does not match supported schema/layout/metadata bounds; no conversion attempted.");}
    }
    try {
      if(b.metadata.values().stream().anyMatch(m->m.id()==NativeCommitJournal.ID||m.key().equals(NativeCommitJournal.KEY))) {
        NativeCommitJournal.verify(target,b,clock);
        return new Report(Status.ALREADY_JOURNALED,token,"Existing journal must not be initialized again; no ownership inferred.");
      }
      NativeCommitJournal.bootstrap(target,b,NativeCommitJournal.digest(target,b),clock);
      return new Report(Status.NATIVE_BOOTSTRAP_READY,token,"Read-only candidate; external freeze and exact initialization approval still required.");
    }catch(RuntimeException blocked){return new Report(Status.BLOCKED_CONTROL,token,"Native schema/control/budget verification failed; no changes planned.");}
  }
  static Plan plan(ManualQuarterMaintenance.Binding target,Book before,String approvedBaseline,
      FreezeEvidence evidence,FreezeVerifier verifier,Clock clock) {
    Report report=inspect(target,before,clock);
    if((report.status()!=Status.NATIVE_BOOTSTRAP_READY&&report.status()!=Status.LEGACY_BOOTSTRAP_READY)||!report.baselineToken().equals(approvedBaseline))throw new Rejected();
    verify(target,approvedBaseline,evidence,verifier,clock);
    JournalPlan journal=report.status()==Status.LEGACY_BOOTSTRAP_READY?LegacyToNativeCutoverPlan.prepare(target,before,clock):NativeCommitJournal.bootstrap(target,before,NativeCommitJournal.digest(target,before),clock);
    if(!before.tables().equals(journal.expected().tables()))throw new Rejected();
    return new Plan(target,before.copy(),approvedBaseline,evidence,verifier,clock,journal);
  }
  private static void verify(ManualQuarterMaintenance.Binding target,String baseline,FreezeEvidence evidence,FreezeVerifier verifier,Clock clock) {
    Objects.requireNonNull(evidence);Objects.requireNonNull(verifier);
    if(!clock.instant().isBefore(evidence.expiresAt()))throw new Rejected();
    verifier.verify(target,baseline,evidence);
    if(!clock.instant().isBefore(evidence.expiresAt()))throw new Rejected();
  }
  static final class Plan {
    enum Readback { EXACT_APPLIED, UNCHANGED_UNCERTAIN, DIFFERENT_UNCERTAIN }
    private final ManualQuarterMaintenance.Binding target;private final Book before;
    private final String baseline;private final FreezeEvidence evidence;private final FreezeVerifier verifier;
    private final Clock clock;private final Instant created;private final JournalPlan journal;private boolean attempted;
    Plan(ManualQuarterMaintenance.Binding t,Book b,String token,FreezeEvidence e,FreezeVerifier v,Clock c,JournalPlan j){target=t;before=b;baseline=token;evidence=e;verifier=v;clock=c;created=c.instant();journal=j;}
    /** A fresh full snapshot is required. Failure consumes the plan: never blindly retry.
     * Successful readback proves snapshot, NOT continued exclusive writer ownership. */
    synchronized void dispatchOnce(Book fresh,JournalPlan.Sink sink) {
      if(attempted)throw new Rejected();attempted=true;
      if(clock.instant().isBefore(created)||!token(target,fresh).equals(baseline))throw new Rejected();
      verify(target,baseline,evidence,verifier,clock);journal.dispatchOnce(sink);
    }
    Readback reconcile(Book observed) {
      if(journal.matchesAfter(observed))return Readback.EXACT_APPLIED;
      return token(target,observed).equals(baseline)?Readback.UNCHANGED_UNCERTAIN:Readback.DIFFERENT_UNCERTAIN;
    }
    ManualQuarterMaintenance.Binding targetBinding(){return target;}
    String exactWire(){return journal.wire();}
    String beforeToken(){return JournalSessionAuthority.fullToken(before);}
    void requireScopeTime(){Instant now=clock.instant();if(now.isBefore(created)||!now.isBefore(evidence.expiresAt()))throw new Rejected();}
    void revalidate(){requireScopeTime();verify(target,baseline,evidence,verifier,clock);}
    Book expected(){return journal.expected();}
  }
}
