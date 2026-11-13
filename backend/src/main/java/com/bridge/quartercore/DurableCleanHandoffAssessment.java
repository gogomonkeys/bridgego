package com.bridge.quartercore;

import java.time.*;
import java.util.*;
import static com.bridge.quartercore.NativeSheetsModel.*;
import static com.bridge.quartercore.NarrowQuarterPoc.*;

/** Read-only classification of a freshly, completely read native snapshot. This is NOT
 * ownership, source exclusion, an acknowledgement of the previous HTTP response, or
 * proof that an abruptly killed process had no accepted work in RAM. A separately
 * approved native-only source policy and unresolved-operation checks remain mandatory.
 * In particular an applied-but-unacknowledged CLOSE is indistinguishable here from an
 * acknowledged CLOSE. A new process may use this durable atomic-close classification
 * for separately authorized reconciliation and a NEW receipt-CAS claim, never to resend
 * the old CLOSE. This does not clear a live process's UNKNOWN latch, nor reconcile other
 * unresolved accepted work or an OPEN/PENDING/APPLIED snapshot. */
final class DurableCleanHandoffAssessment {
  enum State { CLEAN_CURRENT_RESUMABLE, CLEAN_DUE_REQUIRES_MAINTENANCE, BLOCKED }
  record Result(State state, String priorOwner, String snapshotToken, Instant cutoff) {}
  private DurableCleanHandoffAssessment() {}
  static Result inspect(ManualQuarterMaintenance.Binding binding, Book fullNative, Clock clock) {
    try {
      NativeCommitJournal.verify(binding, fullNative, clock);
      Root r=root(new ArrayList<>(fullNative.metadata.values()));
      if(!r.dataset().equals(binding.datasetId()) || !r.state().equals("CLOSED")
          || r.owner().isBlank() || !r.operation().equals("CLOSE-"+r.owner())
          || !r.hash().equals(hash(r.owner()))) return blocked();
      Root predecessor=new Root(r.dataset(),r.epoch(),r.anchor(),r.nextSheet(),r.next()-1,
          r.owner(),"OPEN","",hash(""));
      var expected=receipt(predecessor,r.operation(),r.hash(),"CLOSED");
      var actual=fullNative.metadata.get(r.next()-1);
      if(actual==null || actual.id()!=expected.id() || !actual.key().equals(expected.key())
          || actual.sheet()!=null || !NativeCommitJournal.canonical(parse(actual.value()))
              .equals(NativeCommitJournal.canonical(parse(expected.value())))) return blocked();
      var recovery=JournalQuarterProtocol.inspect(binding,fullNative,clock);
      State state=switch(recovery.state()) {
        case CLEAN_CURRENT -> State.CLEAN_CURRENT_RESUMABLE;
        case READY_DUE -> State.CLEAN_DUE_REQUIRES_MAINTENANCE;
        default -> State.BLOCKED;
      };
      if(state==State.BLOCKED)return blocked();
      return new Result(state,r.owner(),JournalSessionAuthority.fullToken(fullNative),recovery.cutoff());
    } catch(RuntimeException invalid) { return blocked(); }
  }
  private static Result blocked(){return new Result(State.BLOCKED,"","",null);}
}
