import com.bridge.scoring.DealScoreCalculator;
import com.bridge.scoring.DealScoreCalculator.DealScore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Independent truth-case oracle. Never imports implementation tests or their expectation tables. */
public final class ScoringOracleReview {
  record Input(int level, int tricks, boolean attackPrivilege, boolean defencePrivilege) {}

  static void require(boolean condition, String message, Input input) {
    if (!condition) throw new AssertionError(message + ": " + input);
  }

  static DealScore check(Input input) {
    int level = input.level(), tricks = input.tricks();
    boolean ap = input.attackPrivilege(), dp = input.defencePrivilege();
    DealScore actual = DealScoreCalculator.calculate(level, tricks, ap, dp);

    // Independent total-score partition: failure / exact success / excess success.
    int threshold = level + 6;
    int ordinary;
    boolean attackerWins;
    if (tricks < threshold) { ordinary = -level; attackerWins = false; }
    else if (tricks == threshold) { ordinary = 2 * level + 1; attackerWins = true; }
    else { ordinary = level; attackerWins = true; }
    int attackerPrivilegeDelta = ap ? (attackerWins ? 2 : -1) : 0;
    int defenderPrivilegeDelta = dp ? (attackerWins ? -1 : 2) : 0;

    require(actual.attack().total() == ordinary + attackerPrivilegeDelta, "attacking total", input);
    require(actual.defence().total() == defenderPrivilegeDelta, "defending total", input);
    require(actual.contractLevel() == level && actual.attackingTarget() == threshold, "contract metadata", input);
    require(actual.attackingTricks() == tricks && actual.defendingTricks() + tricks == 13, "trick conservation", input);
    require(actual.attack().won() == attackerWins && actual.defence().won() != attackerWins, "exclusive winner", input);
    require(actual.attack().privilegeUsed() == ap && actual.defence().privilegeUsed() == dp, "privilege metadata", input);

    // Components must explain the totals; exact bonus is ADDITIVE, not a substitute for base.
    require(actual.attack().base() == (attackerWins ? level : 0), "base component", input);
    require(actual.attack().exactTargetBonus() == (tricks == threshold ? level + 1 : 0), "additive exact bonus", input);
    require(actual.attack().penalty() == (attackerWins ? 0 : -level), "fixed failure penalty", input);
    require(actual.attack().privilegeAdjustment() == attackerPrivilegeDelta, "attacker privilege component", input);
    require(actual.defence().base() == 0 && actual.defence().exactTargetBonus() == 0 && actual.defence().penalty() == 0,
        "defender ordinary components must all be zero", input);
    require(actual.defence().privilegeAdjustment() == defenderPrivilegeDelta, "defender privilege component", input);
    require(actual.attack().total() == actual.attack().base() + actual.attack().exactTargetBonus()
        + actual.attack().penalty() + actual.attack().privilegeAdjustment(), "component sum", input);

    // Each team's flag must not change the opposing score or any hand/contract facts.
    DealScore flipAttack = DealScoreCalculator.calculate(level, tricks, !ap, dp);
    DealScore flipDefence = DealScoreCalculator.calculate(level, tricks, ap, !dp);
    require(actual.defence().equals(flipAttack.defence()), "attacker privilege isolation", input);
    require(actual.attack().equals(flipDefence.attack()), "defender privilege isolation", input);
    require(actual.equals(DealScoreCalculator.calculate(level, tricks, ap, dp)), "repeat after interleaved calls", input);
    return actual;
  }

  static void invalid(Input input) {
    try {
      DealScoreCalculator.calculate(input.level(), input.tricks(), input.attackPrivilege(), input.defencePrivilege());
      throw new AssertionError("accepted invalid input: " + input);
    } catch (IllegalArgumentException expected) { /* Explicit domain rejection, not incidental arithmetic failure. */ }
  }

  public static void main(String[] args) throws Exception {
    List<Input> valid = new ArrayList<>();
    for (int level = 1; level <= 7; level++)
      for (int tricks = 0; tricks <= 13; tricks++)
        for (int bits = 0; bits < 4; bits++)
          valid.add(new Input(level, tricks, (bits & 1) != 0, (bits & 2) != 0));
    List<DealScore> firstResults = new ArrayList<>();
    for (Input input : valid) firstResults.add(check(input));

    // Replay in reverse to detect cross-call state; concurrently evaluate the same finite domain.
    for (int i = valid.size() - 1; i >= 0; i--)
      require(firstResults.get(i).equals(check(valid.get(i))), "reverse replay purity", valid.get(i));
    var executor = Executors.newFixedThreadPool(8);
    try {
      List<Callable<DealScore>> calls = valid.stream().<Callable<DealScore>>map(i -> () -> check(i)).toList();
      var results = executor.invokeAll(calls);
      for (int i = 0; i < valid.size(); i++)
        require(firstResults.get(i).equals(results.get(i).get()), "concurrent determinism", valid.get(i));
    } finally { executor.shutdownNow(); }

    int[] badLevels = {Integer.MIN_VALUE, 0, 8, Integer.MAX_VALUE};
    int[] badTricks = {Integer.MIN_VALUE, -1, 14, Integer.MAX_VALUE};
    int invalidCount = 0;
    for (int level : badLevels) for (int tricks = 0; tricks <= 13; tricks++) for (int bits = 0; bits < 4; bits++) {
      invalid(new Input(level, tricks, (bits & 1) != 0, (bits & 2) != 0)); invalidCount++;
    }
    for (int level = 1; level <= 7; level++) for (int tricks : badTricks) for (int bits = 0; bits < 4; bits++) {
      invalid(new Input(level, tricks, (bits & 1) != 0, (bits & 2) != 0)); invalidCount++;
    }
    for (int level : badLevels) for (int tricks : badTricks) for (int bits = 0; bits < 4; bits++) {
      invalid(new Input(level, tricks, (bits & 1) != 0, (bits & 2) != 0)); invalidCount++;
    }
    if (valid.size() != 392 || invalidCount != 400) throw new AssertionError("case enumeration mismatch");

    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[0]))));
    String json = """
        {
          "status": "PASS",
          "valid_input_cases": 392,
          "invalid_boundary_input_cases": 400,
          "total_distinct_input_cases": 792,
          "additional_checks_within_valid_cases": ["all result components", "trick conservation", "exclusive winners", "independent privileges", "sequential replay", "interleaved calls", "reverse replay", "concurrent determinism"],
          "production_source_sha256": "%s",
          "java_runtime": "%s",
          "production_edits": false,
          "network_or_external_services": false
        }
        """.formatted(hash, System.getProperty("java.version"));
    Files.writeString(Path.of(args[1]), json);
    System.out.print(json);
  }
}
