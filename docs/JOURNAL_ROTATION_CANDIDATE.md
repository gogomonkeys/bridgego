# Journal-v1 rotation: local protocol candidate

JournalRotation.plan uses an already journal-verified full native snapshot, an OPEN root and its exact expected owner. It never acquires ownership. The only batch includes: old anchor guard; unique old-next receipt arbitration; new monotonically numbered ledger sheet; literal ledger copy; deletion of old anchor; current-epoch receipt replacement; new root; continuous-sequence journal proof referencing the previous proof hash. All eight tables' values must be unchanged. Journal metadata and unrelated metadata are not deleted.

A delayed old batch fails because its guard names the deleted old anchor; a competing ordinary batch arriving first consumes the old next receipt, so rotation fails atomically. Duplicate rotation likewise fails, and JournalPlan cannot dispatch twice. Unknown stays unresolved until the exact expected full snapshot is positively verified. BEFORE is not permission to resend.

No core or original parser was modified. This is a separate protocol from the original exact WritePermit/QuarterPlan. The ordinary JournaledNativeTransport continues to reject rotations; no live sink or capability is added.

## Writer lifecycle boundary

Rotating changes the anchor ID, root epoch and receipt layout. A successful proof does NOT update an existing Service's cached ids/known root/rowCounts. NativeWriterAdapter.retireForRotation closes the local writer before separately controlled rotation, without closing the remote root or writing anything. The retired writer cannot resume; the remote OPEN root also blocks a fresh Service from claiming it. A future explicitly verified lifecycle must either adopt the full new snapshot with ownership proof or safely transition to CLOSED before a new owner claims. Neither is silently performed by this candidate. No writer is reopened after rotation here.

Tests cover all-values/lifetime preservation, predecessor proof continuity, old/duplicate/competing batches, unknown no-resend, serialize-native/readback/new Service ownership rejection, and retired writer rejection after the layout changes. Real Google transport and live lifecycle remain unvalidated.

## Restricted same-process success adoption

NativeWriterAdapter.checkpoint now provides a separate safe success path. Under the same Service serialization monitor, only its actual JournaledNativeTransport can generate rotation from a freshly journal-verified snapshot matching the Service's current root. No caller can inject a Book or wire. One combined batch is sent; only exact after-state proof with the same owner, incremented epoch and expected anchor permits adoptBaseline + known-root update. The existing admitted writer can then issue profile/match operations using the new anchor. This is not new-process OPEN ownership takeover.

If dispatch or readback is unknown, or validation fails, the Service is closed permanently in that adapter. Even later positive journal evidence does not silently reopen it. Tests cover successful checkpoint followed by both profile and match writes, and unknown-before/unknown-after-apply retirement without resend. The earlier retireForRotation path remains for independently controlled operations; it still does not reopen.
