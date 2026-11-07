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
      String teamId, String playerType, String controlMode) {}

  public record Snapshot(
      String roomId,
      long revision,
      String phase,
      int capacity,
      int plannedDeals,
      boolean allReady,
      List<PlayerView> players,
      String hostPlayerId,
      boolean canStart,
      boolean canRematch,
      GameState.MatchArchive lastMatch,
      GameState.View game,
      You you,
      Long allHumansReconnectDeadline) {}

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
      String roomId, int playerCount, int capacity, String state, String phase, boolean joinable, int plannedDeals) {}

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
    boolean takeover;
    boolean automated() { return computer || takeover; }
    boolean present() { return automated() || !connections.isEmpty(); }
    boolean humanOnline() { return !computer && !connections.isEmpty(); }
    String controlMode() {
      return computer ? "BOT" : takeover ? "BOT_TAKEOVER"
          : reconnectDueTick != null ? "RECONNECTING" : "HUMAN";
    }
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
    final int plannedDeals;
    long revision;
    String hostPlayerId;
    GameState game;
    GameState.MatchArchive lastMatch;
    MatchPersistence persistence;
    MatchPersistence lastPersistence;
    Long allOfflineSince;
    Long allHumansReconnectDeadline;
    Long allHumansReconnectDue;
    Long closedAtTick;
    String computerActionKey;
    long computerActionDue;
    String computerTargetDeal;
    final Map<Integer, String> computerTargets = new HashMap<>();
    final Map<String, AcceptedAction> actions = new HashMap<>();
    Map<String, Member> members = new LinkedHashMap<>();

    Room(String id, int plannedDeals) {
      this.id = id;
      this.plannedDeals = plannedDeals;
    }
  }

  private final Map<String, String> accountsByPlayer = new HashMap<>();
  private final Set<String> admittedTesters = new HashSet<>();
  private com.bridge.persistence.StatsStore statsStore;
  private boolean quarterRetentionEnabled;
  private CollectionPolicy experimentPolicy = CollectionPolicy.restrictedEmpty();
  private boolean experimentMatchStarted;
  @Autowired
  public synchronized void experimentPolicy(CollectionPolicy policy) { this.experimentPolicy = Objects.requireNonNull(policy); }

  @Autowired
  public synchronized void quarterRetentionEnabled(
      @org.springframework.beans.factory.annotation.Value("${BRIDGE_QUARTER_POLICY_VERIFIED:false}") boolean enabled) {
    quarterRetentionEnabled = enabled;
  }

  @Autowired
  public synchronized void statsStore(com.bridge.persistence.StatsStore store) { this.statsStore = store; }

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

  // Google linking changes attribution for future matches only. Guest credentials and seats
  // are deliberately NOT replaced. No remote network call is allowed inside these hooks.
  synchronized void requireCurrentSession(Identity who) {
    Identity current = authenticate(who.token());
    if (!current.equals(who)) throw fail(401, "UNAUTHORIZED", "Session changed");
  }

  public synchronized void requireOutsideRoom(Identity who) {
    requireCurrentSession(who);
    if (membership.containsKey(who.playerId()))
      throw fail(409, "AUTH_IN_ROOM", "Leave the room before changing Google identity");
  }

  public synchronized void linkAccountOutsideRoom(Identity who, String accountId) {
    linkAccountOutsideRoom(who, accountId, false);
  }

  public synchronized void linkAccountOutsideRoom(Identity who, String accountId, boolean admittedTester) {
    requireOutsideRoom(who);
    UUID.fromString(accountId);
    String old = accountsByPlayer.get(who.playerId());
    if (old != null && !old.equals(accountId))
      throw fail(409, "ACCOUNT_ALREADY_LINKED", "Unlink the current account first");
    accountsByPlayer.put(who.playerId(), accountId);
    if (admittedTester) admittedTesters.add(who.playerId());
    else admittedTesters.remove(who.playerId());
  }

  public synchronized void unlinkAccountOutsideRoom(Identity who) {
    requireOutsideRoom(who);
    accountsByPlayer.remove(who.playerId());
    admittedTesters.remove(who.playerId());
  }

  public synchronized String accountId(Identity who) {
    requireCurrentSession(who);
    return accountsByPlayer.get(who.playerId());
  }

  public synchronized String persistenceStatus(String roomId, String gameId, Identity who) {
    authenticate(who.token());
    Room r = room(roomId);
    if (r.lastMatch == null || !r.lastMatch.gameId().equals(gameId))
      throw fail(404, "NO_LAST_MATCH", "Completed match is no longer available");
    if (!archiveParticipant(r, who))
      throw fail(403, "NOT_PARTICIPANT", "Only original participants may read save status");
    if (r.lastPersistence == null || !r.lastPersistence.matchId().equals(gameId)) return "DISABLED";
    return r.lastPersistence.status(statsStore);
  }

  public synchronized Me me(Identity id) {
    expireInitialSubscriptions();
    return new Me(id.playerId(), id.displayName(), membership.get(id.playerId()));
  }

  public synchronized List<Summary> list() {
    expireInitialSubscriptions();
    return rooms.values().stream()
        .filter(r -> r.closedAtTick == null)
        .map(
            r ->
                new Summary(
                    r.id,
                    r.members.size(),
                    4,
                    r.game == null ? "WAITING" : r.game.active() ? "ACTIVE" : "FINISHED",
                    r.game == null ? "WAITING" : r.game.phase,
                    r.game == null && r.members.size() < 4, r.plannedDeals))
        .toList();
  }

  public synchronized Snapshot create(Identity id) {
    return create(id, null);
  }

  public synchronized Snapshot create(Identity id, Integer plannedDeals) {
    int count = plannedDeals == null ? 3 : plannedDeals;
    if (count != 1 && count != 3 && count != 5)
      throw fail(400, "INVALID_PLANNED_DEALS", "plannedDeals must be 1, 3 or 5");
    requireRunning();
    expireInitialSubscriptions();
    ensureFree(id);
    if (rooms.values().stream().filter(r -> r.closedAtTick == null).count() >= limits.maxRooms())
      throw fail(409, "ROOM_LIMIT_REACHED", "The beta table limit has been reached");
    Room room = new Room(UUID.randomUUID().toString(), count);
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
      Member departing = member(room, id);
      int seat = departing.seat;
      room.members.remove(id.playerId());
      membership.remove(id.playerId());
      Member replacement = permanentComputer(seat);
      room.members.put(replacement.identity.playerId(), replacement);
      room.computerActionKey = null;
      electConnectedHost(room);
      if (!hasOnlineHuman(room)) terminateNoHumans(room, "NO_HUMANS_REMAIN");
      else {
        if (!paused(room)) room.game.resumeTimer();
        changed(room);
      }
      // With no remaining human owners there is nobody authorized to retrieve an archive.
      if (room.members.values().stream().noneMatch(m -> !m.computer)) {
        room.members.clear();
        rooms.remove(room.id);
        changed(room);
      }
      return;
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
    m.takeover = false;
    room.computerActionKey = null;
    room.allHumansReconnectDeadline = null;
    room.allHumansReconnectDue = null;
    if (room.game != null && room.game.active()) electConnectedHost(room);
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
      m.takeover = false;
      m.reconnectDeadline = clock.millis() + GAME_RECONNECT_MS;
      m.reconnectDueTick = ticker.getAsLong() + GAME_RECONNECT_MS;
      r.computerActionKey = null;
      if (!hasOnlineHuman(r) && r.allHumansReconnectDue == null) {
        r.allHumansReconnectDeadline = clock.millis() + GAME_RECONNECT_MS;
        r.allHumansReconnectDue = ticker.getAsLong() + GAME_RECONNECT_MS;
      }
      electConnectedHost(r);
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

  // One authority sweep handles initial subscriptions (5s), active takeover grace (10s),
  // and operation budgets (20s). WAITING disconnects still release immediately.
  @Scheduled(fixedDelay = 100)
  public synchronized void expireInitialSubscriptions() {
    if (stopping) return;
    long now = clock.millis();
    long monotonicNow = ticker.getAsLong();
    for (Room r : new ArrayList<>(rooms.values())) {
      if (r.game != null && r.game.active()) {
        if (!hasOnlineHuman(r)) {
          if (r.allHumansReconnectDue != null && monotonicNow >= r.allHumansReconnectDue)
            terminateNoHumans(r, "NO_HUMANS_CONNECTED");
        } else {
          boolean transitioned = false;
          for (Member m : r.members.values()) {
            if (!m.computer && m.connections.isEmpty() && m.reconnectDueTick != null
                && monotonicNow >= m.reconnectDueTick) {
              m.takeover = true;
              m.reconnectDeadline = null;
              m.reconnectDueTick = null;
              transitioned = true;
            }
          }
          if (transitioned) {
            r.computerActionKey = null;
            if (!paused(r)) r.game.resumeTimer();
            changed(r);
          }
        }
      }
      boolean computerMoved = advanceComputer(r, monotonicNow);
      if (r.game != null && !paused(r)
          && (!computerMoved || r.game.phase.equals("PRIVILEGE_DECISION"))
          && r.game.expireOperation(automatedSources(r))) changed(r);
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
      if (!m.automated()) continue;
      GameState.View view = g.view(m.seat, false);
      if ((g.phase.equals("REDEAL_DECISION") && g.canChooseRedeal(m.seat, false))
          || (g.phase.equals("PRIVILEGE_DECISION") && view.privilege().canChoose())
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
    String source = actor.computer ? "BOT" : "BOT_TAKEOVER";
    if (g.phase.equals("REDEAL_DECISION")) g.chooseRedeal(seat, true);
    else if (g.phase.equals("PRIVILEGE_DECISION")) g.choose(seat, false, source);
    else if (g.phase.equals("BIDDING")) {
      if (!g.dealId.equals(r.computerTargetDeal)) {
        r.computerTargetDeal = g.dealId;
        r.computerTargets.clear();
      }
      String target = r.computerTargets.computeIfAbsent(seat,
          ignored -> ComputerStrategy.longestSuit(g.hands.get(seat), random));
      GameState.LegalCall call = ComputerStrategy.call(seat, target, g.hands.get(seat), g.highest);
      g.call(seat, call.type(), call.level(), call.strain(), source);
    } else if (g.phase.equals("PLAYING")) {
      var play = g.view(null, false).play();
      g.play(seat, ComputerStrategy.play(seat, g.hands.get(seat), play.currentTrick(), g.contract.strain(), random), source);
    } else g.acknowledge(seat);
    r.computerActionKey = null;
    changed(r);
    return true;
  }

  private void cleanupIdle(long now) {
    for (Room r : new ArrayList<>(rooms.values())) {
      if (quarterRetentionEnabled && r.game != null && r.game.terminal()
          && r.lastPersistence != null && r.lastPersistence.expiredAtTaiwanQuarter(clock.instant())) {
        for (Member m : r.members.values()) membership.remove(m.identity.playerId(), r.id);
        r.members.clear(); rooms.remove(r.id); // terminate only completed archives, never active play
        continue;
      }
      if (r.closedAtTick != null) {
        if (now - r.closedAtTick >= limits.finishedRoomIdleMs()) {
          for (Member m : r.members.values()) membership.remove(m.identity.playerId(), r.id);
          r.members.clear();
          rooms.remove(r.id);
          changed(r);
        }
        continue;
      }
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
        accountsByPlayer.remove(entry.getValue().playerId());
        admittedTesters.remove(entry.getValue().playerId());
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

  private boolean hasOnlineHuman(Room r) {
    return r.members.values().stream().anyMatch(Member::humanOnline);
  }

  private Map<Integer, String> automatedSources(Room r) {
    Map<Integer, String> sources = new HashMap<>();
    for (Member m : r.members.values()) if (m.automated())
      sources.put(m.seat, m.computer ? "BOT" : "BOT_TAKEOVER");
    return sources;
  }

  private Member permanentComputer(int seat) {
    Identity bot = new Identity(null, "bot-" + UUID.randomUUID(), "電腦" + List.of("一", "二", "三", "四").get(seat));
    Member replacement = new Member(bot, seat, clock.millis());
    replacement.computer = true;
    replacement.ready = true;
    replacement.disconnectedAt = null;
    replacement.initialSubscriptionDeadline = null;
    return replacement;
  }

  private void terminateNoHumans(Room r, String reason) {
    if (r.game == null || !r.game.active()) return;
    r.game.finishWithoutHumans(reason);
    r.closedAtTick = ticker.getAsLong();
    r.computerActionKey = null;
    r.allHumansReconnectDue = null;
    r.allHumansReconnectDeadline = null;
    clearGameDeadlines(r);
    changed(r);
  }

  private void electConnectedHost(Room r) {
    Member host = r.members.get(r.hostPlayerId);
    if (host == null || !host.humanOnline())
      r.hostPlayerId = r.members.values().stream().filter(Member::humanOnline)
          .min(Comparator.comparingInt(m -> m.seat)).map(m -> m.identity.playerId()).orElse(null);
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
    Room previous = rooms.get(membership.get(id.playerId()));
    if (previous != null && previous.closedAtTick != null) remove(previous, id.playerId());
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
    if (r.persistence != null) r.persistence.observe(r.members.values());
    if (r.game != null && r.game.terminal()) {
      // Refresh the safe projection when the final two-second display unlocks review.
      r.lastMatch = r.game.archive();
      if (r.persistence != null) {
        r.persistence.finish(r.lastMatch, clock.instant(), statsStore);
        r.lastPersistence = r.persistence;
      }
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
        && !(quarterRetentionEnabled && r.lastPersistence != null
            && r.lastPersistence.expiredAtTaiwanQuarter(clock.instant()))
        && r.lastMatch.roster().stream().anyMatch(p -> p.playerId().equals(viewer.playerId()));
  }

  private boolean canRematch(Room r, Identity viewer) {
    return viewer != null
        && r.closedAtTick == null
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
                        m.computer || !m.connections.isEmpty(),
                        m.disconnectedAt,
                        m.reconnectDeadline,
                        m.initialSubscriptionDeadline,
                        GameState.team(m.seat), m.computer ? "BOT" : "HUMAN", m.controlMode()))
            .toList();
    return new Snapshot(
        r.id,
        r.revision,
        r.game == null ? "WAITING" : r.game.phase,
        4,
        r.plannedDeals,
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
            : r.game.view(viewer == null ? null : member(r, viewer).seat,
                paused(r), viewer != null && member(r, viewer).takeover),
        viewer == null ? null : you(r, member(r, viewer)),
        r.allHumansReconnectDeadline);
  }

  private boolean paused(Room r) {
    return r.game != null
        && r.game.active()
        && (!hasOnlineHuman(r) || r.members.values().stream().anyMatch(m -> !m.present()));
  }

  private You you(Room r, Member m) {
    boolean unavailable = paused(r) || m.takeover;
    return new You(
        m.identity.playerId(),
        m.seat,
        GameState.team(m.seat),
        r.game == null ? List.of() : r.game.hands.getOrDefault(m.seat, List.of()),
        r.game == null ? List.of() : r.game.legalCalls(m.seat, unavailable),
        r.game == null ? List.of() : r.game.legalCards(m.seat, unavailable),
        r.game != null && r.game.canAcknowledge(m.seat, unavailable));
  }

  private void clearGameDeadlines(Room r) {
    for (Member m : r.members.values()) {
      m.reconnectDeadline = null;
      m.reconnectDueTick = null;
      m.takeover = false;
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
    if (experimentPolicy.experiment()) {
      var humans = r.members.values().stream().filter(m -> !m.computer).toList();
      boolean linked = humans.stream().anyMatch(m -> accountsByPlayer.containsKey(m.identity.playerId()));
      if (linked) {
        if (!experimentPolicy.experimentActive(clock)) throw fail(403, "EXPERIMENT_EXPIRED", "Experiment expired");
        if (r.plannedDeals != 1 || humans.size() != 1 || !admittedTesters.contains(humans.get(0).identity.playerId()))
          throw fail(403, "EXPERIMENT_UNAVAILABLE", "One admitted tester, three bots and one deal are required");
        if (experimentMatchStarted) throw fail(409, "EXPERIMENT_MATCH_LIMIT", "The experiment match slot is already consumed");
        experimentMatchStarted = true; // a failed/abandoned start never restores admission
      }
    }
    r.game = new GameState(random, ticker, r.plannedDeals);
    r.persistence = new MatchPersistence(r.game.id, r.plannedDeals, clock.instant(),
        r.members.values(), accountsByPlayer, r.members.values().stream()
            .filter(m -> !m.computer).allMatch(m -> accountsByPlayer.containsKey(m.identity.playerId())
                && admittedTesters.contains(m.identity.playerId())));
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

  public synchronized CommandResult redeal(String roomId, Identity id, String gameId,
      String dealId, String actionId, Long expected, boolean redeal) {
    requireRunning();
    expireInitialSubscriptions();
    Room r = room(roomId);
    Member m = member(r, id);
    String signature = "REDEAL:" + gameId + ":" + dealId + ":" + redeal;
    AcceptedAction old = accepted(r, id, actionId, signature);
    if (old != null) return replay(r, id, actionId, old);
    revision(r, expected);
    if (m.takeover) throw fail(409, "CONTROL_TAKEN_OVER", "Reconnect to resume control before acting");
    activeGame(r, gameId, dealId).chooseRedeal(m.seat, redeal);
    return applied(r, id, actionId, signature);
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
    if (m.takeover) throw fail(409, "CONTROL_TAKEN_OVER", "Reconnect to resume control before acting");
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
    if (m.takeover) throw fail(409, "CONTROL_TAKEN_OVER", "Reconnect to resume control before acting");
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
    if (m.takeover) throw fail(409, "CONTROL_TAKEN_OVER", "Reconnect to resume control before acting");
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
    if (m.takeover) throw fail(409, "CONTROL_TAKEN_OVER", "Reconnect to resume control before acting");
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
      if (!m.computer && !m.humanOnline()) {
        r.members.remove(m.identity.playerId());
        membership.remove(m.identity.playerId());
      } else {
        m.ready = m.computer;
        m.initialSubscriptionDeadline = null;
        m.disconnectedAt = null;
      }
    }
    clearGameDeadlines(r);
    r.allHumansReconnectDeadline = null;
    r.allHumansReconnectDue = null;
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
