# Statistics read diagnostics candidate

This is an observability patch, not a confirmed fix for the intermittent statistics failure. No production data, configuration, permissions, polling frequency, statistics rules or public response schema is changed.

## Diagnosis supported

The prior service intentionally returned `UNAVAILABLE` without retaining an internal reason. That makes a past failure impossible to attribute conclusively from application logs.

The new bounded log distinguishes:
- admission: another statistics read already owns the single-reader permit;
- storage: upstream HTTP, integrity, state or unexpected failures;
- boundary: read crossed a quarter boundary or returned an invalid snapshot time;
- projection: calculation/validation rejected the returned snapshot.

Example (synthetic):
```
statistics_read_unavailable stage=STORAGE reason=STORAGE_HTTP storageStatus=429 elapsedMs=240
```

Only closed enums, an allowlisted HTTP status and monotonic elapsed milliseconds are logged. Exception messages, class names, stack traces, causes, account/session identifiers, names, email addresses, record contents, spreadsheet IDs and credentials are not logged. Repeated identical stage/reason/status combinations are limited to one event per 60 seconds per service instance.

Logging is best-effort; a logging backend exception cannot replace the safe fallback.

The existing fail-closed response and single-reader behavior are unchanged. No retry, cache, extra external read, write or automatic cleanup is added.

## Offline evidence

- An isolated replay of anonymized persisted record shapes passed through the actual storage export and projection code with no network or writes.
- The normal combined export performs 14 Sheets reads. This increases exposure to latency/quota issues, but is not proof that a quota failure caused the reported incident.
- A fully synthetic retained regression covers an old four-column account, unmarked history and a current casual result together.
- Concurrency testing demonstrates the existing transient BUSY result and later recovery without extra storage work.
- A successful later read does not establish that the underlying intermittent failure is resolved.

Private replay inputs and the temporary replay test are not included in the candidate.
