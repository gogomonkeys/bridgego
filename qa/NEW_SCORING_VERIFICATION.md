# New scoring candidate verification — 2026-11-04

Local implementation only. No deployment or version bump; baseline labels remain v0.10.0 pending publication approval. This checkpoint supersedes the scoring and artifact claims in the historical bidding-only verification files.

## Rules and scope

For contract level L (still legal 1..7), target is 6+L. Exact success earns L+2; excess success earns L. Failure deducts the actual trick shortfall, without a cap. Defence earns +1 only on contract failure. Each opted team's privilege independently adds +1 on a win or -1 on a loss.

The backend still stores one completed result after 13 tricks and accumulates it once. UI help, privilege tooltip, settlement components and examples now match the new rule. Historical views show saved values without recomputation. Backend/API fields remain unchanged. Existing 8-HCP and 3/4-level bot bidding, human legal bids, trick legality and lifecycle are untouched. ComputerStrategy.java, RoomService.java and GameState.java are byte-identical to the previous HCP8 candidate source ZIP.

## Completed checks

- Clean offline Java 17 Maven web package: 645 tests, zero failures/errors/skips; 405 scoring tests include 392 exhaustive combinations, focused exact/excess/failure/extreme-loss examples and invalid ranges.
- Independent Java oracle: 392 valid plus 400 invalid boundary inputs, checking all components, isolated privilege flags, reverse replay and concurrent determinism. See NEW_SCORING_ORACLE.json.
- PlannedDealsTest: complete 1/3/5-deal matches with independent new-score expectations, cumulative totals and stored archive values after rematch. Controlled three-deal tie updated to exercise -12/-12 under new rules; final retries cannot overcount.
- Frontend: 431 tests across 31 files, typecheck, ESLint and production build passed. New display tests cover exact/excess, large negative scores, defenders, independent privilege and legacy stored values. Existing installed dependencies used.
- API structural drift gate: 20 HTTP operations / 43 application errors passed; not full OpenAPI schema validation.
- Packaged-JAR PWA HTTP validators: 21 checks passed. All 18 frontend dist files match embedded JAR assets byte for byte.
- Final packaged JAR SHA256: `572c6dd4438934bbdf97071267cd277e0f3ec593b41b1fcada8d66db1e10d4b5`.

## HTTP/WebSocket acceptance

Full play against the final JAR passed on separate loopback servers: 1 deal = 16 checks, 3 deals = 38 checks, 5 deals = 60 checks (114 total). Each completed through final cumulative score/winner, terminal reconnect and late-leave immutability. Every deal verifies all score components against the independent Python oracle, trick conservation, history privacy, transition/replay guards and accumulation. See NEW_SCORING_PLAY_1.json, NEW_SCORING_PLAY_3.json and NEW_SCORING_PLAY_5.json.

The initial shared-server parallel attempt hit the existing rate limit and is not passing evidence; the completed separate-server reruns preserve all normal rate-limit settings.

## Limits

No production mutation, deployment, physical-device or browser-pixel verification. No fresh dependency-install certification. Source and test changes are local and publication needs separate approval.
