# Native clean-close restart composition (not provisioned)

The shipped `NativeRestartCompiledRelease.current()` returns null. No production restart contract, credential, deployed service, or automatic permission is configured. Setting `BRIDGE_NATIVE_BOOT_MODE=restart` selects a path only; it rejects before credential loading unless a reviewed build explicitly provisions a fixed contract.

A future approved contract pins target and service-account issuer, the versioned native-only source protocol identity, explicit restart and retention approval references, retention rules, origin and audience. It must cover excluded legacy/manual/import writers and durable CLOSE reconciliation plus new receipt-CAS ownership. Durable metadata does not grant this policy. No old-process RAM acknowledgement or repeated platform death attestation is inferred or required under that source policy.

`PROTOCOL_IDENTITY` is a versioned compatibility identity, not a self-referential deployment Git SHA. The initial operator workflow continues validating actual deployment revision through its signed observation without relaxation. If a compiled contract is present, the initial runtime requires exactly matching target, policy, approval reference, origin and audience before activation. The same compiled policy is reconstructed on restart, not copied from prior JVM B1/B2 objects or an HTTP hash.

The restart launcher creates exactly one gate and staged backend, registers that same backend with Spring, and then attempts one resume. Fresh complete native reads must classify durable CLOSE-owner lineage. Current-quarter state receives a new CLAIM. Due state runs the five verified quarterly phases before new CLAIM. OPEN/PENDING/APPLIED/unverified/unknown remains maintenance-only, never falls back to initial bootstrap or legacy storage. The existing quarterly coordinator independently opens business admission after inspecting the attached lifecycle.

ContextClosedEvent does not close the backend early. Native SmartLifecycle orderly shutdown runs first; runtime resources and signed-observation relay are disposed after lifecycle stop. Backend disposal is once-only asynchronous resource cleanup, not another CLOSE attempt.

Local test seams accept explicit in-memory contracts and synthetic IO. They are package-private and are not environment parsers. Two-JVM packaged tests are separate test artifacts, not production provisioning or evidence about the current online service.

## Fixed E clock rehearsal seam

`NativeRestartRuntimeAssembly.fixedE` is a future dedicated-composition helper only, not a launcher or permission to write. It pins the complete restricted E target and internally selects real UTC for capability deadlines/rollback checks and `2026-12-31T16:00:01Z` for business-quarter classification. The package-private injectable-clock variant is for local tests. Normal constructors remain single-clock; the compiled production release is still null.

The dedicated fixed-E composition below serves maintenance externally and denies game API/WebSocket entry while allowing internal verification through the opened test gate. It remains unprovisioned for online writes.

### Fixed E baseline bundle (local-only, unprovisioned)
`FixedERestartLauncher` defaults off. Its explicit readonly main serves maintenance-only HTTP, performs one bounded complete native read, and requires the previously verified exact E token plus CLEAN_CURRENT_RESUMABLE. It never constructs a writable runtime. Existing general production environment variables are ignored, not removed; dedicated target/clock overrides are rejected before credential access. Only exact GET /health/live and /health/route respond as maintenance health; the separately described fixed verified synthetic artifact is downloadable only after successful preflight. Other endpoints remain 503.

The package-private runtime composition requires an immutable reviewed full E baseline bundle. The original read-only stage did not provision that bundle; the separately reviewed bounded-restart candidate below now pins the actual captured snapshot. Synthetic example fixtures are never substituted for it.

A supplied reviewed bundle permits at most three new CLAIM/CLOSE pairs. The starting snapshot token is exact. Later CLOSED snapshots require a freshly verified current journal, exact sequence advancement, unchanged original metadata prefix, all eight tables/grid layouts unchanged, exact root advancement, and only precisely validated new CLAIM/CLOSE receipt slots. Only new control JSON key order is normalized. Three completed pairs exhaust the allowance and prohibit a fourth claim. DUE, OPEN, pending, unknown, extra metadata and changed data are rejected before claim.

This is bounded receipt-lineage verification under the separately approved native-only source policy and exclusion of manual/legacy writers. The overwritten historical journal proof chain cannot be reconstructed; this mechanism does not claim to do so or resist a coordinated out-of-policy forgery. The ordinary journal hash definition and validator are unchanged. The fixed-E Spring composition installs the dedicated maintenance filter even if the internal test gate becomes OPEN, so public game/auth/API/WebSocket routes remain inaccessible.

The standard jar now has an explicit `BRIDGE_RUNTIME_MODE=fixed-e-readonly` branch; it delegates only to the readonly launcher and sanitizes startup failures. It also requires `BRIDGE_FIXED_E_READONLY_PREFLIGHT=true`; otherwise it stops before credentials. Default `game` is unchanged. A future separately authorized rehearsal deployment must set `BRIDGE_READONLY_STARTUP_ENABLED=false` to disable other startup helpers, and must not treat this readonly mode as write-release provisioning.

After the exact pinned E snapshot passes complete validation, GET `/fixed-e/verified-baseline.json` without any query can download an at-most-2-MiB, no-store attachment. It contains the fixed binding, full token and complete validated normalized native projection, explicitly not the original raw Google response. Before success, on failure, for other methods/queries, or if the size bound is exceeded, no artifact is published. There is no upload route or HTTP authority construction.

### Reviewed E bounded-restart candidate (not deployed)
The real normalized E artifact was retrieved and independently checked on 2026-11-13. `synthetic-e/restart-verified-baseline.json` is now pinned by raw SHA-256 `6643d9ad0f59c9884d0fa3f28bce7afc1656eddce492c86abc7fa0041598275d` and full token `391e7a1e413e997f0fa6d186f217dec3bd9ac8239ba90fc0d03020d9a60c6bca`. This supersedes the earlier unconfigured E bundle notes, not the still-unprovisioned formal `NativeRestartCompiledRelease`.

A separately authorized E rehearsal uses `BRIDGE_RUNTIME_MODE=fixed-e-restart`, `BRIDGE_FIXED_E_BOUNDED_RESTART_ENABLED=true`, and `BRIDGE_READONLY_STARTUP_ENABLED=false`. Existing service-account JSON is consumed lazily for the fixed E target only. Preserve formal target variables: they are not selected. No target, clock or baseline override is permitted. The internal business clock is fixed to 2026-12-31T16:00:01Z; security uses system UTC. Public login/game/WebSocket/statistics stay behind the fixed maintenance filter; temporary health routing may use `/health/live` or `/health/route`, not normal game readiness. Normal game mode and formal release remain unchanged.

Only clean CLOSED baseline lineage may start another CLAIM, with at most three pairs total. No quarterly cleanup is permitted because the bundle requires current-quarter state. Failed/unknown operations are not retried. A process that observes an exactly permitted OPEN during old/new overlap may perform only the bounded read-only wait described below; it never claims until verified CLOSED. Abrupt termination can leave OPEN and require review. Graceful CLOSE depends on the bounded shutdown lifecycle; timeout remains UNKNOWN, not success.

For independent final evidence, switch to `BRIDGE_RUNTIME_MODE=fixed-e-completion-readonly`, retain `BRIDGE_READONLY_STARTUP_ENABLED=false`, set both `BRIDGE_FIXED_E_READONLY_PREFLIGHT=true` and `BRIDGE_FIXED_E_COMPLETION_READONLY_ENABLED=true`. This stops the previous process normally and starts a writer-free server. It publishes the same fixed artifact path only after complete fresh verification of 1–3 completed pairs, unchanged eight tables and exact bounded lineage. The original `fixed-e-readonly` mode still accepts only the original token. Completion mode never claims or retries; failure exposes no artifact. Restore original game mode/configuration only after independently checking final evidence.

Overlap handling update: Render restart starts the replacement before stopping the old instance. The fixed-E startup now performs only bounded complete readonly polling before its unique claim: at most 13 reads, with 15-second waits and a 180-second monotonic budget checked before and after every read. Only an exactly permitted, journal-verified fresh CLAIM/OPEN with unchanged baseline data can wait for CLOSE. PENDING/APPLIED, changed data, unrelated OPEN, failed/unknown read, rollback or timeout stop immediately. No write authority exists during waiting; once startup advances toward its single claim, it never enters the wait again. Completion-readonly uses the same bounded wait so the previous service can finish its clean shutdown. Individual network reads retain their existing bounded transport deadline; an in-flight read crossing the overall budget is rejected on return without writing. Live/route remain maintenance responses while waiting; game readiness remains false.

### Separate E idle/wake rehearsal (local candidate)
The second rehearsal preserves the original restart bundle unchanged. It pins `/synthetic-e/idle-wake-verified-baseline.json` at SHA-256 `8c38643a70cd87fba1de5ebbb684ba906a28286a2a440246fe5793833f8f23ad`, full token `2c8db9559c124f1d9d34ffe79c8d6413bb282382772554be55b9abbda33834a7`, journal sequence 18/CLOSED. This separate bundle permits only TWO new CLAIM/CLOSE pairs; a third claim is prohibited. Both clocks, target, public maintenance filter, strict lineage and bounded pre-write OPEN wait are unchanged.

Only `BRIDGE_RUNTIME_MODE=fixed-e-idle-wake` together with `BRIDGE_FIXED_E_IDLE_WAKE_ENABLED=true` and `BRIDGE_READONLY_STARTUP_ENABLED=false` selects this rehearsal. The normal restart flag cannot enable it. No target/baseline/clock override exists, and no formal release is enabled.

Its independent readonly completion mode is `fixed-e-idle-completion-readonly`, requiring `BRIDGE_FIXED_E_IDLE_COMPLETION_READONLY_ENABLED=true`, `BRIDGE_FIXED_E_READONLY_PREFLIGHT=true`, and `BRIDGE_READONLY_STARTUP_ENABLED=false`. It exports only a fully verified 1–2 pair descendant of the new baseline, at the existing fixed no-query artifact path. It performs no writes. The old restart and old completion modes continue to use the original three-pair bundle.

### Idle continuation without assuming the prior outcome
The independent `fixed-e-idle-continuation` mode pins the same reviewed seq18 snapshot. It requires `BRIDGE_FIXED_E_IDLE_CONTINUATION_ENABLED=true` and the other startup reader disabled. It does NOT assume that seq20 exists: before any new claim the complete fresh native snapshot must prove exactly one or two permitted pairs relative to seq18, with the original metadata prefix and all eight tables unchanged. Zero pairs, three pairs, unknown reads and invalid lineage cannot claim. A valid in-flight CLAIM may only be watched read-only under the existing bounded wait; no old write is resent.

`fixed-e-idle-continuation-readonly` requires `BRIDGE_FIXED_E_IDLE_CONTINUATION_READONLY_ENABLED=true`, `BRIDGE_FIXED_E_READONLY_PREFLIGHT=true`, and `BRIDGE_READONLY_STARTUP_ENABLED=false`. Only exactly THREE verified pairs release the success artifact. Stopping after one or two pairs is an incomplete/aborted rehearsal, not success, and no success artifact is published. Neither the old two-pair idle bundle nor the original restart bundle is replaced. Formal release stays disabled. The 180-second wait is an acceptance window checked before/after each bounded network read, not a guarantee that an in-flight read is forcibly interrupted at exactly 180 seconds.

Continuation operations must inspect the single first-read log `E_CONTINUATION_PRECHECK completedPairs=1` before beginning the quiet interval. After idle/wake, `completedPairs=2` is expected. If the initial deployment already reports 2, only one pair remains: abort/restore rather than starting a fresh full sleep/wake rehearsal. The log is emitted only after the same freshly read snapshot passes complete bundle and claim-bound checks; it contains no identifiers, tokens or player data, performs no extra read, and is not itself proof of CLAIM/CLOSE completion.

### Reconstructed native overlap wait (local, not deployed)
The normal runtime's first read now performs bounded readonly observation before any claim authority. Only complete verified native OPEN evidence, precise latest receipt and a fixed native owner/epoch/anchor are observed; legitimate business writes may advance the journal sequence and receipt slot together. A fully verified durable CLOSE is then handed to the existing one-shot CAS startup. The overwritten historical journal chain is not reconstructed. PENDING/APPLIED, unknown reads, owner changes, tamper, rollback or timeout stop without write retry. E's stricter bundle wait is unchanged.

The acceptance window is 180 seconds, at most 13 reads separated by 15 seconds; individual reads retain their bounded network timeout and are rejected if they return after the window. Pre-existing or post-read thread interruption rejects even CLOSED while preserving the interrupt flag. An empty prior owner is accepted only for the exact initial epoch-1 bootstrap receipt, optionally followed by the explicit historical-admission receipt matching its active envelope; it is not allowed for arbitrary subsequent claims. Formal compiled release remains unprovisioned.

### Managed native retention: internal overall v2
The candidate policy rules explicitly name `expired-personal-results-fold-verified-lifetime-preserve-accounts-overall-v2`. Managed native QuarterPlan selects `AggregatePolicy.retainOverallV2()`. The exact FOLD batch writes lifetime account carry and the identity-free formal/casual six-count quarterly row while removing expired envelopes/commits; APPLIED/full readback is still required before COMPLETE. Duplicate quarter rows, malformed summaries and unexpected readback fail closed; uncertain writes are not replayed.

The five-column existing sheet layout/header is preserved. V2 rows use `[quarter, INTERNAL_COUNTS, canonical JSON, "", 2]`; this is internal aggregate storage, not independent permission to publish. The shared production codec is called by a native adapter rather than copied. Existing v1 SUPPRESSED/CANDIDATE rows decode as unavailable historical counts, never zero. Existing isolated E plans and legacy CLI/old AggregatePolicy constructors continue to suppress unless the new explicit v2 policy is selected. The formal compiled release remains disabled.

### Fixed E Q2 overall-v2 once-only rehearsal (local candidate)
`fixed-e-overall-v2` is a separate default-off maintenance mode requiring `BRIDGE_FIXED_E_OVERALL_V2_ENABLED=true` and `BRIDGE_READONLY_STARTUP_ENABLED=false`. It pins the complete seq24 CLOSED E artifact (token `2954294101aec13be408deb1f0fd12236f77d8b707f83ca8793d3329252d56bd`, raw SHA `9148b6cf65e5df73536cec0cdfe36d27938e77547281c7d8c2eb64394c6dc6ca`). No target, baseline, purpose or clock parameter is accepted. Real UTC controls operator approval/deadlines; business time alone is fixed to 2027-03-31T16:00:01Z.

The existing private preview/nonce approval host wraps a single five-phase native quarter protocol. The narrow authority factory checks fixed E, exact initial full token and Q2 before selecting the explicit v2 planning path. Managed production rules and previous restricted E plans are unchanged. There is no ordinary reopen, no fixture insertion, no reset, and no automatic retry/resume. Exact baseline mismatch on a later process rejects before writes.

Expected result: seq29/root.next31, five new receipts 26–30, two Q1 raw matches removed, one formal and one casual six-count summary each `(1,1,0,1,0,0)`, exact lifetime carry from the independently derived expectation, previous Q3/Q4 SUPPRESSED preserved, Q2 COMPLETE, and original identity/control prefix retained. Final complete native reread must match the actual authority's verified token and all independent expectations; historical reader must report Q1 v2 and Q3/Q4 unavailable. Only VERIFIED exposes `/fixed-e/verified-baseline.json` as a bounded no-store normalized projection attachment, never a raw Google response or a write grant. UNKNOWN/cancel/expiry/rollback/interruption suppress success and artifacts. Public game/auth/WebSocket services are never started.

Unknown-result operating restriction: REVIEW leaves maintenance HTTP running and never schedules another execution. A platform restart can only arm a new readonly preview, not execute automatically. Even if the baseline still matches, a late prior request may exist; matching the baseline is NOT evidence that nothing was sent. Do not approve another execute after UNKNOWN until independent review resolves the old attempt. A fresh private nonce/preview digest is required for every process, and receipt-slot CAS protects against conflicting late writes without proving their absence. No restart-resume deletion path is introduced.

### Formal candidate assembly remains unapproved

`NativeRestartCompiledRelease` now assembles the fixed target, origin, Google audience,
protocol identity and overall-v2 retention rules from the single pending candidate.
The private build-time approval reference remains **null**. This is compilable wiring,
not a release approval: `current()` remains null and `require()` fails closed.
No environment variable, HTTP input, Sheet value or fixture reference fills it.
A future approved source change must bind the real approval reference to this exact
scope, with excluded legacy/manual writers and native-only source assumptions rechecked.

Both managed initial startup and restart require that compiled contract. Initial
startup checks it before relay-key parsing or credential construction; therefore an
environment-supplied approval reference cannot activate the pending formal candidate.
Restricted synthetic initial composition is unchanged. This change does not deploy,
bootstrap, migrate, clean, or write any Sheet, and does not turn a local test's synthetic
approval reference into user authorization.

### Current formal approval excludes irreversible quarterly deletion

The fixed formal target is restricted to initial/current-quarter startup in Taiwan
2026 Q4 (inclusive 2026-09-30T16:00:00Z, exclusive 2026-12-31T16:00:00Z).
This permits normal current-quarter writers and clean restarts only; it is not a
standing deletion authorization. StartupFactory checks before first claim, and
restart checks before any due-quarter maintenance. The same-JVM timer cannot obtain
a quarterly deletion authority: its policy operation is explicitly denied for this
fixed formal target. It remains in maintenance and requires a new, exact per-action
approval before irreversible expired data deletion. No environment setting relaxes
this scope. Restricted E and unrelated synthetic managed fixtures retain their
existing semantics. The compiled reference is still absent in this local candidate.

### 2026-11-13 Q4 approval binding (supersedes pending-reference status above)

The coordinating root relayed the owner's explicit approval for the fixed formal
cutover and configuration. The compiled reference is now
`formal-cutover-20261113-user-approved-q4`. It binds the existing exact target,
origin, audience, protocol and rules; environment values must match rather than
supply new authority. This is local source preparation, not evidence of deployment
or successful cutover. `FormalCurrentQuarterScope` remains unchanged: Q4 startup
and current-quarter clean restart only, with each irreversible quarterly deletion
still denied until separate action-time approval. No E target or clock is enabled
by this formal contract.
