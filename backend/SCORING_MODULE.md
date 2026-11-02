# Pure custom scoring module — not wired to unfinished gameplay

Added 2026-10-29 under `com.bridge.scoring`. During the isolated-calculator checkpoint, no Spring component, endpoint, room lifecycle, transport, game start, or version was changed. Later v0.2.0 game-flow changes are documented separately in README.md. The delivered v0.1.3 JAR was not rebuilt/replaced; its SHA-256 remains `0b7a99c868fbca90509219112b76045a6219dd97d203963547d8f7d404cd2700`.

`DealScoreCalculator.calculate(level, attackingTricks, attackingPrivilege, defendingPrivilege)` is deterministic and has no side effects. Level must be1..7 and attacking tricks 0..13; invalid inputs throw `IllegalArgumentException`. Result includes target, both trick counts and immutable per-team breakdowns: won, privilegeUsed, base, exactTargetBonus, penalty, privilegeAdjustment; `total()` adds the components. This is a Java value model, not a new HTTP schema; a future endpoint must deliberately project its DTO, including total.

Confirmed rules only:
- Attacking target = 6+level; reaching or exceeding it wins the deal.
- Successful attack: base=level; **exactly** reaching target additionally earns level+1. Exceeding target gets no exact-target bonus.
- Failed attack: fixed penalty=−level, not multiplied by missing tricks.
- Defending ordinary score is always 0; defending team wins iff attacking team fails.
- Independently for each team, using privilege adds +2 for a won deal or −1 for a lost deal. No standard bridge scoring is assumed.

Examples: bid 2 and 8 tricks =2+3=5; with attacking privilege=7. Bid 2 and 9..13 tricks =2 before privilege. Bid 2 and 0..7 tricks =−2 before privilege. Defending privilege gives−1 when attack succeeds or+2 when attack fails.

## Executed checks
`mvn test` using the documented container Java compiler flags: **431 total tests passed, zero failures/errors/skips**. Scoring tests: **404** = 392 exhaustive valid cases (7 levels × 14 trick counts × 4 privilege combinations), 4 focused example tests and 8 invalid-range cases. Existing 27 backend tests also passed. No `package` command was run for this module, and the stable JAR hash was verified unchanged afterward.

Host/start/auction/privilege rules are implemented separately in the v0.2.0 milestone; no dummy is used. None of those controllers invoke this calculator before authoritative card play exists. This calculator does not score an unfinished deal or aggregate a three-deal match; callers must supply authoritative completed-deal trick counts once gameplay exists. The no-dummy decision does not alter this score function.

The later v0.2.0 build includes these source classes but still does not invoke them on auction completion or abort. The earlier431-test run above records the isolated-module checkpoint; current aggregate results are in TEST_RESULTS.md.

## v0.3.0 integration
The pure calculator is now called only after the authoritative trick engine completes all13tricks. GameState maps attacking/defending breakdowns to NS/EW, explicitly serializes total, appends exactly one immutable completed-deal result and updates match totals. Immediate next-deal creation does not erase history. Idempotent final-card retries and concurrent final plays cannot score twice. Forfeit preserves completed scores and never supplies synthetic trick counts for an unfinished deal. Earlier nonintegration statements above are historical checkpoint descriptions.
