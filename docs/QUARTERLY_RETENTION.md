# Quarterly raw match retention (local implementation only)

## Approved scope and unresolved policy

User selected deletion of prior-quarter personal raw match records at Taiwan quarter boundaries (January/April/July/October 1), retention of accounts, and retention only of non-person-identifying overall statistics. This code has not run against real Sheets. No live deletion, deployment, credentials, scheduler or external configuration change is included.

The exact treatment of a game spanning the boundary and the aggregation privacy policy are NOT yet approved. Runtime collection must remain disabled behind an explicit policy-readiness flag until those decisions and operational prerequisites are resolved. The conservative candidate guard accepts only games whose start and finish are both within the current quarter. Candidate deletion groups by `finished_at`; this attribution is a proposal, not an approved production interpretation. Cross-boundary matches never contribute to retained aggregates.

Default aggregate policy is SUPPRESS_ALL. Optional constructor injection permits testing a candidate threshold of at least 50 matches AND 20 distinct human identity values, with total match and completed-deal counts rounded down to tens. Only quarter and these two counts survive, or a SUPPRESSED marker. No player, account, guest, match, deal, seat, precise date, score or per-user statistic is retained in the aggregate table. Identity values are used transiently for the threshold and never written to aggregate rows. Multiple revisions of one match suppress the cohort instead of double-counting. These thresholds and rounding reduce disclosure but do not provide robust anonymity: guests may use multiple identifiers; external knowledge and small or overlapping populations can re-identify activity. Separate privacy review/approval is required before enabling any candidate counts. Raw deletion does not depend on aggregate approval.

## Time semantics

`QuarterlyRetention.currentQuarterStart` uses `Asia/Taipei`. For example October 1, 2026 00:00 Taipei is September 30 16:00 UTC. A finish strictly before the boundary expires; a finish exactly on the boundary is current. Missing/malformed timestamps and future finishes fail closed. The normal-access hook `requireCurrent` rejects expired, future, and cross-quarter envelopes even before physical cleanup. `requireCollectionReady` requires the durable ledger to be COMPLETE for exactly the current quarter boundary. Policy readiness is an additional independent requirement.

The free Render service can sleep. Exact midnight physical deletion is not guaranteed. At the first post-boundary request/wake, raw access/submission guards must take effect immediately; collection remains blocked until offline cleanup is verified. Cleanup is performed during the next exclusive maintenance window, not concurrently with the app. There is no hidden self-clearing config, no auto-enabled collection and no automatic purge scheduler in this change. Status must distinguish PENDING/FAILED/UNCERTAIN from COMPLETE and must never report an on-time purge merely because the boundary passed.

## Storage and offline operation

New tabs, each within columns A:E:

- `retention_ledger`: quarter, cutoff_utc, state, completed_at, schema_version.
- `quarterly_aggregates`: quarter, publication, matches_rounded, deals_rounded, schema_version.

Initialize headers only; do not invent a COMPLETE watermark. The watermark prevents retry/import of old raw envelopes even if wall-clock configuration regresses. Every normal submission/import path must call the gate. An authorized erasure/retention maintenance decoder remains capable of reading older raw rows for deletion; it must not become a public historical export.

`QuarterlyRetention` is intentionally not a Spring bean or public controller. Its default constructor is dry-run only. Destructive use requires an explicitly enabled offline harness, a fresh plan-bound confirmation and a trusted proof that ALL game writers, identity writers, account-erasure maintenance writers, replicas, queued retries and manual spreadsheet writers are stopped for the entire operation. A digest is a stale-plan check, NOT a lock or CAS. Without zero overlap, do not purge; leave collection blocked and report cleanup pending. No tester endpoint is permitted to invoke whole-dataset cleanup.

`preview()` snapshots all seven mutable tabs plus immutable dataset metadata. `execute(planId, confirmationText)` checks the same whole snapshot and current boundary, then atomically replaces the seven tabs, removing expired envelope and commit rows, retaining current raw records, and preserving accounts, auth identities and erasure tombstones unchanged. First replacement records PENDING plus the cutoff. After successful readback of the deleted raw data, a second atomic replacement records COMPLETE and the actual verification timestamp, then verifies that write too. A lost response is reconciled by readback, never by blind destructive replay. Failed readback yields UNCERTAIN and no completedAt. If final metadata write fails, PENDING remains and a fresh preview can safely resume without duplicating aggregates. Existing account-erasure five-tab replacements must leave the new tabs untouched and must never overlap retention.

A fresh read is needed before retrying any uncertain operation. Unsupported atomic replacement, absent tabs, invalid watermark, corrupt raw rows or changed snapshot fail closed. Corrupt data may need authorized manual repair before cleanup can proceed; it must not be silently dropped.

## Local verification

`QuarterlyRetentionTest` covers all four boundaries and year rollover, old/current cutoff, old retries, cross-quarter/future rejection, unchanged accounts/identities/tombstones, watermark resurrection rejection, default dry-run, explicit confirmation, zero-overlap guard, changed snapshot, candidate suppression thresholds, failures, response-loss readback and interrupted PENDING recovery. Fake gateways only; these tests make no network calls.
