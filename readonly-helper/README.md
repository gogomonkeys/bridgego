# Bounded read-only startup observation

This standalone helper accompanies the unchanged BridgeGo v0.13.2 game. It is disabled by default. It does not migrate, erase, clean up, append, repair, or fence any spreadsheet writer.

The helper uses only the existing managed service-account credential, compares its email to the independently confirmed sharing identity, obtains a `spreadsheets.readonly` token, and requests one fixed complete spreadsheet resource. It emits structural counts and hashes only, never spreadsheet values, credentials, tokens, or exception messages. Unknown format, missing configuration, timeout, or memory limits reject the observation. A successful structural observation is not business-semantic verification or authorization to mutate data.

Build independently: `mvn -f readonly-helper/pom.xml clean package`. Normal game sources, POM, frontend, and APIs remain unchanged. The Dockerfile builds each artifact independently and uses `ops/start-managed.sh` as the entrypoint.

Only these two non-secret activation settings are added:

- `BRIDGE_READONLY_STARTUP_ENABLED=true`
- `BRIDGE_READONLY_STARTUP_EXPIRES_AT=<absolute UTC instant>`

The expiry must be in the future and at most 15 minutes from helper startup. Existing managed collection mode, exact spreadsheet/dataset IDs, Sheets enabled setting, and existing credential must already match. Do not create a key, expand permissions, or copy credentials into source/config files.

Each startup attempts at most one token POST and one complete GET, with no retry. New cold starts can repeat that read before expiry. There is no durable claim or other write. A watchdog limits the helper process to 35 seconds plus a 2-second termination grace; it exits before the game starts. The helper has a 128 MiB heap cap, which may reject a large response. Failure still starts the original game. TERM/INT cancels startup instead of starting a game after shutdown.

After the supervised observation, set `BRIDGE_READONLY_STARTUP_ENABLED=false`. Apply that setting through the platform's supported deploy flow and verify `/health/ready`; a service restart alone can reuse old environment values. No plan upgrade or extra resource is required. Deployment can interrupt in-memory matches even though game code is unchanged.

Tests are synthetic and local. The small native-format fixture contains only display metadata and the public Google issuer URL. Two candidate-suite checks of unrelated newer writer classes are not part of this standalone module; the helper never packages those writers. The existing production writer remains unchanged and is not fenced by this read-only feature.

Diagnostic revision: startup output additionally contains a finite `diagnostic.stage` and `diagnostic.error` enum. The first failure snapshot is frozen per invocation. No response body, dynamic field, credential, identifier, URL, header, numeric HTTP code, or exception message/class name is emitted. `REJECTED` is a coarse category and does not establish the precise underlying cause. Routes, readonly scope, limits, default-off activation, and cleanup behavior are unchanged.
