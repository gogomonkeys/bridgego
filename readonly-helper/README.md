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

Earlier diagnostic-only revision: startup output additionally contains a finite `diagnostic.stage` and `diagnostic.error` enum. The first failure snapshot is frozen per invocation. No response body, dynamic field, credential, identifier, URL, header, numeric HTTP code, or exception message/class name is emitted. `REJECTED` is a coarse category and does not establish the precise underlying cause. Routes, readonly scope, limits, default-off activation, and cleanup behavior are unchanged.

The earlier parser-diagnostic revision further distinguished fixed root, theme, sheet, grid, cell-value, cell-format, table, dataset-marker, and numeric-row checks. They never include actual field names from the response, coordinates, sheet IDs/titles, player values, or format values. Those diagnostic marks alone did not relax validation or alter parsing results; the separate v2 compatibility change is documented below.

The optional format audit enumerates one already-parsed response and emits only fixed enum-keyed aggregate counts. Its `completeScan` means bounded enumeration completed, not validation success. Exact-style counters do not establish valid schema placement or business meaning. Unknown structures remain rejected by the original validator; audit errors do not replace strict failures. No second Google request, per-cell hash, sample, URL, actual field name, position, identity, or format value is output.

When `completeScan` is false, every format total describes only the safely bounded subset that was enumerated. Fixed reason counters explain structural skips without identifying locations or values. These partial counts must not be treated as whole-workbook counts or acceptance evidence.

Compatibility profile `legacy-eight-native-v2-readonly` recognizes only the exact issuer-link format at `auth_identities` data-row column zero for the previously validated native Arial or Carlito theme. The value, hyperlink, entered/effective URIs, default style, underline and both LINK RGB representations must match; unknown fields, including empty ones, are rejected. The audit's exact-link template uses the same strict theme validation. Column metadata permits integer pixel sizes 1–10000; rows remain 1–1000. These are local display-only bounds, not API maximums, and the exact metadata stays in the layout fingerprint. Target hashes include this compatibility profile version. No transport, permission, activation, request-count or game behavior changes.
