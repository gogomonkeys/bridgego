# Phase 1 local verification, 2026-11-05

Base: gogomonkeys/bridgego 6b077cb90dfe402e9a07260798da51f5ba469c99 (v0.11.0).
Candidate only. No push, PR, deployment, Google project, credential, OAuth grant, Sheet creation or sharing took place. No true Google sign-in or Sheets request was run.

## Passed

- Java 17.0.21 / Maven 3.9.11, offline existing dependency cache: clean test package with web profile. **681 tests, 0 failures/errors/skips**, executable production-layout JAR built with local frontend.
- Includes 22 new auth tests (4 signed-RSA JWT, 15 service/concurrency, 3 HTTP-controller); 9 new fake-Sheets tests; 5 authoritative match integration tests.
- Existing game/scoring, privacy, takeover, timing, room lifecycle, WebSocket and controller unit suites all rerun in the 681 total. This does not mean every historical actual-network soak was repeated.
- Frontend: **33 files, 442 tests passed**, including 11 new optional-auth/disclosure tests; typecheck, lint and production Vite build passed.
- API documentation structural drift gate: 27 HTTP operations / 53 application error codes, internal references, expected auth and frontend message coverage. Full OpenAPI validator not installed; full schema validation not claimed.
- Actual local HTTP smoke with the packaged JAR: disabled public config, guest creation/status, disabled nonce, foreign-Origin denial, same-Origin unlink preserves guest bearer, room creation, in-room unlink denial, missing last match and missing bearer rejection. No live Google requests.
- git diff --check passed.

## Important tested behaviors

- Google RSA signature/issuer/audience/authorized-party/expiry/iat/nbf/nonce/subject checks; forged algorithm/key/signature rejected. Verification is injectable for tests, not bypassed by a production fake-token switch.
- Nonce bound to guest session, consent, one-use/replay and expiry. Cancellation targets the correct nonce; old cancellation cannot erase a newer attempt. Cancellation/expiry/room entry during I/O prevent final account attachment. Cancellation/logout during blocked verification produce zero storage calls; cancellation after storage begins cannot undo an existing mapping. Unlink serializes with login to avoid false signed-out responses.
- Auth network/storage operations run outside RoomService global lock. Link/unlink only outside rooms, retain playerId/token. Matching Google identities use durable random account UUID mapping. Failures leave guest playable.
- Final match captured once, server-side only; late presentation/disconnect cannot alter timestamp/hash. Rematch gets a new match ID. Original-participant ACL on save status. No name, token, private hands or played-card history in persistence projection.
- Frozen account/guest attribution; no retroactive link. NO_CONTEST stores no unfinished deal. Human seats replaced/controlled by bots are marked MIXED; original participant identities retained.
- Fake Sheets post-commit timeout readback, duplicate/conflicting commits, missing/orphan/corrupt batches, marker/payload structural consistency, 403 stop, 429/503 retry, queue rejection, canonical immutable payload and typed export, persistent identity reuse. Simulated restart explicitly loses an unsaved pending envelope and returns UNKNOWN; explicitly resupplying an already-committed authoritative envelope confirms without appending again.
- Terminal status cache bounded to 4096. Unknown/evicted status is UNKNOWN rather than DISABLED or CONFIRMED. No durable-outbox/restart recovery guarantee.

## Review and remaining limits

Independent security reread identified and corrected: auth unlink/login race, nonce expiry recheck, marker/payload shape mismatch acceptance, unbounded status-cache growth, and inaccurate anonymous/logout wording. Focused suites passed after fixes.

Actual Chromium desktop/mobile checks were attempted with installed Chromium and Playwright, including an approved escalation. Browser creation failed before navigation with `socket() failed: Operation not permitted` in process_singleton_posix; no screenshot or browser-flow success is claimed. Do not treat unit tests as physical-device or pixel validation. The exact attempted UI scenarios were default-disabled guest play, consent-before-GIS-load, locally intercepted fake GIS cancellation/401 fallback, and guest continuity.

Not run: real Google OAuth/JWKS network, Google consent/verification workflow, actual service-account credentials/permissions, live Sheets quota/failure behavior, real multi-instance coordination, full Postgres migration/rollback, physical mobile/PWA acceptance, historical long-duration network soaks. All require further environment/configuration or separate approval where applicable.

Deployment must remain disabled pending privacy/retention decisions and approvals listed in docs/PHASE1-GOOGLE-SHEETS.zh-TW.md. Keep one writer and no overlapping deployments. Current source keeps baseline application release labels; select and apply a new release version only when publication is authorized. Persistence envelopes explicitly identify this as the phase1-local candidate.

## Reproduction

From backend with Java17 and an existing Maven dependency cache: mvn clean test package -Pweb. Build frontend first: npm ci, npm run typecheck, npm run lint, npm test, npm run build. This run reused the existing locked dependency tree via a local node_modules symlink; it was not a fresh npm installation. The symlink is excluded from deliverables.

From repository root: python qa/check-api-docs.py. No configured secrets are necessary for the default-disabled build/tests. Do not supply real production credentials to tests.
