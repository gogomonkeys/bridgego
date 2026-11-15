package com.bridge.room;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TutorialEngineTest {
  private GameState.Card c(String suit, int rank) { return new GameState.Card(suit, rank); }

  @Test
  void everyGuidedLegalBranchPreservesTheLessonAndCanFinishNormally() {
    int branches = 0;
    for (int club : List.of(2, 3)) {
      for (int heart : List.of(2, 3)) {
        var preview = new TutorialEngine();
        preview.play(c("CLUB", club));
        preview.play(c("HEART", heart));
        for (var third : preview.legalCards()) {
          var e = new TutorialEngine();
          assertEquals(TutorialEngine.Lesson.FOLLOW_SUIT, e.lesson());
          assertEquals(List.of(c("CLUB", 2), c("CLUB", 3)), e.legalCards());
          e.play(c("CLUB", club));
          assertEquals(TutorialEngine.Lesson.WIN_TRICK, e.lesson());
          assertEquals(1, e.completedTricks().get(0).winnerSeat());
          assertEquals(List.of(c("HEART", 2), c("HEART", 3)), e.legalCards());
          e.play(c("HEART", heart));
          assertEquals(TutorialEngine.Lesson.TRUMP_WHEN_VOID, e.lesson());
          assertEquals(1, e.completedTricks().get(1).winnerSeat());
          assertEquals(c("DIAMOND", 14), e.currentTrick().get(0).card());
          assertTrue(e.hand().stream().noneMatch(card -> card.suit().equals("DIAMOND")));
          assertEquals(e.hand(), e.legalCards());
          e.play(third);
          assertEquals(TutorialEngine.Lesson.FREE_PLAY, e.lesson());
          assertEquals(third.suit().equals("SPADE") ? 0 : 1,
              e.completedTricks().get(2).winnerSeat());
          int remainingPlays = 0;
          while (e.lesson() != TutorialEngine.Lesson.COMPLETE) {
            assertFalse(e.legalCards().isEmpty());
            e.play(e.legalCards().get(0));
            assertTrue(++remainingPlays <= 10);
          }
          assertEquals(10, remainingPlays);
          assertEquals(13, e.completedTricks().size());
          var seen = new HashSet<GameState.Card>();
          for (var trick : e.completedTricks()) {
            assertEquals(4, trick.plays().size());
            for (var play : trick.plays()) assertTrue(seen.add(play.card()));
          }
          assertEquals(52, seen.size());
          assertTrue(e.hand().isEmpty());
          assertTrue(e.legalCards().isEmpty());
          assertThrows(IllegalStateException.class, () -> e.play(c("CLUB", 2)));
          branches++;
        }
      }
    }
    assertEquals(44, branches);
  }

  @Test
  void illegalOrRepeatedCardsDoNotAdvanceOrModifyState() {
    var e = new TutorialEngine();
    var hand = e.hand();
    var trick = e.currentTrick();
    for (var illegal : List.of(c("SPADE", 2), c("CLUB", 14), c("CLUB", 1))) {
      assertThrows(IllegalArgumentException.class, () -> e.play(illegal));
      assertEquals(hand, e.hand());
      assertEquals(trick, e.currentTrick());
      assertEquals(TutorialEngine.Lesson.FOLLOW_SUIT, e.lesson());
    }
    assertThrows(IllegalArgumentException.class, () -> e.play(null));
    e.play(c("CLUB", 2));
    assertThrows(IllegalArgumentException.class, () -> e.play(c("CLUB", 2)));
    assertEquals(TutorialEngine.Lesson.WIN_TRICK, e.lesson());
  }

  @Test
  void sessionsAndReadProjectionsAreIsolated() {
    var a = new TutorialEngine();
    var b = new TutorialEngine();
    assertThrows(UnsupportedOperationException.class, () -> a.hand().clear());
    a.play(c("CLUB", 2));
    assertEquals(13, b.hand().size());
    assertEquals(TutorialEngine.Lesson.FOLLOW_SUIT, b.lesson());
    assertTrue(b.completedTricks().isEmpty());
    assertEquals(12, a.hand().size());
  }
}
