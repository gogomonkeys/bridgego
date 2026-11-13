# Local write admission candidate (not deployed)

`BRIDGE_LOCAL_WRITE_GATE_ENABLED` defaults to false. The disabled wrapper returns the identical existing gateway, preserving behavior. Explicit `true` enables only a CLOSED barrier, not maintenance execution or deletion. PersistenceConfiguration shares the gate-wrapped production gateway between stats and identity stores.

Enabled processes boot as STARTUP_BLOCKED. No production path calls `verifyAndOpenAtStartup`; a future coordinator must supply real read-only durable-state and writer-ownership verification. A no-op callback is only used in isolated unit tests and is not evidence or a production verifier. Do not enable this flag in production yet: writes would intentionally remain blocked.

All gateway mutations (appendAtomic, replaceAtomic, saveAccountProfile) are counted. `pauseAndDrain` immediately rejects new calls and waits for admitted mutations. Timeout or interruption leaves admission closed. A mutation exception, including an unknown network response, latches UNKNOWN; subsequent retries are blocked at admission. UNKNOWN cannot reopen in this instance. Reads remain available for diagnosis. Successful draining also cannot reopen the same gate, avoiding stale readers committing after maintenance. There is no privileged maintenance-write bypass in this implementation.

## Not provided by this change

- This is NOT a distributed lock, persistent transaction log, entire read/check/write transaction scope, write deduplication protocol, or quarterly scheduler.
- A process-local zero active count does not prove that an earlier host's HTTP request has finished, nor stop imports, operators, connectors or other processes.
- A future restart must consult durable ownership/control state; this skeleton does not implement that check. Boot remains blocked instead of assuming prior UNKNOWN was resolved.
- Queued stats records and in-flight login/room operations still require lifecycle handling, recovery/readback, user-visible maintenance, and safe startup fencing before destructive automation can be connected.
- Actual quarterly cleanup requires a separately authorized, fully verified maintenance execution path. No existing real data was read or changed by these local tests.

Tests: `SheetsWriteGateTest` covers disabled compatibility/default wiring, all three mutation methods, read-through, failed verification, UNKNOWN no-retry, concurrent drain/new-write rejection, and no reopening. Existing `DatasetBindingTest` remains passing.

The gate does not serialize concurrently admitted mutations. More than one request may already be in flight when one becomes UNKNOWN, and those requests may still finish. UNKNOWN blocks NEW admission only; it neither cancels an HTTP request nor rolls back its effects. Real single-writer integration still requires operation-level serialization, persistent receipts and reconciliation across restarts. Neither a gate state nor a drained count alone proves exclusive ownership or safe destructive execution.

## Operation-level follow-up

`BRIDGE_QUARTER_GATE_ENABLED` (default false) now wires `QuarterMaintenanceGate` around the same production gateway and into both stores. This is separate from the earlier mutation-only flag, which should remain false. Enabled quarter gates start STARTING. Production writer wiring does not itself call openVerified or enable deletion.

The quarter gate serializes the entire attached operation using one fair process-local lock. Stats accepts a permit BEFORE enqueue, so queued jobs count toward draining; workers attach across reads, write and verification. Queue rejection/cancellation releases unstarted permits as definitely not dispatched. Identity legacy/profile flows and statistics reads use the same operation boundary. Gate-enabled writer failures do not execute the legacy automatic retry loop; unsuccessful write permits block the gate before the next queued operation can attach. Existing disabled constructors preserve legacy behavior.

This improves process-local operation consistency, but still does not prove remote exclusivity, persistent intent, crash recovery or external-writer fencing. HTTP entry permits should count admission without holding the operation serialization lock across unrelated application locks. Room lifecycle draining and verification of pending remote requests remain separate integration responsibilities.

`QuarterOperationWiringTest` adds startup/unattached-write rejection, unknown-after-write with queued-work block, stats/identity serialization and drain accounting, and queued cancellation release coverage. Targeted suite: 24 passed including the existing store/binding/mutation-gate tests.

## Explicit coordinator/scheduler wiring

PersistenceConfiguration now declares QuarterMaintenanceCoordinator and QuarterMaintenanceScheduler beans, using Clock.systemUTC and RoomService freeze/finish maintenance hooks. The DurableControl bean is deliberately `unavailable()`, not a real Sheets adapter. No destructive transport is installed. With the quarter gate disabled, scheduled ticks are no-ops. With it enabled, startup is closed and the unavailable durable inspection leads to BLOCKED_MANUAL, not automatic reopening or deletion.

DO NOT deploy with BRIDGE_QUARTER_GATE_ENABLED=true: the real adapter is not implemented and verified; enabling it would intentionally leave the game in maintenance. Adding beans is integration scaffolding only, not completed automated quarterly cleanup.
