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

The package-private runtime composition requires an immutable reviewed full E baseline bundle; `compiled()` is currently null because the true full baseline snapshot has not yet been obtained. Synthetic fixtures do not represent that snapshot. No online write path is provisioned.

A supplied reviewed bundle permits at most three new CLAIM/CLOSE pairs. The starting snapshot token is exact. Later CLOSED snapshots require a freshly verified current journal, exact sequence advancement, unchanged original metadata prefix, all eight tables/grid layouts unchanged, exact root advancement, and only precisely validated new CLAIM/CLOSE receipt slots. Only new control JSON key order is normalized. Three completed pairs exhaust the allowance and prohibit a fourth claim. DUE, OPEN, pending, unknown, extra metadata and changed data are rejected before claim.

This is bounded receipt-lineage verification under the separately approved native-only source policy and exclusion of manual/legacy writers. The overwritten historical journal proof chain cannot be reconstructed; this mechanism does not claim to do so or resist a coordinated out-of-policy forgery. The ordinary journal hash definition and validator are unchanged. The fixed-E Spring composition installs the dedicated maintenance filter even if the internal test gate becomes OPEN, so public game/auth/API/WebSocket routes remain inaccessible.

The standard jar now has an explicit `BRIDGE_RUNTIME_MODE=fixed-e-readonly` branch; it delegates only to the readonly launcher and sanitizes startup failures. It also requires `BRIDGE_FIXED_E_READONLY_PREFLIGHT=true`; otherwise it stops before credentials. Default `game` is unchanged. A future separately authorized rehearsal deployment must set `BRIDGE_READONLY_STARTUP_ENABLED=false` to disable other startup helpers, and must not treat this readonly mode as write-release provisioning.

After the exact pinned E snapshot passes complete validation, GET `/fixed-e/verified-baseline.json` without any query can download an at-most-2-MiB, no-store attachment. It contains the fixed binding, full token and complete validated normalized native projection, explicitly not the original raw Google response. Before success, on failure, for other methods/queries, or if the size bound is exceeded, no artifact is published. There is no upload route or HTTP authority construction.
