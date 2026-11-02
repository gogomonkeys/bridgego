package com.bridge.room;

import java.util.*;

/** Pure authoritative trick rules; not wired to rooms until the v0.3 lifecycle is approved. */
final class TrickEngine {
  record Play(int seat, GameState.Card card, String source) {}

  record Trick(int number, int leaderSeat, List<Play> plays, int winnerSeat) {}

  private static final List<String> SUITS = List.of("CLUB", "DIAMOND", "HEART", "SPADE");
  private static final Comparator<GameState.Card> LOWEST =
      Comparator.comparingInt(GameState.Card::rank)
          .thenComparingInt(card -> SUITS.indexOf(card.suit()));
  private final Map<Integer, List<GameState.Card>> hands = new HashMap<>();
  private final List<Trick> completed = new ArrayList<>();
  private final List<Play> current = new ArrayList<>();
  private final String trump;
  private int leader;
  private int next;

  TrickEngine(Map<Integer, List<GameState.Card>> dealtHands, int lastBidder, String strain) {
    if (lastBidder < 0 || lastBidder > 3 || strain == null || !GameState.STRAINS.contains(strain))
      throw new IllegalArgumentException("Invalid contract");
    if (dealtHands.size() != 4) throw new IllegalArgumentException("Four hands required");
    Set<GameState.Card> unique = new HashSet<>();
    for (int seat = 0; seat < 4; seat++) {
      List<GameState.Card> cards = dealtHands.get(seat);
      if (cards == null || cards.size() != 13)
        throw new IllegalArgumentException("Thirteen cards required");
      for (var card : cards) {
        if (card == null
            || card.suit() == null
            || !SUITS.contains(card.suit())
            || card.rank() < 2
            || card.rank() > 14
            || !unique.add(card)) throw new IllegalArgumentException("Invalid or duplicate card");
      }
      hands.put(seat, new ArrayList<>(cards));
    }
    trump = strain.equals("NO_TRUMP") ? null : strain;
    leader = next = (lastBidder + 1) % 4;
  }

  List<GameState.Card> hand(int seat) {
    if (!hands.containsKey(seat)) throw new IllegalArgumentException("Invalid seat");
    return List.copyOf(hands.get(seat));
  }

  List<GameState.Card> legalCards(int seat) {
    if (finished() || seat != next) return List.of();
    List<GameState.Card> cards = hands.get(seat);
    if (!current.isEmpty()) {
      String led = current.get(0).card().suit();
      List<GameState.Card> matching = cards.stream().filter(c -> c.suit().equals(led)).toList();
      if (!matching.isEmpty()) return matching;
    }
    return List.copyOf(cards);
  }

  void play(int seat, GameState.Card card) {
    play(seat, card, "MANUAL");
  }

  void timeout() {
    if (finished()) throw new IllegalStateException("Deal complete");
    play(next, legalCards(next).stream().min(LOWEST).orElseThrow(), "TIMEOUT");
  }

  private void play(int seat, GameState.Card card, String source) {
    if (finished()) throw new IllegalStateException("Deal complete");
    if (seat != next) throw new IllegalArgumentException("Not your turn");
    if (card == null || !legalCards(seat).contains(card))
      throw new IllegalArgumentException("Illegal card");
    hands.get(seat).remove(card);
    current.add(new Play(seat, card, source));
    if (current.size() < 4) {
      next = (seat + 1) % 4;
      return;
    }
    String led = current.get(0).card().suit();
    Play winner = current.get(0);
    for (Play candidate : current) {
      int a = category(candidate.card(), led), b = category(winner.card(), led);
      if (a > b || (a == b && candidate.card().rank() > winner.card().rank())) winner = candidate;
    }
    completed.add(new Trick(completed.size() + 1, leader, List.copyOf(current), winner.seat()));
    current.clear();
    leader = next = winner.seat();
  }

  private int category(GameState.Card card, String led) {
    return card.suit().equals(trump) ? 2 : card.suit().equals(led) ? 1 : 0;
  }

  boolean finished() {
    return completed.size() == 13;
  }

  Integer nextSeat() {
    return finished() ? null : next;
  }

  int leaderSeat() {
    return leader;
  }

  int trickNumber() {
    return Math.min(13, completed.size() + 1);
  }

  List<Play> currentTrick() {
    return List.copyOf(current);
  }

  List<Trick> completedTricks() {
    return List.copyOf(completed);
  }

  Map<String, Integer> tricksByTeam() {
    int ns = (int) completed.stream().filter(t -> t.winnerSeat() % 2 == 0).count();
    return Map.of("NS", ns, "EW", completed.size() - ns);
  }
}
