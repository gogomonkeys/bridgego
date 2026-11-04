# Custom scoring module

Current local candidate rules, approved 2026-11-04. Not deployed.

`DealScoreCalculator.calculate(level, attackingTricks, attackingPrivilege, defendingPrivilege)` is pure and deterministic. Legal contract levels remain 1..7; completed attacking tricks are 0..13. Invalid inputs throw `IllegalArgumentException`.

- Target is 6 + level. Attack wins if it reaches or exceeds target; otherwise defence wins.
- Successful attack has base = level. Exact target adds a fixed bonus of 2; overtricks add no bonus.
- Failed attack has base and bonus zero, penalty = attacking tricks - target. No cap. Level 7 with zero tricks is -13 before privilege.
- Defence has base = 1 only on attack failure, otherwise zero. Defence bonus and penalty are zero.
- Each team's opted privilege independently adds +1 on its win or -1 on its loss.
- Total is the sum of base, exactTargetBonus, penalty and privilegeAdjustment. The API fields stay unchanged.

Examples: level 2 with 8 tricks earns 4, or 5 with privilege; 9 tricks earns 2, or 3 with privilege; 6 tricks earns -2, or -3 with privilege. Defence earns 1 on failure, or 2 with privilege; on successful attack it earns 0, or -1 with privilege.

GameState invokes the calculator only after all 13 authoritative tricks, stores one immutable result and adds its totals once. Settlement, completed-deal history and last-match archives display the stored breakdown, never recompute past scores. Incomplete deals and forfeits preserve only previously completed scores. The same rules apply to 1, 3 and 5 deal matches, whose winner is determined by cumulative totals (ties remain draws).

Tests enumerate all 392 valid combinations (7 levels × 14 trick counts × 4 privilege options), invalid boundaries, exact/over/shortfall examples and the deepest uncapped loss. PlannedDealsTest exercises complete 1/3/5-deal matches and verifies independent expected totals, accumulation and archived values after rematch. Current verification is recorded in ../qa/NEW_SCORING_VERIFICATION.md.
