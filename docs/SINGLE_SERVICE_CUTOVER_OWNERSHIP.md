# Single-service cutover / ownership assessment

Scope: local source inspection plus parent-reported read-only Render inventory. No credentials, live Sheet reads, writes or deployments were performed for this assessment.

## Established facts, not hypothetical extra services

Parent's latest read-only Render inventory reports one BridgeGo web service in the workspace, free tier, one instance, auto-deploy off, previews off, maintenance false, health `/health/ready`. Live remains c92c69e (rollback deployment at 08:36:46Z, service updated at 08:37:38Z); the old isolated-test deployment is deactivated. This excludes another listed service/preview in that workspace at that observation, not processes outside that inventory or a request already accepted by Google.

Keep this single service. A new database, distributed lease and extra permanent worker are not prerequisites for the scoped, fail-closed candidate. Do not require repeating the original legacy cutover every quarter during a continuously verified native writer session.

## Actual local writer paths

| Path | Protection / remaining scope |
|---|---|
| PersistenceConfiguration native selection | One NativePersistenceSelection backend shared by IdentityStore, StatsStore and lifecycle factory. Native mode returns a rejecting legacy gateway and does not create legacy stores. Missing factory fails startup; no legacy fallback. |
| Legacy SheetsStatsStore | Background queue, up to four retry attempts when quarter gate disabled. `close()` interrupts active work and cancels queued work; it does NOT prove a remote request never applied. |
| Legacy SheetsIdentityStore | Up to three identity retries when gate disabled; profile one attempt. A new application's local gate cannot cancel an old process's request. |
| ErasureMaintenanceApplication | Creates GoogleSheetsGateway directly outside selected gameplay backend. Must not run concurrently against the selected source. |
| ExperimentBootstrapApplication / ExperimentRuntimeConfiguration | Separate experiment gateway. Native selection rejects experiment mode, but an independently launched older binary is outside that protection. |
| QuarterRetentionOperator (operator-kit Maven profile) | Separately runnable local operator. New web deployment does not disable a previously running local operator. |
| NativeStoresBackend + JournalSessionAuthority | Shared queue/session, exact full journal, bounded one-shot ordinary plans; guarded clean renewal. Unresolved operations stay blocked. |
| NativeQuarterLifecycle | Uses the same backend: drain, exact clean close, retire ordinary capability, independently authorized five-phase maintenance, fresh CLOSED verification, fresh-owner ordinary reopen. |

## One-time, actionable cutover evidence

1. **Fixed source and release:** selected spreadsheet/dataset/purpose, exact reviewed code revision, and canonical full baseline. No secret values need enter a report.
2. **Old service stopped/deactivated:** verify the specific predecessor deployment is no longer running, not merely that desired instance count is one. Disable automatic restart/redeployment of that old release during cutover. The reported auto-deploy/preview settings already reduce this scope; do not invent other deployed services.
3. **Old work accounted for:** stop admission, let known queued/in-flight operations resolve, record exact final full snapshot and queue outcome before the predecessor exits. A timed-out write or shutdownNow is not a positive zero-write proof. If an earlier operation is genuinely UNKNOWN, retain the block and investigate that operation rather than redoing every unrelated test.
4. **Other known entry points:** establish that the listed local operator/maintenance/experiment launchers are stopped and no scheduled run will target this source during cutover. Check existing authorized schedules/process evidence; do not assume they exist. A source-owner acknowledgement can identify manually controlled tools, but is not a claim that a metadata flag remotely locks Google.
5. **Baseline compatibility:** full strict legacy parsing, supported schema/layout and explicit lifetime compatibility report. The local parser/metadata-only plan exists; it does not prove the actual formal source is compatible until read. No automatic lifetime-marker fabrication.
6. **Initialize once and read back:** only the approved metadata-only exact baseline plan; all data preserved. Unknown response is read-only reconciled, never automatically resent. Then bind the chosen native writer to the verified journal baseline and fresh owner.

Same-target initialization is usable when the preceding evidence actually excludes old outstanding writers. It is not permanently blocked merely because Sheets lacks a database transaction table. Conversely, metadata cannot fence an old direct append. If a particular old request cannot be resolved, a separately approved new target with preserved old source is an optional isolation route, not the default architecture; tail reconciliation remains necessary.

## Minimal production provisioning, not another generic abstraction

Install one trusted component implementing NativePersistenceSelection.Factory and NativeQuarterLifecycleFactory. It must:

* verify the one-time source-cutover evidence and exact target/baseline;
* create one ordinary Handle + Service + NativeStoresBackend and register its release hook;
* return that same backend to stores and, when the lifecycle factory receives it, reject a different backend instance;
* supply ownership verification against the current process session/full committed journal, without treating OPEN/CLOSED alone as a grant;
* supply an independently authorized quarter operation bound to exact CLOSED baseline, quarter, owner and operation ID, through a maintenance-capable bounded sink; ordinary authority continues to reject retention mutations;
* close the old ordinary Handle before maintenance, close maintenance before reopening, and create a fresh ordinary owner generation after the exact complete readback;
* on ordinary expiry, revalidate the original scope and last known full state rather than ask for another human Google login or fabricate approval;
* retain UNKNOWN as terminal and refuse restart takeover without predecessor reconciliation.

Current code has the selected-backend lifecycle seam and pure/native protocol integration tests. It does **not** install the production cutover evidence source or a formal Google maintenance-capability provider. The Render inventory supplies part of the evidence, not an implementation of all verifier callbacks. Setting an environment flag is configuration, not evidence.

## Ongoing quarters versus restarts

A normal quarter in the same continuously known writer process should require no new legacy cutover ritual: stop new matches, allow registered existing matches to finish and save, drain queue, close writer, verify, clean, reclaim and reopen. Operator/user authorization scope for actual cleanup is separate from proving ownership; this note does not broaden it.

An unplanned restart is a different event. Current restart readers can classify and verify committed content but cannot use a surviving OPEN root to grant takeover when a late predecessor request is unresolved. Default remains blocked, not repeated blind retries. A controlled clean predecessor close can enable a verified startup path once a concrete provisioning implementation is installed. This is a specific restart-recovery gap, not a reason to add recurring manual steps to every healthy quarterly run.

## Implemented external observation verifier (local only)

`SingleServiceEvidenceProvider` now implements the checks rather than delegating all decisions to a boolean callback. A fixed binding pins workspace, service, reviewed revision, target, original cutover reference and original full baseline. Authenticated `Source` observations must be fresh (bounded at most five minutes), have exactly one active current deployment/instance, no deployment overlap, the expected settings, and a complete disposition for the known external writers. Failure/rollback revokes that provider instance. A Source may use a bounded cache; session callbacks must not perform a workspace scan for each HTTP request. Actual authenticated Render/process/handoff loading is **not installed**; Source.unavailable remains a valid fail-closed default, and local JSON/env strings are not attestation.

Ordinary session verifiers pin a specific owner and specific approved session baseline while continuing to check the original source-cutover reference. New baseline derivation is limited to an independently checked clean restart handoff or a real COMPLETE JournalQuarterAuthority's exact verified CLOSED token. It is not an allow-list of arbitrary self-computed digests. Same JVM sleep/wake uses continuity, not a new predecessor handoff.

For native OPEN takeover, a distinct NativePredecessor observation must establish native-only source exclusion, fixed old owner/process/revision, terminated predecessor and accepted operations settled or durably accounted for. A lone service count, OPEN marker or terminated process cannot imply an empty accepted queue. Known UNKNOWN accepted work remains blocked; the receipt-slot CAS separately protects the remote mutation race, not lost in-memory results. PENDING/APPLIED quarterly state is not eligible.

Current verifier intentionally requires the observed current deployment to be `live`. A new deployment whose readiness depends on the unopened gate may not reach that state. Production rollout wiring must use authenticated current-process/startup evidence and actual Render lifecycle states to avoid this circular dependency; this local implementation does not silently relax health or permit arbitrary building/overlapping deployments. This is a concrete provisioning limitation, not proof of a production-ready external loader.
