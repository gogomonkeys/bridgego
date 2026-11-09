# Local-only manual quarterly trial candidate

This source copy is not deployed. The normal Dockerfile remains the guest-game baseline. No Google request, real credential read or spreadsheet setup/write is part of local validation.

Build separate profile `manual-quarter-trial`; entry point `com.bridge.persistence.SyntheticCleanupManualQuarterWeb`. Default disabled. Arming requires `BRIDGE_MANUAL_QUARTER_TRIAL_ENABLED=true`, existing PUBLIC_ORIGIN/GOOGLE_CLIENT_ID/operator SHA pin and approved synthetic sheet pin. Credentials are supplied only server-side after explicit authenticated preview. This profile is SLF4J provider-free and the original NOP-only transport guard remains unchanged.

The new host calls only SyntheticCleanupManualQuarterTrial, LiveQuarterStorage and ManualQuarterMaintenance, not the old four-stage session/wire simulator or persistent marker protocol. A fresh Google proof, nonce, consent and manual writer-freeze are required for preview/refresh and confirmation. Confirmation is one checkbox tied to the current plan ID, not copied hashes.

Fixture is deterministic at 2026-11-09T00:00:00Z. `defaultFixture(configuredTarget)` validates the existing target hash pin. The exact complete eight-table values must match before the real engine preview is accepted. Counts must remain exactly 1 expired / 2 retained; later-quarter rollover is rejected. `SyntheticCleanupManualQuarterFixtureExport` is a local-only main reading only BRIDGE_SYNTHETIC_SHEET_ID and emitting JSON containing fixed tables, epoch and target hash. It is excluded from the web JAR. No fixture-upload or reseed HTTP endpoint exists. Future separately approved connector preparation must use these exact tables and must not delete old uncertain run state to make the fixture fit.

One confirmation authorizes the existing two engine writes: removal plus PENDING, then readback and COMPLETE. Both use seven mutable tabs, A:E throughout existing grid rows, fields=userEnteredValue; dataset is not rewritten and formats are not changed. Accounts/identities/tombstones are retained. History and backups are not erased.

An expired never-executed preview can be refreshed in the same session with fresh authentication. Old plan IDs become invalid. Any attempted execution with unknown outcome permanently closes that process session. No retry/reset/reseed or auto-advance exists. This is procedural single-process supervision, not cross-restart fencing: interruption requires manual inspection, never redeploy/restart to retry an unknown result.
