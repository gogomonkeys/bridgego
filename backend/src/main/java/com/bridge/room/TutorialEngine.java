package com.bridge.room;

import java.util.*;

/**
 * Isolated, in-memory teaching exercise. Not a room mode or persistence input.
 * Uses the production trick rules; no card is forced or exempt from follow-suit rules.
 */
final class TutorialEngine {
  static final int HUMAN_SEAT = 0;
  static final String TRUMP = "SPADE";
  enum Lesson { FOLLOW_SUIT, WIN_TRICK, TRUMP_WHEN_VOID, FREE_PLAY, COMPLETE }

  private final TrickEngine engine = new TrickEngine(deal(), 2, TRUMP);
  private int humanPlays;

  TutorialEngine() { advanceBots(); }

  Lesson lesson() {
    if (engine.finished()) return Lesson.COMPLETE;
    return switch (humanPlays) {
      case 0 -> Lesson.FOLLOW_SUIT;
      case 1 -> Lesson.WIN_TRICK;
      case 2 -> Lesson.TRUMP_WHEN_VOID;
      default -> Lesson.FREE_PLAY;
    };
  }

  List<GameState.Card> hand() { return engine.hand(HUMAN_SEAT); }
  List<GameState.Card> legalCards() { return engine.legalCards(HUMAN_SEAT); }
  List<TrickEngine.Play> currentTrick() { return engine.currentTrick(); }
  List<TrickEngine.Trick> completedTricks() { return engine.completedTricks(); }

  void play(GameState.Card card) {
    engine.play(HUMAN_SEAT, card);
    humanPlays++;
    advanceBots();
  }

  private void advanceBots() {
    while (!engine.finished() && engine.nextSeat() != HUMAN_SEAT) {
      int seat = engine.nextSeat();
      var legal = engine.legalCards(seat);
      GameState.Card chosen = null;
      if (seat == 1 && engine.currentTrick().isEmpty() && engine.trickNumber() <= 3) {
        String suit = List.of("CLUB", "HEART", "DIAMOND").get(engine.trickNumber() - 1);
        var scripted = new GameState.Card(suit, 14);
        if (legal.contains(scripted)) chosen = scripted;
      }
      if (chosen == null) chosen = legal.stream()
          .min(Comparator.comparingInt(GameState.Card::rank)
              .thenComparing(GameState.Card::suit)).orElseThrow();
      engine.play(seat, chosen, "BOT");
    }
  }

  private static Map<Integer, List<GameState.Card>> deal() {
    Map<Integer, List<GameState.Card>> hands = new HashMap<>();
    for (int seat = 0; seat < 4; seat++) hands.put(seat, new ArrayList<>());
    add(hands, 0, "CLUB", 2, 3);
    add(hands, 0, "HEART", 2, 3);
    add(hands, 0, "SPADE", 2, 10);
    add(hands, 1, "CLUB", 6, 14);
    add(hands, 1, "HEART", 14, 14);
    add(hands, 1, "DIAMOND", 14, 14);
    add(hands, 1, "DIAMOND", 2, 3);
    add(hands, 2, "CLUB", 4, 4);
    add(hands, 2, "HEART", 4, 9);
    add(hands, 2, "DIAMOND", 4, 9);
    add(hands, 3, "CLUB", 5, 5);
    add(hands, 3, "HEART", 10, 13);
    add(hands, 3, "DIAMOND", 10, 13);
    add(hands, 3, "SPADE", 11, 14);
    return hands;
  }

  private static void add(Map<Integer, List<GameState.Card>> hands, int seat,
      String suit, int first, int last) {
    for (int rank = first; rank <= last; rank++) hands.get(seat).add(new GameState.Card(suit, rank));
  }
}
