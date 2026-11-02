package com.bridge.scoring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class DealScoreCalculatorTest {
  // Independent explicit table transcribed from the confirmed custom scoring attachment.
  private static final int[] TARGET = {0, 7, 8, 9, 10, 11, 12, 13};
  private static final int[] BASE = {0, 1, 2, 3, 4, 5, 6, 7};
  private static final int[] EXACT_BONUS = {0, 2, 3, 4, 5, 6, 7, 8};
  private static final int[] FAILURE = {0, -1, -2, -3, -4, -5, -6, -7};

  static Stream<Arguments> allValidInputs() {
    return IntStream.rangeClosed(1, 7)
        .boxed()
        .flatMap(
            level ->
                IntStream.rangeClosed(0, 13)
                    .boxed()
                    .flatMap(
                        tricks ->
                            IntStream.range(0, 4)
                                .mapToObj(
                                    flags ->
                                        Arguments.of(
                                            level, tricks, (flags & 1) != 0, (flags & 2) != 0))));
  }

  @ParameterizedTest(name = "level={0}, tricks={1}, attackPrivilege={2}, defencePrivilege={3}")
  @MethodSource("allValidInputs")
  void exhaustiveConfirmedRules(
      int level, int tricks, boolean attackPrivilege, boolean defencePrivilege) {
    var result = DealScoreCalculator.calculate(level, tricks, attackPrivilege, defencePrivilege);
    boolean won = tricks >= TARGET[level];
    int base = won ? BASE[level] : 0;
    int bonus = tricks == TARGET[level] ? EXACT_BONUS[level] : 0;
    int penalty = won ? 0 : FAILURE[level];
    int attackAdjustment = attackPrivilege ? (won ? 2 : -1) : 0;
    int defenceAdjustment = defencePrivilege ? (won ? -1 : 2) : 0;

    assertAll(
        () -> assertEquals(level, result.contractLevel()),
        () -> assertEquals(TARGET[level], result.attackingTarget()),
        () -> assertEquals(tricks, result.attackingTricks()),
        () -> assertEquals(13 - tricks, result.defendingTricks()),
        () -> assertEquals(won, result.attack().won()),
        () -> assertEquals(!won, result.defence().won()),
        () -> assertEquals(attackPrivilege, result.attack().privilegeUsed()),
        () -> assertEquals(defencePrivilege, result.defence().privilegeUsed()),
        () -> assertEquals(base, result.attack().base()),
        () -> assertEquals(bonus, result.attack().exactTargetBonus()),
        () -> assertEquals(penalty, result.attack().penalty()),
        () -> assertEquals(attackAdjustment, result.attack().privilegeAdjustment()),
        () -> assertEquals(base + bonus + penalty + attackAdjustment, result.attack().total()),
        () -> assertEquals(0, result.defence().base()),
        () -> assertEquals(0, result.defence().exactTargetBonus()),
        () -> assertEquals(0, result.defence().penalty()),
        () -> assertEquals(defenceAdjustment, result.defence().privilegeAdjustment()),
        () -> assertEquals(defenceAdjustment, result.defence().total()),
        () ->
            assertEquals(
                result,
                DealScoreCalculator.calculate(level, tricks, attackPrivilege, defencePrivilege)));
  }

  @Test
  void confirmedBidTwoExactEightScoresFiveOrSeven() {
    assertEquals(5, DealScoreCalculator.calculate(2, 8, false, false).attack().total());
    var privileged = DealScoreCalculator.calculate(2, 8, true, true);
    assertEquals(7, privileged.attack().total());
    assertEquals(2, privileged.attack().base());
    assertEquals(3, privileged.attack().exactTargetBonus());
    assertEquals(2, privileged.attack().privilegeAdjustment());
    assertEquals(-1, privileged.defence().total());
  }

  @Test
  void exceedingEightOnBidTwoGetsOnlyBaseWithoutPrivilege() {
    for (int tricks = 9; tricks <= 13; tricks++) {
      var result = DealScoreCalculator.calculate(2, tricks, false, false);
      assertEquals(2, result.attack().total());
      assertEquals(0, result.attack().exactTargetBonus());
      assertEquals(0, result.defence().total());
    }
  }

  @Test
  void failedBidTwoIsFixedMinusTwoNotPerMissingTrick() {
    for (int tricks = 0; tricks < 8; tricks++) {
      var ordinary = DealScoreCalculator.calculate(2, tricks, false, false);
      assertEquals(-2, ordinary.attack().total());
      assertEquals(0, ordinary.defence().total());
      var privileged = DealScoreCalculator.calculate(2, tricks, true, true);
      assertEquals(-3, privileged.attack().total());
      assertEquals(2, privileged.defence().total());
    }
  }

  @Test
  void levelSevenExactThirteenAndFailureBoundaries() {
    assertEquals(15, DealScoreCalculator.calculate(7, 13, false, false).attack().total());
    assertEquals(17, DealScoreCalculator.calculate(7, 13, true, false).attack().total());
    assertEquals(-7, DealScoreCalculator.calculate(7, 12, false, false).attack().total());
  }

  @ParameterizedTest
  @CsvSource({
    "0,8",
    "8,8",
    "-2147483648,8",
    "2147483647,8",
    "2,-1",
    "2,14",
    "2,-2147483648",
    "2,2147483647"
  })
  void rejectsOutOfRangeInputs(int level, int tricks) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DealScoreCalculator.calculate(level, tricks, false, false));
  }
}
