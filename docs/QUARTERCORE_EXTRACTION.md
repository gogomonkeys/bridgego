# Quarterly core extraction provenance

31 classes are isolated under `com.bridge.quartercore`, extracted from the recovered maintenance source used for the 2026-11-12 isolated online B validation (five writes, COMPLETE then CLOSED). Source hashes and extracted hashes are recorded in QUARTERCORE_PROVENANCE.json. CoreProvenanceTest normalizes the package name and asserts identical original hashes for the 28 rename-only classes. MaintenanceRelease is explicitly inert; StrictNativeSheetsResource and NarrowQuarterPoc have the explicitly marked extensions described below.

For the original extraction, only `com.bridge.persistence` package/import references were renamed except MaintenanceRelease: BUNDLED is now UNPROVISIONED, and requireLiveBinding always rejects. Existing exclusion/issuer restrictions remain. There is no bundled fake-sheet target and no live write release authority. This does not authorize use of a new live target.

Excluded entry points: MaintenanceMain, MaintenanceLauncher, MaintenanceArmOperator, ControlReadOnlyMain, ControlReadOnlyHost. Extracted classes are not Spring components/configurations and have no main entry point. Model/service construction does not discover credentials or start network traffic; Google transport factories retain explicit opt-in and provider operations as in the source. No bean wires this core automatically. Credential source/transport factory use requires a separate explicit adapter boundary.

## API seams (package-private)

- NativeSheetsModel / StrictNativeSheetsResource: literal native book model and strict full-response decoding.
- NarrowSheetsTransport / BatchedSheetsTransport: bounded read/control/related scopes and one-shot batch operations.
- NarrowQuarterPoc.Service: start, tick, reconcile, cleanClose, state and quarterSummary; owns serialization and pending operation protocol.
- QuarterPlan / LocalPlanningSnapshot: canonical planning, before/after verification and completion proof.
- BootArm / WritePermit: capability dependency preserved; no launcher/arm-issuing public entry point shipped.
- ManagedGoogleSheetsFactory / OAuth / ServiceAccount: original constrained credentials, bounded Google exchange and read-only default. Extracted live-release path always rejects.

Public adapter/factory facades under the same package may access these types without making the kernel public. Their changes are separate from the extraction manifest.

## Local tests

ExtractedCoreParityTest reuses the tested parser accept/reject cases, model five-phase preservation check, UNKNOWN no-resend checks (each phase), and adds an unprovisioned-release rejection assertion. RecoveredFixture is test-only, with test resources under /quartercore, not a runtime target. CoreProvenanceTest pins all extracted source hashes and checks no component/configuration/main entry points were added. These tests do not contact Google and do not constitute a new production acceptance test.

## Later explicit legacy preflight extension

StrictNativeSheetsResource now additionally exposes a package-private decodeLegacyFull using the unchanged bounded Streaming parser, rejecting all metadata and requiring exact target/dataset. Existing runtime decodeFull is unchanged. CoreProvenanceTest removes only this explicitly marked extension and proves the normalized original source hash still matches. Both manifests pin the complete new source hash. This pure parser does not perform Google reads or establish ownership. The rename-only count after the additional settlement extension is 28 of 31.

Follow-up explicit extension: NarrowQuarterPoc.Service.matchDrain is isolated between REGISTERED SETTLEMENT DRAIN EXTENSION markers. It grants only gate-registered old games a boundary-drain save path; ordinary match/openRequired remain byte-identical. CoreProvenanceTest strips only that block and checks the original source hash, while separately pinning the full extended file hash. See CROSS_QUARTER_SETTLEMENT_DRAIN.md.

## Registered settlement drain extension

NarrowQuarterPoc additionally exposes matchDrain for an attached registered-game settlement grant. It preserves the normal match/openRequired implementation and validates the previously verified ledger boundary, account/erasure rules, exact envelope and timestamps. It permits only already-started matches to append at the quarter boundary before cleanup; new-quarter results survive cleanup and queued old-quarter results enter the normal fold. CoreProvenanceTest strips only the marked extension to verify the original normalized source hash, while both manifests pin the complete extended source.
