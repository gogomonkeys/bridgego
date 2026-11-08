# Synthetic cleanup runtime: independent safety review

Review date: 2026-11-08. This review and its tests are local-only. No external credentials, service settings, permissions, spreadsheet data, deployment, or push are authorized by this review.

## Scope and acceptance boundary

The useful first increment is a dedicated Render-compatible **read-only web preflight**, not a cleanup runtime declared complete. An authorized operator can trigger a direct full read of the fixed isolated workbook. The executable must not start the game dependency graph, use scheduled jobs, read Sheets at startup, seed test data, or expose any mutation route. A successful full read is still not account erasure, quarterly retention, or production readiness.

Only the privately supplied target matching compiled SHA-256 `e248f80801b5a0b1e4844f5e11c899e4a91c87ae4b852479fac3b43c527ab86e` may be addressed. Request bodies, URLs, environment inputs and query strings must not select another sheet. Service-account credentials, if used in a later separately authorized execution, remain in the existing Render environment. Preparation and local tests must use only fake credentials/transports. Existing credentials may have broader permissions than the target restriction in code; do not describe the code restriction as an IAM grant restriction.

The previously captured connector response explicitly reports `COMMENTS_VIEW_MODE_OMITTED`. Treat it as invalid unchanged input. Do not rewrite it to `INCLUDED`, project metadata away, treat absent comments as empty, or switch to ranges/value-only reads. The scaffold also does not establish a fully provisioned synthetic dataset.

## Phase 1 security properties and test matrix

Tests should assert the absence of downstream calls as well as the outward error. Unless a row explicitly permits it, expected counts are zero credential reads, zero token requests, zero Sheets requests, and zero writes.

| ID | Scenario | Required result |
| --- | --- | --- |
| W01 | Start server, GET/HEAD health, repeated health checks | No external read, no credential load, no game/identity/writer/scheduler graph. Health distinguishes server availability from cleanup readiness. |
| W02 | Unknown route or an erasure/seed/execute/quarter route, any HTTP method | Reject; never reaches a write transport. There are no mutation routes. |
| W03 | Changed target in JSON, query, URL, header, or environment override | Reject or ignore only explicitly documented irrelevant input; actual transport remains hard-bound to fixed target. Unknown JSON fields fail closed. |
| W04 | Wrong method, non-JSON content type, duplicate keys, trailing JSON, over-limit request, malformed UTF-8 | Reject before verifier/credential/Sheets calls. Bound actual streamed bytes, not only Content-Length. |
| W05 | Missing/wrong/multiple Origin, cross-origin request, forged forwarding headers | Reject authenticated action before credential/Sheets access. Use configured HTTPS origin, not untrusted Host or X-Forwarded-* to derive trust. |
| W06 | Missing configuration, missing approved operator subject, invalid origin/client ID | No authentication or Sheets access. Do not infer operator identity from workbook contents or first login. |
| A01 | Invalid signature, issuer, audience, authorized party, nonce, expiry; malformed token | Reject; original token and verifier exception never returned or logged. |
| A02 | Valid Google principal not on explicit operator allowlist | Reject before service-account credential access; authenticating a Google user alone does not authorize them. |
| A03 | Operator principal equals or is substituted for synthetic target | Operator and reserved synthetic account remain distinct. No actual user subject is seeded into target fixtures. |
| A04 | Reuse nonce, use a nonce from another action, use expired nonce | Reject; consume one-use nonce before processing the proof, including failed attempts. |
| A05 | Concurrent submissions with same nonce; capacity exhaustion; slow upstream | At most one read per proof; bounded requests, concurrency and nonce lifetime. Failures do not schedule a retry. |
| A06 | Missing fresh explicit read consent/confirmation | No service-account credential load or external read. Confirm exact target/purpose, not an arbitrary statement from the client. |
| H01 | Authenticated read trigger | Lazy credential access, one read-only-scope token acquisition, one exact GET with includeGridData=true and commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED; no fields/ranges. |
| H02 | Credential/token endpoint URI injection; redirects; proxy overrides | Credentials/tokens go only to fixed Google endpoints over verified TLS; no redirects, custom trust, proxy fallback, alternate auth, metadata server or ADC. |
| H03 | Token/Sheets 401, 403, 429, 5xx, redirect, timeout, interrupted call | Stop without automatic retry or token-refresh-and-retry; display sanitized unavailable result. |
| H04 | Oversized/chunked/truncated body, incompatible Content-Type/Encoding, contradictory headers | Bounded fail-closed response handling; no unbounded buffering or hidden decompression. |
| H05 | Slow/stalled response body after headers | Whole-operation deadline covers body read and cannot leak unbounded threads or leave a late action running. |
| P01 | Real unmodified OMITTED response; absent/wrong comments mode | Reject; no safety relaxation or fallback read. |
| P02 | Wrong sheet ID/URL, extra tab, missing tab, duplicate sheet IDs/names, unknown field | Reject rather than interpreting subset as complete. |
| P03 | Missing/incomplete/overlapping row or column metadata, out-of-bounds cells or dimensions | Reject; full visibility cannot be inferred from populated data range. |
| P04 | Formula, note, comment, unexpected hyperlink/format, table/chart/protected range/hidden data | Reject unsupported content; only exact reviewed display-only metadata may be accepted. |
| P05 | Corrupt headers/schema/dataset purpose; populated unexpected cells; unknown personal data | Reject semantic readiness; never adopt observed values as the approved binding. |
| P06 | Byte-identical valid fabricated complete response | Report only the explicitly verified read/schema properties. Do not report seeded fixture, cleanup, writer exclusion, or readiness as proved. |
| O01 | Any success/failure response or log | No credential, bearer/ID token, subject, account, match JSON, raw response, stack trace, or key path. Return a small stable summary. |
| O02 | Browser refresh/back/repeated clicks after submission | No automatic re-submission. A fresh explicit action and fresh nonce are required for another read. |
| B01 | Inspect dedicated JAR and normal game JAR | Dedicated artifact has only intended graph; normal artifact does not gain synthetic runtime/bootstrap. No test proof/fake auth can be enabled by runtime configuration. |

The phase-1 service deliberately has no authenticated session cookies. Each read requires a fresh nonce and Google proof, with an exact configured Origin. Tests must verify that this simpler protocol does not accidentally introduce ambient cookie authorization.

## Phase 2 mutation prerequisites: intentionally not complete

A future seed/erase/quarter operation requires separate action approval and a reviewed implementation. It must not be enabled by changing a single flag in the read-only preflight. The pending work includes:

1. Exact fixture contract, fixed dataset binding, reserved synthetic identity, and independent preservation assertions. Operator Google identity is never the deletion target.
2. Complete unmasked `INCLUDED` preflight and readback, with bounded strict schema and metadata checks, plus real service access established under separate authorization.
3. Explicit fresh operator authentication, whole-workbook plan binding, expiry and exact confirmation for each intended mutation.
4. Proven writer exclusion for all game, connector, maintenance, manual, queue and replica writers throughout execution. A digest is not a lock or compare-and-swap.
5. Durable one-shot attempt evidence on the fixed test Sheet using an atomic uniqueness mechanism. A normal cell overwrite is not an atomic claim. Render's ephemeral filesystem cannot be treated as restart-safe storage. Do not create infrastructure or expand permissions without authorization.
6. A claim key stable by fixed target, dataset, operation and phase, independent of plan/session/process ID. New previews, a restart, another replica or a second endpoint cannot replay a previously attempted operation.
7. Fail-closed crash behavior: once claim creation may have happened, uncertain claim/mutation results never trigger another mutation. Only independently authorized read-only reconciliation is allowed. A final HTTP success is not verification.
8. Quarterly two-phase cleanup treats initial deletion and final COMPLETE marking separately. Unknown first phase cannot proceed; failed final phase remains pending/uncertain. Completion timestamps cannot be fabricated or overwritten by retries.

Required crash/fault cases: before claim; rejected claim; uncertain claim; after durable claim before mutation; mutation applied with response lost; readback unavailable; readback differs; verified mutation before final marker; final marker applied with response lost; process restart; concurrent processes; fresh session/new plan; corrupt/partial marker. Every such test asserts the allowed cumulative write count and that no client/server retry or restart replays a batch.

## Reporting language

Acceptable initial result: “Local read-only preflight implementation and fake-network tests passed; no external read, deploy, seed or cleanup was performed. Live full-read compatibility and cleanup remain unverified.”

Do not call this a complete cleanup runtime, real Google authorization verification, successful live test, deployed change, proven deletion from backups/revision history, or safe production activation.

## Static review outcome

The current phase-1 implementation has an explicit locked Spring graph, does not scan or start the game application, and exposes only page/script/health plus challenge/read routes. Settings validate the fixed target hash before service construction. Credential access is deferred behind allowlisted operator authentication and fresh single-use proof. The scaffold parser preserves the original response and shares strict structural checks with the existing full parser; its separate exact scaffold validator does not loosen the production parse entry point. Health and success summaries keep cleanupReady and writesEnabled false. No mutation transport or endpoint was added.

Review feedback addressed in code includes rechecking nonce expiry after verification and before credential access, avoiding system proxies for the token/workbook client, redacting request stringification, requiring at least 2048-bit RSA key material, and rejecting duplicate/ambiguous response headers. Malformed response headers close the body before rejection. Operator subjects beginning with the entire reserved SYNTHETIC-ONLY prefix are rejected independently of the allowlist hash.

The final timeout design was reviewed statically. Authentication runs in a dedicated principal-only worker with a bounded wait of at most ten seconds. A timeout cancels the result and returns an authentication failure. That worker has no Sheet continuation, so even a late successful principal cannot trigger credential access or a workbook read. The separate authActive guard remains set until the verifier actually exits, preventing another proof worker from accumulating after a timeout. If cancellation occurs before the callable begins, this conservative guard can remain blocked until restart; that is an availability limitation, not an authorization bypass.

The token/workbook worker uses a separate per-read cancellation flag, checks it after credential acquisition and before each network phase, and checks it again after late headers are installed and before body consumption. Timeout initiates body closure. A response arriving after cancellation is closed without consuming its body or advancing to the next GET. The active guard remains held until the worker actually exits, so a non-cooperative stream cannot cause overlapping reads. These properties prevent late Sheet access after a failed authentication and prevent a canceled token response from advancing to a Sheet GET; they do not promise immediate termination of non-cooperative third-party I/O.

No data-targeting or operator-authorization blocker was found in this phase-1 scope. This is not a claim that every row of the broader acceptance matrix has been executed.

Availability and deployment limits remain:

- The bounded authentication wrapper returns after timeout, but the underlying reused Google verifier's JWKS body read may ignore interruption. A stranded principal-only worker blocks new authentication until it exits or the service restarts. It cannot continue to a Sheet read. There is no cleanup write to replay; bounded caller wait is not proof of underlying I/O termination.
- Nonblank jdk.httpclient.HttpClient.log and javax.net.debug properties are now rejected before configuration or client construction. A locked Spring environment is still not a JVM sandbox. Hostile agents, instrumentation, replacement trust stores and binary modification remain outside the application isolation guarantee.
- Actual OAuth origin compatibility, operator allowlist, existing secret availability, target permissions, complete comment-visible response, cloud startup, TLS termination and browser behavior remain externally unverified.

## Local test artifacts and execution status

`backend/src/test/java/com/bridge/syntheticcleanup/SyntheticCleanupIndependentReviewTest.java` adds 17 fake-only tests for auth, freshness, replay, concurrency, cooldown, stream zeroing, configuration, Origin/query restrictions, forbidden routes, streamed body bounds and strict request content. Tests requiring the approved target accept the private `bridge.synthetic.test.sheet` property. Without it they explicitly skip and must not be reported as passed. No real credential, Google sign-in, HTTP call, Sheet data or external mutation is used.

No Maven command was run by the independent reviewer. The builder reported an initial focused run of 29 tests with zero failures or skips, including these 17 tests. Additional authentication-timeout, late-header-cancellation and logging regressions, plus the final synthetic-prefix tightening, are subject to the parent's final coordinated full run. Final exact results must be recorded by the build owner after testing the final source with the private synthetic target binding. The earlier focused result is not a full-suite or live-network claim.
