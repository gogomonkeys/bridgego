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

  static GameState.LegalCall call(int seat, String target, GameState.Bid highest) {
    if (highest != null && highest.seat() % 2 == seat % 2)
      return new GameState.LegalCall("PASS", null, null);
    int current = highest == null ? -1 : (highest.level() - 1) * 5 + GameState.STRAINS.indexOf(highest.strain());
    for (int level = 1; level <= 2; level++)
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
    if (table.size() == 3 && winner.seat() % 2 == seat % 2) {
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
