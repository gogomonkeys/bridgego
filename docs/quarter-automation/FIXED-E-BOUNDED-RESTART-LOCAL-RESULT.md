# Fixed E bounded restart candidate: local result, 2026-11-13

No deployment, remote E read/write or production-data change was performed in this local candidate stage. The preceding read-only E deployment was separately authorized, verified, backed up and restored to the original game.

- Complete clean web package: 1,454 tests passed, zero failures/errors/skips.
- Original unchanged packaged main: explicit-mode guards, absent/false opt-ins, target overrides and competing startup-reader configuration rejected; poison fake credential retained maintenance-only behavior with no artifact/game/API/WebSocket access. Formal restart remains unprovisioned. Thirteen cases passed.
- Derived packaged harness: every production class/library/resource byte identical to packaged main; only Start-Class and explicitly named local test harness classes differ. Real saved E baseline parsed with the strict native model. All Sheets exchanges local fake transport, never Google.
- True two-JVM overlap: replacement starts while predecessor OPEN, serves maintenance and performs zero writes. Predecessor normal SIGTERM commits verified CLOSE; replacement then performs a fresh CLAIM. Both orderly closes preserve every sheet cell, format, shape and ID.
- Three separate normal JVM CLAIM/CLOSE pairs pass; fourth refused without mutation.
- Busy shutdown, applied-but-UNKNOWN response, PENDING state and formal-purpose target refuse additional writes/recovery. OPEN negative cases observe maintenance and zero writes before stopping the harness; virtual monotonic unit tests separately exercise the full 180-second bound.
- Independent review found no blocking code issue. Completion-readonly validates 1–3 pairs, so online final review must specifically require the intended two pairs and journal sequence 18, not merely a completion log.

The reviewed actual normalized artifact is included byte-for-byte, pinned by SHA-256 and full native token. It contains only previously authorized synthetic E data. Future publication must explicitly disclose this resource. Normal production release remains null/default off.

Render's official deployment documentation says Restart Service starts the replacement first and sends SIGTERM to the predecessor after the new service is healthy and traffic has switched. Therefore the pre-CLAIM/read-only-postcheck wait is necessary. It performs at most 13 complete reads, 15 seconds apart, with a 180-second monotonic acceptance budget checked before/after each read; existing individual network timeouts remain bounded. An in-flight read crossing the overall budget is rejected on return. No write retry or unknown-state reset occurs.

Proposed online sequence, requiring separate approval: fixed E launch -> one supported normal Render restart -> writer-free completion-readonly capture -> original game/health restoration. All public game routes remain maintenance. At most three ownership pairs, no eight-table data changes, no quarterly deletion, no formal writes. Use existing free service and allowance only. This will prove the normal platform restart path if it passes; it is not evidence of actual idle sleep/wake or recovery after SIGKILL.

Sources: https://render.com/docs/deploys#restarting-a-service and https://render.com/docs/deploys#zero-downtime-deploys (checked 2026-11-13).
