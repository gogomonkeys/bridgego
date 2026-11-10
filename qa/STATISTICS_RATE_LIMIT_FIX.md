# Statistics rate-limit repair candidate

Release v0.13.2 is authorized. This document records pre-release validation; deployment status is verified separately. Based on v0.13.1 commit `0bba19f5c02d15b1812a80b7fa34ea48ee0cb6bd`. No production data, credentials, configuration or cleanup changed.

## Confirmed symptom

Safe production diagnostics recorded admission BUSY at 2026-11-10 10:45:32 UTC, followed by storage HTTP 429 at 10:46:03 UTC. The earlier statistics export performed 14 logical table reads; managed DatasetBinding separately verified the dataset for each read, making up to 28 Sheets GET requests per statistics request. The earlier offline 14-read count omitted that wrapper.

## Repair scope

- Three batched Sheets GET requests: control tables plus dataset, envelope/commit tables plus dataset, then control tables plus dataset again.
- Every batch verifies the exact configured dataset marker and requested table/range set. The first control gate must pass before requesting raw records.
- Retains canonical hash/chunk/commit/duplicate, erasure, account-row, lifetime-carry and quarter-boundary checks. The same target account row and retention/erasure controls must match before and after. Offline maintenance remains required; batchGet is not represented as a cross-request transaction.
- No persistent statistics cache, cross-account result sharing, automatic retry or read queue.
- Non-2xx responses preserve the received HTTP status and safe Retry-After without reading, parsing or logging the untrusted error body. Failed or blocked body close cannot hide an already-known 429.
- HTTP 429 starts shared statistics-only cooldown: at least 60 seconds, or a longer valid Retry-After. During cooldown there are no storage requests. Very large valid delay values do not overflow or trigger early retries.
- The UI receives only nullable fixed availability reasons and retry seconds. It waits at most one hour at a time; longer upstream delays remain enforced server-side. BUSY requests receive a short retry delay without starting another storage read.
- Manual retry is disabled with a countdown; expiry only unlocks the button and never sends a request automatically. Classification, score, retention and consent rules remain unchanged.

## Verification limits

Synthetic tests exercise the batch transport/parser, DatasetBinding, existing integrity/privacy guards, concurrent reads, cooldown and account changes without network or writes. Production recovery will require an authorized deployment and a normal authenticated read afterwards; the local candidate alone does not establish live recovery. Browser-interaction verification may remain limited by the available browser tool; unit tests are not a substitute for an observed countdown interaction.

## Local verification (2026-11-10)

- Full backend web-profile clean test/package: 1,124 tests, zero failures/errors/skips.
- Local credential-free production-profile HTTP smoke: 17/17 checks, statistics/Sheets disabled. This proves routing/auth/no-store contracts, not live authenticated statistics recovery.
- API structural drift gate: 32 HTTP operations / 68 application errors; not a full OpenAPI schema validation.
- `operator-kit`, `erasure-rehearsal`, `account-quarter-rehearsal`, and `live-quarter-maintenance`: clean package compatibility passed. No maintenance command was run.
- Independent read-only review found no remaining known blocker after the dataset-order fixture and rejected-response handling were corrected.
- Final frontend: 573/573 tests, lint, TypeScript and production build passed; nullable API metadata covered.
- Final combined web artifact rebuilt and loopback HTTP 17/17 repeated after maintenance packaging.
- Real browser countdown interaction was not observed: the available CUA inventory call remained blocked, and local headless Chromium could not launch under the environment's socket restriction. No bypass was attempted.
