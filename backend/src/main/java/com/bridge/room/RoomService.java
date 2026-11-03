package com.bridge.room;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * All state transitions (including presence and manual departure) share one lock. In-memory
 * single-node MVP: no claim of distributed atomicity or durable sessions.
 */
@Service
public class RoomService {
  public static final long INITIAL_SUBSCRIPTION_MS = 5_000;
  public static final long GAME_RECONNECT_MS = 10_000;
  static final long COMPUTER_ACTION_MS = 900;

  public record Identity(String token, String playerId, String displayName) {}

  public record Me(String playerId, String displayName, String roomId) {}

  public record PlayerView(
      String playerId,
      String displayName,
      int seat,
      boolean ready,
      boolean connected,
      Long disconnectedAt,
      Long reconnectDeadline,
      Long initialSubscriptionDeadline,
      String teamId, String playerType) {}

  public record Snapshot(
      String roomId,
      long revision,
      String phase,
      int capacity,
      boolean allReady,
      List<PlayerView> players,
      String hostPlayerId,
      boolean canStart,
      boolean canRematch,
      GameState.MatchArchive lastMatch,
      GameState.View game,
      You you) {}

  public record You(
      String playerId,
      int seat,
      String teamId,
      List<GameState.Card> ownHand,
      List<GameState.LegalCall> legalCalls,
      List<GameState.Card> legalCards,
      boolean canAcknowledgeResult) {}

  public record CommandResult(
      String actionId, String outcome, long appliedRevision, Snapshot room) {}

  private record AcceptedAction(String signature, long revision) {}

  public record Summary(
      String roomId, int playerCount, int capacity, String state, String phase, boolean joinable) {}

  public static class Failure extends RuntimeException {
    public final int status;
    public final String code;

    public Failure(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  static class Member {
    Identity identity;
    int seat;
    boolean ready;
    boolean computer;
    boolean present() { return computer || !connections.isEmpty(); }
    Long disconnectedAt;
    Long initialSubscriptionDeadline;
    Long reconnectDeadline;
    Long reconnectDueTick;
    final Set<String> connections = new HashSet<>();

    Member(Identity id, int seat, long now) {
      identity = id;
      this.seat = seat;
      disconnectedAt = now;
      initialSubscriptionDeadline = now + INITIAL_SUBSCRIPTION_MS;
    }
  }

  static class Room {
    String id;
    long revision;
    String hostPlayerId;
    GameState game;
    GameState.MatchArchive lastMatch;
    Long allOfflineSince;
    String computerActionKey;
    long computerActionDue;
    String computerTargetDeal;
    final Map<Integer, String> computerTargets = new HashMap<>();
    final Map<String, AcceptedAction> actions = new HashMap<>();
    // Captured once at first network failure. Later disconnects cannot extend adjudication.
    final Map<String, Long> adjudicationDeadlines = new LinkedHashMap<>();
    final Set<String> failedTeams = new TreeSet<>();
    Map<String, Member> members = new LinkedHashMap<>();

    Room(String id) {
      this.id = id;
    }
  }

  private final Map<String, Identity> sessions = new HashMap<>();
  private final Map<String, Long> sessionLastSeen = new HashMap<>();
  private final BetaLimits limits;
  private final String instanceEpoch = UUID.randomUUID().toString();
  private boolean stopping;
  private final Map<String, Room> rooms = new LinkedHashMap<>();
  private final Map<String, String> membership = new HashMap<>();
  private final Clock clock;
  private final java.util.function.LongSupplier ticker;
  private Consumer<Snapshot> publisher = s -> {};
  private final SecureRandom random = new SecureRandom();

  public RoomService() {
    this(Clock.systemUTC(), () -> System.nanoTime() / 1_000_000, BetaLimits.defaults());
  }

  @Autowired
  public RoomService(BetaLimits limits) {
    this(Clock.systemUTC(), () -> System.nanoTime() / 1_000_000, limits);
  }

  RoomService(Clock clock) {
    this(clock, clock::millis);
  }

  RoomService(Clock clock, java.util.function.LongSupplier ticker) {
    this(clock, ticker, BetaLimits.defaults());
  }

  RoomService(Clock clock, java.util.function.LongSupplier ticker, BetaLimits limits) {
    this.clock = clock;
    this.ticker = ticker;
    this.limits = limits;
  }

  public synchronized void publisher(Consumer<Snapshot> publisher) {
    this.publisher = publisher;
  }

  public synchronized Identity createSession(String name) {
    requireRunning();
    expireInitialSubscriptions();
    if (sessions.size() >= limits.maxSessions())
      throw fail(503, "SESSION_CAPACITY", "Guest capacity reached; try again later");
    String validatedName = Nickname.validate(name);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    Identity id =
        new Identity(
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            UUID.randomUUID().toString(),
            validatedName);
    sessions.put(id.token(), id);
    sessionLastSeen.put(id.token(), ticker.getAsLong());
    return id;
  }

  public synchronized Identity authenticate(String token) {
    expireInitialSubscriptions();
    Identity id = sessions.get(token);
    if (id == null) throw fail(401, "UNAUTHORIZED", "Guest session is missing or expired");
    sessionLastSeen.put(token, ticker.getAsLong());
    return id;
  }

  public synchronized Me me(Identity id) {
    expireInitialSubscriptions();
    return new Me(id.playerId(), id.displayName(), membership.get(id.playerId()));
  }

  public synchronized List<Summary> list() {
    expireInitialSubscriptions();
    return rooms.values().stream()
        .map(
            r ->
                new Summary(
                    r.id,
                    r.members.size(),
                    4,
                    r.game == null ? "WAITING" : r.game.active() ? "ACTIVE" : "FINISHED",
                    r.game == null ? "WAITING" : r.game.phase,
                    r.game == null && r.members.size() < 4))
        .toList();
  }

  public synchronized Snapshot create(Identity id) {
    requireRunning();
    expireInitialSubscriptions();
    ensureFree(id);
    if (rooms.size() >= limits.maxRooms())
      throw fail(409, "ROOM_LIMIT_REACHED", "The beta table limit has been reached");
    Room room = new Room(UUID.randomUUID().toString());
    room.hostPlayerId = id.playerId();
    rooms.put(room.id, room);
    return add(room, id);
  }

  public synchronized Snapshot join(String roomId, Identity id) {
    requireRunning();
    expireInitialSubscriptions();
    Room room = room(roomId);
    if (room.members.containsKey(id.playerId())) return snapshot(room, id);
    if (room.game != null)
      throw fail(409, "GAME_IN_PROGRESS", "No replacement players after a game starts");
    ensureFree(id);
    if (room.members.size() == 4) throw fail(409, "ROOM_FULL", "Room already has four players");
    return add(room, id);
  }

  private Snapshot add(Room room, Identity id) {
    int seat = 0;
    Set<Integer> occupied = new HashSet<>();
    room.members.values().forEach(m -> occupied.add(m.seat));
    while (occupied.contains(seat)) seat++;
    room.members.put(id.playerId(), new Member(id, seat, clock.millis()));
    membership.put(id.playerId(), room.id);
    changed(room);
    return snapshot(room, id);
  }

  public synchronized Snapshot get(String roomId, Identity id) {
    expireInitialSubscriptions();
    Room room = room(roomId);
    member(room, id);
    return snapshot(room, id);
  }

  public synchronized CommandResult addComputer(String roomId, Identity id, String actionId,
      Long expected, Integer seat) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    member(r, id);
    String signature = "ADD_COMPUTER:" + seat;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    computerHost(r, id);
    if (seat == null || seat < 0 || seat > 3)
      throw fail(400, "INVALID_SEAT", "Seat must be between 0 and 3");
    if (r.members.values().stream().anyMatch(m -> m.seat == seat))
      throw fail(409, "SEAT_OCCUPIED", "Seat is occupied");
    Identity bot = new Identity(null, "bot-" + UUID.randomUUID(), "電腦" + List.of("一", "二", "三", "四").get(seat));
    Member m = new Member(bot, seat, clock.millis());
    m.computer = true;
    m.ready = true;
    m.disconnectedAt = null;
    m.initialSubscriptionDeadline = null;
    r.members.put(bot.playerId(), m);
    return applied(r, id, actionId, signature);
  }

  public synchronized CommandResult removeComputer(String roomId, Identity id, String playerId,
      String actionId, Long expected) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    member(r, id);
    String signature = "REMOVE_COMPUTER:" + playerId;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    computerHost(r, id);
    Member bot = r.members.get(playerId);
    if (bot == null || !bot.computer)
      throw fail(409, "NOT_COMPUTER_PLAYER", "Only a computer player can be removed");
    r.members.remove(playerId);
    return applied(r, id, actionId, signature);
  }

  private void computerHost(Room r, Identity id) {
    if (!Objects.equals(r.hostPlayerId, id.playerId()))
      throw fail(403, "HOST_ONLY", "Only the host can manage computer players");
    if (r.game != null) throw fail(409, "WRONG_PHASE", "Computer seats are locked after starting");
    if (member(r, id).connections.isEmpty())
      throw fail(409, "HOST_OFFLINE", "Host must connect before managing computer players");
  }

  public synchronized Snapshot ready(String roomId, Identity id, boolean ready) {
    requireRunning();
    expireInitialSubscriptions();
    Room room = room(roomId);
    Member m = member(room, id);
    if (room.game != null)
      throw fail(409, "WRONG_PHASE", "Ready is only available in the waiting room");
    if (m.ready == ready) return snapshot(room, id);
    m.ready = ready;
    changed(room);
    return snapshot(room, id);
  }

  public synchronized void leave(String roomId, Identity id) {
    requireRunning();
    expireInitialSubscriptions();
    String current = membership.get(id.playerId());
    if (current == null) return;
    if (!current.equals(roomId)) throw fail(403, "NOT_MEMBER", "Not a member of this room");
    Room room = room(roomId);
    if (room.game != null && room.game.active()) {
      int departingSeat = member(room, id).seat;
      room.game.recordForfeit(id.playerId(), departingSeat, "MANUAL_EXIT");
      String team = GameState.team(departingSeat);
      if (room.game.phase.equals("FORFEIT_PENDING")) {
        room.failedTeams.add(team);
        room.adjudicationDeadlines.remove(id.playerId());
        advanceAdjudication(room, ticker.getAsLong());
      } else {
        room.failedTeams.add(team);
        finishForfeit(room, "MANUAL_EXIT");
      }
    }
    remove(room, id.playerId());
  }

  // Only the initial HTTP-to-WebSocket handshake reserves a provisional seat for five seconds.
  public synchronized Snapshot connect(String roomId, Identity id, String connection) {
    requireRunning();
    expireInitialSubscriptions();
    Room room = room(roomId);
    Member m = member(room, id);
    if (!m.connections.contains(connection) && m.connections.size() >= limits.maxSocketsPerSession())
      throw fail(429, "CONNECTION_LIMIT", "Too many connections for this guest");
    boolean wasDisconnected = m.connections.isEmpty();
    m.connections.add(connection);
    room.allOfflineSince = null;
    m.disconnectedAt = null;
    m.initialSubscriptionDeadline = null;
    m.reconnectDeadline = null;
    m.reconnectDueTick = null;
    if (room.game != null && room.game.phase.equals("FORFEIT_PENDING"))
      advanceAdjudication(room, ticker.getAsLong());
    if (room.game != null && !paused(room)) room.game.resumeTimer();
    if (wasDisconnected) changed(room);
    return snapshot(room, id);
  }

  public synchronized void disconnect(String roomId, String playerId, String connection) {
    if (stopping) return;
    expireInitialSubscriptions();
    Room r = rooms.get(roomId);
    if (r == null) return;
    Member m = r.members.get(playerId);
    // Unknown/old connection IDs cannot mark a resumed player offline; another tab keeps presence.
    if (m == null || !m.connections.remove(connection) || !m.connections.isEmpty()) return;
    if (r.game != null && r.game.active()) {
      r.game.pauseTimer();
      m.disconnectedAt = clock.millis();
      if (r.game.phase.equals("FORFEIT_PENDING")) {
        // Newly lost connections during adjudication cannot acquire a fresh grace period.
        m.reconnectDeadline = null;
        m.reconnectDueTick = null;
      } else {
        m.reconnectDeadline = clock.millis() + GAME_RECONNECT_MS;
        m.reconnectDueTick = ticker.getAsLong() + GAME_RECONNECT_MS;
      }
      changed(r);
    } else if (r.game != null && r.game.terminal()) {
      // Finished participants may reconnect to inspect the retained result; only explicit leave
      // releases this membership. No reconnect deadline can rewrite the terminal outcome.
      m.disconnectedAt = clock.millis();
      m.reconnectDeadline = null;
      m.reconnectDueTick = null;
      changed(r);
    } else remove(r, playerId);
  }

  public synchronized boolean hasConnection(String roomId, String playerId, String connection) {
    Room r = rooms.get(roomId);
    return r != null
        && r.members.containsKey(playerId)
        && r.members.get(playerId).connections.contains(connection);
  }

  // One authority sweep handles initial subscriptions (5s), active reconnect forfeits (10s),
  // and operation budgets (20s). WAITING disconnects still release immediately.
  @Scheduled(fixedDelay = 100)
  public synchronized void expireInitialSubscriptions() {
    if (stopping) return;
    long now = clock.millis();
    long monotonicNow = ticker.getAsLong();
    for (Room r : new ArrayList<>(rooms.values())) {
      if (r.game != null && r.game.active()) {
        if (r.game.phase.equals("FORFEIT_PENDING")) {
          advanceAdjudication(r, monotonicNow);
        } else {
          Set<String> expired = new TreeSet<>();
          for (Member m : r.members.values()) {
            if (m.reconnectDueTick != null && monotonicNow >= m.reconnectDueTick) {
              r.game.recordForfeit(m.identity.playerId(), m.seat, "RECONNECT_TIMEOUT");
              expired.add(GameState.team(m.seat));
            }
          }
          if (!expired.isEmpty()) {
            r.failedTeams.addAll(expired);
            for (Member m : r.members.values())
              if (m.connections.isEmpty()
                  && m.reconnectDueTick != null
                  && !r.failedTeams.contains(GameState.team(m.seat)))
                r.adjudicationDeadlines.put(m.identity.playerId(), m.reconnectDueTick);
            advanceAdjudication(r, monotonicNow);
          }
        }
      }
      boolean computerMoved = advanceComputer(r, monotonicNow);
      if (r.game != null && !paused(r)
          && (!computerMoved || r.game.phase.equals("PRIVILEGE_DECISION"))
          && r.game.expireOperation()) changed(r);
      for (Member m : new ArrayList<>(r.members.values())) {
        if (m.initialSubscriptionDeadline != null && now >= m.initialSubscriptionDeadline) {
          remove(r, m.identity.playerId());
        }
      }
    }
    cleanupIdle(monotonicNow);
  }

  // One bounded action per room/sweep. No executor, socket, guest session or recursive loop.
  private boolean advanceComputer(Room r, long now) {
    if (r.game == null || !r.game.active() || paused(r)) {
      r.computerActionKey = null;
      return false;
    }
    GameState g = r.game;
    Member actor = null;
    for (Member m : r.members.values()) {
      if (!m.computer) continue;
      GameState.View view = g.view(m.seat, false);
      if ((g.phase.equals("PRIVILEGE_DECISION") && view.privilege().canChoose())
          || (g.phase.equals("BIDDING") && Objects.equals(view.auction().nextSeat(), m.seat))
          || (g.phase.equals("PLAYING") && Objects.equals(view.play().nextSeat(), m.seat))
          || (g.phase.equals("DEAL_RESULT") && g.canAcknowledge(m.seat, false))) {
        actor = m;
        break;
      }
    }
    if (actor == null) { r.computerActionKey = null; return false; }
    GameState.PlayView publicPlay = g.view(null, false).play();
    String key = g.id + ":" + g.dealId + ":" + g.phase + ":" + actor.seat + ":"
        + g.calls.size() + ":" + (publicPlay == null ? "" : publicPlay.trickNumber() + ":" + publicPlay.currentTrick().size());
    // The key identifies a turn without serializing private hands or completed trick history.
    if (!key.equals(r.computerActionKey)) {
      r.computerActionKey = key;
      var timer = g.view(null, false).operationTimer();
      r.computerActionDue = now + (timer == null ? COMPUTER_ACTION_MS : Math.min(COMPUTER_ACTION_MS, timer.remainingMs()));
    }
    if (now < r.computerActionDue) return false;
    int seat = actor.seat;
    if (g.phase.equals("PRIVILEGE_DECISION")) g.choose(seat, false, "BOT");
    else if (g.phase.equals("BIDDING")) {
      if (!g.dealId.equals(r.computerTargetDeal)) {
        r.computerTargetDeal = g.dealId;
        r.computerTargets.clear();
      }
      String target = r.computerTargets.computeIfAbsent(seat,
          ignored -> ComputerStrategy.longestSuit(g.hands.get(seat), random));
      GameState.LegalCall call = ComputerStrategy.call(seat, target, g.highest);
      g.call(seat, call.type(), call.level(), call.strain(), "BOT");
    } else if (g.phase.equals("PLAYING")) {
      var play = g.view(null, false).play();
      g.play(seat, ComputerStrategy.play(seat, g.hands.get(seat), play.currentTrick(), g.contract.strain(), random), "BOT");
    } else g.acknowledge(seat);
    r.computerActionKey = null;
    changed(r);
    return true;
  }

  private void cleanupIdle(long now) {
    for (Room r : new ArrayList<>(rooms.values())) {
      boolean offlineTerminal = r.game != null && r.game.terminal()
          && r.members.values().stream().filter(m -> !m.computer).allMatch(m -> m.connections.isEmpty());
      if (!offlineTerminal) r.allOfflineSince = null;
      else if (r.allOfflineSince == null) r.allOfflineSince = now;
      else if (now - r.allOfflineSince >= limits.finishedRoomIdleMs()) {
        for (Member m : new ArrayList<>(r.members.values())) remove(r, m.identity.playerId());
      }
    }
    for (var entry : new ArrayList<>(sessions.entrySet())) {
      String token = entry.getKey();
      // Any seated guest stays valid, including fully automatic play and reconnect grace.
      if (membership.containsKey(entry.getValue().playerId())) {
        sessionLastSeen.put(token, now);
      } else if (now - sessionLastSeen.getOrDefault(token, now) >= limits.sessionIdleMs()) {
        sessions.remove(token);
        sessionLastSeen.remove(token);
      }
    }
  }

  public String instanceEpoch() { return instanceEpoch; }
  public synchronized boolean stopping() { return stopping; }
  BetaLimits limits() { return limits; }

  public synchronized void beginMaintenance() {
    if (stopping) return;
    stopping = true;
    for (Room r : rooms.values()) if (r.game != null) r.game.pauseTimer();
  }

  private void requireRunning() {
    if (stopping) throw fail(503, "SERVER_MAINTENANCE", "Server is stopping; reconnect later");
  }

  // A failed team cannot erase failure by returning late. Only the opponent's already-running
  // grace can delay the final decision; newly disconnected players never start another window.
  private void advanceAdjudication(Room r, long now) {
    if (r.failedTeams.isEmpty() || !r.game.active()) return;
    boolean updated = !r.game.phase.equals("FORFEIT_PENDING");
    for (var entry : new ArrayList<>(r.adjudicationDeadlines.entrySet())) {
      Member m = r.members.get(entry.getKey());
      if (m == null || !m.connections.isEmpty()) {
        r.adjudicationDeadlines.remove(entry.getKey());
        updated = true;
      } else if (now >= entry.getValue()) {
        r.game.recordForfeit(m.identity.playerId(), m.seat, "RECONNECT_TIMEOUT");
        r.failedTeams.add(GameState.team(m.seat));
        r.adjudicationDeadlines.remove(entry.getKey());
        updated = true;
      }
    }
    if (r.failedTeams.size() == 2 || r.adjudicationDeadlines.isEmpty()) {
      finishForfeit(r, r.failedTeams.size() == 2 ? "BOTH_TEAMS_FAILED" : "RECONNECT_TIMEOUT");
      changed(r);
    } else if (updated) {
      List<Integer> waiting =
          r.adjudicationDeadlines.keySet().stream()
              .map(r.members::get)
              .filter(Objects::nonNull)
              .map(m -> m.seat)
              .sorted()
              .toList();
      r.game.setPendingForfeit(r.failedTeams, waiting);
      changed(r);
    }
  }

  private void finishForfeit(Room r, String reason) {
    r.game.finishForfeit(reason, r.failedTeams);
    r.adjudicationDeadlines.clear();
    clearGameDeadlines(r);
  }

  private void remove(Room r, String playerId) {
    r.members.remove(playerId);
    membership.remove(playerId);
    if (r.members.values().stream().noneMatch(m -> !m.computer)) {
      r.members.clear();
      rooms.remove(r.id);
      changed(r);
      return;
    }
    if (r.game == null && Objects.equals(r.hostPlayerId, playerId))
      r.hostPlayerId =
          r.members.values().stream()
              .filter(m -> !m.computer)
              .min(Comparator.comparingInt(m -> m.seat))
              .map(m -> m.identity.playerId())
              .orElse(null);
    changed(r);
    if (r.members.isEmpty()) rooms.remove(r.id);
  }

  private void ensureFree(Identity id) {
    if (membership.containsKey(id.playerId()))
      throw fail(409, "ALREADY_IN_ROOM", "Leave your current room first");
  }

  private Room room(String id) {
    Room r = rooms.get(id);
    if (r == null) throw fail(404, "ROOM_NOT_FOUND", "Room does not exist");
    return r;
  }

  private Member member(Room r, Identity id) {
    Member m = r.members.get(id.playerId());
    if (m == null || m.computer) throw fail(403, "NOT_MEMBER", "Not a human member of this room");
    return m;
  }

  // Publish immutable full snapshots while the transition lock is held, so room revisions
  // identify committed state. The transport filters subscribers by room and current membership.
  private Snapshot changed(Room r) {
    if (r.game != null && r.game.terminal()) {
      // Refresh the safe projection when the final two-second display unlocks review.
      r.lastMatch = r.game.archive();
      electTerminalHost(r);
    }
    r.revision++;
    Snapshot s = snapshot(r, null);
    publisher.accept(s);
    return s;
  }

  private void electTerminalHost(Room r) {
    Member host = r.members.get(r.hostPlayerId);
    if (host == null || host.connections.isEmpty())
      r.hostPlayerId =
          r.members.values().stream()
              .filter(m -> !m.computer && !m.connections.isEmpty())
              .min(Comparator.comparingInt(m -> m.seat))
              .map(m -> m.identity.playerId())
              .orElse(null);
  }

  private boolean archiveParticipant(Room r, Identity viewer) {
    return viewer != null
        && r.lastMatch != null
        && r.lastMatch.roster().stream().anyMatch(p -> p.playerId().equals(viewer.playerId()));
  }

  private boolean canRematch(Room r, Identity viewer) {
    return viewer != null
        && r.game != null
        && r.game.phase.equals("MATCH_RESULT")
        && Objects.equals(r.hostPlayerId, viewer.playerId())
        && r.members.containsKey(viewer.playerId())
        && !r.members.get(viewer.playerId()).connections.isEmpty();
  }

  private Snapshot snapshot(Room r, Identity viewer) {
    List<PlayerView> players =
        r.members.values().stream()
            .sorted(Comparator.comparingInt(m -> m.seat))
            .map(
                m ->
                    new PlayerView(
                        m.identity.playerId(),
                        m.identity.displayName(),
                        m.seat,
                        m.ready,
                        m.present(),
                        m.disconnectedAt,
                        m.reconnectDeadline,
                        m.initialSubscriptionDeadline,
                        GameState.team(m.seat), m.computer ? "BOT" : "HUMAN"))
            .toList();
    return new Snapshot(
        r.id,
        r.revision,
        r.game == null ? "WAITING" : r.game.phase,
        4,
        players.size() == 4 && players.stream().allMatch(p -> p.ready() && p.connected()),
        players,
        r.hostPlayerId,
        viewer != null
            && Objects.equals(viewer.playerId(), r.hostPlayerId)
            && r.game == null
            && players.size() == 4
            && players.stream().allMatch(p -> p.ready() && p.connected()),
        canRematch(r, viewer),
        archiveParticipant(r, viewer) ? r.lastMatch : null,
        r.game == null
            ? null
            : r.game.view(viewer == null ? null : member(r, viewer).seat, paused(r)),
        viewer == null ? null : you(r, member(r, viewer)));
  }

  private boolean paused(Room r) {
    return r.game != null
        && r.game.active()
        && (r.game.phase.equals("FORFEIT_PENDING")
            || r.members.values().stream().anyMatch(m -> !m.present()));
  }

  private You you(Room r, Member m) {
    return new You(
        m.identity.playerId(),
        m.seat,
        GameState.team(m.seat),
        r.game == null ? List.of() : r.game.hands.getOrDefault(m.seat, List.of()),
        r.game == null ? List.of() : r.game.legalCalls(m.seat, paused(r)),
        r.game == null ? List.of() : r.game.legalCards(m.seat, paused(r)),
        r.game != null && r.game.canAcknowledge(m.seat, paused(r)));
  }

  private void clearGameDeadlines(Room r) {
    for (Member m : r.members.values()) {
      m.reconnectDeadline = null;
      m.reconnectDueTick = null;
    }
  }

  /** Side-effect-free projection for a subscriber; never publish another player's private hand. */
  public synchronized Snapshot viewFor(String roomId, Identity id) {
    Room r = room(roomId);
    member(r, id);
    return snapshot(r, id);
  }

  private AcceptedAction accepted(Room r, Identity id, String actionId, String signature) {
    try {
      UUID.fromString(actionId);
    } catch (Exception e) {
      throw fail(400, "INVALID_ACTION_ID", "actionId must be a UUID");
    }
    AcceptedAction old = r.actions.get(id.playerId() + ":" + actionId);
    if (old != null && !old.signature().equals(signature))
      throw fail(409, "ACTION_ID_REUSED", "actionId already names a different command");
    return old;
  }

  private void revision(Room r, Long expected) {
    if (expected == null) throw fail(400, "INVALID_REVISION", "expectedRevision is required");
    if (expected != r.revision)
      throw fail(409, "STALE_REVISION", "Refresh the room before trying again");
  }

  private CommandResult replay(Room r, Identity id, String actionId, AcceptedAction old) {
    return new CommandResult(actionId, "REPLAYED", old.revision(), snapshot(r, id));
  }

  private CommandResult applied(Room r, Identity id, String actionId, String signature) {
    Snapshot committed = changed(r);
    r.actions.put(
        id.playerId() + ":" + actionId, new AcceptedAction(signature, committed.revision()));
    return new CommandResult(actionId, "APPLIED", committed.revision(), snapshot(r, id));
  }

  public synchronized CommandResult start(
      String roomId, Identity id, String actionId, Long expected) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    member(r, id);
    String signature = "START";
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    if (!Objects.equals(r.hostPlayerId, id.playerId()))
      throw fail(403, "HOST_ONLY", "Only the host can start");
    if (r.game != null) throw fail(409, "WRONG_PHASE", "This room already started a game");
    if (r.members.size() != 4
        || r.members.values().stream().anyMatch(m -> !m.ready || !m.present()))
      throw fail(409, "START_NOT_READY", "Four ready and connected players are required");
    r.game = new GameState(random, ticker);
    r.game.roster(
        r.members.values().stream()
            .sorted(Comparator.comparingInt(m -> m.seat))
            .map(
                m ->
                    new GameState.PlayerInfo(
                        m.identity.playerId(),
                        m.identity.displayName(),
                        m.seat,
                        GameState.team(m.seat), m.computer ? "BOT" : "HUMAN"))
            .toList());
    return applied(r, id, actionId, signature);
  }

  private GameState activeGame(Room r, String gameId, String dealId) {
    if (r.game == null || !r.game.id.equals(gameId))
      throw fail(409, "STALE_GAME", "Game no longer matches");
    if (!r.game.dealId.equals(dealId)) throw fail(409, "STALE_DEAL", "Deal no longer matches");
    if (!r.game.active()) throw fail(409, "WRONG_PHASE", "Game is finished");
    if (paused(r)) throw fail(409, "GAME_PAUSED", "Waiting for disconnected players");
    return r.game;
  }

  public synchronized CommandResult privilege(
      String roomId,
      Identity id,
      String gameId,
      String dealId,
      String actionId,
      Long expected,
      boolean use) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    Member m = member(r, id);
    String signature = "PRIVILEGE:" + gameId + ":" + dealId + ":" + use;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    activeGame(r, gameId, dealId).choose(m.seat, use);
    return applied(r, id, actionId, signature);
  }

  public synchronized CommandResult call(
      String roomId,
      Identity id,
      String gameId,
      String dealId,
      String actionId,
      Long expected,
      String type,
      Integer level,
      String strain) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    Member m = member(r, id);
    String signature = "CALL:" + gameId + ":" + dealId + ":" + type + ":" + level + ":" + strain;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    activeGame(r, gameId, dealId).call(m.seat, type, level, strain);
    return applied(r, id, actionId, signature);
  }

  public synchronized CommandResult play(
      String roomId,
      Identity id,
      String gameId,
      String dealId,
      String actionId,
      Long expected,
      GameState.Card card) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    Member m = member(r, id);
    String signature = "PLAY:" + gameId + ":" + dealId + ":" + card;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    activeGame(r, gameId, dealId).play(m.seat, card);
    return applied(r, id, actionId, signature);
  }

  public synchronized GameState.MatchArchive lastMatch(String roomId, Identity id) {
    expireInitialSubscriptions();
    Room r = room(roomId);
    if (r.lastMatch == null) throw fail(404, "NO_LAST_MATCH", "No completed match is archived");
    if (!archiveParticipant(r, id))
      throw fail(403, "NOT_PARTICIPANT", "Only original match participants may read its archive");
    return r.lastMatch;
  }

  public synchronized CommandResult acknowledgeResult(
      String roomId, Identity id, String gameId, String dealId, String actionId, Long expected) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    Member m = member(r, id);
    String signature = "ACK_RESULT:" + gameId + ":" + dealId;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    activeGame(r, gameId, dealId).acknowledge(m.seat);
    return applied(r, id, actionId, signature);
  }

  public synchronized CommandResult rematch(
      String roomId, Identity id, String gameId, String actionId, Long expected) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    member(r, id);
    String signature = "REMATCH:" + gameId;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    if (!Objects.equals(r.hostPlayerId, id.playerId()))
      throw fail(403, "HOST_ONLY", "Only the host can return this room to waiting");
    if (r.game == null || !r.game.id.equals(gameId))
      throw fail(409, "STALE_GAME", "Game no longer matches");
    if (!canRematch(r, id))
      throw fail(409, "WRONG_PHASE", "Rematch requires a connected host and finished game");
    // The terminal transition already captured the immutable participant-filtered archive.
    for (Member m : new ArrayList<>(r.members.values())) {
      if (!m.present()) {
        r.members.remove(m.identity.playerId());
        membership.remove(m.identity.playerId());
      } else {
        m.ready = m.computer;
        m.initialSubscriptionDeadline = null;
        m.disconnectedAt = null;
      }
    }
    clearGameDeadlines(r);
    r.adjudicationDeadlines.clear();
    r.failedTeams.clear();
    r.game = null;
    r.computerActionKey = null;
    r.computerTargetDeal = null;
    r.computerTargets.clear();
    return applied(r, id, actionId, signature);
  }

  static Failure fail(int status, String code, String message) {
    return new Failure(status, code, message);
  }
}
