# Local-only legacy business observation

Contract: `legacy-record-contract-v1`. Native compatibility remains `legacy-eight-native-v2-readonly`.

This candidate has **not** been deployed or run against production. It consumes only the logical tables already produced by the single complete native-resource parser. It performs no additional GET, credential read, storage call, write, queue creation or background work. Transport, default-off activation, readonly scope, existing 32 MiB response / 128 MiB heap / 35 second watchdog limits and startup cleanup remain unchanged. Existing production observations are not retroactively upgraded to semantic evidence.

## Interpreting the report

`Report.businessSemantics` contains only a fixed contract version, overall state, enum-keyed per-domain state/reason counts, and aggregate counts of decoded, expired and lifetime-eligible records. It contains no row numbers, sheet values, identifiers, per-record hashes, payloads, dates, exception text or dynamic reason keys. `toString()` methods are redacted. Raw logical tables and decoded payloads have no public accessor. Package-private decoder trees exist only during the bounded local scan.

- `VERIFIED` means the named supported **stored-record contract** completed with no detected contradiction or unresolved dependency. It is not proof of gameplay, identity ownership, history or storage authenticity.
- `INVALID` means a contradiction with a checked supported contract. Examples are a hash mismatch, missing referenced account, malformed v1 row, invalid fixed-room totals or a restored v1 tombstoned identifier.
- `UNAVAILABLE` means unsupported versions/extensions, missing evidence, prerequisite failure, pending/stale retention, ambiguous history, or a verification budget. It is not a declaration of corruption.
- Overall state is the greatest severity across domains (`INVALID`, then `UNAVAILABLE`, then `VERIFIED`). Independent domains and independently decodable records continue after a failure. A failed prerequisite marks dependent observations `UNAVAILABLE`; it does not erase independent errors or successes. Reason counts are observation occurrences, not distinct affected-person counts. An unrecoverable local unexpected exception produces fixed unavailable reasons and never emits its cause.
- `supportedRecordContractVerified()` is true only when every domain is `VERIFIED`. Startup may then emit `OBSERVED_SUPPORTED_RECORD_CONTRACT`; otherwise a structurally valid resource retains `OBSERVED_STRUCTURE_ONLY` and includes the semantic findings. Neither status authorizes anything.
- `businessSemanticsVerified()`, `mutationPermitted()` and `writerExclusionVerified()` remain **false**. The semantic summary also keeps `coherentSnapshotVerified()` false. A single GET is not a writer fence, transaction, coherent cutover or future mutation preflight.

Structural failures still terminate before business inspection. In particular, the existing native parser rejects duplicate commit IDs / chunk slots and noncanonical numeric strings before semantic aggregation. This deliberate existing compatibility boundary is stricter than backend `readVerified`, which accepts identical duplicate rows. No duplicate is silently collapsed to pass the native parser. Direct semantic tests do not substitute for native-to-startup integration tests of this boundary.

## Checks and support

### Commit and chunk linkage

Every committed marker must have canonical UUID match identity, positive exact-int revision, `match_id:revision` commit identity, lowercase SHA-256 and bounded positive chunk count. Every chunk references a marker and agrees on hash/count. All unique zero-based slots must exist; ordering on the sheet is immaterial. Missing, orphan, duplicate and conflicting slots are distinct fixed findings. Concatenated payloads must be exact canonical JSON, match SHA-256 and carry matching match/revision fields. Canonicalization preserves nulls and arrays, sorts object keys, permits integer numbers only, and does not trim or normalize contents. Duplicate keys, trailing JSON, whitespace/noncanonical encodings and coercion/wraparound are rejected. Hash validity alone never proves truth or identity. Lone UTF-16 surrogates are `UNSUPPORTED_TEXT_ENCODING` / unavailable because Java UTF-8 replacement cannot prove an exact hash binding; joined text and decoded strings/field names are checked. Valid supplementary pairs, including pairs split across chunks, remain supported.

All revisions are inspected. No maximum/latest revision is selected. Multiple records for one match make revision policy unavailable and prevent contribution aggregation for that match. A lone revision above one is valid representationally but its revision history remains unavailable; erasure can replace a payload at the same revision/commit ID, and the snapshot does not preserve its earlier payload. A revision above one does not itself establish that an erasure occurred.

### Envelopes

Schema 1 is decoded using an adapted pure `FinalMatchEnvelope` validator. Supported markers are:

- `four-humans-at-settlement-v1` (historical FORMAL statistics)
- `match-category-at-settlement-v2` (settlement-derived FORMAL/CASUAL)
- `fixed-room-type-v3` (fixed room category and lifecycle)
- `elapsed-including-pauses-v1`
- `account-career-v1`, with score version `bridgego-custom-v0.11-1`

No app-version allowlist is invented; a nonblank textual app version is required. Unknown schema, rule or score versions are unavailable. Absent historical markers are not inferred from HUMAN/MIXED controls. Valid unmarked/v1/v2 records are decoded but the stronger overall record contract is unavailable, rather than imposing v3 lifecycle or timing rules on them. Absent lifetime marker means lifetime eligibility is unavailable, not eligible-by-assumption.

The validator covers participant seat/key uniqueness and supported account/bot/control/team/category rules; deal identity/index uniqueness and marked elapsed bounds; fixed-v3 planned counts, sequence, sum of stored per-deal NS/EW totals, outcome/reason/winner consistency and zero-human restrictions. Known scalar field types use the source maintenance field inventory. Unknown extension field names remain preserved but explicitly unavailable.

It does **not** replay cards, bids, tricks, game history, custom scoring formulas or calculate per-deal scores from components. It does not compare sum of elapsed values to wall-clock duration. Stored deal totals and their match sums can be checked without proving the original game. No candidate score calculator was imported.

### Accounts, identities and erasure

Accounts support the existing 2-, 4- and 5-column headers, schema 1 blank profile cells, schema 2 nickname/email validation, canonical UUIDs and unique account rows. Blank lifetime cells remain valid. Identity v1 supports exact Google issuer, nonblank subject of at most 255 characters, canonical existing account reference and conflict-free issuer/subject mapping. It does not impose a new trimmed/no-control subject restriction that the production identity writer does not require. No reverse requirement is invented that every account must have an identity or a match. Exact duplicate identity mappings are explicitly counted under the backend idempotent mapping policy; contradictory mappings are invalid.

Erasure v1 requires the first META sentinel, supported ACCOUNT/GUEST/IDENTITY kinds, lowercase digests and version 1. Digest semantics are `SHA256("bridge-erasure-v1" + NUL + kind + NUL + identifier)`, with identity identifier `issuer + NUL + subject`. Exact duplicate v1 tombstones are explicitly counted under the backend set policy. Unknown row schemas do not acquire v1 semantics. Known tombstones are checked against accounts, identities and all decoded participant identifiers. Unknown/invalid prerequisites retain unavailable status.

Participant account references must exist. Null human account/guest values do not imply corruption or require an erased marker. A valid random `erased_marker` requires null account/guest values but cannot be linked to a digest or deleted identity: erasure provenance stays unavailable when markers occur. No identifiers are emitted or retained in report output.

### Time and retention

Asia/Taipei quarter boundaries are used, including UTC dates on the previous calendar day. Ledger v1 requires unique quarters, matching boundary and quarter text, COMPLETE or PENDING states, COMPLETE time at/after boundary and no future completion/cutoff, and blank PENDING completion time. The greatest cutoff determines current readiness; historical PENDING rows do not make a later COMPLETE invalid. Missing, latest PENDING or stale cutoff is unavailable. Duplicate quarters and malformed supported rows are invalid.

Every decoded record must have parseable start/finish, start <= finish <= observation time, and finish >= valid latest retention cutoff. Finish at cutoff is allowed; start may precede the quarter. Finish assigns the quarter. One observation time is captured for the semantic scan; later quarter changes require another controlled observation. A current COMPLETE ledger is an assertion in the data, not proof writers were stopped or deletion was performed correctly.

### Lifetime carry and arithmetic

Carry v1 validates exact fields, version/rule/score, canonical Taipei-quarter `through`, valid earlier tracked quarter, integer safe-range counts, outcomes/wins/losses/draw equations, completed-deal bounds, zero-deal score, casual forfeit prohibition and combined-category match bound. Unknown carry schema/rule/score is unavailable; malformed supported carry is invalid. Blank carry is an empty sentinel.

Carry through must not exceed latest valid retention cutoff. Eligible unique known-marked raw account contributions must not precede carry through. Current contribution and carry addition are overflow-checked per category, with combined match count checked separately, matching the source Carry constructor. Multi-revision records are never folded or chosen by latest revision. This helper computes no account replacement and cannot persist carry.

Any persisted carry has **unavailable historical derivation**, even if all local equations pass. Deleted raw records cannot be reconstructed. `trackedFromQuarter` is the earliest represented contributing quarter, not proof of when collection began.

### Quarterly aggregates

V1 supports unique `YYYY-Q[1-4]` quarters and either SUPPRESSED with empty counts, or CANDIDATE_COUNTS with nonnegative decimal long values divisible by 10. Unknown publication/schema is unavailable. No assumed fixed rounding, minimum match/human threshold, deal-count ratio or carry/aggregate conservation is imposed.

Aggregate quarters at/after the retention cutoff or overlapping retained raw quarters produce unavailable generator-consistency findings, **not** an invented backend decoder corruption rule. Historic derivation remains unavailable for every aggregate row. Missing historical quarters are not invented as errors. Account carry totals cannot equal match aggregates in general: guest/bot/null identities, eligibility markers, erasure, rounding and suppression differ, and source records were deleted.

## Resource limits and output privacy

The semantic scan permits at most 1,000 logical rows per tab (999 data rows), 20,000 UTF-16 code units per cell, and 12 Mi UTF-16 code units in all logical cells. A joined payload is capped at 1,000,000 characters, depth 32, 200,000 tokens, 128-character names, 100-character numbers and 10,000 completed deals; fixed-v3's actual lifecycle is much smaller. Carry JSON is capped at 20,000 characters, depth 6, 256 tokens, 128-character names and 64-character numbers. These are verification budgets, not declarations about game-data validity or Google limits. Exceeding one produces fixed `BUDGET_EXCEEDED` / `UNAVAILABLE`, rather than INVALID data.

Payloads are decoded sequentially; only bounded account/marker/chunk references, numeric contributions and aggregate sets are retained during inspection. Reports keep no payload. There is no public raw-table accessor, storage adapter, credential use or new thread. Synthetic regression tests cover max logical rows, per-cell/cumulative/payload/inner-parser budgets, and a native 999-record / 999-carry parse under the helper's 128 MiB heap. They are local resource evidence, not a guarantee that every <=32 MiB response fits the existing heap or full network/watchdog budget.

## Source adaptation and drift

No backend source was modified. Three package-private helper-prefixed classes adapt only pure validation and arithmetic. They do not package or instantiate `SheetsStatsStore`, `SheetsGateway`, Google gateways, Spring or maintenance executors. The helper POM and dependency set are unchanged.

Source reference SHA-256 values at adaptation time:

| Source | SHA-256 |
| --- | --- |
| FinalMatchEnvelope.java | dce62b96696a2a589f19551af63c35e026ee1c21f53bb85d3ff40fc150271e73 |
| SheetsStatsStore.java | f9b6f3b4d830145a890385a1c9cfe9ae1c7023ebb9d08ae4509f9def2a97204f |
| AccountProfileRows.java | cf32606650b3f2933dd1cf6ab359b03346cdf7440523f770ef8fcf2ae6b8cbdd |
| ErasureLedger.java | 92b7e9f025db1c55f53dee0593a52b6453e175c4934bd6a0812fced504dbf023 |
| RetentionLedger.java | 920690813264257f27c1b9480669252ccfadd61665c4770749fd44b35c8a9ab0 |
| QuarterlyRetention.java | bfe2701a7f21488016123d123c5b66a3dc0bb4d4595061d6af9673e43dc83433 |
| LifetimeStatistics.java | f212e3785c313f202b968026ac5488212f701c5894c4aa783c218e60d1a56248 |
| ManualQuarterMaintenance.java | f797337115e5c8388a7866906f6990c2cc810607ac0805cf7bcef144a5eec289 |
| SheetsIdentityStore.java | 03acee8b96197553e3f0de04b7cb401ef15f64f879362738bef455d66e09a18c |

These copies do **not** automatically track backend changes. Future source/schema/rule changes require review against this contract, tests and a deliberate contract-version update. Strict JSON, exact numeric types and unavailable classification are helper safeguards, not a claim of byte-for-byte parity with every permissive backend coercion.

## Local verification

Run the standalone module with JDK 17. The retained `validation/` notes/logs distinguish the initial interrupted shared-target run from later serialized clean validation. The final report records exact test counts, bounded-heap timing and jar safety audit. No live test is required or authorized by this implementation.
