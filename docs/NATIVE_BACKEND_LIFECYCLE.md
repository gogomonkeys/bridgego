# Local native backend lifecycle handoff

`NativeStoresBackend` now preserves the identity/statistics bean instance across an exclusively drained maintenance window. Package-private APIs inspect the owned journaled snapshot, clean-close once, and reopen with a newly provisioned ordinary session against the exact verified CLOSED post-quarter snapshot. A replacement must be STARTING, same target and a backend-generated fresh session owner, and cannot have already dispatched CLAIM. Fresh full snapshot equality is checked immediately before its normal claim. The old Service and its capability are retired after verified CLOSED, before quarterly writes. The replacement is adopted only after exact OPEN readback.

The shared gate must be MAINTENANCE, all admitted operations and the asynchronous queue drained, and no UNKNOWN saves. Unknown close/reopen latches failure and prevents repeat dispatch or reprovision through this backend. This is same-process handoff, not restarted OPEN takeover or a distributed lease.

The trusted lifecycle provider owns the original Google handle and must revoke it after verified clean close, before maintenance authority is installed. Replacement sessions carry a release callback to revoke their capability/provider on failed adoption or backend shutdown. The two-argument Session convenience constructor is for model transports without external resources. The trusted factory must approve that fresh owner for the unchanged source; this is a new session grant, not renewal of the previous owner. Receipt duplicate rejection is unchanged. Gate permits cover the full queue/mutation lifetime; executor afterExecute bookkeeping is not treated as an unresolved operation.

No production provider, environment flag or network capability is created by these APIs; the existing unavailable default remains unchanged.

Tests cover local full close/five-phase quarter/reclaim/profile and unknown close with no resend. They do not assert live Google execution.

## Distinct-process boundary tests

`NativeLifecycleRestartProcessTest` writes synthetic complete native snapshots, then starts a distinct Java process for each lifecycle stage. The child strictly parses the snapshot, constructs a fresh STARTING Service plus lifecycle/coordinator, and confirms that unavailable ownership evidence keeps admission BLOCKED_MANUAL with zero dispatches. Covered stages are original OPEN, drained CLOSED, verified quarter CLOSED, reopened OPEN, and UNKNOWN close both before and after late application. A delayed old-writer final wire is rejected against post-close snapshots by its occupied receipt slot. This tests the production lifecycle's safe restart refusal, **not** successful automatic cross-process ownership transfer or live Google operation.

## Controlled clean restart

`NativeClosedTakeover` supports a different JVM only when independently verified evidence attests an acknowledged clean shutdown of the exact CLOSED snapshot, predecessor termination, source exclusion and no unresolved dispatch. It verifies a fresh full journal twice, provisions a new bounded session owner, and claims exactly once. The gate remains STARTING for normal coordinator validation. OPEN, unknown/late CLOSED and old-quarter CLOSED are not accepted by this path. The latter must pass the separately authorized maintenance path first.

Normal `backend.close()` is not a remote clean-close guarantee. An unannounced SIGTERM/kill or shutdown with active games may leave OPEN and cannot be advertised as controlled clean restart. No shutdown hook forcibly adjudicates games or drops queue work. The positive child-JVM test supplies explicit synthetic evidence and uses the authority-gated local Handle sink, not a production Render proof.

## Native-only OPEN fencing

The separate `NativeOpenTakeover` path is not the clean-CLOSED path and does not loosen ordinary session authority. `NativeTakeoverPlan` reserves the exact next receipt slot while replacing only owner/root/journal; no business table changes. A single-use takeover capability requires the fixed full OPEN snapshot, current-quarter COMPLETE ledger and external native-only source proof. After ACK plus exact full readback, that capability is revoked and a separate ordinary session is provisioned against its verified ownership evidence. A new STARTING Service adopts only this invocation's verified result; the synthetic stored-state startup bypass is never called.

Both races are tested: old native batch wins first and takeover stops, or takeover wins and old receipt-slot request is rejected. UNKNOWN takeover, including later exact application, is terminal and never reprovisioned by the same invocation. This proves fencing, not durability of unpersisted queue work. External evidence must also establish no unresolved accepted work or completed reconciliation. A crash with an unknown volatile queue remains blocked. Legacy unfenced requests must already be excluded. Production evidence/credential wiring is still unavailable by default.

The child-JVM OPEN test performs the capability CAS and adopts a new ordinary authority-gated Handle, then updates a profile without a second CLAIM. The ordinary authority is derived from the completed takeover's immutable target/owner/full-token proof rather than an arbitrary caller snapshot.
