> Historical bidding-only checkpoint. Scoring was subsequently updated in this local candidate. The old JAR hash and byte-identical scoring claims below describe the earlier checkpoint, not the current candidate; see NEW_SCORING_VERIFICATION.md for current evidence.

# Local candidate: four-level bidding with an 8-HCP two-level threshold

Not deployed. Based on the deployed v0.10.0 frozen source ZIP, SHA256 `1e103cf43ea78595b0524069a1f64b887b926378eeb9d713a42614927053483a`. Supersedes the earlier local four-level candidate and paused experiment. User confirmed the 8-point threshold on 2026-11-04.

## Behavior

- Chosen longest suit has at least 6 cards and whole own hand has at least 2 aces: maximum level 4.
- Otherwise chosen suit has at least 5 cards and whole own hand has at least 2 aces: maximum level 3.
- Otherwise own hand has at least 8 HCP: maximum level 2.
- Otherwise: maximum level 1. HCP is A=4, K=3, Q=2, J=1, other ranks=0.

Continue bidding the lowest legal level in that fixed chosen suit, passing when partner leads or no legal bid remains within the ceiling. No jump to the maximum, no no-trump bids, no suit-selection changes, no scoring changes, no other gameplay changes. Applies to regular bots and disconnected-human takeovers through their shared RoomService path. No other player's hand is passed to the strategy. Existing GameState.highCardPoints is reused without changing redeal eligibility.

Only production files changed from v0.10.0: ComputerStrategy accepts the player's hand and computes the ceiling; RoomService passes that seat's hand. Scoring sources are byte-identical to the baseline. Baseline release identifiers remain v0.10.0 until publication is approved.

## Current verification

- Clean Java 17 / Maven offline web package with `-Dproject.build.outputTimestamp=1980-02-01T00:00:00Z`: 644 tests, zero failures/errors/skips, including 404 scoring tests.
- Strategy matrices: 99,264 call decisions across all 4 seats and 4 chosen suits, lengths 4/5/6/7, 0–4 aces, and explicit 7/8/9 HCP hands with either 0 or 1 ace. Each fixture is 13 distinct cards; all 140 highest-bid combinations plus opening are checked. Verifies same-level legal overcalls, partner-pass, minimum legal level, all four ceilings, and no escalation beyond 4.
- Extra test verifies a different long suit cannot qualify a short selected suit.
- RoomService integration: 576 deterministic scenarios across 4 seats, regular server bots and disconnected-human takeovers, 18 hand boundaries and highest levels 1/2/3/4 no-trump. Includes 7/8/9 HCP, long suits without 2 aces, and 3/4-level ace thresholds. Runs real service scheduling and validates BOT/BOT_TAKEOVER provenance.
- Frontend: 425 tests in 30 files, typecheck, ESLint, and production build passed. Existing installed dependencies were used; this is not a fresh dependency-download certification.
- API structural drift gate: 20 HTTP operations and 43 application errors; not full OpenAPI schema validation.
- Packaged frontend: all 18 dist files byte-identical inside the final JAR.
- Final JAR SHA256: `ccdc874c946e944f30bf985e35ed5cf9ac85845a38dee03a9406bb1a0ccaf744`.

Final-JAR checks also passed: PWA 21, beta HTTP/WebSocket 30, takeover/redeal/privacy HTTP/WebSocket 34. No deployment, production mutation, physical-device, Docker or new browser-pixel verification is claimed. All checks run locally. Historical logs outside this source tree are not current verification evidence.
