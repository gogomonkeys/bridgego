import { useSyncExternalStore } from "react";
import { nicknameValidation } from "./nickname";
import { timerDisplayAnchor } from "./timer-view";
import {
  acceptSnapshot,
  type Session,
  type Room,
  type RoomSummary,
  type Call,
  type Card,
  type CommandResult,
} from "./model";
const KEY = "bridgego.phase1.guest";
const EPOCH_KEY = "bridgego.server.epoch";
let serverEpoch: string | null = null;
let epochRevision = 0;
type Status = "offline" | "connecting" | "online";
interface State {
  serviceMaintenance?: boolean;
  networkAvailable?: boolean;
  session: Session | null;
  room: Room | null;
  rooms: RoomSummary[];
  status: Status;
  busy: boolean;
  initialized: boolean;
  error: string | null;
  notice: string | null;
  pendingAction: string | null;
  snapshotReceivedAt: number;
  connectionEpoch: number;
}
let state: State = {
  session: null,
  room: null,
  rooms: [],
  status: "offline",
  busy: false,
  initialized: false,
  error: null,
  notice: null,
  pendingAction: null,
  snapshotReceivedAt: 0,
  connectionEpoch: 0,
};
const listeners = new Set<() => void>();
const emit = (patch: Partial<State>) => {
  state = { ...state, ...patch };
  listeners.forEach((f) => f());
};
export const getClientState = () => state;
export const useClient = () =>
  useSyncExternalStore((fn) => {
    listeners.add(fn);
    return () => {
      listeners.delete(fn);
    };
  }, getClientState);
// The transport belongs to this module, not a page/component: navigation keeps it alive.
let roomId: string | null = null,
  socket: WebSocket | null = null,
  retry: ReturnType<typeof setTimeout> | undefined;
let generation = 0,
  retryCount = 0,
  initialized = false;
interface DisconnectDiagnostic {
  timestamp: string;
  code: number;
  reason: string;
  wasClean: boolean;
  lifetimeMs: number;
  visibility: string;
  retryAttempt: number;
}
const closeReasons: Record<number, string> = {
  1000: "連線正常關閉",
  1001: "頁面或服務離開",
  1006: "連線異常中斷（未收到關閉訊框）",
  1008: "驗證或訊息規則不符",
  1011: "伺服器傳輸錯誤",
  4001: "原生連線心跳逾時",
  4002: "首次訂閱逾時",
  4003: "房間成員身分已結束",
};
// Bounded local diagnostics: never include token, room/player IDs, raw URL/reason or frames.
const disconnectDiagnostics: DisconnectDiagnostic[] = [];
export const getConnectionDiagnostics = () =>
  disconnectDiagnostics.map((entry) => ({ ...entry }));
function recordDisconnect(entry: DisconnectDiagnostic) {
  disconnectDiagnostics.push(entry);
  if (disconnectDiagnostics.length > 20) disconnectDiagnostics.shift();
  if (entry.code === 1000 || entry.code === 4003)
    console.info("[BridgeGo connection closed]", entry);
  else console.warn("[BridgeGo connection closed]", entry);
}
class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status?: number,
  ) {
    super(message);
  }
}
class HttpResponseError extends Error {
  constructor(public status: number) {
    super(
      `服務回應不完整或格式異常（HTTP ${status}），請確認前後端服務與連線後重試。`,
    );
  }
}
let recoveryError: string | null = null;
const messages: Record<string, string> = {
  SERVER_RESTARTED:
    "服務已重新啟動，原牌局與訪客身分已結束。請輸入名稱重新加入，開始新的遊戲。",
  SERVER_MAINTENANCE: "服務正在重新啟動，原牌局無法延續，請稍後確認服務狀態。",
  ROOM_LIMIT_REACHED: "目前兩桌皆已使用，請加入現有等候室或稍後再試。",
  SESSION_CAPACITY: "目前體驗人數已滿，請稍後再試。",
  RATE_LIMITED: "操作太頻繁，請稍候再試。",
  CONNECTION_LIMIT: "連線數已達上限，請關閉多餘分頁後再試。",
  ROOM_FULL: "房間已滿，最多只能有四位玩家。",
  ROOM_NOT_FOUND: "找不到這個房間，可能已經解散。",
  NOT_IN_ROOM:
    "席位已釋出（斷線或手動離開）。請重新加入，是否有空位以房間現況為準。",
  NOT_MEMBER:
    "席位已釋出（斷線或手動離開）。請重新加入，是否有空位以房間現況為準。",
  HOST_ONLY: "只有房主可以開始遊戲。",
  START_NOT_READY: "需要四位玩家全部在線且已準備。",
  WRONG_PHASE: "遊戲階段已變更，請重新同步後操作。",
  GAME_IN_PROGRESS: "這個房間已開局，無法再加入；請建立或選擇其他等候室。",
  GAME_PAUSED: "有人離線，遊戲暫停中，請等候重連。",
  NOT_YOUR_TURN: "還沒輪到你叫牌。",
  ILLEGAL_CALL: "這個叫牌目前不可使用，請以最新狀態為準。",
  STALE_REVISION: "狀態已更新，已重新同步；請再次確認後操作。",
  ACTION_ID_REUSED: "操作識別碼衝突，請重新同步。",
  CHOICE_LOCKED: "你的特權選擇已送出，不能更改。",
  WRONG_GAME: "這場遊戲已變更，請重新同步。",
  WRONG_DEAL: "已重新發牌，請依新手牌重新選擇。",
  INVALID_NAME: "請輸入 1–5 個字的訪客名稱，不可包含控制字元。",
  ALREADY_IN_ROOM: "你已在另一個房間，請先離開。",
  UNAUTHORIZED: "訪客身分已失效，請重新建立。",
  INVALID_SESSION: "訪客身分已失效，請重新建立。",
};
function observeEpoch(next: string | null): boolean {
  if (!next || next.length > 128) return false;
  const changed = serverEpoch !== null && serverEpoch !== next;
  serverEpoch = next;
  try {
    localStorage.setItem(EPOCH_KEY, next);
  } catch {
    // Storage may be disabled or full; the in-memory baseline still protects this tab.
  }
  if (changed) {
    epochRevision++;
    stop();
    localStorage.removeItem(KEY);
    emit({
      session: null,
      room: null,
      rooms: [],
      serviceMaintenance: false,
      error: null,
      notice: messages.SERVER_RESTARTED,
    });
  }
  return changed;
}
function maintenance() {
  stop();
  emit({
    room: null,
    serviceMaintenance: true,
    notice: messages.SERVER_MAINTENANCE,
  });
}
async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  if (state.networkAvailable === false)
    throw new ApiError("OFFLINE", "目前離線，請恢復網路後再操作。");
  if (method !== "GET" && state.serviceMaintenance)
    throw new ApiError("SERVER_MAINTENANCE", messages.SERVER_MAINTENANCE);
  const token = state.session?.token;
  const requestedEpochRevision = epochRevision;
  const requestedGeneration = generation;
  const response = await fetch(`/api${path}`, {
    method,
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      ...(state.session
        ? { Authorization: `Bearer ${state.session.token}` }
        : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(8000),
  });
  // Late HTTP responses cannot roll a confirmed boot baseline back.
  if (
    requestedEpochRevision !== epochRevision ||
    requestedGeneration !== generation ||
    token !== state.session?.token
  )
    throw new ApiError(
      "IDENTITY_CHANGED",
      "連線狀態已改變，請依目前畫面操作。",
    );
  const restarted = observeEpoch(
    response.headers.get("X-Bridge-Instance-Epoch"),
  );
  if (restarted && token)
    throw new ApiError("SERVER_RESTARTED", messages.SERVER_RESTARTED);
  const acceptedEpochRevision = epochRevision;
  const acceptedGeneration = generation;
  // A development proxy may return an empty/non-JSON 500 when its upstream is down.
  // Such a response cannot confirm whether a mutation applied: do NOT classify it
  // as an authoritative ApiError or discard the original actionId.
  let data: unknown;
  try {
    data = await response.json();
  } catch {
    if (
      acceptedGeneration !== generation ||
      acceptedEpochRevision !== epochRevision ||
      token !== state.session?.token
    )
      throw new ApiError("IDENTITY_CHANGED", "訪客身分已改變，請重新整理。");
    throw new HttpResponseError(response.status);
  }
  if (
    acceptedGeneration !== generation ||
    acceptedEpochRevision !== epochRevision ||
    token !== state.session?.token
  )
    throw new ApiError("IDENTITY_CHANGED", "訪客身分已改變，請重新整理。");
  if (data === null || typeof data !== "object")
    throw new HttpResponseError(response.status);
  if (!response.ok) {
    const failure = data as Record<string, unknown>;
    if (typeof failure.code !== "string")
      throw new HttpResponseError(response.status);
    if (failure.code === "SERVER_MAINTENANCE") maintenance();
    if (response.status === 401) {
      stop();
      localStorage.removeItem(KEY);
      emit({
        session: null,
        room: null,
        rooms: [],
        error: messages.UNAUTHORIZED,
      });
    }
    throw new ApiError(
      failure.code,
      messages[failure.code] ??
        (typeof failure.message === "string"
          ? failure.message
          : "請求失敗，請稍後重試。"),
      response.status,
    );
  }
  return data as T;
}
interface PendingCommand {
  path: string;
  method: string;
  body: Record<string, unknown>;
  generation: number;
  label: string;
}
let pendingCommand: PendingCommand | null = null;
function clearPending() {
  pendingCommand = null;
  emit({ pendingAction: null });
}
// Invalidate old socket callbacks before closing, so intentional leave cannot reconnect.
function stop() {
  generation++;
  clearPending();
  roomId = null;
  clearTimeout(retry);
  const old = socket;
  socket = null;
  old?.close();
  emit({ status: "offline" });
}
// HTTP and WebSocket responses can arrive out of order; room + revision guard wins.
function apply(room: Room, newConnection = false) {
  const accepted = acceptSnapshot(state.room, room, roomId);
  if (accepted && accepted !== state.room) {
    const snapshotReceivedAt = timerDisplayAnchor(
      state.room,
      state.snapshotReceivedAt,
      accepted,
      performance.now(),
    );
    emit({
      room: accepted,
      snapshotReceivedAt,
      ...(newConnection ? { connectionEpoch: state.connectionEpoch + 1 } : {}),
    });
    return true;
  }
  return false;
}
// Every reconnect authenticates again and receives a full authoritative snapshot.
function connect() {
  if (!roomId || !state.session) return;
  const mine = generation,
    target = roomId;
  emit({ status: "connecting" });
  const ws = new WebSocket(
    `${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/ws`,
  );
  socket = ws;
  const startedAt = performance.now();
  let firstSnapshot = true;
  ws.onopen = () => {
    if (mine !== generation || socket !== ws) {
      ws.close();
      return;
    }
    ws.send(
      JSON.stringify({
        type: "subscribe",
        token: state.session?.token,
        roomId: target,
      }),
    );
    // Native server Ping/browser Pong owns liveness. Never infer a dead transport
    // from suspended JavaScript timers or from a lack of room-state changes.
  };
  ws.onmessage = (event) => {
    if (mine !== generation || socket !== ws) return;
    try {
      const data = JSON.parse(event.data);
      if (data.type === "maintenance") {
        if (
          !observeEpoch(
            typeof data.instanceEpoch === "string" ? data.instanceEpoch : null,
          )
        )
          maintenance();
        return;
      }
      if (data.type === "snapshot") {
        const applied = apply(data.room, firstSnapshot);
        if (applied) {
          retryCount = 0;
          emit({ status: "online", notice: null });
          firstSnapshot = false;
        }
      }
      if (data.type === "error") {
        stop();
        emit({ room: null, error: messages[data.code] ?? data.message });
        if (
          ["NOT_MEMBER", "ROOM_NOT_FOUND", "UNAUTHORIZED"].includes(data.code)
        )
          void recover();
      }
    } catch {
      emit({ error: "收到無法辨識的伺服器資料。" });
    }
  };
  ws.onclose = (event) => {
    if (mine !== generation || socket !== ws) return;
    if (event.code === 1012) {
      maintenance();
      return;
    }
    const reason = closeReasons[event.code] ?? "連線已關閉，原因未明";
    recordDisconnect({
      timestamp: new Date().toISOString(),
      code: event.code,
      reason,
      wasClean: event.wasClean,
      lifetimeMs: Math.round(Math.max(0, performance.now() - startedAt)),
      visibility:
        typeof document === "undefined" ? "unknown" : document.visibilityState,
      retryAttempt: retryCount,
    });
    // Do not display stale seat/ready as reserved after a transport loss.
    // Resubscribe only validates existing membership; it never invokes fresh join.
    const wasTerminal =
      state.room?.phase === "MATCH_RESULT" ||
      state.room?.phase === "ABORTED" ||
      state.room?.game?.matchResult != null;
    const wasPending = state.room?.phase === "FORFEIT_PENDING";
    const wasActive =
      state.room?.phase !== undefined &&
      state.room.phase !== "WAITING" &&
      state.room.phase !== "ABORTED";
    generation++;
    clearPending();
    socket = null;
    emit({
      status: "offline",
      room: null,
      notice: wasTerminal
        ? `${reason}（代碼 ${event.code}），正在恢復已結束的結果頁；既定結果不因斷線改變。`
        : wasPending
          ? `${reason}（代碼 ${event.code}），正在恢復重連裁定狀態；既有期限不重置，失敗判定以伺服器為準。`
          : wasActive
            ? `${reason}（代碼 ${event.code}），正在恢復原遊戲。伺服器偵測斷線後保留 10 秒；逾時依規則判負，已完成分數保留，未完成局不計分。`
            : state.room?.phase === "WAITING"
              ? `${reason}（代碼 ${event.code}），正在確認席位。等候室斷線後不保留席位；若已釋出，請重新加入，不保證仍有空位。`
              : `${reason}（代碼 ${event.code}），正在確認房間與席位狀態，請稍候。`,
    });
    scheduleRecovery(generation);
  };
  ws.onerror = () => {
    if (mine !== generation || socket !== ws) return;
    // Browsers do not expose transport-error details; never stringify this event.
    console.warn("[BridgeGo transport error]", {
      visibility:
        typeof document === "undefined" ? "unknown" : document.visibilityState,
    });
    // Only onclose owns room invalidation and retry; onerror alone cannot establish cause.
  };
}
function enter(room: Room) {
  if (roomId !== room.roomId) {
    stop();
    roomId = room.roomId;
    emit({ room: null });
    apply(room);
    connect();
  } else {
    apply(room);
    // A resumed page may observe CLOSED before the delayed close event arrives.
    // An OPEN or CONNECTING transport is never replaced by foreground recovery.
    if (!socket || socket.readyState >= 2) {
      const old = socket;
      socket = null;
      old?.close();
      connect();
    }
  }
}
function scheduleRecovery(expectedGeneration: number) {
  clearTimeout(retry);
  retry = setTimeout(
    () => void recover(true, expectedGeneration),
    Math.min(500 * 2 ** retryCount++, 2000),
  );
}
let recoveryFlight: {
  generation: number;
  retry: boolean;
  promise: Promise<void>;
} | null = null;
function recover(
  retryOnFailure = false,
  expectedGeneration = generation,
): Promise<void> {
  if (recoveryFlight?.generation === expectedGeneration) {
    recoveryFlight.retry ||= retryOnFailure;
    return recoveryFlight.promise;
  }
  const flight = {
    generation: expectedGeneration,
    retry: retryOnFailure,
    promise: Promise.resolve(),
  };
  recoveryFlight = flight;
  flight.promise = performRecovery(
    expectedGeneration,
    () => flight.retry,
  ).finally(() => {
    if (recoveryFlight === flight) recoveryFlight = null;
  });
  return flight.promise;
}
async function performRecovery(
  expectedGeneration: number,
  shouldRetry: () => boolean,
) {
  if (
    !state.session ||
    state.serviceMaintenance ||
    generation !== expectedGeneration
  )
    return;
  const previousRecoveryError = recoveryError;
  let recoveryGeneration = expectedGeneration;
  try {
    // Reconcile membership before opening another socket. No fresh join is ever implicit.
    const me = await api<{ roomId: string | null }>("/me");
    if (generation !== expectedGeneration) return;
    if (me.roomId) {
      const room = await api<Room>(`/rooms/${encodeURIComponent(me.roomId)}`);
      if (generation !== expectedGeneration) return;
      enter(room);
    } else {
      const hadRoom = roomId !== null;
      stop();
      emit({
        room: null,
        notice: hadRoom ? "已確認目前沒有房間席位，請選擇房間重新加入。" : null,
      });
    }
    // enter()/stop() may deliberately advance the generation within this recovery.
    recoveryGeneration = generation;
    await refreshRooms();
    if (generation !== recoveryGeneration) return;
    if (previousRecoveryError !== null && state.error === previousRecoveryError)
      emit({ error: null });
    if (recoveryError === previousRecoveryError) recoveryError = null;
  } catch (error) {
    // A leave, identity switch or newer room transition makes this async work obsolete.
    if (generation !== recoveryGeneration) return;
    recoveryError = error instanceof Error ? error.message : "無法恢復連線。";
    emit({ error: recoveryError });
    const permanent =
      (error instanceof ApiError || error instanceof HttpResponseError) &&
      [401, 403, 404].includes(error.status ?? 0);
    if (shouldRetry() && state.session && !permanent)
      scheduleRecovery(recoveryGeneration);
  }
}

async function run(task: () => Promise<void>) {
  if (state.busy) return;
  emit({ busy: true, error: null, notice: null });
  try {
    await task();
  } catch (error) {
    emit({
      error:
        error instanceof TypeError
          ? "暫時無法連線至服務，請確認網路或稍後再試；尚無法確認服務是否重新啟動。"
          : error instanceof Error
            ? error.message
            : "連線失敗，請稍後重試。",
    });
  } finally {
    emit({ busy: false });
  }
}
async function refreshRooms() {
  if (state.session) emit({ rooms: await api<RoomSummary[]>("/rooms") });
}
async function synchronizeRoom() {
  const id = roomId,
    expectedGeneration = generation;
  if (!id) return;
  const room = await api<Room>(`/rooms/${encodeURIComponent(id)}`);
  if (generation === expectedGeneration) apply(room);
}
async function executeCommand(command: PendingCommand) {
  if (command.generation !== generation) {
    clearPending();
    throw new Error("連線已改變，請先重新同步。");
  }
  pendingCommand = command;
  emit({ pendingAction: command.label });
  try {
    const result = await api<CommandResult>(
      command.path,
      command.method,
      command.body,
    );
    if (generation === command.generation) apply(result.room);
    if (pendingCommand === command) clearPending();
  } catch (error) {
    if (error instanceof ApiError) {
      if (pendingCommand === command) clearPending();
      if (error.code === "STALE_REVISION" && generation === command.generation)
        await synchronizeRoom();
      throw error;
    }
    if (generation !== command.generation) {
      if (pendingCommand === command) clearPending();
      throw error;
    }
    throw new Error(
      "操作結果尚未確認。可用同一識別碼重試，或先重新同步；不會自動重複操作。",
    );
  }
}
function gameCommand(
  operation:
    | "start"
    | "privilege"
    | "calls"
    | "plays"
    | "acknowledge-result"
    | "rematch",
  payload: Record<string, unknown> = {},
) {
  return run(async () => {
    if (pendingCommand)
      throw new Error("請先重試上一個未確認的操作，或重新同步。");
    const room = state.room;
    if (!room || state.status !== "online") throw new Error("請等候連線完成。");
    if (operation !== "start" && !room.game) throw new Error("尚未開局。");
    const root = `/rooms/${encodeURIComponent(room.roomId)}`;
    const path =
      operation === "start"
        ? `${root}/start`
        : operation === "rematch"
          ? `${root}/games/${encodeURIComponent(room.game!.gameId)}/rematch`
          : `${root}/games/${encodeURIComponent(room.game!.gameId)}/deals/${encodeURIComponent(room.game!.dealId)}/${operation}`;
    await executeCommand({
      path,
      method: operation === "privilege" ? "PUT" : "POST",
      body: {
        actionId: crypto.randomUUID(),
        expectedRevision: room.revision,
        ...payload,
      },
      generation,
      label:
        operation === "start"
          ? "開局"
          : operation === "privilege"
            ? "特權選擇"
            : operation === "plays"
              ? "出牌"
              : operation === "acknowledge-result"
                ? "確認結算"
                : operation === "rematch"
                  ? "返回等候室"
                  : "叫牌",
    });
  });
}
export const client = {
  checkService: () =>
    run(async () => {
      const revision = epochRevision;
      const requestedGeneration = generation;
      const response = await fetch("/health/ready", {
        cache: "no-store",
        signal: AbortSignal.timeout(8000),
      });
      const data = await response.json();
      if (revision !== epochRevision || requestedGeneration !== generation)
        return;
      if (!data || typeof data.instanceEpoch !== "string")
        throw new Error("無法確認服務狀態，請稍後再試。");
      const changed = observeEpoch(data.instanceEpoch);
      if (!response.ok || data.status !== "ready") {
        maintenance();
        return;
      }
      emit({ serviceMaintenance: false });
      if (!changed) await recover(true);
    }),
  acknowledgeResult: () => gameCommand("acknowledge-result"),
  rematch: () => gameCommand("rematch"),
  play: (card: Card) =>
    gameCommand("plays", { card: { suit: card.suit, rank: card.rank } }),
  start: () => gameCommand("start"),
  privilege: (use: boolean) => gameCommand("privilege", { use }),
  call: (call: Call) =>
    gameCommand(
      "calls",
      call.type === "PASS"
        ? { type: "PASS" }
        : { type: "BID", level: call.level, strain: call.strain },
    ),
  retryCommand: () =>
    run(async () => {
      if (pendingCommand) await executeCommand(pendingCommand);
    }),
  synchronize: () =>
    run(async () => {
      await synchronizeRoom();
      clearPending();
    }),
  async initialize() {
    if (initialized) return;
    initialized = true;
    try {
      serverEpoch = localStorage.getItem(EPOCH_KEY);
      const saved = JSON.parse(localStorage.getItem(KEY) ?? "null");
      if (saved?.token && saved?.playerId && saved?.displayName)
        emit({ session: saved });
    } catch {
      localStorage.removeItem(KEY);
    }
    emit({
      networkAvailable:
        typeof navigator === "undefined" || navigator.onLine !== false,
    });
    window.addEventListener("offline", () => emit({ networkAvailable: false }));
    const resume = () => {
      if (
        typeof document !== "undefined" &&
        document.visibilityState !== "visible"
      )
        return;
      emit({
        networkAvailable:
          typeof navigator === "undefined" || navigator.onLine !== false,
      });
      if (state.networkAvailable === false) return;
      clearTimeout(retry);
      // Read-only reconciliation, deduplicated with onclose/online recovery.
      // Never rejoin released seats or replay a pending game command.
      void recover(true);
    };
    window.addEventListener("online", resume);
    window.addEventListener("pageshow", resume);
    if (typeof document !== "undefined")
      document.addEventListener("visibilitychange", resume);
    await recover(true);
    emit({ initialized: true });
    // Same-origin tabs share a guest. A token change must stop the old room transport.
    window.addEventListener("storage", (event) => {
      if (event.key === KEY) {
        stop();
        emit({
          session: null,
          room: null,
          rooms: [],
          notice: "另一個分頁變更了訪客身分，請重新整理此頁。",
        });
      }
    });
  },
  login: (displayName: string) =>
    run(async () => {
      const nickname = nicknameValidation(displayName);
      if (nickname.error) throw new Error(nickname.error);
      const session = await api<Session>("/sessions", "POST", {
        displayName: nickname.normalized,
      });
      localStorage.setItem(KEY, JSON.stringify(session));
      emit({ session });
      await refreshRooms();
    }),
  create: () => run(async () => enter(await api<Room>("/rooms", "POST", {}))),
  join: (id: string) =>
    run(async () =>
      enter(
        await api<Room>(
          `/rooms/${encodeURIComponent(id.trim())}/join`,
          "POST",
          {},
        ),
      ),
    ),
  ready: () =>
    run(async () => {
      const id = roomId;
      if (!id) return;
      const player = state.room?.players.find(
        (p) => p.playerId === state.session?.playerId,
      );
      const requestedGeneration = generation;
      const response = await api<Room>(
        `/rooms/${encodeURIComponent(id)}/ready`,
        "PUT",
        {
          ready: !player?.ready,
        },
      );
      if (generation === requestedGeneration && state.status === "online")
        apply(response);
    }),
  leave: () =>
    run(async () => {
      const id = roomId;
      if (!id) return;
      const wasActive =
        state.room?.phase !== "WAITING" &&
        state.room?.phase !== "ABORTED" &&
        state.room?.phase !== "MATCH_RESULT" &&
        state.room?.game?.matchResult == null;
      await api(`/rooms/${encodeURIComponent(id)}/leave`, "POST", {});
      stop();
      emit({
        room: null,
        notice: wasActive
          ? "已退出遊戲，勝負依伺服器判定；已完成局數保留，未完成的局不計分。"
          : "已離開房間，準備狀態與席位已立即清除。",
      });
      await refreshRooms();
    }),
  refresh: () => run(refreshRooms),
  forget: () => {
    if (state.room) return;
    stop();
    localStorage.removeItem(KEY);
    emit({ session: null, rooms: [], error: null });
  },
  clearError: () => emit({ error: null }),
};
