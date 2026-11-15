package com.bridge.room;

import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

/** Ephemeral exercises, deliberately disconnected from match archives/statistics writers. */
@Service
public final class TutorialService {
  public record View(String sessionId, long revision, TutorialEngine.Lesson lesson,
      String trump, int humanSeat, List<GameState.Card> hand, List<GameState.Card> legalCards,
      List<TrickEngine.Play> currentTrick, List<TrickEngine.Trick> completedTricks) {}
  private static final class Session {
    final String id = UUID.randomUUID().toString();
    final TutorialEngine engine = new TutorialEngine();
    long revision, touched;
  }
  private final Map<String, Session> sessions = new HashMap<>();
  private final LongSupplier clock;
  public TutorialService() { this(() -> System.nanoTime() / 1_000_000); }
  TutorialService(LongSupplier clock) { this.clock = clock; }
  private void expire() { sessions.values().removeIf(s -> clock.getAsLong() - s.touched > 3_600_000); }
  synchronized View start(String owner) {
    expire();
    if (!sessions.containsKey(owner)) {
      if (sessions.size() >= 128) throw RoomService.fail(429, "TUTORIAL_CAPACITY", "Try again later");
      Session created = new Session();
      created.touched = clock.getAsLong();
      sessions.put(owner, created);
    }
    return view(owner);
  }
  synchronized View current(String owner) {
    expire();
    return sessions.containsKey(owner) ? view(owner) : null;
  }
  synchronized View view(String owner) {
    expire();
    Session s = sessions.get(owner);
    if (s == null) throw RoomService.fail(404, "TUTORIAL_NOT_FOUND", "Start a new tutorial");
    s.touched = clock.getAsLong();
    return new View(s.id, s.revision, s.engine.lesson(), TutorialEngine.TRUMP,
        TutorialEngine.HUMAN_SEAT, s.engine.hand(), s.engine.legalCards(),
        s.engine.currentTrick(), s.engine.completedTricks());
  }
  synchronized View play(String owner, String id, Long revision, GameState.Card card) {
    View before = view(owner);
    if (!before.sessionId().equals(id) || revision == null || before.revision() != revision)
      throw RoomService.fail(409, "TUTORIAL_STALE", "Reload the tutorial before playing");
    Session s = sessions.get(owner);
    try { s.engine.play(card); }
    catch (IllegalArgumentException | IllegalStateException invalid) {
      throw RoomService.fail(409, "TUTORIAL_ILLEGAL_PLAY", "Choose a legal card");
    }
    s.revision++;
    return view(owner);
  }
  synchronized void exit(String owner, String id) {
    expire();
    Session s = sessions.get(owner);
    if (s == null) return;
    if (!s.id.equals(id)) throw RoomService.fail(409, "TUTORIAL_STALE", "Tutorial session changed");
    sessions.remove(owner);
  }
}
