package com.bridge.quartercore;

import java.time.Clock;
import java.util.Objects;
import static com.bridge.quartercore.NativeSheetsModel.*;

/** New journal-v1 exact combined-wire plan. Deliberately incompatible with old WritePermit
 * and QuarterPlan exact snapshots. No live sink or automatic authorization is supplied. */
public final class JournalPlan {
  @FunctionalInterface interface Sink { void dispatchExact(String finalNativeBatch); }
  private final ManualQuarterMaintenance.Binding binding;private final String beforeDigest,wire;
  private final Book expected;private final NativeCommitJournal.Evidence evidence;private final Clock clock;
  private boolean attempted;
  JournalPlan(ManualQuarterMaintenance.Binding binding,String beforeDigest,String wire,Book expected,
      NativeCommitJournal.Evidence evidence,Clock clock) {
    this.binding=binding;this.beforeDigest=beforeDigest;this.wire=wire;this.expected=expected.copy();this.evidence=evidence;this.clock=clock;
  }
  String wire(){return wire;}Book expected(){return expected.copy();}String beforeDigest(){return beforeDigest;}
  NativeCommitJournal.Evidence evidence(){return evidence;}
  /** Attempt flag is consumed before entering sink. Exception means UNKNOWN, never retry. */
  synchronized void dispatchOnce(Sink sink){if(attempted)throw new Rejected();attempted=true;Objects.requireNonNull(sink).dispatchExact(wire);}
  boolean matchesAfter(Book observed) {
    try{return NativeCommitJournal.verify(binding,observed,clock).equals(evidence)
        &&NativeCommitJournal.canonical(observed.nativeValue()).equals(NativeCommitJournal.canonical(expected.nativeValue()));}
    catch(RuntimeException unknown){return false;}
  }
}
