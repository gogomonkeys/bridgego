# Private Sheets adapter (phase 1)

Disabled by default. This implementation has not created, connected, or deployed any Google resource. Enable only after an operator privately creates/configures a spreadsheet and server service-account credential, grants only the service account access, and confirms the deployment has **one server instance and no overlapping deployments/writers**. Sheets cannot enforce uniqueness across processes. Never publish the sheet or expose its credentials to the frontend.

Server environment:

- `BRIDGE_SHEETS_ENABLED=true`
- `BRIDGE_SHEETS_SPREADSHEET_ID`: private spreadsheet ID
- `BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON`: complete service-account JSON, injected as a server secret. Do not put it in source, frontend build variables, logs, or exported diagnostics.
- `BRIDGE_SHEETS_QUEUE_CAPACITY`: default 128, positive integer

Required existing tabs and headers (the adapter does not create them):

- `envelopes`: `commit_id`, `payload_hash`, `chunk_index`, `chunk_count`, `canonical_json_chunk`
- `commits`: `commit_id`, `payload_hash`, `chunk_count`, `match_id`, `revision`
- `accounts`: `account_id`, `schema_version`
- `auth_identities`: `issuer`, `subject`, `account_id`, `schema_version`

The physical schema deliberately differs from the preparatory normalized matches/participants/deals schema. The server payload remains a structured, versioned match snapshot, containing participants and completed deals. Canonical JSON recursively sorts object keys, preserves array order, distinguishes null/string/boolean/integer, uses UTF-8 SHA-256, and rejects floating-point values. The caller must use stable array ordering and provide schema/score/app versions. Identity fields are verified Google issuer/subject and a random UUID only; no email, name, avatar or token is retained. Mapping creation returns only after durable read-back succeeds; storage failures must fail login closed.

Each snapshot is split into <=20,000 UTF-16-unit cells without splitting surrogate pairs. All chunks plus the commit marker are appended in **one** `spreadsheets.batchUpdate` with appendCells requests. Explicit stringValue fields provide RAW-equivalent semantics, including strings beginning with `=`; no formulaValue is used. Retry is bounded (four stats attempts / three identity attempts), with exponential delay, jitter for stats, and Retry-After handling for 429/5xx/timeouts (if the requested wait exceeds the 30-second retry budget, stop rather than retry prematurely). HTTP 400/401/403 stop instead of blind retry. Timeout is unknown outcome: next attempt reads and validates existing storage before any append. Retries retain the original match commit identity and hash.

`StatsStore.submit` only takes an immutable server-built envelope and enqueues it without network calls. It is safe to call while assembling an authoritative terminal snapshot; the worker performs network I/O outside the game lock. PENDING is not saved; only complete read-back hash validation sets CONFIRMED. REJECTED means the queue is full; FAILED includes exhausted/denied requests; CONFLICT means integrity validation failed. The same ID with different contents is never overwritten. Status is process-local with at most 4096 terminal entries retained; evicted or absent IDs return UNKNOWN and are not a durable history query. Submit can retry a FAILED or REJECTED same-hash snapshot while it remains available to the caller.

Export uses `StatsStore.exportCommitted()` and returns immutable envelopes with fresh typed Jackson payload trees. It verifies **all** markers/chunks, required counts, canonical JSON and hashes before returning results; any corruption, partial batch or orphan causes fail-closed export rather than silently dropping data. Exact physical duplicates collapse to one logical commit; mismatched duplicate contents fail. This conservative whole-sheet check also blocks further writes until an operator investigates existing corruption. Preserve a private raw backup before repairs.

For Postgres migration, pause the writer, export and validate these envelopes, flatten the typed match/participants/deals objects into the preparatory normalized schema, and insert with transaction + PK/FK/unique constraints. Keep original hash, commit ID, schema and scoring version and source payload for audit. Do not COUNT raw physical Sheets rows. Do not export identity mappings with gameplay analytics. Export is server-internal only; no public bulk-data endpoint is provided.

Limits: all-sheet scans suit a small pilot only; they are not a scalable database. The queue is **not a durable outbox**. Process restarts, free-instance suspension, shutdown or redeploy before confirmation can lose pending results. No exactly-once or every-match-saved guarantee is made. No cross-process identity uniqueness guarantee exists. Move to a transactional database before running concurrent replicas, growing to large history, or needing complete durability. API quotas, free hosting limits and credential setup must be checked at actual rollout.

Tests use a fake storage boundary only, including post-commit timeout recovery, logical duplicate handling, conflicts, orphan/incomplete/corrupt rows, 403 refusal, 429/503 retry, queue saturation, immutable canonical snapshot, typed export and durable identity retry. Live Google OAuth/permissions/API behavior remains an operator-run smoke test before enabling production.

## Erasure readiness

See `ACCOUNT_ERASURE.md` for the default-dry-run, owner-verified offline maintenance workflow and its limitations. All writers/exports now require an `erasure_ledger` tab with header `kind, identifier_hash, schema_version` and sentinel `META, erasure-ledger-v1, 1`; absent/malformed ledger fails closed. No live deletion has been performed, and fake-verifier tests do not establish live readiness.
