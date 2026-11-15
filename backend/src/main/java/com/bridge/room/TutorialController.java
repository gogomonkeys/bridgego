package com.bridge.room;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tutorial")
public final class TutorialController {
  private final RoomService rooms;
  private final TutorialService tutorials;
  private final Set<String> origins;
  private IdentityStore store;
  private final boolean enabled;
  public record Status(boolean enabled, boolean signedIn, boolean offered, TutorialService.View game) {}
  public record Choice(Boolean start) {}
  public record PlayRequest(String sessionId, Long expectedRevision, GameState.Card card) {}
  public record ExitRequest(String sessionId) {}
  public record State(TutorialService.View game) {}
  public TutorialController(RoomService rooms, TutorialService tutorials, CollectionPolicy policy,
      @Value("${PUBLIC_ORIGIN:}") String publicOrigin, Environment env) {
    this.rooms = rooms; this.tutorials = tutorials;
    String flag = env.getProperty("BRIDGE_TUTORIAL_ENABLED", "true");
    if (!Set.of("true", "false").contains(flag))
      throw new IllegalArgumentException("BRIDGE_TUTORIAL_ENABLED must be true or false");
    enabled = Boolean.parseBoolean(flag);
    origins = Set.of(AllowedOrigins.forProfile(Arrays.asList(env.getActiveProfiles()).contains("prod")
        || policy.managed() || policy.experiment(), publicOrigin));
  }
  @Autowired
  public TutorialController(RoomService rooms, TutorialService tutorials, CollectionPolicy policy,
      @Value("${PUBLIC_ORIGIN:}") String publicOrigin, Environment env, ObjectProvider<IdentityStore> stores) {
    this(rooms, tutorials, policy, publicOrigin, env);
    store = stores.getIfAvailable();
  }
  private RoomService.Identity identity(String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer "))
      throw RoomService.fail(401, "UNAUTHORIZED", "Bearer session required");
    var who = rooms.authenticate(bearer.substring(7));
    rooms.requireOutsideRoom(who);
    return who;
  }
  private String ownerKey(RoomService.Identity who) {
    String account = rooms.accountId(who);
    return who.playerId() + ":" + (account == null ? "guest" : account);
  }
  private String owner(String bearer) { return ownerKey(identity(bearer)); }
  private void origin(String origin) {
    if (origin == null || !origins.contains(origin))
      throw RoomService.fail(403, "AUTH_ORIGIN", "Allowed origin required");
  }
  private void unchanged(RoomService.Identity who, String account) {
    rooms.requireOutsideRoom(who);
    if (!Objects.equals(account, rooms.accountId(who)))
      throw RoomService.fail(409, "TUTORIAL_IDENTITY_CHANGED", "Identity changed; reload");
  }
  private void requireEnabled() {
    if (!enabled) throw RoomService.fail(503, "TUTORIAL_DISABLED", "New tutorials are temporarily disabled");
  }
  private boolean offered(String account) {
    if (account == null) return false;
    try {
      if (store == null || !store.enabled()) throw new IllegalStateException();
      return store.tutorialOffered(account);
    } catch (RuntimeException unavailable) {
      throw RoomService.fail(503, "TUTORIAL_PREFERENCE_UNAVAILABLE", "Tutorial preference could not be confirmed");
    }
  }
  @GetMapping("/status") public Status status(
      @RequestHeader(value="Authorization", required=false) String bearer) {
    var who = identity(bearer);
    String account = rooms.accountId(who);
    boolean acknowledged = enabled && offered(account);
    synchronized (rooms) {
      unchanged(who, account);
      return new Status(enabled, account != null, acknowledged, tutorials.current(ownerKey(who)));
    }
  }
  @PostMapping("/choice") public Status choice(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin, @RequestBody Choice request) {
    origin(origin);
    if (request == null || request.start() == null)
      throw RoomService.fail(400, "TUTORIAL_CHOICE_REQUIRED", "Choose start or skip");
    var who = identity(bearer);
    requireEnabled();
    String account = rooms.accountId(who);
    if (account != null) {
      try {
        if (store == null || !store.enabled()) throw new IllegalStateException();
        store.markTutorialOffered(account);
      } catch (RuntimeException unavailable) {
        throw RoomService.fail(503, "TUTORIAL_PREFERENCE_UNVERIFIED", "Read status before retrying");
      }
    }
    synchronized (rooms) {
      unchanged(who, account);
      var game = request.start() ? tutorials.start(ownerKey(who)) : tutorials.current(ownerKey(who));
      return new Status(enabled, account != null, account != null, game);
    }
  }
  @PostMapping public TutorialService.View start(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin) {
    origin(origin);
    synchronized (rooms) {
      String owner = owner(bearer);
      requireEnabled();
      return tutorials.start(owner);
    }
  }
  @GetMapping("/state") public State state(
      @RequestHeader(value="Authorization", required=false) String bearer) {
    synchronized (rooms) { return new State(tutorials.current(owner(bearer))); }
  }
  @GetMapping public TutorialService.View view(
      @RequestHeader(value="Authorization", required=false) String bearer) {
    synchronized (rooms) { return tutorials.view(owner(bearer)); }
  }
  @PostMapping("/play") public TutorialService.View play(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin, @RequestBody PlayRequest request) {
    origin(origin);
    synchronized (rooms) { return tutorials.play(owner(bearer), request.sessionId(), request.expectedRevision(), request.card()); }
  }
  @PostMapping("/exit") public Map<String, Boolean> exit(
      @RequestHeader(value="Authorization", required=false) String bearer,
      @RequestHeader(value="Origin", required=false) String origin, @RequestBody ExitRequest request) {
    origin(origin);
    synchronized (rooms) { tutorials.exit(owner(bearer), request.sessionId()); }
    return Map.of("exited", true);
  }
  @ExceptionHandler(RoomService.Failure.class) public ResponseEntity<?> failure(RoomService.Failure e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }
  @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> malformed() {
    return ResponseEntity.badRequest().body(Map.of("code", "INVALID_JSON", "message", "Invalid request body"));
  }
}
