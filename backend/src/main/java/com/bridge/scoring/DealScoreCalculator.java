package com.bridge.scoring;

/**
 * Pure scoring for the user's confirmed custom rules. Called only after an authoritative completed
 * thirteen-trick deal; no Spring registration, mutable state, or persistence.
 */
public final class DealScoreCalculator {
  private DealScoreCalculator() {}

  /** Components are additive; penalty and a losing privilege adjustment are negative. */
  public record TeamScore(
      boolean won,
      boolean privilegeUsed,
      int base,
      int exactTargetBonus,
      int penalty,
      int privilegeAdjustment) {
    public int total() {
      return base + exactTargetBonus + penalty + privilegeAdjustment;
    }
  }

  public record DealScore(
      int contractLevel,
      int attackingTarget,
      int attackingTricks,
      int defendingTricks,
      TeamScore attack,
      TeamScore defence) {}

  /**
   * Calculates both teams' scores without changing state or consulting external data. Privilege is
   * independently available to each team in every deal. The defending team wins iff the attacking
   * team fails its target; defending ordinary score is one on a win and zero on a loss.
   *
   * @throws IllegalArgumentException if level is outside 1..7 or attackingTricks outside 0..13
   */
  public static DealScore calculate(
      int level, int attackingTricks, boolean attackingPrivilege, boolean defendingPrivilege) {
    if (level < 1 || level > 7) {
      throw new IllegalArgumentException("Contract level must be between 1 and 7");
    }
    if (attackingTricks < 0 || attackingTricks > 13) {
      throw new IllegalArgumentException("Attacking tricks must be between 0 and 13");
    }

    int target = 6 + level;
    boolean attackWon = attackingTricks >= target;
    TeamScore attack =
        new TeamScore(
            attackWon,
            attackingPrivilege,
            attackWon ? level : 0,
            attackingTricks == target ? 2 : 0,
            attackWon ? 0 : attackingTricks - target,
            privilegeAdjustment(attackingPrivilege, attackWon));
    TeamScore defence =
        new TeamScore(
            !attackWon,
            defendingPrivilege,
            attackWon ? 0 : 1,
            0,
            0,
            privilegeAdjustment(defendingPrivilege, !attackWon));
    return new DealScore(level, target, attackingTricks, 13 - attackingTricks, attack, defence);
  }

  private static int privilegeAdjustment(boolean used, boolean won) {
    return used ? (won ? 1 : -1) : 0;
  }
}
