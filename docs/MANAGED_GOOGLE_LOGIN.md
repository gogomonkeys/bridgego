# Managed Google login and match saving

Normal game candidate, not a trial host. Google login is optional. Guests continue to play. Managed mode admits verified, consenting Google identities without a tester allowlist; nonce, origin, same-session and outside-room checks remain unchanged.

## Minimal configuration merge

Preserve existing GOOGLE_CLIENT_ID, PUBLIC_ORIGIN, BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON and all existing experiment/test target values. Do not overwrite credentials or silently reuse an old test spreadsheet. The deploying operator supplies the separately verified real target and dataset ID; neither is embedded in code.

- BRIDGE_RUNTIME_MODE=game
- BRIDGE_COLLECTION_MODE=managed
- BRIDGE_SHEETS_ENABLED=true
- BRIDGE_SHEETS_SPREADSHEET_ID=<explicit verified managed sheet>
- BRIDGE_DATASET_ID=<explicit canonical UUID>
- BRIDGE_PRIVACY_CONTACT=jody.yycc@gmail.com
- BRIDGE_PRIVACY_RETENTION=<account retention, manual Taiwan-quarter personal-match cleanup, retained non-identifying aggregate policy, history/backup limits>
- BRIDGE_ERASURE_WORKFLOW_VERIFIED=false
- BRIDGE_QUARTER_POLICY_VERIFIED=false
- BRIDGE_EXPERIMENT_RECOVERY_ENABLED=false (if inherited from the prior experiment)

Managed is an explicitly selected collection policy, not a claim that erasure/quarter workflows passed acceptance. Runtime enforcement of quarterly rules is independent of those verification flags. Missing privacy metadata or disabled storage keeps linking unavailable; guest play remains available.

The selected sheet must have exactly the configured dataset marker rows `[purpose,dataset_id]` and `[bridgego-managed,<configured ID>]`. Gameplay and manual account-erasure maintenance share DatasetBinding and check that marker for every operation. The marker cannot be rewritten through these gateways. Existing restricted gateways continue to require their separate restricted marker; old two-argument quarter bindings default to restricted.

No provisioning or ledger writes occur on startup. The existing identity and stats writers require the real current-quarter retention ledger to be COMPLETE before writing, and respect erasure tombstones. An existing PENDING dataset cannot be made ready by a mode change or verified flag. A verified new empty dataset's initial watermark is initialization, not evidence a deletion test passed.

## Scope and limits

Only a completed eligible match whose human participants all linked and consented is saved. One guest excludes the entire match. No old guest matches are backfilled. Login success is not save confirmation. Existing final-match status checks report pending/confirmed/failure separately. No cross-device match-history product is added.

Accounts remain until account-deletion handling. Personal raw matches are manually cleaned by Taiwan finishedAt quarter, not by an automatic scheduler or daily rule. Non-identifying overall aggregates may be retained according to policy; current publication remains suppressed. Backups, spreadsheet revisions and exports are not erased by table rewrites. Account deletion requires verified ownership and manual handling through the published contact; no acceptance certification is claimed.

Use one gameplay service process: Sheets supplies no distributed uniqueness/CAS and the queue is in memory. Restart, network failure or write failure can lose queued records. All game/identity writers must stop before manual maintenance. The existing manual quarter CLI accepts an explicit optional dataset_purpose=bridgego-managed in its operator manifest; omitted purpose stays restricted. This does not enable automatic cleanup or bypass confirmation.

## Validation strategy

Local checks cover the changed policy, consent/session security, mixed-guest exclusion, destination binding, current-quarter enforcement and normal web build. The user performs real Google login and gameplay online; account and match values are then checked read-only. No live credentials, Google calls, sharing changes or deployment were performed by this implementation.
