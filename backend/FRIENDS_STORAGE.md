# Friends storage candidate

This change does not create or change any real spreadsheet. Default `BRIDGE_FRIENDS_ENABLED=false` keeps the feature disabled. Enable only after deployment review and an explicitly approved, dataset-bound initialization of a `social` sheet with exactly this A:E header:

`kind | account_id | peer_or_key | state_or_value | revision`

No startup provisioning, migration, automatic quarterly purge, credential or email export is introduced. Current account nickname is read from validated `accounts`; codes are random 64-bit (16 hex characters), not derived from email or Google subject. Only existing registered accounts with nicknames can get codes. Clients must never receive raw persistence `accountId` fields.

Rows:
- CODE: account, literal `code`, random code, `1`.
- EDGE: requesting account, receiving account, PENDING or FRIEND, request UUID. A friendship is one row visible mutually. Crossed requests still need an explicit acceptance. Reject/remove deletes current edge.
- OP: acting account, caller action UUID, SHA-256 of operation signature, `1`. Receipts prevent a repeated request/accept/remove from being applied again after restart. They are not activity analytics.

Storage is serialized within ONE app process. There is no distributed lock; all other game/operator/manual writers must remain stopped during maintenance and multi-instance deployment must not be enabled. One mutation uses one atomic social-only batch followed by validated readback. Unknown results latch further mutations in that process. Read-only lookup remains available to inspect outcome; there is no blind resend. On restart the exact action UUID receipt reconciles a repeat. A new action UUID is not an authorized recovery strategy for an unknown result.

State reads share a 30-second process cache, invalidated on mutation and quarter boundary. Presence/room invitations never write this table. HTTP 429 imposes at least 60 seconds cooldown, extended by Retry-After; it does not loop automatically. Friend and pending edge limits are currently 100 per account. Entire table is capped at 1,000 rows including the header to remain compatible with the existing bounded maintenance parser. Receipts count toward capacity and are not silently pruned. Capacity failures require planned maintenance/design review, not a deletion retry.

Quarterly maintenance permits this one optional, validated social table and carries it through unchanged; the seven-table rewrite never targets it. Unknown other sheets are still rejected. Account erasure detects the optional social table and removes the erased account's code, either-direction edges and own receipts in the SAME confirmed atomic erasure batch, then verifies readback. Rebuild/use the updated operator artifact; old artifacts reject the new layout or do not understand social data and must not be used after enablement. Google Sheets revision history and external backups remain outside active-schema erasure, as before.

Before enablement: verify the current deployment's single writer, explicit dataset binding, current-quarter COMPLETE ledger, validated account rows and no erased identities, approved new sheet metadata/header, rebuilt operator tests/artifact, and synthetic friend/request/erasure/quarter regression. Do not populate production with synthetic accounts. This document is not approval to initialize or deploy.
