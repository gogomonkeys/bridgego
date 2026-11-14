# Leaderboard candidate (local development, not deployed)

Based on b801f4daf4a119d3a477d73b7a3edb363c2f677f. This candidate is deliberately not pushed or deployed.

## Behavior
- A signed-in-only leaderboard entrance sits beside Statistics in the lobby and opens the leaderboard tab directly, with season/lifetime filters for formal matches only.
- Ranking uses signed cumulative score descending, then exact wins / (wins + losses + draws), without rounding for comparisons. No-contest is excluded from the rate denominator. Exact ties share competition ranks.
- Top 100 entries plus the caller's own rank are returned. Only nickname, summary counts and a `mine` flag leave the server. Account IDs and email never appear in the response.
- An account with no recorded matches in the selected period is not ranked.
- Guest and Google sign-in precede nickname input. Returning Google accounts reuse their stored nickname; Google identity, not display name, links statistics.

## Retention and integrity
- No new leaderboard table, copied historical details, or scheduled job is introduced. Season totals reuse current committed envelopes; lifetime totals reuse existing account carry plus current lifetime-marked envelopes.
- Existing quarter cleanup code/schema is unchanged. Account/carry validation and current-quarter readiness guard the read.
- Three bounded Sheets batch reads are used. Every account/control must remain unchanged across the snapshot; mixed snapshots fail closed. Reads never repair or write data.
- Existing statistics single-reader and rate-limit cooldown are shared with leaderboard reads. Unknown/failing reads return unavailable, not fabricated zero data.

## Rollout status
`BRIDGE_LEADERBOARD_ENABLED` remains `false` by default because this candidate is not deployed. The user has decided that eligible Google-linked accounts automatically participate, using formal results only. Concise entry/leaderboard notices identify visible nickname and formal performance; details are in Help. Guests and casual results never rank. There is no opt-in checkbox or operator test mode.

Public deployment remains separate from this local development task. No existing blocked release is retried or bypassed. Only formal results rank; casual statistics remain available privately. Tied ranks and no minimum-match threshold remain current candidate choices; no additional score or win-rate formula is introduced.

## Verification scope
Local unit/integration tests cover projection, storage snapshots, authorization, no-store responses, disabled default, shared cooldown, and login/nickname behavior. These tests do not certify production connectivity, live user consent, or automatic quarterly cleanup deployment.

## Independent review
Read-only review found and corrected the legacy lobby Google-link path overwriting a saved nickname, plus the too-short nickname-save timeout. Unknown nickname-save results stop resubmission and direct the player to sign in afresh to read the saved state. Browser visual QA and real Google end-to-end sign-in remain unperformed in this local candidate.

## Final local verification (2026-11-14)
- Backend clean rebuild: 1,140 tests, 0 failures, 0 errors, 0 skipped.
- Frontend: 51 test files / 588 tests passed; typecheck, lint and production build passed.
- `git diff --check` passed.
- Independent review corrections included; lobby shortcut opens the leaderboard directly and remains hidden for guests.
- Not a live release or a real-Google browser E2E certification.

## Compact-copy revision
User-facing ranking/statistics pages now retain short hints, numeric breakdowns and links to Help. Detailed scoring, ranking, storage, retention and deletion explanations live in Help. Existing nickname wording now matches the account-first flow. The login notice identifies saved data and its private destination, and now discloses automatic nickname/formal-performance ranking. The user resolved participation as automatic for eligible linked accounts; the rollout flag remains off until an authorized deployment.

## Formal-only revision
Backend rejects CASUAL as a leaderboard filter, and projection contains no casual selection path. Tests cover guest exclusion, casual current/carry exclusion and quarterly raw-history removal preserving lifetime formal ranking. No quarterly cleanup or account schema changed.

Latest formal-only verification: backend clean rebuild 1,142 tests passed; frontend 51 files / 593 tests passed, typecheck, lint and build passed. No production publication performed.
