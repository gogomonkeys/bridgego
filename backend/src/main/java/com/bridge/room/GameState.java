package com.bridge.room;

import java.security.SecureRandom;
import java.util.*;

/**
 * Server-only aggregate. Never serialize this object: View and own-hand projections are explicit.
 */
final class GameState {
  static final List<String> STRAINS = List.of("CLUB", "DIAMOND", "HEART", "SPADE", "NO_TRUMP");

  record Card(String suit, int rank) {}

  record Call(int seat, String type, Integer level, String strain, String source) {}

  record LegalCall(String type, Integer level, String strain) {}

  record Bid(int seat, int level, String strain) {}

  record Contract(String type, int holderSeat, int level, String strain, String teamId) {}

  record Privilege(
      String status,
      Boolean yourChoice,
      String yourChoiceSource,
      boolean canChoose,
      Map<String, Boolean> usedByTeam) {}

  record Redeal(Integer yourPoints, boolean eligible, Boolean yourChoice, boolean canChoose) {}

  record Auction(Integer nextSeat, List<Call> calls, Bid highestBid, Contract result) {}

  record AuctionRound(String dealId, int dealIndex, int redealCount, int dealerSeat,
      List<Call> calls, Contract result, String status) {}

  record TrickView(int number, int leaderSeat, List<TrickEngine.Play> plays,
      int winnerSeat, boolean cardsVisible) {}

  record OperationTimer(String kind, Integer turnSeat, long remainingMs, boolean paused) {}

  record ForfeitEvent(String playerId, int seat, String cause) {}

  record PlayerInfo(String playerId, String displayName, int seat, String teamId, String playerType) {
    PlayerInfo(String playerId, String displayName, int seat, String teamId) {
      this(playerId, displayName, seat, teamId, "HUMAN");
    }
  }

  record PlayView(
      Integer nextSeat,
      int leaderSeat,
      int trickNumber,
      List<TrickEngine.Play> currentTrick,
      List<TrickView> completedTricks,
      Map<String, Integer> tricksByTeam) {}

  record TeamScore(
      boolean won,
      boolean privilegeUsed,
      int base,
      int exactTargetBonus,
      int penalty,
      int privilegeAdjustment,
      int total) {}

  record DealResult(
      String dealId,
      int dealIndex,
      int dealerSeat,
      Contract contract,
      Map<String, Boolean> privilegeUsed,
      Map<String, Integer> tricksByTeam,
      Map<String, TeamScore> scores,
      List<TrickEngine.Trick> completedTricks,
      List<AuctionRound> auctionRounds) {}

  record DealResultView(String dealId, int dealIndex, int dealerSeat, Contract contract,
      Map<String, Boolean> privilegeUsed, Map<String, Integer> tricksByTeam,
      Map<String, TeamScore> scores, List<TrickView> completedTricks,
      List<AuctionRound> auctionRounds) {}

  record PendingForfeit(String reason, List<String> failedTeamIds, List<Integer> waitingSeats) {}

  record MatchResult(
      String outcome, String winnerTeamId, String reason, List<String> failedTeamIds) {}

  record MatchArchive(
      String gameId,
      List<PlayerInfo> roster,
      Map<String, Integer> teamTotals,
      List<DealResultView> completedDealResults,
      MatchResult matchResult,
      List<ForfeitEvent> forfeitEvents,
      List<AuctionRound> auctionRounds) {}

  record View(
      String gameId,
      String dealId,
      int plannedDeals,
      int dealIndex,
      int completedDeals,
      int dealerSeat,
      int redealCount,
      String lastRedealReason,
      boolean paused,
      String abortReason,
      OperationTimer operationTimer,
      List<PlayerInfo> roster,
      Privilege privilege,
      Redeal redeal,
      Auction auction,
      List<AuctionRound> auctionRounds,
      PlayView play,
      List<Integer> resultAcknowledgedSeats,
      Map<String, Integer> teamTotals,
      List<DealResultView> completedDealResults,
      PendingForfeit pendingForfeit,
      MatchResult matchResult,
      List<ForfeitEvent> forfeitEvents) {}

  private static final long OPERATION_MS = 20_000;
  private static final long DEAL_RESULT_MS = 90_000;
  static final long TRICK_RESULT_MS = 2_000;
  private final java.util.function.LongSupplier ticker;
  private long remainingMs;
  private long runningSince;
  private boolean timerPaused;

  final String id = UUID.randomUUID().toString();
  String dealId;
  String phase = "PRIVILEGE_DECISION";
  String abortReason;
  int redealCount;
  String lastRedealReason;
  int nextSeat;
  int consecutivePasses;
  boolean revealed;
  final Map<Integer, List<Card>> hands = new HashMap<>();
  private final Set<Integer> redealEligible = new HashSet<>();
  private final Map<Integer, Boolean> redealChoices = new HashMap<>();
  final Map<Integer, Boolean> votes = new HashMap<>();
  final Map<Integer, String> voteSources = new HashMap<>();
  final List<Call> calls = new ArrayList<>();
  private final List<AuctionRound> finishedAuctions = new ArrayList<>();
  private final Set<String> reviewableDeals = new HashSet<>();
  Bid highest;
  Contract contract;
  private final SecureRandom random;
  private TrickEngine tricks;
  private int dealerSeat;
  private int dealIndex = 1;
  private final int plannedDeals;
  private List<PlayerInfo> roster = List.of();
  private final List<DealResult> results = new ArrayList<>();
  private final Map<String, Integer> totals = new HashMap<>(Map.of("NS", 0, "EW", 0));
  private PendingForfeit pendingForfeit;
  private MatchResult matchResult;
  private final List<ForfeitEvent> forfeitEvents = new ArrayList<>();
  private final Set<Integer> resultAcknowledgments = new TreeSet<>();

  MatchArchive archive() {
    if (!terminal()) return null;
    return new MatchArchive(
        id,
        roster,
        Map.copyOf(totals),
        resultViews(),
        matchResult,
        List.copyOf(forfeitEvents),
        auctionRounds());
  }

  boolean canAcknowledge(Integer seat, boolean paused) {
    return seat != null
        && seat >= 0
        && seat < 4
        && !paused
        && phase.equals("DEAL_RESULT")
        && !resultAcknowledgments.contains(seat);
  }

  void acknowledge(int seat) {
    if (!phase.equals("DEAL_RESULT"))
      throw RoomService.fail(409, "WRONG_PHASE", "Not viewing a deal result");
    if (seat < 0 || seat > 3) throw RoomService.fail(403, "NOT_MEMBER", "Invalid participant seat");
    resultAcknowledgments.add(seat);
    if (resultAcknowledgments.size() == 4) advanceDeal();
  }

  private void advanceDeal() {
    dealIndex++;
    dealerSeat = (dealerSeat + 1) % 4;
    redealCount = 0;
    lastRedealReason = null;
    deal();
  }

  void recordForfeit(String playerId, int seat, String cause) {
    if (!active()) return;
    ForfeitEvent event = new ForfeitEvent(playerId, seat, cause);
    if (!forfeitEvents.contains(event)) forfeitEvents.add(event);
  }

  void roster(List<PlayerInfo> players) {
    if (!roster.isEmpty()) throw new IllegalStateException("Roster already assigned");
    roster = List.copyOf(players);
  }

  void setPendingForfeit(Set<String> failedTeams, List<Integer> waitingSeats) {
    if (!active()) return;
    phase = "FORFEIT_PENDING";
    pendingForfeit =
        new PendingForfeit(
            "AWAITING_EXISTING_RECONNECT_GRACE",
            failedTeams.stream().sorted().toList(),
            waitingSeats.stream().sorted().toList());
  }

  void finishForfeit(String reason, Set<String> failedTeams) {
    if (!active()) return;
    boolean both = failedTeams.size() == 2;
    matchResult =
        new MatchResult(
            both ? "NO_CONTEST" : "FORFEIT",
            both ? null : failedTeams.contains("NS") ? "EW" : "NS",
            both ? "BOTH_TEAMS_FAILED" : reason,
            failedTeams.stream().sorted().toList());
    pendingForfeit = null;
    phase = "MATCH_RESULT";
    // Only actually completed deals can be reviewed after an interrupted match.
    results.forEach(result -> reviewableDeals.add(result.dealId()));
  }

  void finishWithoutHumans(String reason) {
    if (!active()) return;
    matchResult = new MatchResult("NO_CONTEST", null, reason, List.of());
    pendingForfeit = null;
    phase = "MATCH_RESULT";
    results.forEach(result -> reviewableDeals.add(result.dealId()));
  }

  GameState(SecureRandom random) {
    this(random, () -> System.nanoTime() / 1_000_000);
  }

  GameState(SecureRandom random, java.util.function.LongSupplier ticker) {
    this(random, ticker, 3);
  }

  GameState(SecureRandom random, java.util.function.LongSupplier ticker, int plannedDeals) {
    if (plannedDeals != 1 && plannedDeals != 3 && plannedDeals != 5)
      throw new IllegalArgumentException("plannedDeals must be 1, 3 or 5");
    this.plannedDeals = plannedDeals;
    this.random = random;
    this.ticker = ticker;
    deal();
  }

  private void deal() {
    resetTimer();
    dealId = UUID.randomUUID().toString();
    phase = "PRIVILEGE_DECISION";
    resultAcknowledgments.clear();
    votes.clear();
    redealEligible.clear();
    redealChoices.clear();
    voteSources.clear();
    calls.clear();
    hands.clear();
    revealed = false;
    highest = null;
    contract = null;
    tricks = null;
    nextSeat = dealerSeat;
    consecutivePasses = 0;
    List<Card> deck = new ArrayList<>(52);
    for (String suit : STRAINS.subList(0, 4))
      for (int rank = 2; rank <= 14; rank++) deck.add(new Card(suit, rank));
    Collections.shuffle(deck, random);
    for (int seat = 0; seat < 4; seat++) {
      hands.put(seat, List.copyOf(deck.subList(seat * 13, seat * 13 + 13)));
      if (qualifiesForRedeal(hands.get(seat))) redealEligible.add(seat);
    }
    if (!redealEligible.isEmpty()) phase = "REDEAL_DECISION";
  }

  static int highCardPoints(List<Card> hand) {
    return hand.stream().mapToInt(card -> switch (card.rank()) {
      case 14 -> 4;
      case 13 -> 3;
      case 12 -> 2;
      case 11 -> 1;
      default -> 0;
    }).sum();
  }

  static boolean qualifiesForRedeal(List<Card> hand) {
    int points = highCardPoints(hand);
    return points < 5 || (points == 5 && hand.stream().noneMatch(c -> c.rank() == 14));
  }

  boolean canChooseRedeal(Integer seat, boolean paused) {
    return seat != null && !paused && phase.equals("REDEAL_DECISION")
        && redealEligible.contains(seat) && !redealChoices.containsKey(seat);
  }

  void chooseRedeal(int seat, boolean redeal) {
    if (!phase.equals("REDEAL_DECISION"))
      throw RoomService.fail(409, "WRONG_PHASE", "Not choosing whether to redeal");
    if (!redealEligible.contains(seat))
      throw RoomService.fail(409, "REDEAL_NOT_ELIGIBLE", "This hand does not qualify for a redeal");
    if (redealChoices.containsKey(seat))
      throw RoomService.fail(409, "CHOICE_LOCKED", "The redeal choice is already fixed");
    redealChoices.put(seat, redeal);
    if (redeal) {
      lastRedealReason = "HAND_REDEAL";
      redealCount++;
      deal();
    } else if (redealChoices.keySet().containsAll(redealEligible)) {
      phase = "PRIVILEGE_DECISION";
      resetTimer();
    }
  }

  static String team(int seat) {
    return seat % 2 == 0 ? "NS" : "EW";
  }

  boolean terminal() {
    return matchResult != null;
  }

  boolean active() {
    return !phase.equals("ABORTED") && !terminal();
  }

  void abort(String reason) {
    phase = "ABORTED";
    abortReason = reason;
    remainingMs = 0;
  }

  private Boolean teamChoice(int parity) {
    Boolean a = votes.get(parity), b = votes.get(parity + 2);
    if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) return true;
    if (Boolean.FALSE.equals(a) && Boolean.FALSE.equals(b)) return false;
    return null;
  }

  void choose(int seat, boolean use) { choose(seat, use, "MANUAL"); }

  void choose(int seat, boolean use, String source) {
    if (!phase.equals("PRIVILEGE_DECISION"))
      throw RoomService.fail(409, "WRONG_PHASE", "Not choosing privilege");
    if (votes.containsKey(seat) || teamChoice(seat % 2) != null)
      throw RoomService.fail(409, "CHOICE_LOCKED", "This team's choice is already fixed");
    votes.put(seat, use);
    voteSources.put(seat, source);
    if (teamChoice(0) != null && teamChoice(1) != null) {
      revealed = true;
      phase = "BIDDING";
      resetTimer();
    }
  }

  private static int bidRank(int level, String strain) {
    return (level - 1) * 5 + STRAINS.indexOf(strain);
  }

  void call(int seat, String type, Integer level, String strain) {
    call(seat, type, level, strain, "MANUAL");
  }

  void call(int seat, String type, Integer level, String strain, String source) {
    if (!phase.equals("BIDDING")) throw RoomService.fail(409, "WRONG_PHASE", "Not bidding");
    if (seat != nextSeat) throw RoomService.fail(409, "NOT_YOUR_TURN", "Wait for your turn");
    if ("PASS".equals(type)) {
      if (level != null || strain != null)
        throw RoomService.fail(400, "INVALID_CALL", "PASS has no level or strain");
      calls.add(new Call(seat, type, null, null, source));
      consecutivePasses++;
      if (highest == null && consecutivePasses == 4) {
        finishedAuctions.add(auctionRound("ALL_PASS"));
        lastRedealReason = "ALL_PASS";
        redealCount++;
        deal();
        return;
      }
      if (highest != null && consecutivePasses == 3) {
        phase = "PLAYING";
        contract =
            new Contract(
                "CONTRACT",
                highest.seat(),
                highest.level(),
                highest.strain(),
                team(highest.seat()));
        finishedAuctions.add(auctionRound("CONTRACT"));
        tricks = new TrickEngine(hands, highest.seat(), highest.strain());
        resetTimer();
        return;
      }
    } else if ("BID".equals(type)) {
      if (level == null || level < 1 || level > 7 || strain == null || !STRAINS.contains(strain))
        throw RoomService.fail(
            400, "INVALID_CALL", "Bid requires level 1..7 and a supported strain");
      if (highest != null && bidRank(level, strain) <= bidRank(highest.level(), highest.strain()))
        throw RoomService.fail(409, "ILLEGAL_CALL", "Bid must exceed the current bid");
      highest = new Bid(seat, level, strain);
      calls.add(new Call(seat, type, level, strain, source));
      consecutivePasses = 0;
    } else throw RoomService.fail(400, "INVALID_CALL", "Only BID and PASS are supported");
    nextSeat = (seat + 1) % 4;
    resetTimer();
  }

  private boolean timed() {
    return phase.equals("REDEAL_DECISION")
        || phase.equals("PRIVILEGE_DECISION")
        || phase.equals("BIDDING")
        || phase.equals("PLAYING")
        || phase.equals("DEAL_RESULT")
        || phase.equals("TRICK_RESULT");
  }

  private void resetTimer() {
    resetTimer(OPERATION_MS);
  }

  private void resetTimer(long durationMs) {
    remainingMs = durationMs;
    runningSince = ticker.getAsLong();
    timerPaused = false;
  }

  private long remaining() {
    return Math.max(
        0, remainingMs - (timerPaused ? 0 : Math.max(0, ticker.getAsLong() - runningSince)));
  }

  void pauseTimer() {
    if (timed() && !timerPaused) {
      remainingMs = remaining();
      timerPaused = true;
    }
  }

  void resumeTimer() {
    if (timed() && timerPaused) {
      runningSince = ticker.getAsLong();
      timerPaused = false;
    }
  }

  // One transition only: a delayed scheduler never burns future players' entire turns.
  boolean expireOperation() { return expireOperation(Map.of()); }

  boolean expireOperation(Map<Integer, String> automatedSources) {
    if (!timed() || timerPaused || remaining() > 0) return false;
    if (phase.equals("REDEAL_DECISION")) {
      // An automated eligible seat must still choose redeal when a scheduler wakes late.
      for (int seat = 0; seat < 4; seat++) {
        int candidate = seat;
        boolean automated = automatedSources.containsKey(seat)
            || roster.stream().anyMatch(p -> p.seat() == candidate && p.playerType().equals("BOT"));
        if (automated && canChooseRedeal(seat, false)) {
          chooseRedeal(seat, true);
          return true;
        }
      }
      for (int seat : redealEligible) redealChoices.putIfAbsent(seat, false);
      phase = "PRIVILEGE_DECISION";
      resetTimer();
    } else if (phase.equals("PRIVILEGE_DECISION")) {
      for (int seat = 0; seat < 4; seat++) {
        if (!votes.containsKey(seat)) {
          votes.put(seat, false);
          int votingSeat = seat;
          boolean computer = roster.stream().anyMatch(p -> p.seat() == votingSeat && p.playerType().equals("BOT"));
          voteSources.put(seat, automatedSources.getOrDefault(seat, computer ? "BOT" : "TIMEOUT"));
        }
      }
      revealed = true;
      phase = "BIDDING";
      resetTimer();
    } else if (phase.equals("BIDDING")) call(nextSeat, "PASS", null, null, "TIMEOUT");
    else if (phase.equals("DEAL_RESULT")) advanceDeal();
    else if (phase.equals("TRICK_RESULT")) {
      if (tricks.finished()) reviewableDeals.add(dealId);
      phase = tricks.finished() ? (terminal() ? "MATCH_RESULT" : "DEAL_RESULT") : "PLAYING";
      if (!terminal()) resetTimer(phase.equals("DEAL_RESULT") ? DEAL_RESULT_MS : OPERATION_MS);
    } else {
      tricks.timeout();
      afterPlay();
    }
    return true;
  }

  private OperationTimer operationTimer() {
    if (!timed()) return null;
    String kind = switch (phase) {
      case "REDEAL_DECISION" -> "REDEAL_DECISION";
      case "TRICK_RESULT" -> "TRICK_RESULT";
      case "DEAL_RESULT" -> "DEAL_RESULT";
      case "PLAYING" -> "PLAY_TURN";
      case "BIDDING" -> "BID_TURN";
      default -> "PRIVILEGE";
    };
    return new OperationTimer(
        kind,
        phase.equals("PLAYING") ? tricks.nextSeat() : phase.equals("BIDDING") ? nextSeat : null,
        remaining(),
        timerPaused);
  }

  List<Card> legalCards(Integer seat, boolean paused) {
    return seat == null || paused || !phase.equals("PLAYING") ? List.of() : tricks.legalCards(seat);
  }

  void play(int seat, Card card) { play(seat, card, "MANUAL"); }

  void play(int seat, Card card, String source) {
    if (!phase.equals("PLAYING")) throw RoomService.fail(409, "WRONG_PHASE", "Not playing");
    if (!Objects.equals(tricks.nextSeat(), seat))
      throw RoomService.fail(409, "NOT_YOUR_TURN", "Wait for your turn");
    if (card == null
        || card.suit() == null
        || !STRAINS.subList(0, 4).contains(card.suit())
        || card.rank() < 2
        || card.rank() > 14)
      throw RoomService.fail(400, "INVALID_CARD", "Card requires valid suit and rank 2..14");
    if (!tricks.legalCards(seat).contains(card))
      throw RoomService.fail(409, "ILLEGAL_CARD", "Choose a legal card from your hand");
    tricks.play(seat, card, source);
    afterPlay();
  }

  private static TeamScore scoreView(com.bridge.scoring.DealScoreCalculator.TeamScore s) {
    return new TeamScore(
        s.won(),
        s.privilegeUsed(),
        s.base(),
        s.exactTargetBonus(),
        s.penalty(),
        s.privilegeAdjustment(),
        s.total());
  }

  private void afterPlay() {
    for (int seat = 0; seat < 4; seat++) hands.put(seat, tricks.hand(seat));
    if (!tricks.currentTrick().isEmpty()) {
      resetTimer();
      return;
    }
    // The engine commits a trick atomically. Keep its four public cards visible before
    // allowing the next leader to play. This budget never consumes the next turn.
    phase = "TRICK_RESULT";
    resetTimer(TRICK_RESULT_MS);
    if (!tricks.finished()) return;
    String attack = contract.teamId(), defence = attack.equals("NS") ? "EW" : "NS";
    Map<String, Boolean> privileges = Map.of("NS", teamChoice(0), "EW", teamChoice(1));
    var score =
        com.bridge.scoring.DealScoreCalculator.calculate(
            contract.level(),
            tricks.tricksByTeam().get(attack),
            privileges.get(attack),
            privileges.get(defence));
    Map<String, TeamScore> scores =
        Map.of(attack, scoreView(score.attack()), defence, scoreView(score.defence()));
    results.add(
        new DealResult(
            dealId,
            dealIndex,
            dealerSeat,
            contract,
            privileges,
            tricks.tricksByTeam(),
            scores,
            tricks.completedTricks(),
            auctionRounds().stream().filter(round -> round.dealIndex() == dealIndex).toList()));
    scores.forEach((team, value) -> totals.put(team, totals.get(team) + value.total()));
    if (results.size() == plannedDeals) {
      int comparison = Integer.compare(totals.get("NS"), totals.get("EW"));
      matchResult =
          new MatchResult(
              "SCORED",
              comparison == 0 ? null : comparison > 0 ? "NS" : "EW",
              plannedDeals == 3 ? "THREE_DEALS_COMPLETE" : "PLANNED_DEALS_COMPLETE",
              List.of());
    }
  }

  private PlayView playView() {
    if (tricks == null) return null;
    boolean showingResult = phase.equals("TRICK_RESULT");
    TrickEngine.Trick shown = showingResult
        ? tricks.completedTricks().get(tricks.completedTricks().size() - 1) : null;
    return new PlayView(
        showingResult ? null : tricks.nextSeat(),
        showingResult ? shown.leaderSeat() : tricks.leaderSeat(),
        showingResult ? shown.number() : tricks.trickNumber(),
        showingResult ? shown.plays() : tricks.currentTrick(),
        trickViews(tricks.completedTricks(), reviewableDeals.contains(dealId)),
        tricks.tricksByTeam());
  }

  private AuctionRound auctionRound(String status) {
    return new AuctionRound(dealId, dealIndex, redealCount, dealerSeat,
        List.copyOf(calls), contract, status);
  }

  private List<AuctionRound> auctionRounds() {
    List<AuctionRound> rounds = new ArrayList<>(finishedAuctions);
    if (rounds.stream().noneMatch(round -> round.dealId().equals(dealId)))
      rounds.add(auctionRound("IN_PROGRESS"));
    return List.copyOf(rounds);
  }

  private static List<TrickView> trickViews(List<TrickEngine.Trick> source, boolean visible) {
    return source.stream().map(trick -> new TrickView(trick.number(), trick.leaderSeat(),
        visible ? trick.plays() : List.of(), trick.winnerSeat(), visible)).toList();
  }

  private List<DealResultView> resultViews() {
    return results.stream().map(result -> new DealResultView(result.dealId(), result.dealIndex(),
        result.dealerSeat(), result.contract(), result.privilegeUsed(), result.tricksByTeam(),
        result.scores(), trickViews(result.completedTricks(), reviewableDeals.contains(result.dealId())),
        result.auctionRounds())).toList();
  }

  List<LegalCall> legalCalls(Integer seat, boolean paused) {
    if (seat == null || paused || !phase.equals("BIDDING") || seat != nextSeat) return List.of();
    List<LegalCall> result = new ArrayList<>();
    result.add(new LegalCall("PASS", null, null));
    for (int level = 1; level <= 7; level++)
      for (String strain : STRAINS)
        if (highest == null || bidRank(level, strain) > bidRank(highest.level(), highest.strain()))
          result.add(new LegalCall("BID", level, strain));
    return List.copyOf(result);
  }

  View view(Integer viewerSeat, boolean paused) {
    return view(viewerSeat, paused, false);
  }

  View view(Integer viewerSeat, boolean paused, boolean controlTakenOver) {
    return new View(
        id,
        dealId,
        plannedDeals,
        dealIndex,
        results.size(),
        dealerSeat,
        redealCount,
        lastRedealReason,
        paused,
        abortReason,
        operationTimer(),
        roster,
        new Privilege(
            revealed ? "REVEALED" : "PENDING",
            viewerSeat == null ? null : votes.get(viewerSeat),
            viewerSeat == null ? null : voteSources.get(viewerSeat),
            viewerSeat != null
                && !controlTakenOver
                && !paused
                && phase.equals("PRIVILEGE_DECISION")
                && !votes.containsKey(viewerSeat)
                && teamChoice(viewerSeat % 2) == null,
            revealed ? Map.of("NS", teamChoice(0), "EW", teamChoice(1)) : null),
        new Redeal(
            viewerSeat != null && phase.equals("REDEAL_DECISION")
                ? highCardPoints(hands.getOrDefault(viewerSeat, List.of())) : null,
            viewerSeat != null && phase.equals("REDEAL_DECISION") && redealEligible.contains(viewerSeat),
            viewerSeat == null || !phase.equals("REDEAL_DECISION") ? null : redealChoices.get(viewerSeat),
            !controlTakenOver && canChooseRedeal(viewerSeat, paused)),
        new Auction(
            phase.equals("BIDDING") ? nextSeat : null, List.copyOf(calls), highest, contract),
        auctionRounds(),
        playView(),
        List.copyOf(resultAcknowledgments),
        Map.copyOf(totals),
        resultViews(),
        pendingForfeit,
        matchResult,
        List.copyOf(forfeitEvents));
  }
}
