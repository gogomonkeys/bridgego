# Synthetic cleanup phase 1: read-only web preflight

This is local preparation, not deployment approval and not completed cleanup verification. The dedicated `synthetic-cleanup-preflight` Maven profile builds `bridgego-synthetic-preflight.jar`; `Dockerfile.synthetic-preflight` is an optional reviewed build path. Existing Dockerfile, Render service settings and normal game entry point are unchanged.

## What runs

A separate explicit Spring graph serves a mobile browser page and `/health/ready`. It does not scan the game package, enable schedulers, create account links, initialize Sheets storage, or start writers. Startup does not read a Sheet or open the service-account secret. Default configuration is disabled; health always distinguishes `configured` from `cleanupReady: false` and `writesEnabled: false`.

After the operator checks the read-consent box, the page requests a short-lived single-use nonce, performs real Google sign-in, and makes one explicit same-origin read request. The Google verifier is unchanged. The normalized Google issuer/subject must match one privately configured SHA-256 allowlist entry; synthetic subjects are rejected as operator identities. Nonces are consumed even after failed proof, expire after two minutes, and are rechecked after authentication. There are no session cookies, automatic reads, refresh loops, retries in application code, or write routes. A failed read needs fresh proof and an explicit new action after a one-minute cooldown.

The source obtains a short-lived token with **spreadsheets.readonly** scope from the existing injected service-account key. The key is read only inside the authenticated read. It requests exactly one fixed target URL with `includeGridData=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED`, with no `fields` or `ranges`. No target comes from HTTP requests. A compiled target hash must match the private deployment identifier before startup. The source has no Sheets mutation method, does not follow redirects or system proxies, limits responses, and enforces a deadline over token and workbook response consumption. A worker still running after timeout blocks overlapping reads.

The parser keeps original response bytes, requires `COMMENTS_VIEW_MODE_INCLUDED`, enumerates all row and column metadata, and rejects unknown metadata, populated comments, extra tabs/data, formulas, notes and unsupported formats. Only the known eight headers and exact existing B2 marker `SYNTHETIC-ONLY-FORMAT-TEST` are accepted. This scaffold check is a separate entry point: the production quarter parser still requires its complete fixture schema. A successful scaffold probe is **not** an erasure or quarterly-cleanup pass.

## Private deployment prerequisites, not performed here

Only after separate deployment authorization, configure:

- `BRIDGE_SYNTHETIC_PREFLIGHT_ENABLED=true`
- `PUBLIC_ORIGIN`: exact HTTPS origin of this isolated service, without trailing slash
- `GOOGLE_CLIENT_ID`: the approved public Google web client ID, with that origin configured in Google
- `BRIDGE_SYNTHETIC_SHEET_ID`: the already-approved private test Sheet ID matching the compiled hash
- `BRIDGE_SYNTHETIC_MARKER=SYNTHETIC-ONLY-FORMAT-TEST`
- `BRIDGE_SYNTHETIC_OPERATOR_SHA256`: lowercase SHA-256 of `https://accounts.google.com`, a newline, and the verified authorized operator's exact Google subject. Do not infer this from email or synthetic subject.
- `BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON`: existing encrypted server environment secret, injected in place. Never copy it into source, logs, a browser, a new key, or a local test.
- `PORT`: host-provided port; defaults to 8080.

The actual test ID and real operator subject are not stored in this feature's public source. Missing or invalid settings do not enable reading. Configured health does not establish credentials validity, service-account access, OAuth origin compatibility, comment visibility or schema readiness.

## Local verification only

Use fake Google principals, an ephemeral generated RSA test key, fake HTTP responses and generated synthetic JSON. The focused suite may receive `-Dbridge.synthetic.test.sheet=...` privately to exercise the compiled allowed target. It never sends a network request or reads a real credential. If absent, tests requiring that private target explicitly skip; do not report those cases passed.

Run Maven only in the coordinated exclusive build slot. Normal web packaging excludes this feature. Do not use or commit private test properties/evidence.

## Phase 2 remains unimplemented

There is no fixture seed, re-fixture, account erasure, quarterly maintenance, confirmation plan, or mutation route here. Erasure and quarter engines require different fixtures; their phases must be separately planned and approved. Before any later write, require full fresh baseline and candidate fingerprints, fresh plan-bound confirmation, exclusive writer control, and an atomic fixed attempt marker in this isolated Sheet. The marker must survive restarts, prevent replay under a new plan, and preserve an unknown outcome without retry. Render ephemeral files cannot establish that property. Implement and review those guards before adding any write capability.

## Availability and JVM limits

The operator verifier has a bounded wait. If a remote Google-key response ignores interruption, the dedicated principal-only worker cannot continue to a Sheet read, and subsequent authentication attempts are blocked until it exits (or the service is restarted). This deliberately favors fail-closed behavior over availability; it is not a retry mechanism. The read worker applies the same no-overlap rule to stalled response bodies, including headers that arrive after cancellation.

HTTP header logging and TLS debug JVM properties are rejected before configuration. Custom JVM agents, replacement trust stores, hostile process instrumentation, and modification of this binary are outside this application's isolation guarantee; do not enable them on the dedicated deployment. Local tests do not prove Render TLS routing or real Google origin/token behavior.

## Read-only diagnostics and browser lifecycle

The response reports separate `authentication`, `read`, and `schema` states: `NOT_ATTEMPTED`, `UNVERIFIED`, or `SUCCESS`. A successful transport read does not establish valid schema, and a successful scaffold/schema check does not establish cleanup readiness. The server emits only fixed enum stage codes, without exception objects, remote response text, credentials, identity, target identifiers, or nonces. These diagnostics are not a complete audit trail and cannot reconstruct a result that the browser never received.

Server cooldown remaining seconds are rounded up and returned in health, challenge rejection, and read results. The browser countdown is only a hint; the server remains authoritative. Challenge requests during cooldown are rejected before asking for another Google proof. No timer sends requests. After cooldown, the operator must explicitly start a fresh attempt; the previous nonce is never reused.

Progress and the most recently completed result are separate. Starting a new attempt keeps the old result visibly labelled as the prior completed result. Duplicate or expired Google callbacks cannot submit another read. Revoking consent before submission prevents the read; revoking it after submission cannot undo that read, so the browser keeps the request pending until it receives a result or an unknown outcome. A pending request holds the local attempt lock even if consent is toggled. No credentials or results are persisted in browser storage. Refresh loses the displayed result but fetches the authoritative server cooldown; it does not submit a read. A lost read response is unverified, not evidence of success or cancellation.

Local browser behavior tests (Node, no network):

`node --test backend/src/test/js/syntheticcleanup/preflight.test.cjs`

These execute the actual served script against fake DOM, timers, Google callbacks, and fetch responses. They cover duplicate callbacks, cancellation while challenge/read is pending, stale/expired callbacks, previous-result preservation, explicit cooldown restart, refreshed cooldown, and sanitized unknown outcomes. Run them separately from the coordinated Maven suite.
