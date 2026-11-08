# Quarterly raw match retention (local implementation only)

## Requested scope, current behavior and unresolved policy

User selected deletion of prior-quarter personal raw match records at Taiwan quarter boundaries (January/April/July/October 1), retention of accounts, and retention only of non-person-identifying overall statistics. This code has not run against real Sheets. No live deletion, deployment, credentials, scheduler or external configuration change is included.

The current implementation assigns a match to the quarter containing `finished_at`, including a game that started before that quarter. Start must not be after finish. This describes existing code, not new permission to activate collection. Before production activation, confirm the recorded cross-quarter policy and complete the operational acceptance checks. Aggregate privacy thresholds are not approved; runtime collection remains disabled behind explicit readiness flags.

Default aggregate policy is SUPPRESS_ALL. Optional constructor injection permits testing a candidate threshold of at least 50 matches AND 20 distinct human identity values, with total match and completed-deal counts rounded down to tens. Only quarter and these two counts survive, or a SUPPRESSED marker. No player, account, guest, match, deal, seat, precise date, score or per-user statistic is retained in the aggregate table. Identity values are used transiently for the threshold and never written to aggregate rows. Multiple revisions of one match suppress the cohort instead of double-counting. These thresholds and rounding reduce disclosure but do not provide robust anonymity: guests may use multiple identifiers; external knowledge and small or overlapping populations can re-identify activity. Separate privacy review/approval is required before enabling any candidate counts. Raw deletion does not depend on aggregate approval.

## Time semantics

`QuarterlyRetention.currentQuarterStart` uses `Asia/Taipei`. For example October 1, 2026 00:00 Taipei is September 30 16:00 UTC. A finish strictly before the boundary expires; a finish exactly on the boundary is current. Missing/malformed timestamps and future finishes fail closed. The normal-access hook `requireCurrent` rejects expired finishes, future finishes and reversed start/finish timestamps before physical cleanup. A valid match finishing exactly on the boundary belongs to the new quarter even when it started earlier. `requireCollectionReady` requires the durable ledger to be COMPLETE for exactly the current quarter boundary. Policy readiness is an additional independent requirement.

The free Render service can sleep. Exact midnight physical deletion is not guaranteed. At the first post-boundary request/wake, raw access/submission guards must take effect immediately; collection remains blocked until offline cleanup is verified. Cleanup is performed during the next exclusive maintenance window, not concurrently with the app. There is no hidden self-clearing config, no auto-enabled collection and no automatic purge scheduler in this change. Status must distinguish PENDING/FAILED/UNCERTAIN from COMPLETE and must never report an on-time purge merely because the boundary passed.

## Storage and offline operation

New tabs, each within columns A:E:

- `retention_ledger`: quarter, cutoff_utc, state, completed_at, schema_version.
- `quarterly_aggregates`: quarter, publication, matches_rounded, deals_rounded, schema_version.

Initialize headers only; do not invent a COMPLETE watermark. The watermark prevents retry/import of old raw envelopes even if wall-clock configuration regresses. Every normal submission/import path must call the gate. An authorized erasure/retention maintenance decoder remains capable of reading older raw rows for deletion; it must not become a public historical export.

`QuarterlyRetention` is intentionally not a Spring bean or public controller. Its default constructor is dry-run only. Destructive use requires an explicitly enabled offline harness, a fresh plan-bound confirmation and a trusted proof that ALL game writers, identity writers, account-erasure maintenance writers, replicas, queued retries and manual spreadsheet writers are stopped for the entire operation. A digest is a stale-plan check, NOT a lock or CAS. Without zero overlap, do not purge; leave collection blocked and report cleanup pending. No tester endpoint is permitted to invoke whole-dataset cleanup.

`preview()` snapshots all seven mutable tabs plus immutable dataset metadata. `execute(planId, confirmationText)` checks the same whole snapshot and current boundary, then atomically replaces the seven tabs, removing expired envelope and commit rows, retaining current raw records, and preserving accounts, auth identities and erasure tombstones unchanged. First replacement records PENDING plus the cutoff. After successful readback of the deleted raw data, a second atomic replacement records COMPLETE and the actual verification timestamp, then verifies that write too. A lost response is reconciled by readback, never by blind destructive replay. Failed readback yields UNCERTAIN and no completedAt. The direct engine rejects re-execution and a replacement preview after an attempted plan; the guarded manual operator permits only read-only inspection of that attempt. If final metadata write fails, PENDING remains. After investigation, a new explicitly authorized maintenance session with a fresh preview can resume without duplicating aggregates. Existing account-erasure five-tab replacements must leave the new tabs untouched and must never overlap retention.

A fresh plan for an already COMPLETE current quarter performs full fresh snapshot verification without rewriting any table or replacing the original completion timestamp. The manual wrapper also performs read-only reconciliation. In the dedicated live CLI this path does not create an attempt marker, but an existing attempt marker still forces preview-only mode. Failed or changed no-op readback cannot fall through into a write.

A fresh read is needed before retrying any uncertain operation. Unsupported atomic replacement, absent tabs, invalid watermark, corrupt raw rows or changed snapshot fail closed. Corrupt data may need authorized manual repair before cleanup can proceed; it must not be silently dropped.

## Local verification

The fake-only coverage in `QuarterlyRetentionTest`, `ManualQuarterMaintenanceTest`, `LiveQuarterStorageTest` and `LiveQuarterCliTest` includes:

- All four Taiwan boundaries and year rollover, finish one nanosecond before versus exactly on the boundary, and valid cross-quarter matches attributed by finish.
- Expired raw envelope/commit removal, byte-for-byte preservation of accounts, identities, erasure tombstones, current canonical payloads/hashes and pre-existing permitted aggregate rows.
- The following quarter removes previously retained raw matches while preserving account data and older aggregate markers.
- Old-record resurrection, stale/future/invalid timestamps, invalid metadata, stale plans, stopped-writer gates and default preview-only behavior.
- Fresh same-quarter COMPLETE no-op, original completion timestamp preservation, and failures/changes during no-op readback.
- Failed first/second writes, lost responses, unavailable readback, PENDING recovery in a reviewed new session, and no destructive replay of an attempted plan.
- Dedicated CLI attempt-marker behavior, including no marker for a verified no-op and no bypass of a pre-existing marker.

These tests use fabricated identifiers and in-memory gateways/transports only. They do not call Google, load real credentials, inspect a real Sheet or prove external acceptance. Current run results must be reported separately rather than inferred from this coverage list.

## Limits that local tests do not resolve

- The default/manual/live path retains only `SUPPRESSED` quarter markers, not numeric anonymous totals. Core-only tests can preserve or construct candidate counts, but `CANDIDATE_COUNTS` is not an approved anonymous publication format. The manual/live validator rejects those rows until a separately reviewed policy supports them. Existing permitted aggregate rows are preserved, not recomputed or attributed to individuals.
- Account IDs and Google identity mappings intentionally survive quarter cleanup. Erasure tombstones also survive and are pseudonymous control data, not anonymous statistics. The cleanup removes prior-quarter identifiable **match** records; it does not delete all personal account data.
- A values-only core digest is not a whole-workbook metadata check. The guarded manual path relies on its storage adapter's complete-layout contract. `LiveQuarterStorage` additionally parses a bounded full workbook and checks a sticky metadata fingerprint across reads. Unsupported fields, extra populated regions, formulas, notes, comments or other unknown metadata fail closed rather than being silently ignored.
- Active-sheet rewrites cannot establish deletion from Google revision history, vendor backups, exported files, logs, prior downloads or other copies. Inventory and handling of those copies require separate authorization and acceptance evidence.
- No scheduler was installed. Exact midnight physical cleanup, real writer shutdown, Google request/response behavior, real quotas/permissions, restoration handling and actual production policy remain externally unverified. Keep readiness flags false until those checks are complete.
