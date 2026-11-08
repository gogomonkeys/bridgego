# Restricted test / erasure / quarterly retention verification

2026-11-05 local candidate, based on v0.11.0 commit 6b077cb90dfe402e9a07260798da51f5ba469c99. Supersedes the earlier PHASE1_VERIFICATION counts for this checkout. No external settings, Google resources, permissions, credentials, deployments or real deletions changed.

## Historical executed checks (2026-11-05)

- Java17.0.21, Maven3.9.11, existing offline cache: **clean test package -Pweb succeeded; 720 tests, zero failures/errors/skips**. Packaged current frontend in executable JAR.
- Backend includes 33 auth/policy tests; 7 authoritative match integration/eligibility/archive-expiry tests; 31 persistence tests (10 StatsStore, 9 account erasure, 8 quarter retention, 3 dataset guards, 1 atomic request-body builder); 4 maintenance runtime/security integration tests. Existing game/scoring/socket suites rerun too.
- Frontend: **34 test files / 448 tests passed**; typecheck, lint and production build passed after final rejection/disclosure text changes.
- API structural gate: **28 ordinary-game HTTP operations / 56 application error codes**, auth annotations, references, frontend messages and rendered catalog. Maintenance endpoints are isolated and documented separately. Full OpenAPI schema validator was not installed.
- git diff --check passed.
- Maintenance embedded JavaScript parsed with node --check.
- Packaged ordinary-game runtime actual localhost HTTP smoke: collection/verification default off, restricted mode, guest session creation and continuity, exact-origin rejection, in-room account-change rejection. No live Google call.

## What the new tests establish

Restricted policy rejects unapproved, forged, email-like or purpose-swapped identities before any IdentityStore call. Verification-only never links or stores identities, returns only the caller's verified subject, and has distinct consent/challenge. Empty allowlist, missing privacy contact/retention, unverified erasure or quarter policy all keep operational collection disabled. Every initial HUMAN must have admitted linked tester state; a single guest/unapproved human excludes the entire match. Existing visitor play remains functional.

Every storage operation checks the explicit dedicated dataset UUID/purpose; missing/wrong metadata refuses reads/writes. Test/prod IDs cannot match when a production ID is configured. Accounts and stats require mandatory erasure and current-quarter retention metadata. Queued records with expired finish timestamps, future finishes or start after finish are rejected; valid games spanning a boundary follow the existing `finished_at` quarter attribution; normal internal export cannot expose old raw records; authorized offline decoding remains available for cleanup. This does not promise that a network request begun before midnight cannot complete afterward.

Erasure tests verify fresh proof, same owner twice, exact per-plan confirmation, disabled-execution and stopped-writer gates, stale preview rejection, other-player/score preservation, per-seat random replacement markers, recomputed hashes, residual suppression ledger, old identifier replay even under a new match ID, idempotency, timeout/readback, uncertain and unapplied outcomes. Unknown metadata refuses deletion. Replacement writes only the previously read A:E schema columns in one batch, never F+ or dataset marker.

Maintenance tests start the real isolated Spring graph and confirm no game service/controller, stats/identity writer or scheduler. Game APIs/assets are absent; /health/ready identifies maintenance. Origin, secure cookie/session, nonce/plan isolation, body limits, dry-run, fresh same-owner execution and replay protection are exercised with fake Google and fake Sheets. Use /maintenance, not the service-worker-cached root URL.

Quarter tests cover all Taiwan calendar quarter boundaries/year rollover, stale/future/reversed-timestamp rejection and finish-time attribution for games spanning a boundary, raw-row purge with accounts/identities/tombstones unchanged, watermark replay blocking, default suppression, candidate aggregate validation, stale plans, dry-run/offline gates, failure/timeout/readback and recovery of interrupted PENDING state. Completion time is recorded only after readback. Completed room archives expire at an approved Taiwan boundary; active games and accounts are not destroyed. A test initially jumped the mutable clock after a match had already started and incorrectly expected an unexpired timer; fixture corrected to start at the intended instant, then rerun passed.

## Not established / still blocked before activation

- Real Google sign-in, consent, CSP, cookies, PWA service-worker behavior and current platform shutdown have NOT been tested. Earlier installed Chromium launch failed with environment Unix-socket permission restrictions; no pixel or actual browser success is claimed.
- Actual Google Sheets permissions, quotas, destructive atomic requests, timing, backups, revision history and restore rehearsal NOT run. Fake boundary tests are not live deletion proof.
- Public deletion contact specified: jody.yycc@gmail.com. User approved Taiwan quarters, raw personal match deletion at quarter end, accounts retained. Current code assigns cross-quarter games by `finished_at`; verify the recorded attribution policy before production activation. Aggregate privacy thresholds remain unapproved. SUPPRESS_ALL is the aggregate default; test-injected 50-match/20-human/rounded-tens candidate is NOT an approved policy.
- Quarter physical cleanup remains an offline, explicitly confirmed engine, not an unattended schedule or a public/tester endpoint. There is no guaranteed midnight purge on sleeping/free Render. Requests can block expired data and new collection, but physical deletion waits for exclusive operator maintenance. Operator invocation/automation must be finalized after policy approval; do not claim that a schedule is installed.
- No cross-process lock/CAS exists. A boolean writer acknowledgment is NOT proof. All old game/identity/erasure writers, deploy overlaps, imports and manual edits must actually stop; only a fresh process may resume. Unknown outcomes require investigation, not blind retry.
- Erasure hashes remain pseudonymous control data and prevent re-registration. Shared facts and unmanaged copies can still identify activity. This is not a legal-compliance certification or erasure of Google version history/backups.
- Contact/retention strings do not automatically enforce an operator's promises. Both BRIDGE_ERASURE_WORKFLOW_VERIFIED and BRIDGE_QUARTER_POLICY_VERIFIED default false. Keep them false until the actual approved workflows are rehearsed.

No publish/push/deploy/real data collection is authorized merely by these local checks. Source release labels still reflect the v0.11 baseline; choose a new release only when publication is explicitly approved. Local node_modules symlink reused locked dependencies and is excluded from the source bundle; fresh npm ci was not rerun.

## 2026-11-08 local follow-up scope

The earlier counts above are historical, not results for the current recovery patch. Added fake-only quarterly checks cover all four exact Taiwan boundaries, pre-existing aggregate preservation, subsequent-quarter cleanup, fresh same-quarter COMPLETE no-op without changing completion time, and failed/uncertain attempted-plan replay rejection. The dedicated live CLI no-op path does not create an attempt marker and cannot fall through into writes after failed readback; an existing marker still forces preview-only mode. See `docs/QUARTERLY_RETENTION.md` for the current behavior and scope limits. Report current executed test results separately. No real Sheets access, credentials, push or deployment are included in this follow-up.
