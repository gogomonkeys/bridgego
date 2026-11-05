# Account erasure: restricted-test operator maintenance

## Readiness and authority

This is locally implemented and fake-storage-tested code, **not evidence that an account or Google resource has been deleted**. No live operation or live Google smoke test has been performed. `OperatorAccountErasure` is absent from the ordinary game process; its default constructor is dry-run only. A separate locally implemented maintenance-only browser runtime now connects nonce challenges and Google verification, with per-plan confirmation and fresh owner proof. It has not been exercised with live Google or actual deployment shutdown. See ../docs/ERASURE_MAINTENANCE.zh-TW.md; use /maintenance, not the potentially service-worker-cached root URL. Do not enable collection on the assumption that injectable-verifier unit tests are an operational ownership/deletion workflow.

Execution requires all of the following, independently:

1. A deliberately enabled offline maintenance harness, not the running gameplay server. Restrict it to the explicitly approved private test dataset via `TestDatasetGateway`; never point it at production by changing a label.
2. All gameplay **and identity-creation** writers, processes, overlapping deployments and import jobs stopped. Drain or cancel queues and resolve in-flight/unknown writes before preview. An environment acknowledgement and a local thread's `isStopped()` alone cannot establish exclusivity across processes. An operator must verify deployment/process shutdown. Do not restart an old process, session set or old queue afterward.
3. A new server-generated challenge and a nonce-bound Google ID token verified with the approved client audience, signature, issuer, expiry and nonce. Preview derives the account only from this verified issuer/subject mapping; there is no client-supplied account-ID authority.
4. An immutable preview showing affected matches/seats and whole-schema before/after digests, valid for five minutes. Operator reviews the effect and gives the exact plan/digest-bound confirmation text at action time.
5. A **second**, fresh challenge and valid Google proof from the same owner at execution, plus the expected original dataset digest. A stale snapshot, changed dataset marker, missing header/sentinel, wrong owner, absent confirmation or disabled execution stops before a write.

Credentials must travel only through an approved localhost/maintenance browser POST flow with session/CSRF binding, never chat, command-line arguments, files, email, screenshots, URLs, logs or browser local storage. A claimed issuer/subject or manually typed account UUID is not proof. If this browser flow or its approved OAuth origin is unavailable, stop at preparation; do not weaken ownership checking. Creating/changing an approved OAuth origin or credential still needs its own authorization.

## Effects and boundaries

The engine removes the verified account's `accounts` row and all `auth_identities` rows mapped to that account. In affected shared matches it clears that account's `account_id`/`guest_id` fields and adds a fresh random `erased_marker` separately for each match/seat. The marker is not reused across matches and contains no hash or account identifier. Other participants, match outcomes and score data are preserved. No names, emails, tokens, cards, or inferred identity are added. Unknown participant fields or unexpected top-level envelope fields stop the plan rather than accidentally retaining unreviewed personal attributes.

Google ownership cannot prove ownership of earlier guest-only sessions. The schema prohibits simultaneous non-null account and guest IDs. Guest-only history is therefore **not guessed, linked or removed** by this account workflow; a separate verified guest-ownership workflow would be needed. The preview is explicit about account-linked records only.

Changed canonical envelopes and commit hashes, account/identity rows and the erasure ledger are replaced in one `spreadsheets.batchUpdate` using `updateCells`. Replacement is limited to the verified **A:E schema columns** in the five named tabs. The `dataset` marker is included in the digest but never replaced. Columns F onward, formatting, comments, other tabs, Sheets revision history, copies and backups are outside this operation; never claim they were deleted. Unexpected cells within the A:E table schema fail validation. The code does not claim legal anonymity: retained shared match facts may still be identifying in context.

Readback must match the full planned after-digest before returning `VERIFIED`. Timeout triggers readback, not blind destructive retry. `NOT_APPLIED` means the original digest is still visible; `UNCERTAIN` means the outcome cannot be established. Keep maintenance in place, investigate and obtain a fresh preview/confirmation as needed. Replaying the same confirmed plan is read-only if its after-digest is already present. A later owner-authenticated preview returns `ALREADY_ERASED` from the ledger without another rewrite.

Sheets has no cross-process compare-and-swap: the final digest check and atomic update are not one transaction. Whole-table reads across tabs are not one snapshot either. **Exclusive offline operation is mandatory**, and a digest is not a distributed lock.

## Durable restoration prevention and residual data

Required `erasure_ledger` rows:

- Header: `kind`, `identifier_hash`, `schema_version`
- Sentinel: `META`, `erasure-ledger-v1`, `1`
- Tombstones: `ACCOUNT` or `IDENTITY`, domain-separated SHA-256 digest, `1`
- `GUEST` tombstones are understood by replay validation, but this account-only workflow does not invent guest ownership.

All stats writes and exports validate the ledger and reject tombstoned account/guest identifiers, even if replayed under a new match ID. Identity resolution rejects tombstoned identities. Missing or malformed ledger data fails closed. The retained hashes are **pseudonymous personal data**, not anonymous proof and not total erasure. Their sole purpose is preventing restored/queued/imported old identifiers. Current policy keeps them for the lifetime of this dataset and **blocks re-registration of the erased Google identity**. A future re-enrollment policy must be explicitly designed rather than silently bypassing the tombstone.

This cannot protect against a full pre-erasure backup restore that also removes the ledger, nor against manual bypass of the validated adapters. Before any import/restore, keep the current ledger separately under restricted operator control, validate it first, merge its tombstones, reject any resurrected identifiers, and verify the entire resulting dataset. Never restore an old ledger over the current one. No production import API exists in this phase.

## Operator completion checklist

- Verify live after-digest and inspect only the minimal redacted summary, not copied credentials or raw identity lists.
- Keep the current ledger protected. Start only a fresh gameplay process with empty old queues and invalidated old auth sessions; recheck the dataset marker.
- Inventory private backups, local exports, exported diagnostics, spreadsheet copies, Google revision history and any F-onward/helper data separately. Restrict access immediately and apply an explicitly approved purge/retention policy. Provider retention/version-history restrictions may prevent immediate all-copy erasure; disclose this rather than promising it.
- Obtain separate action-time approval before irreversible backup or copy purges. This service does not perform those purges.
- Tell the owner exactly which active account-linked rows were changed, that shared score facts remain, that suppression hashes remain and re-registration is blocked, and which other copies/guest-only records are outside scope. Never say “all data everywhere was deleted.”
