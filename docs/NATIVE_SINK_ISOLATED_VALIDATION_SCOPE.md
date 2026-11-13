# New journal sink validation: preserve B evidence

Local-only review. No new Google/Render reads, credentials, writes, builds or deployments.

## What the existing B evidence establishes

Sources: `maintenance-B-live-20261112/RESULTS.zh-TW.md` and the saved
`fixture-new-20261112/final-readback.json`, not a fresh remote observation.
The report records the previous five-phase isolated test as CLOSED, next receipt 7,
expired record removed, current record and identity/profile retained, lifetime +7/-3,
and restoration of the original game. Saved native metadata contains root 1,
receipts 2–6 and test arm 10001 (`test.arm.A`). There is no journal 20001
(`bg.commit.v1`). The eight sheets have 1000 rows and 26 columns each.

B may be read as prior evidence. It cannot validate the new writer without changes:
bootstrap itself changes root/receipt and adds the journal; its current COMPLETE
retention cutoff does not exercise DUE deletion. Rewriting old fixtures or advancing
artificial dates in B would alter the earlier evidence. Do not reuse its arm/nonce,
old approvals, receipts or UNKNOWN operations as a new grant.

## Minimal new isolated target C

Seek explicit approval for one new synthetic spreadsheet with fresh dataset ID,
sharing only with the existing authorized BridgeGo service account. No real player
identity, Google subject, email or production history is needed. Preserve A, B and
the formal target unchanged. Do not infer approval from this document.

Scope of setup and test writes:

* Eight named sheets: `dataset`, `accounts`, `auth_identities`, `envelopes`,
  `commits`, `erasure_ledger`, `retention_ledger`, `quarterly_aggregates`.
* Native grid/layout: 1000 × 26 for each sheet, normalized full row/column metadata;
  synthetic business rows confined to A:E. Exact row counts and before/after ranges
  must come from the final reviewed fixture manifest, not an unrestricted whole-book
  rewrite. No unrelated worksheets, notes, comments or external links.
* Metadata: initial CLOSED bootstrap root id 1, bounded sequential anti-replay receipts,
  journal id 20001. Root references the exact new sheet/anchor identifiers. Rotation,
  if tested, additionally creates its reviewed new anchor and removes only the old
  synthetic anchor. Do not copy B's arm or old receipt history.
* Known synthetic previous-quarter and current-quarter records, two fake accounts,
  explicit lifetime/version markers, and native canonical snapshot hash. Seed old
  records rather than changing the production retention period. Initial journal
  bootstrap is its own explicitly approved setup operation, not an inferred effect
  of finding an empty table.
* A fixed new target/dataset binding throughout all requests and a separately approved
  quarter operation with exact CLOSED baseline, owner, operation ID and short expiry.
  Shared service-account credentials are not permission to touch any other workbook.

## Minimum useful acceptance sequence

1. Full native read validates target, all eight sheets/layout/comments coverage, exact
   synthetic baseline, journal and independent source-ownership evidence. Confirm
   old game/operator sources cannot target C; do not infer this from a single flag.
2. Use the real bounded ordinary sink for identity/profile and one FORMAL and one CASUAL
   completed game. Confirm category, scores, lifetime and registered completion rules.
3. Cross-quarter real-game draining is verified locally with the already tested injected
   game clock; it is NOT claimed as a live calendar-boundary test on November 12. For C,
   stop new games, finish an already registered game, drain its queued save, and positively
   read back the same ordinary owner clean close. Preseed supported prior-quarter records
   and an old COMPLETE retention cutoff for the isolated cleanup case, rather than waiting
   until January 1 or changing the live retention period. These may be separate C fixtures/
   explicitly approved setup phases; do not silently reset a journaled completed run.
4. Retire ordinary handle. Use the separate maintenance grant and bounded Google sink
   for exact five-phase cleanup. Verify removed/retained rows, profile/identity, lifetime,
   journal/receipts and completion timestamp after fresh APPLIED readback.
5. Close maintenance handle before granting a new ordinary owner; verify its CLAIM,
   reopen admission and save/read a further game. Merely seeing the maintenance page
   disappear is not proof that the new store is usable.
6. Controlled restart: retired predecessor plus exact clean journal/CLOSED baseline
   and independent takeover evidence may open a new owner. An arbitrary surviving OPEN
   root, last-known hash or COMPLETE marker cannot authorize takeover. UNKNOWN before or
   after an applied POST stays blocked. Exercise these failures locally first; any remote
   fault-injection needs a separately bounded target/operation and no blind retry.

Success in C verifies the new isolated transport/lifecycle, not the formal workbook's
schema, source freeze or historic lifetime compatibility. Current formal eligibility
has one unresolved item per parent report; this review did not inspect private data.

## Clock and authorization boundary

The default proposed C test uses real UTC for Google OAuth/token issuance, HTTP deadlines
and live business dates. It does not change the Render game clock, production quarterly
schedule, token clock or computer clock. Synthetic prior-quarter records are sufficient
for the five-phase destructive path; local injected-clock tests cover boundary admission
and in-flight game draining. If a full live synthetic boundary is later desired, it needs
an explicitly isolated test-only game/protocol clock with a fixed target allowlist and
separate approval. OAuth and transport clocks must remain real. No such test-only live
clock adapter is supplied or enabled by this review.

## Zero-additional-spend execution envelope (proposal, not approval)

Use the existing Free single Render service only, no extra service/database/worker/cron,
no plan upgrade and no paid build tier change. Keep auto-deploy/previews off. Prepare and
review locally, then at most one bounded candidate deployment and rollback to the verified
existing game artifact. The old artifact rollback should not require rebuilding the game;
verify availability before interrupting it. Restore source/config, health `/health/ready`,
maintenance=false and confirm homepage/live/ready on the same restored instance.

Historical local evidence is not current billing:

* User previously reported 50/500 included pipeline minutes (450 remaining).
* Later B run `state.txt` records 60/500 (440 remaining), monthly cap $0 and Free
  runtime 28.4/750. Do not silently treat either figure as today's remaining quota.
* B arm descriptor 08:33:30.026Z to result 08:34:15.426Z is about 45.4 seconds.
  This is an observed arm-to-result interval, not measured build time.
* Rollback created 08:35:41.964Z to live 08:36:46.262Z was about 64.3 seconds.
  Health/config restoration was verified by 08:37:38.538Z. These are examples, not an SLA.
* The saved files do not establish the candidate's exact build minutes. A fresh read-only
  billing/deployment check is required before starting. Reserve enough included quota for
  both the candidate and recovery; do not schedule repeated rebuild loops or enable paid
  overage. If the free allowance or zero-spend guarantee is uncertain, do not start.

Suggested operational cap: one candidate build attempt; any failed/unknown test stops
without resubmission and uses read-only diagnosis. If recovery is needed, prioritize the
preverified artifact rollback. An extra candidate build is a separately reviewed change,
not an automatic retry. A nominal one-hour maintenance window is a user-facing planning
allowance, not permission to consume paid resources or leave the game down indefinitely.

### Missing concrete approvals before C work

1. Create one fresh C synthetic spreadsheet/dataset and share only with the existing
   BridgeGo service account; preserve old A/B/formal sources. Describe the eight-sheet,
   metadata/layout and fake-account scope above. This assignment has NOT authorized it.
2. Temporarily interrupt the existing game for the bounded candidate, restoring the
   known game artifact and health/config afterwards. Confirm exact release/target before
   deployment. If a public commit contains C identifiers, disclose that it contains target
   IDs, not credentials or player records.
3. A concrete non-restorable deletion operation still needs action-time confirmation of
   the exact C target, baseline and bounded fake-data scope. An expired grant or changed
   baseline requires a fresh preview/approval; no consent is inferred from a billing cap,
   environment setting, old B confirmation or this document.
4. Any new persistent credential, broader permission, paid option, extra target/service,
   real-data migration or runtime clock modification is outside this proposal. The plan
   deliberately needs none of these; ask separately if an actual blocker makes one necessary.

## Approved C follow-up and concrete runner ordering

Update from coordinating parent: the user has now approved a fresh purely synthetic C
spreadsheet, sharing with the existing service account, and temporary game interruption
with restoration. A later read-only Render check reports 60/500 included minutes, $0 cap,
and the unchanged original c92 game. Earlier "missing approval" notes above are historical;
they are not a request to ask again for the same approved scope.

For recoverable synthetic fixture mutations, preserve and verify an equivalent before
artifact before writing: actual complete native response (with target and layout coverage),
canonical parsed snapshot/hash, binding manifest and exact planned changes. A projected
Book JSON alone is not an actual native response or proof of comments coverage. Preserve
these artifacts and compare any restoration against the full before state. Within this
already approved, recoverable C scope, do not invent a new user approval at every phase.
Real-player data, irrecoverable deletion or expansion to another target is not covered.

Candidate reviewed: 367422077958b64fd2f656aada98395d57997f91. It contains the new factories,
grants, lifecycle and staged startup seams but no executable C isolation main. The smallest
runner addition is a same-package `com.bridge.quartercore.SyntheticJournalMain` with a
single worker and fixed C release binding, no Spring gameplay startup or legacy fallback.
The old B `MaintenanceLauncher` provides a useful bounded health/shutdown pattern, but its
BootArm/WritePermit transaction path cannot authorize the new journal sink.

Do not append an old B-style Sheet arm to an already journaled C baseline: the journal
covers every metadata entry except itself, so such a change invalidates the proof. A small
fixed operator POST carrier is preferable: freshly generated private nonce, exact preview
hash binding C/dataset/revision/operation/quarter/full baseline/deadline, one atomic consume
before any dispatch. Keep the nonce out of URLs, Sheets, user messages and public logs;
private authenticated operator access only. Do not accept target overrides or arbitrary
wire payloads. Unknown execution never resets the nonce or retries. This carrier conveys
the existing authorized C operation; it does not claim formal process ownership evidence.

A true-UTC C fixture with an old COMPLETE cutoff is DUE, so ordinary Service.start cannot
precede maintenance: it would attempt retention work that the ordinary authority correctly
refuses. The executable order is therefore:

1. Approved C setup with journal bootstrap, supported prior/current synthetic records and
   preserved verified before artifact; fixed-target full native read.
2. New `JournalQuarterGoogleFactory` plus `JournalQuarterAuthority` executes the five cleanup
   phases once; verify expired removal, current retention, identity/profile and lifetime.
3. Retire maintenance handle, explicitly provision the separate ordinary synthetic owner,
   use `JournalGoogleFactory` and `JournaledNativeTransport` for CLAIM, identity/profile,
   FORMAL/CASUAL synthetic finished-game saves and clean close, verifying each exact result.
4. Restore the original game artifact/config and independently confirm live/ready/version.

This proves real Google journal maintenance and ordinary sink operation in C. It does NOT
prove live calendar-boundary game draining, formal-source compatibility, automated Render
process evidence, or lossless restart of accepted-but-undurable work. Those distinctions
must remain in the final report even when every C check succeeds.
