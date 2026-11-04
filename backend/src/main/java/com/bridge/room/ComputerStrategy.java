package com.bridge.room;

import java.util.*;
import java.util.random.RandomGenerator;

/** Rule-based decisions see only this player's hand, public bids and the current trick. */
final class ComputerStrategy {
  private ComputerStrategy() {}

  static String longestSuit(List<GameState.Card> hand, RandomGenerator random) {
    int maximum = GameState.STRAINS.subList(0, 4).stream()
        .mapToInt(s -> (int) hand.stream().filter(c -> c.suit().equals(s)).count()).max().orElseThrow();
    List<String> tied = GameState.STRAINS.subList(0, 4).stream()
        .filter(s -> hand.stream().filter(c -> c.suit().equals(s)).count() == maximum).toList();
    return tied.get(random.nextInt(tied.size()));
  }

  static GameState.LegalCall call(int seat, String target, List<GameState.Card> hand, GameState.Bid highest) {
    if (highest != null && highest.seat() % 2 == seat % 2)
      return new GameState.LegalCall("PASS", null, null);
    int current = highest == null ? -1 : (highest.level() - 1) * 5 + GameState.STRAINS.indexOf(highest.strain());
    long suitLength = hand.stream().filter(c -> c.suit().equals(target)).count();
    boolean twoAces = hand.stream().filter(c -> c.rank() == 14).count() >= 2;
    int maximumLevel = twoAces && suitLength >= 6 ? 4 : twoAces && suitLength >= 5 ? 3
        : GameState.highCardPoints(hand) >= 8 ? 2 : 1;
    for (int level = 1; level <= maximumLevel; level++)
      if ((level - 1) * 5 + GameState.STRAINS.indexOf(target) > current)
        return new GameState.LegalCall("BID", level, target);
    return new GameState.LegalCall("PASS", null, null);
  }

  static GameState.Card play(int seat, List<GameState.Card> hand, List<TrickEngine.Play> table,
      String trump, RandomGenerator random) {
    if (hand.isEmpty()) throw new IllegalArgumentException("Empty hand");
    Comparator<GameState.Card> rank = Comparator.comparingInt(GameState.Card::rank);
    if (table.isEmpty()) {
      List<String> suits = GameState.STRAINS.subList(0, 4).stream()
          .filter(s -> hand.stream().anyMatch(c -> c.suit().equals(s))).toList();
      String suit = suits.get(random.nextInt(suits.size()));
      return hand.stream().filter(c -> c.suit().equals(suit)).max(rank).orElseThrow();
    }
    String lead = table.get(0).card().suit();
    List<GameState.Card> following = hand.stream().filter(c -> c.suit().equals(lead)).toList();
    TrickEngine.Play winner = table.get(0);
    for (TrickEngine.Play candidate : table)
      if (TrickEngine.beats(candidate.card(), winner.card(), lead, trump)) winner = candidate;
    // Legal follow-suit obligations take precedence over conserving a partner's winning trick.
    boolean partnerWinning = winner.seat() % 2 == seat % 2;
    if (partnerWinning && (table.size() == 3
        || (table.size() == 2 && cannotBeOvertaken(winner.card(), hand, table, lead, trump)))) {
      if (!following.isEmpty()) return minimum(following, random);
      return discard(hand, trump, random);
    }
    if (!following.isEmpty()) {
      GameState.Card largest = following.stream().max(rank).orElseThrow();
      return TrickEngine.beats(largest, winner.card(), lead, trump)
          ? largest : minimum(following, random);
    }
    int tableTrump = table.stream().map(TrickEngine.Play::card).filter(c -> c.suit().equals(trump))
        .mapToInt(GameState.Card::rank).max().orElse(0);
    Optional<GameState.Card> cut = hand.stream().filter(c -> c.suit().equals(trump) && c.rank() > tableTrump).min(rank);
    if (cut.isPresent()) return cut.get();
    return discard(hand, trump, random);
  }

  private static boolean cannotBeOvertaken(GameState.Card winner, List<GameState.Card> hand,
      List<TrickEngine.Play> table, String lead, String trump) {
    // Only our hand and this public trick are known. Treat every other card as a
    // possible fourth-player card, including trumps when the lead is a side suit.
    Set<GameState.Card> known = new HashSet<>(hand);
    table.stream().map(TrickEngine.Play::card).forEach(known::add);
    for (String suit : GameState.STRAINS.subList(0, 4)) {
      for (int rank = 2; rank <= 14; rank++) {
        GameState.Card candidate = new GameState.Card(suit, rank);
        if (!known.contains(candidate) && TrickEngine.beats(candidate, winner, lead, trump)) return false;
      }
    }
    return true;
  }

  private static GameState.Card discard(List<GameState.Card> hand, String trump, RandomGenerator random) {
    List<GameState.Card> nonTrumps = hand.stream().filter(c -> !c.suit().equals(trump)).toList();
    return minimum(nonTrumps.isEmpty() ? hand : nonTrumps, random);
  }

  private static GameState.Card minimum(List<GameState.Card> hand, RandomGenerator random) {
    int minimum = hand.stream().mapToInt(GameState.Card::rank).min().orElseThrow();
    List<GameState.Card> lows = hand.stream().filter(c -> c.rank() == minimum).toList();
    return lows.get(random.nextInt(lows.size()));
  }
}
