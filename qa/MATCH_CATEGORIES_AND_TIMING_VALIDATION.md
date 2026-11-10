# Fixed rooms, terminal/quarter/lifetime statistics and per-deal timing validation

Date: 2026-11-10. Candidate based on v0.12.2 commit `43575d9`, at `bridge-match-categories-20261110`. Pre-release validation completed; live deployment is verified separately. No production database, Sheets, account, permissions or settings changed.

## Final checks

- Backend: offline Maven `-Pweb clean package`, **1,095 tests passed**, 0 failures/errors/skipped. Release-version checks are rerun before publishing.
- Frontend: **558 tests passed**, TypeScript + Vite production build and ESLint passed after final fixed-mode, duration and privacy-copy updates.
- Packaged loopback HTTP: **17 checks passed** on the final JAR with persistence/statistics disabled and a credential-free environment. Includes fixed mode create/list, legacy default CASUAL, invalid null/no mutation, guest/statistics authorization, Origin, and static production bundle. See `RELEASE_013_NETWORK_VALIDATION.json`.
- API/document structural drift gate: **32 operations / 68 errors passed**. Error catalog regenerated. Full OpenAPI validator dependency was unavailable, so no full standards-validator pass is claimed.
- `git diff --check` passed. The separate candidate's `MatchPersistence.tsx` and `MatchPersistence.test.tsx` are byte-for-byte unchanged from `bridge-account-profile-20261110`.
- CUA verified the actual React synthetic lobby selectors and later the personal quarter/lifetime tabs, formal/casual lifetime scores (synthetic 123/456), next-quarter boundary label, and overall view omitting lifetime. Scope selection survived switching back to personal. This is synthetic frontend interaction, not real multiplayer/auth/storage validation.
- Real multiplayer, Google login, Sheets writes, production retention and live account erasure were not performed.

- Lifetime focused core/API/export/retention suites: 85 tests passed. Live parser/storage and profile adapters: 43 tests passed; final mixed-header profile suite 4/4 and strengthened live adapter guard 16/16 passed (overlapping runs, not additive to the web total).
- Independent read-only review found no confirmed blocking issue and reran 20 lifetime tests on a separate copy; passed. Its requested profile/E and mixed-header regressions were added and passed. The final condition review is in `STATISTICS_CONDITION_REVIEW.md`.
- All four isolated operator/rehearsal packaging profiles built successfully; the resulting JARs were checked for LifetimeStatistics and Carry classes. They were not executed against external storage.

## Coverage and behavior

- Mode fixed at room creation and preserved by rematch/archives. Omitted type defaults to CASUAL for old clients; malformed explicit values reject. FORMAL forbids bots and rejects duplicate known accounts; CASUAL remains casual with four humans.
- FORMAL explicit departure forfeits, disconnect permits 10 seconds to return, and no bot takes over. A one-second adjudication window merges opposing near-simultaneous failures. Only already-pending opposing reconnects retain their original deadline; new disconnections do not prolong it. Same-team failures produce one loss, opposing failures NO_CONTEST. Late return cannot undo failure; committed final-card SCORED cannot change.
- Tests cover multiple sockets and stale closes, 9,999 ms recovery, exact timeout, existing opposing grace recovery/expiry, all-offline failure, frozen last card, partial 5-deal matches, rematch mode/seat release and the explicit leaver moving to another room without old-rematch cleanup deleting the new membership.
- Statistics include confirmed finalized matches, separately exposing SCORED/FORFEIT/NO_CONTEST counts. FORFEIT counts winner/loser independent of current score; NO_CONTEST is neither win/loss nor draw. Only actually completed deals, their elapsed time and score totals persist, including zero-completed-deal terminal matches. Guests have no personal statistics or retained guest identifiers.
- Strict v3 validation rejects contradictory room/outcome/reason combinations, completed-forfeit counts, malformed durations, noncontinuous completed indices and invented score totals. v1 and v2 semantics remain unchanged; unmarked historical data is not inferred or rewritten. Operator quarter maintenance and account erasure are verified on synthetic data.
- Each scored deal is timed from its first deal to score commit using monotonic elapsed time. Includes redeals, decisions and disconnect waits; excludes final reveal and inter-deal waiting. The current/deal/previous-match result summaries show per-deal durations. Legacy missing duration is unknown, not zero.
- New consent is `statistics-v4`, saved statistics marker `fixed-room-type-v3`. Existing managed collection/consent gates remain; disabled/restricted/experiment collection is not silently expanded.

- Personal statistics now distinguish quarter/lifetime. Win rate uses wins/(wins+losses+draws), excludes NO_CONTEST and is unknown for denominator zero. Points are actual completed-deal team totals across finalized matches, including negative values and partial forfeits.
- New `account-career-v1` marker gates lifelong retention; unmarked earlier consent does not permit retroactive career folding. Only per-account numeric carry goes in private accounts column E. Quarter purge atomically folds expired eligible raw records and deletes them, then verifies PENDING/COMPLETE. Replay/conflict/failed/uncertain/pending recovery, account erasure, stale/mixed-generation reads and profile updates preserving E are tested.
- A final review fixed a formal-departure/account-switch race by freezing start-time account eligibility, not checking mutable later account links. A targeted regression verifies that the original account still receives the correct forfeit record.

## Remaining release conditions

This document records v0.13.0 pre-release validation; live deployment is checked separately. Future publications require their own authorization and versioning. Release reader/writer/operator tools together because older parsers reject the new marker/fields and 5-column accounts header. The existing workbook must have A:E capacity; unsupported grid size is rejected, not automatically expanded. If a later release changes `app_version`, update the maintenance version allowlist too. The cutoff label is a reporting boundary, not automatic maintenance; the existing manual offline quarterly COMPLETE gate remains. No production migration, historical backfill, automatic cleanup or new monitoring is included. Main implementation/format reference: `docs/MATCH_CATEGORIES.zh-TW.md`.
