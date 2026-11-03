import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Room } from "./model";
import { gameRoom, playedRoom } from "./test-fixtures";
import { operationRemainingMs } from "./timer-view";
const identity = {
  token: "local-token",
  playerId: "alice",
  displayName: "Alice",
};
const room = (revision = 1): Room => ({
  roomId: "ROOM01",
  revision,
  phase: "WAITING",
  capacity: 4,
  allReady: false,
  hostPlayerId: "alice",
  canStart: false,
  canRematch: false,
  lastMatch: null,
  game: null,
  you: {
    playerId: "alice",
    seat: 0,
    teamId: "NS",
    ownHand: [],
    legalCalls: [],
    legalCards: [],
    canAcknowledgeResult: false,
  },
  players: [
    {
      playerId: "alice",
      teamId: "NS",
      displayName: "Alice",
      seat: 0,
      ready: false,
      connected: true,
      disconnectedAt: null,
      reconnectDeadline: null,
    },
  ],
});
class MockSocket {
  static OPEN = 1;
  static instances: MockSocket[] = [];
  readyState = 0;
  onopen: (() => void) | null = null;
  onclose: ((event: { code: number; wasClean: boolean }) => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  sent: string[] = [];
  constructor(public url: string) {
    MockSocket.instances.push(this);
  }
  send(value: string) {
    this.sent.push(value);
  }
  close() {
    this.readyState = 3;
    this.onclose?.({ code: 1006, wasClean: false });
  }
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  receive(value: unknown) {
    this.onmessage?.({ data: JSON.stringify(value) });
  }
}
let values: Map<string, string>;
let storageListener: ((event: { key: string }) => void) | undefined;
let pendingResponse: Promise<Response> | undefined;
const fetchMock = vi.fn();
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
async function setup(inRoom = true) {
  values.set("bridgego.phase1.guest", JSON.stringify(identity));
  fetchMock.mockImplementation((path: string) => {
    if (path === "/api/me")
      return Promise.resolve(
        json({ ...identity, roomId: inRoom ? "ROOM01" : null }),
      );
    if (path === "/api/rooms") return Promise.resolve(json([]));
    if (path === "/api/rooms/ROOM01") return Promise.resolve(json(room()));
    if (pendingResponse) return pendingResponse;
    throw new Error(`Unexpected request: ${path}`);
  });
  const module = await import("./client");
  await module.client.initialize();
  return module;
}
beforeEach(() => {
  vi.spyOn(console, "warn").mockImplementation(() => {});
  vi.resetModules();
  vi.useFakeTimers();
  values = new Map();
  MockSocket.instances = [];
  fetchMock.mockReset();
  pendingResponse = undefined;
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("WebSocket", MockSocket);
  vi.stubGlobal("location", { protocol: "http:", host: "127.0.0.1:5173" });
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  });
  vi.stubGlobal("window", {
    addEventListener: (type: string, callback: typeof storageListener) => {
      if (type === "storage") storageListener = callback;
    },
  });
});
afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("central real-client lifecycle (mock transport, not integration)", () => {
  it("restores stable guest on refresh and subscribes without a token in URL", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    expect(getClientState().session?.playerId).toBe("alice");
    expect(socket.url).toBe("ws://127.0.0.1:5173/ws");
    expect(JSON.parse(socket.sent[0])).toEqual({
      type: "subscribe",
      token: "local-token",
      roomId: "ROOM01",
    });
    socket.receive({ type: "snapshot", room: room(2) });
    expect(getClientState().status).toBe("online");
    expect(getClientState().room?.revision).toBe(2);
  });
  it("reconnects and resubscribes with fresh full snapshot, ignoring stale response", async () => {
    const { getClientState } = await setup();
    const first = MockSocket.instances[0];
    first.open();
    first.receive({ type: "snapshot", room: room(5) });
    first.close();
    expect(getClientState().status).toBe("offline");
    await vi.advanceTimersByTimeAsync(500);
    const second = MockSocket.instances[1];
    second.open();
    expect(JSON.parse(second.sent[0]).roomId).toBe("ROOM01");
    second.receive({ type: "snapshot", room: room(8) });
    second.receive({ type: "snapshot", room: room(7) });
    expect(getClientState().room?.revision).toBe(8);
    expect(getClientState().status).toBe("online");
  });
  it("publishes a new presentation epoch with the first accepted reconnect snapshot, not stale frames", async () => {
    const { getClientState } = await setup();
    const first = MockSocket.instances[0];
    first.open();
    first.receive({ type: "snapshot", room: room(5) });
    expect(getClientState().connectionEpoch).toBe(1);
    first.receive({ type: "snapshot", room: room(6) });
    expect(getClientState().connectionEpoch).toBe(1);
    const previousFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((path: string, ...args: unknown[]) =>
      path === "/api/rooms/ROOM01"
        ? Promise.resolve(json(room(6)))
        : previousFetch(path, ...args),
    );
    first.close();
    await vi.advanceTimersByTimeAsync(500);
    const second = MockSocket.instances[1];
    second.open();
    second.receive({ type: "snapshot", room: room(4) });
    expect(getClientState().connectionEpoch).toBe(1);
    expect(getClientState().status).toBe("connecting");
    expect(getClientState().room?.revision).toBe(6);
    second.receive({ type: "snapshot", room: room(8) });
    expect(getClientState().connectionEpoch).toBe(2);
    expect(getClientState().status).toBe("online");
    expect(getClientState().room?.revision).toBe(8);
    second.receive({ type: "snapshot", room: room(8) });
    expect(getClientState().connectionEpoch).toBe(2);
    second.receive({
      type: "snapshot",
      room: { ...room(99), roomId: "OTHER" },
    });
    expect(getClientState().connectionEpoch).toBe(2);
    expect(getClientState().room?.revision).toBe(8);
  });
  it("accepts an equal-revision reconnect baseline and ignores callbacks from the replaced socket", async () => {
    const { getClientState } = await setup();
    const first = MockSocket.instances[0];
    first.open();
    first.receive({ type: "snapshot", room: room(6) });
    const previousFetch = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((path: string, ...args: unknown[]) =>
      path === "/api/rooms/ROOM01"
        ? Promise.resolve(json(room(6)))
        : previousFetch(path, ...args),
    );
    first.close();
    await vi.advanceTimersByTimeAsync(500);
    const second = MockSocket.instances[1];
    second.open();
    second.receive({ type: "snapshot", room: room(6) });
    expect(getClientState().status).toBe("online");
    expect(getClientState().connectionEpoch).toBe(2);
    first.receive({ type: "snapshot", room: room(99) });
    first.close();
    expect(getClientState().room?.revision).toBe(6);
    expect(getClientState().status).toBe("online");
    expect(getClientState().connectionEpoch).toBe(2);
  });
  it("does not close a healthy idle socket for over 60 seconds without application traffic", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room() });
    await vi.advanceTimersByTimeAsync(65000);
    expect(socket.readyState).toBe(MockSocket.OPEN);
    expect(getClientState().status).toBe("online");
  });
  it("does not close a native-live socket when JavaScript timers resume after suspension", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room() });
    vi.setSystemTime(Date.now() + 120000);
    await vi.advanceTimersByTimeAsync(2000);
    expect(socket.readyState).toBe(MockSocket.OPEN);
    expect(getClientState().room?.roomId).toBe("ROOM01");
  });
  it("ignores stale socket events after replacement and records safe close diagnostics", async () => {
    const { getClientState, getConnectionDiagnostics } = await setup();
    const old = MockSocket.instances[0];
    old.open();
    old.receive({ type: "snapshot", room: room(2) });
    old.close();
    await vi.advanceTimersByTimeAsync(500);
    const current = MockSocket.instances[1];
    current.open();
    current.receive({ type: "snapshot", room: room(3) });
    current.onerror?.(); // Error alone must not invent a close or drop the seat.
    expect(getClientState().status).toBe("online");
    old.receive({ type: "snapshot", room: room(99) });
    old.onclose?.({ code: 1006, wasClean: false });
    old.onopen?.();
    expect(getClientState().room?.revision).toBe(3);
    expect(current.readyState).toBe(MockSocket.OPEN);
    expect(getConnectionDiagnostics()).toHaveLength(1);
    const diagnostic = JSON.stringify(getConnectionDiagnostics());
    expect(diagnostic).not.toContain(identity.token);
    expect(diagnostic).not.toContain("ROOM01");
    expect(diagnostic).toContain("1006");
  });
  it("discards a disconnected seat, never autojoins, and permits an explicit capacity-aware join", async () => {
    const { client, getClientState } = await setup();
    const first = MockSocket.instances[0];
    first.open();
    first.receive({
      type: "snapshot",
      room: { ...room(2), players: [{ ...room().players[0], ready: true }] },
    });
    first.close();
    expect(getClientState().room).toBeNull();
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(
        path === "/api/me"
          ? json({ ...identity, roomId: null })
          : path.endsWith("/join")
            ? json({ code: "ROOM_FULL", message: "Full" }, 409)
            : json([]),
      ),
    );
    await vi.advanceTimersByTimeAsync(30000);
    expect(getClientState().room).toBeNull();
    expect(MockSocket.instances).toHaveLength(1); // /me prevented an invalid resubscription.
    expect(
      fetchMock.mock.calls.some(([path]) => String(path).endsWith("/join")),
    ).toBe(false);
    await client.join("ROOM01");
    expect(getClientState().error).toContain("已滿");
    expect(getClientState().room).toBeNull();
  });
  it.each(["leave", "token change", "new room"])(
    "ignores stale membership recovery after %s",
    async (action) => {
      const { client, getClientState } = await setup();
      const socket = MockSocket.instances[0];
      socket.open();
      socket.receive({ type: "snapshot", room: room(2) });
      let resolve!: (response: Response) => void;
      const pendingMe = new Promise<Response>((r) => {
        resolve = r;
      });
      fetchMock.mockImplementation(
        (path: string, options?: { method?: string }) => {
          if (path === "/api/me") return pendingMe;
          if (path.endsWith("/leave"))
            return Promise.resolve(json({ left: true }));
          if (path === "/api/rooms" && options?.method === "POST")
            return Promise.resolve(json({ ...room(1), roomId: "NEWROOM" }));
          return Promise.resolve(json([]));
        },
      );
      socket.close();
      await vi.advanceTimersByTimeAsync(500);
      if (action === "leave") await client.leave();
      else if (action === "token change")
        storageListener?.({ key: "bridgego.phase1.guest" });
      else await client.create();
      resolve(json({ ...identity, roomId: "ROOM01" }));
      await vi.advanceTimersByTimeAsync(10000);
      expect(getClientState().room?.roomId ?? null).toBe(
        action === "new room" ? "NEWROOM" : null,
      );
      expect(MockSocket.instances).toHaveLength(action === "new room" ? 2 : 1);
    },
  );
  it("stops recovery on server restart 401 instead of repeatedly opening sockets", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    fetchMock.mockResolvedValue(
      json({ code: "UNAUTHORIZED", message: "Expired" }, 401),
    );
    socket.close();
    await vi.advanceTimersByTimeAsync(30000);
    expect(getClientState().session).toBeNull();
    expect(getClientState().error).toContain("失效");
    expect(MockSocket.instances).toHaveLength(1);
  });
  it("retries failed HTTP reconciliation then stops once membership is absent", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    fetchMock.mockRejectedValueOnce(new Error("Temporary network failure"));
    socket.close();
    await vi.advanceTimersByTimeAsync(500);
    expect(getClientState().error).toContain("Temporary");
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(
        path === "/api/me" ? json({ ...identity, roomId: null }) : json([]),
      ),
    );
    await vi.advanceTimersByTimeAsync(30000);
    const calls = fetchMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30000);
    expect(fetchMock.mock.calls).toHaveLength(calls);
    expect(MockSocket.instances).toHaveLength(1);
  });
  it("keeps at most 20 safe timestamped diagnostic entries", async () => {
    const { getConnectionDiagnostics } = await setup();
    for (let i = 0; i < 25; i++) {
      const socket = MockSocket.instances.at(-1)!;
      socket.open();
      socket.close();
      await vi.advanceTimersByTimeAsync(2000);
    }
    const entries = getConnectionDiagnostics();
    expect(entries).toHaveLength(20);
    expect(entries[0].timestamp).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(JSON.stringify(entries)).not.toMatch(
      /local-token|ROOM01|127\.0\.0\.1/,
    );
  });
  it("ignores a late ready response after last-connection loss", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room(2) });
    let resolve!: (response: Response) => void;
    pendingResponse = new Promise((r) => {
      resolve = r;
    });
    const ready = client.ready();
    socket.close();
    resolve(
      json({ ...room(3), players: [{ ...room().players[0], ready: true }] }),
    );
    await ready;
    expect(getClientState().room).toBeNull();
  });
  it("does not let a late ready response overwrite a newer room snapshot", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room(2) });
    let resolve!: (response: Response) => void;
    pendingResponse = new Promise((r) => {
      resolve = r;
    });
    const ready = client.ready();
    socket.receive({
      type: "snapshot",
      room: { ...room(4), players: [{ ...room().players[0], ready: true }] },
    });
    resolve(json(room(3)));
    await ready;
    expect(getClientState().room?.revision).toBe(4);
    expect(getClientState().room?.players[0].ready).toBe(true);
  });
  it("manual leave only clears room after acknowledged success and never reconnects", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    pendingResponse = Promise.resolve(json({ left: true }));
    await client.leave();
    expect(getClientState().room).toBeNull();
    expect(socket.readyState).toBe(3);
    await vi.advanceTimersByTimeAsync(10000);
    expect(MockSocket.instances).toHaveLength(1);
  });
  it("failed leave retains room and reports failure instead of pretending it released the seat", async () => {
    const { client, getClientState } = await setup();
    pendingResponse = Promise.resolve(
      json({ code: "TEMPORARY", message: "Unavailable" }, 503),
    );
    await client.leave();
    expect(getClientState().room?.roomId).toBe("ROOM01");
    expect(getClientState().error).toBe("Unavailable");
  });
  it("identity changes in another tab close the old socket and discard its room", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    storageListener?.({ key: "bridgego.phase1.guest" });
    expect(getClientState().session).toBeNull();
    expect(getClientState().room).toBeNull();
    expect(socket.readyState).toBe(3);
  });
  it("401 clears expired session after a backend restart", async () => {
    values.set("bridgego.phase1.guest", JSON.stringify(identity));
    fetchMock.mockResolvedValue(
      json({ code: "UNAUTHORIZED", message: "Expired" }, 401),
    );
    const { client, getClientState } = await import("./client");
    await client.initialize();
    expect(getClientState().session).toBeNull();
    expect(values.has("bridgego.phase1.guest")).toBe(false);
    expect(getClientState().error).toContain("失效");
  });
  it("host start sends a UUID and expected revision then accepts only personalized response", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({
      type: "snapshot",
      room: { ...room(8), allReady: true, canStart: true },
    });
    fetchMock.mockResolvedValueOnce(
      json({
        actionId: "echo",
        outcome: "APPLIED",
        appliedRevision: 10,
        room: gameRoom(),
      }),
    );
    await client.start();
    const [path, options] = fetchMock.mock.calls.at(-1)!;
    const body = JSON.parse(options.body);
    expect(path).toBe("/api/rooms/ROOM01/start");
    expect(body.expectedRevision).toBe(8);
    expect(body.actionId).toMatch(/^[0-9a-f-]{36}$/);
    expect(Object.keys(body).sort()).toEqual(["actionId", "expectedRevision"]);
    expect(getClientState().room?.you?.ownHand).toHaveLength(13);
  });
  it("retries an uncertain command with the same idempotency key and payload", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom() });
    fetchMock.mockRejectedValueOnce(new TypeError("network"));
    await client.privilege(true);
    expect(getClientState().pendingAction).toBe("特權選擇");
    const first = fetchMock.mock.calls.at(-1)!;
    fetchMock.mockResolvedValueOnce(
      json({
        actionId: "echo",
        outcome: "REPLAYED",
        appliedRevision: 11,
        room: { ...gameRoom(), revision: 11 },
      }),
    );
    await client.retryCommand();
    const second = fetchMock.mock.calls.at(-1)!;
    expect(second[0]).toBe(first[0]);
    expect(second[1].body).toBe(first[1].body);
    expect(getClientState().pendingAction).toBeNull();
  });
  it("refetches on stale command without automatically rebidding", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("BIDDING") });
    fetchMock
      .mockResolvedValueOnce(
        json({ code: "STALE_REVISION", message: "stale" }, 409),
      )
      .mockResolvedValueOnce(json({ ...gameRoom("BIDDING"), revision: 20 }));
    await client.call({ type: "BID", level: 1, strain: "CLUB" });
    expect(getClientState().room?.revision).toBe(20);
    expect(getClientState().pendingAction).toBeNull();
    expect(
      fetchMock.mock.calls.filter(([path]) => String(path).endsWith("/calls")),
    ).toHaveLength(1);
  });
  it("rejects duplicate clicks while command is pending", async () => {
    const { client } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room(8) });
    let resolve!: (response: Response) => void;
    pendingResponse = new Promise((r) => {
      resolve = r;
    });
    const first = client.start();
    await client.start();
    expect(
      fetchMock.mock.calls.filter(([path]) => String(path).endsWith("/start")),
    ).toHaveLength(1);
    resolve(
      json({
        actionId: "echo",
        outcome: "APPLIED",
        appliedRevision: 10,
        room: gameRoom(),
      }),
    );
    await first;
  });
  it("restores the same active private hand via membership recovery without fresh join", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("BIDDING") });
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(
        json(
          path === "/api/me"
            ? { ...identity, roomId: "ROOM01" }
            : path === "/api/rooms/ROOM01"
              ? gameRoom("BIDDING")
              : [],
        ),
      ),
    );
    socket.close();
    expect(getClientState().room).toBeNull();
    expect(getClientState().notice).toContain("10 秒");
    await vi.advanceTimersByTimeAsync(500);
    const resumed = MockSocket.instances[1];
    resumed.open();
    resumed.receive({ type: "snapshot", room: gameRoom("BIDDING") });
    expect(getClientState().room?.game?.dealId).toBe("deal-one");
    expect(getClientState().room?.you?.ownHand).toEqual(
      gameRoom().you!.ownHand,
    );
    expect(
      fetchMock.mock.calls.some(([path]) => String(path).endsWith("/join")),
    ).toBe(false);
  });
  it("clears a pending game action and all private state on identity change", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom() });
    fetchMock.mockRejectedValueOnce(new TypeError("network"));
    await client.privilege(true);
    storageListener?.({ key: "bridgego.phase1.guest" });
    expect(getClientState().room).toBeNull();
    expect(getClientState().pendingAction).toBeNull();
    expect(getClientState().session).toBeNull();
  });
  it("anchors accepted timer snapshots and ignores stale revisions without refreshing their clock", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    const now = vi.spyOn(performance, "now").mockReturnValue(1000);
    const initial = gameRoom("BIDDING");
    initial.game!.operationTimer!.remainingMs = 10000;
    socket.receive({ type: "snapshot", room: initial });
    now.mockReturnValue(4000);
    const stale = structuredClone(initial);
    stale.revision--;
    stale.game!.operationTimer!.remainingMs = 20000;
    socket.receive({ type: "snapshot", room: stale });
    expect(getClientState().snapshotReceivedAt).toBe(1000);
    const delayed = structuredClone(initial);
    delayed.game!.operationTimer!.remainingMs = 12000;
    socket.receive({ type: "snapshot", room: delayed });
    const state = getClientState();
    expect(
      operationRemainingMs(
        state.room!.game!.operationTimer,
        state.snapshotReceivedAt,
        4000,
      ),
    ).toBe(7000);
  });
  it("never sends timeout votes or Pass from local elapsed time", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("BIDDING") });
    const calls = fetchMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(65000);
    expect(fetchMock.mock.calls).toHaveLength(calls);
    expect(getClientState().room?.game?.auction.calls).toHaveLength(0);
    expect(socket.readyState).toBe(MockSocket.OPEN);
  });
});

describe("v0.3 play command and terminal recovery", () => {
  it("retries the same play on the original deal after a newer deal snapshot without leaking the hand", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: playedRoom() });
    fetchMock.mockRejectedValueOnce(new TypeError("network"));
    await client.play({ suit: "SPADE", rank: 2 });
    const first = fetchMock.mock.calls.at(-1)!;
    expect(first[0]).toContain("/deals/deal-one/plays");
    expect(Object.keys(JSON.parse(first[1].body)).sort()).toEqual([
      "actionId",
      "card",
      "expectedRevision",
    ]);
    expect(JSON.parse(first[1].body).card).toEqual({ suit: "SPADE", rank: 2 });
    const next = gameRoom();
    next.revision = 90;
    next.game!.dealId = "deal-two";
    next.game!.dealIndex = 2;
    socket.receive({ type: "snapshot", room: next });
    fetchMock.mockResolvedValueOnce(
      json({
        actionId: "echo",
        outcome: "REPLAYED",
        appliedRevision: 11,
        room: next,
      }),
    );
    await client.retryCommand();
    const second = fetchMock.mock.calls.at(-1)!;
    expect(second[0]).toBe(first[0]);
    expect(second[1].body).toBe(first[1].body);
    expect(getClientState().room?.game?.dealId).toBe("deal-two");
  });
  it("refetches stale play without automatically playing another card", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: playedRoom() });
    fetchMock
      .mockResolvedValueOnce(
        json({ code: "STALE_REVISION", message: "stale" }, 409),
      )
      .mockResolvedValueOnce(json({ ...playedRoom(), revision: 30 }));
    await client.play({ suit: "SPADE", rank: 2 });
    expect(getClientState().room?.revision).toBe(30);
    expect(
      fetchMock.mock.calls.filter(([path]) => String(path).endsWith("/plays")),
    ).toHaveLength(1);
  });
  it("rejects delayed play response after identity change", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: playedRoom() });
    let resolve!: (value: Response) => void;
    pendingResponse = new Promise((r) => (resolve = r));
    const pending = client.play({ suit: "SPADE", rank: 2 });
    storageListener?.({ key: "bridgego.phase1.guest" });
    resolve(
      json({
        actionId: "echo",
        outcome: "APPLIED",
        appliedRevision: 20,
        room: { ...playedRoom(), revision: 20 },
      }),
    );
    await pending;
    expect(getClientState().room).toBeNull();
    expect(getClientState().session).toBeNull();
  });
  it("terminal close describes result recovery, not a new ten-second game grace", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("MATCH_RESULT") });
    socket.close();
    expect(getClientState().notice).toContain("既定結果不因斷線改變");
    expect(getClientState().notice).not.toContain("10 秒");
  });
  it("final trick display recovers the committed result without new game grace", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    const final = gameRoom("MATCH_RESULT");
    final.phase = "TRICK_RESULT";
    final.game!.matchResult = {
      outcome: "SCORED",
      winnerTeamId: "NS",
      reason: "THREE_DEALS_COMPLETE",
      failedTeamIds: [],
    };
    final.game!.operationTimer = {
      kind: "TRICK_RESULT",
      turnSeat: null,
      remainingMs: 1500,
      paused: false,
    };
    socket.receive({ type: "snapshot", room: final });
    socket.close();
    expect(getClientState().notice).toContain("既定結果不因斷線改變");
    expect(getClientState().notice).not.toContain("10 秒");
  });
  it("display time cannot auto-submit a play or close native transport", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: playedRoom() });
    const count = fetchMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(65000);
    expect(fetchMock.mock.calls.length).toBe(count);
    expect(socket.readyState).toBe(MockSocket.OPEN);
    expect(getClientState().room?.phase).toBe("PLAYING");
  });
});

describe("v0.4 acknowledgment and rematch keep command safeguards", () => {
  it("sends one scoped acknowledgment and blocks repeated clicks until response", async () => {
    const { client } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("DEAL_RESULT") });
    let resolve!: (value: Response) => void;
    pendingResponse = new Promise((r) => (resolve = r));
    const first = client.acknowledgeResult();
    await client.acknowledgeResult();
    const calls = fetchMock.mock.calls.filter(([path]) =>
      String(path).endsWith("/acknowledge-result"),
    );
    expect(calls).toHaveLength(1);
    expect(calls[0][0]).toBe(
      "/api/rooms/ROOM01/games/game-one/deals/deal-one/acknowledge-result",
    );
    expect(Object.keys(JSON.parse(calls[0][1].body)).sort()).toEqual([
      "actionId",
      "expectedRevision",
    ]);
    resolve(
      json({
        actionId: "echo",
        outcome: "APPLIED",
        appliedRevision: 11,
        room: { ...gameRoom("DEAL_RESULT"), revision: 11 },
      }),
    );
    await first;
  });
  it("a delayed acknowledgment cannot overwrite timeout advancement to the new deal", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("DEAL_RESULT") });
    let resolve!: (value: Response) => void;
    pendingResponse = new Promise((r) => (resolve = r));
    const pending = client.acknowledgeResult();
    const next = gameRoom();
    next.revision = 30;
    next.game!.dealId = "next-deal";
    next.game!.dealIndex = 2;
    socket.receive({ type: "snapshot", room: next });
    resolve(
      json({
        actionId: "echo",
        outcome: "APPLIED",
        appliedRevision: 11,
        room: { ...gameRoom("DEAL_RESULT"), revision: 11 },
      }),
    );
    await pending;
    expect(getClientState().room?.game?.dealId).toBe("next-deal");
  });
  it("rematch targets game rather than deal and returns waiting without automatic start", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("MATCH_RESULT") });
    fetchMock.mockResolvedValueOnce(
      json({
        actionId: "echo",
        outcome: "APPLIED",
        appliedRevision: 20,
        room: room(20),
      }),
    );
    await client.rematch();
    const [path, options] = fetchMock.mock.calls.at(-1)!;
    expect(path).toBe("/api/rooms/ROOM01/games/game-one/rematch");
    expect(JSON.parse(options.body).expectedRevision).toBe(10);
    expect(getClientState().room?.phase).toBe("WAITING");
    expect(getClientState().room?.game).toBeNull();
    expect(
      fetchMock.mock.calls.some(([p]) => String(p).endsWith("/start")),
    ).toBe(false);
  });
  it("uncertain rematch retry preserves old game identity after waiting snapshot", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("MATCH_RESULT") });
    fetchMock.mockRejectedValueOnce(new TypeError("network"));
    await client.rematch();
    const first = fetchMock.mock.calls.at(-1)!;
    socket.receive({ type: "snapshot", room: room(25) });
    fetchMock.mockResolvedValueOnce(
      json({
        actionId: "echo",
        outcome: "REPLAYED",
        appliedRevision: 20,
        room: room(25),
      }),
    );
    await client.retryCommand();
    const second = fetchMock.mock.calls.at(-1)!;
    expect(second[0]).toBe(first[0]);
    expect(second[1].body).toBe(first[1].body);
    expect(getClientState().room?.game).toBeNull();
  });
  it("stale acknowledgment refetches without duplicate acknowledgment", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("DEAL_RESULT") });
    fetchMock
      .mockResolvedValueOnce(
        json({ code: "STALE_REVISION", message: "stale" }, 409),
      )
      .mockResolvedValueOnce(json({ ...gameRoom(), revision: 22 }));
    await client.acknowledgeResult();
    expect(getClientState().room?.phase).toBe("PRIVILEGE_DECISION");
    expect(
      fetchMock.mock.calls.filter(([path]) =>
        String(path).endsWith("/acknowledge-result"),
      ),
    ).toHaveLength(1);
  });
  it("result timer cannot acknowledge, advance or rematch from local elapsed time", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    const result = gameRoom("DEAL_RESULT");
    result.game!.operationTimer = {
      kind: "DEAL_RESULT",
      turnSeat: null,
      remainingMs: 5000,
      paused: false,
    };
    socket.receive({ type: "snapshot", room: result });
    const count = fetchMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30000);
    expect(fetchMock.mock.calls.length).toBe(count);
    expect(getClientState().room?.phase).toBe("DEAL_RESULT");
    expect(socket.readyState).toBe(MockSocket.OPEN);
  });
});

describe("v0.6.6 interrupted HTTP response recovery", () => {
  it.each([
    [500, "", "HTTP 500"],
    [502, "<html>private proxy detail</html>", "HTTP 502"],
    [200, '{"roomId":', "HTTP 200"],
    [204, null, "HTTP 204"],
  ])(
    "reports incomplete/non-JSON HTTP %s without exposing body",
    async (status, body, detail) => {
      const { client, getClientState } = await setup(false);
      fetchMock.mockResolvedValueOnce(new Response(body, { status }));
      await client.refresh();
      expect(getClientState().error).toContain(detail);
      expect(getClientState().error).not.toMatch(
        /Unexpected|private proxy detail|<html>/,
      );
      expect(getClientState().rooms).toEqual([]);
    },
  );
  it("retains uncertain acknowledgment identity and never automatically repeats it", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: gameRoom("DEAL_RESULT") });
    fetchMock.mockResolvedValueOnce(new Response("", { status: 500 }));
    await client.acknowledgeResult();
    const first = fetchMock.mock.calls.at(-1)!;
    expect(getClientState().pendingAction).toBe("確認結算");
    expect(getClientState().error).toContain("操作結果尚未確認");
    await vi.advanceTimersByTimeAsync(3000);
    expect(fetchMock.mock.calls.at(-1)).toBe(first);
    fetchMock.mockResolvedValueOnce(json({ room: gameRoom("DEAL_RESULT") }));
    await client.retryCommand();
    const retried = fetchMock.mock.calls.at(-1)!;
    expect(retried[0]).toBe(first[0]);
    expect(retried[1].body).toBe(first[1].body);
    expect(getClientState().pendingAction).toBeNull();
  });
  it("retries initial membership lookup after empty proxy 500 and clears stale error", async () => {
    values.set("bridgego.phase1.guest", JSON.stringify(identity));
    fetchMock.mockResolvedValueOnce(new Response("", { status: 500 }));
    const { client, getClientState } = await import("./client");
    await client.initialize();
    expect(getClientState().error).toContain("HTTP 500");
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(json(path === "/api/me" ? { roomId: null } : [])),
    );
    await vi.advanceTimersByTimeAsync(500);
    expect(getClientState().error).toBeNull();
    expect(getClientState().room).toBeNull();
    expect(MockSocket.instances).toHaveLength(0);
    expect(
      fetchMock.mock.calls.every(([, options]) => options.method === "GET"),
    ).toBe(true);
  });
  it("clears stale error and pending-recovery notice when authoritative membership is absent", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room() });
    socket.close();
    fetchMock.mockResolvedValueOnce(new Response("", { status: 500 }));
    await vi.advanceTimersByTimeAsync(500);
    expect(getClientState().error).toContain("HTTP 500");
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(json(path === "/api/me" ? { roomId: null } : [])),
    );
    await vi.advanceTimersByTimeAsync(1000);
    expect(getClientState().error).toBeNull();
    expect(getClientState().notice).not.toContain("正在確認");
    expect(MockSocket.instances).toHaveLength(1);
    expect(
      fetchMock.mock.calls.some(([path]) => String(path).endsWith("/join")),
    ).toBe(false);
  });
});

describe("v0.6.6 recovery boundaries", () => {
  it.each([401, 403, 404])(
    "does not loop on empty permanent HTTP %s",
    async (status) => {
      values.set("bridgego.phase1.guest", JSON.stringify(identity));
      fetchMock.mockImplementation(() =>
        Promise.resolve(new Response("", { status })),
      );
      const { client } = await import("./client");
      await client.initialize();
      await vi.advanceTimersByTimeAsync(30000);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    },
  );
  it("logout cancels initial transient GET recovery", async () => {
    values.set("bridgego.phase1.guest", JSON.stringify(identity));
    fetchMock.mockImplementation(() =>
      Promise.resolve(new Response("", { status: 500 })),
    );
    const { client } = await import("./client");
    await client.initialize();
    client.forget();
    await vi.advanceTimersByTimeAsync(30000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it("does not erase a newer unrelated action error when reconciliation succeeds", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room() });
    socket.close();
    fetchMock.mockResolvedValueOnce(new Response("", { status: 500 }));
    await vi.advanceTimersByTimeAsync(500);
    let resolve!: (value: Response) => void;
    fetchMock.mockImplementation((path: string) => {
      if (path === "/api/me")
        return new Promise<Response>((r) => {
          resolve = r;
        });
      if (path.endsWith("/join"))
        return Promise.resolve(json({ code: "ROOM_FULL" }, 409));
      return Promise.resolve(json([]));
    });
    await vi.advanceTimersByTimeAsync(1000);
    await client.join("ROOM01");
    const unrelated = getClientState().error;
    expect(unrelated).toContain("已滿");
    resolve(json({ roomId: null }));
    await vi.advanceTimersByTimeAsync(0);
    expect(getClientState().error).toBe(unrelated);
  });
  it("replacement close retains the waiting policy only when HTTP confirmed WAITING", async () => {
    const { getClientState } = await setup();
    const socket = MockSocket.instances[0];
    // A replacement socket can fail before its authoritative snapshot arrives.
    socket.open();
    socket.receive({ type: "snapshot", room: room() });
    socket.close();
    await vi.advanceTimersByTimeAsync(500);
    const next = MockSocket.instances[1];
    next.open();
    next.close();
    // Its HTTP snapshot is WAITING, therefore the waiting policy is still legitimate.
    expect(getClientState().notice).toContain("正在確認席位");
  });
});

it("retries a transient room-list failure after its own membership reconciliation", async () => {
  values.set("bridgego.phase1.guest", JSON.stringify(identity));
  fetchMock.mockImplementation((path: string) =>
    Promise.resolve(
      path === "/api/me"
        ? json({ roomId: null })
        : new Response("", { status: 500 }),
    ),
  );
  const { client, getClientState } = await import("./client");
  await client.initialize();
  expect(getClientState().error).toContain("HTTP 500");
  fetchMock.mockImplementation((path: string) =>
    Promise.resolve(json(path === "/api/me" ? { roomId: null } : [])),
  );
  await vi.advanceTimersByTimeAsync(500);
  expect(getClientState().error).toBeNull();
  expect(
    fetchMock.mock.calls.every(([, options]) => options.method === "GET"),
  ).toBe(true);
});

describe("public instance epoch and planned maintenance", () => {
  const withEpoch = (value: unknown, epoch: string, status = 200) =>
    new Response(JSON.stringify(value), {
      status,
      headers: {
        "Content-Type": "application/json",
        "X-Bridge-Instance-Epoch": epoch,
      },
    });
  it("confirmed restart clears identity, room and pending state before parsing a 401", async () => {
    values.set("bridgego.server.epoch", "boot-one");
    const { client, getClientState } = await setup();
    fetchMock.mockResolvedValue(
      withEpoch({ code: "UNAUTHORIZED" }, "boot-two", 401),
    );
    await client.refresh();
    expect(getClientState().session).toBeNull();
    expect(getClientState().room).toBeNull();
    expect(getClientState().pendingAction).toBeNull();
    expect(getClientState().notice).toContain("服務已重新啟動");
    expect(values.get("bridgego.server.epoch")).toBe("boot-two");
    expect(values.has("bridgego.phase1.guest")).toBe(false);
  });
  it("same boot 1006 recovery retains identity and never creates or joins", async () => {
    values.set("bridgego.server.epoch", "boot-one");
    const { getClientState } = await setup();
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (...args) => {
      const response = await original(...args);
      response.headers.set("X-Bridge-Instance-Epoch", "boot-one");
      return response;
    });
    MockSocket.instances[0].close();
    await vi.advanceTimersByTimeAsync(501);
    expect(getClientState().session?.token).toBe(identity.token);
    expect(
      fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
    ).toBe(true);
  });
  it("maintenance clears live room without clearing identity until changed health epoch", async () => {
    values.set("bridgego.server.epoch", "boot-one");
    const { client, getClientState } = await setup();
    MockSocket.instances[0].receive({
      type: "maintenance",
      instanceEpoch: "boot-one",
    });
    expect(getClientState().room).toBeNull();
    expect(getClientState().serviceMaintenance).toBe(true);
    expect(getClientState().session).not.toBeNull();
    const calls = fetchMock.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchMock.mock.calls.length).toBe(calls);
    fetchMock.mockResolvedValue(
      json({ instanceEpoch: "boot-two", status: "ready" }),
    );
    await client.checkService();
    expect(getClientState().session).toBeNull();
    expect(getClientState().serviceMaintenance).toBe(false);
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe("/health/ready");
  });
  it("late old HTTP response cannot roll back a newer socket-confirmed boot", async () => {
    values.set("bridgego.server.epoch", "boot-one");
    const { client, getClientState } = await setup();
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValue(
      new Promise<Response>((r) => {
        resolve = r;
      }),
    );
    const request = client.refresh();
    MockSocket.instances[0].receive({
      type: "maintenance",
      instanceEpoch: "boot-two",
    });
    resolve(withEpoch([], "boot-one"));
    await request;
    expect(values.get("bridgego.server.epoch")).toBe("boot-two");
    expect(getClientState().session).toBeNull();
    expect(getClientState().rooms).toEqual([]);
    expect(getClientState().notice).toContain("服務已重新啟動");
  });
});

it("a confirmed restart during in-flight play clears pending action and never replays it", async () => {
  values.set("bridgego.server.epoch", "boot-one");
  const { client, getClientState } = await setup();
  const socket = MockSocket.instances[0];
  socket.open();
  socket.receive({ type: "snapshot", room: playedRoom() });
  let resolve!: (value: Response) => void;
  pendingResponse = new Promise((r) => {
    resolve = r;
  });
  const playing = client.play({ suit: "SPADE", rank: 2 });
  expect(getClientState().pendingAction).not.toBeNull();
  socket.receive({ type: "maintenance", instanceEpoch: "boot-two" });
  expect(getClientState().pendingAction).toBeNull();
  resolve(
    new Response(JSON.stringify({ room: playedRoom(), outcome: "APPLIED" }), {
      headers: {
        "Content-Type": "application/json",
        "X-Bridge-Instance-Epoch": "boot-one",
      },
    }),
  );
  await playing;
  await vi.advanceTimersByTimeAsync(10000);
  expect(getClientState().room).toBeNull();
  expect(getClientState().session).toBeNull();
  expect(values.get("bridgego.server.epoch")).toBe("boot-two");
  expect(
    fetchMock.mock.calls.filter(([path]) => String(path).endsWith("/plays")),
  ).toHaveLength(1);
  await client.retryCommand();
  expect(
    fetchMock.mock.calls.filter(([path]) => String(path).endsWith("/plays")),
  ).toHaveLength(1);
});

it("maintenance during delayed JSON cannot restore an old room with the same token", async () => {
  values.set("bridgego.server.epoch", "boot-one");
  const { client, getClientState } = await setup();
  const socket = MockSocket.instances[0];
  let resolveBody!: (value: unknown) => void;
  const response = json({});
  vi.spyOn(response, "json").mockReturnValue(
    new Promise((r) => {
      resolveBody = r;
    }),
  );
  fetchMock.mockResolvedValue(response);
  const joining = client.join("ROOM01");
  await Promise.resolve();
  expect(response.json).toHaveBeenCalled();
  socket.receive({ type: "maintenance", instanceEpoch: "boot-one" });
  expect(getClientState().session?.token).toBe(identity.token);
  resolveBody(room(25));
  await joining;
  expect(getClientState().room).toBeNull();
  expect(getClientState().serviceMaintenance).toBe(true);
  expect(MockSocket.instances).toHaveLength(1);
});

it("epoch persistence quota failure does not fail a valid API or lose in-memory baseline", async () => {
  const { client, getClientState } = await setup();
  const original = localStorage.setItem;
  vi.spyOn(localStorage, "setItem").mockImplementation((key, value) => {
    if (key === "bridgego.server.epoch") throw new Error("quota");
    original(key, value);
  });
  const response = json([]);
  response.headers.set("X-Bridge-Instance-Epoch", "boot-one");
  fetchMock.mockResolvedValue(response);
  await client.refresh();
  expect(getClientState().error).toBeNull();
  const next = json([]);
  next.headers.set("X-Bridge-Instance-Epoch", "boot-two");
  fetchMock.mockResolvedValue(next);
  await client.refresh();
  expect(getClientState().session).toBeNull();
  expect(getClientState().notice).toContain("服務已重新啟動");
});

it("network fetch failure has Chinese guidance without claiming confirmed restart", async () => {
  const { client, getClientState } = await setup();
  fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
  await client.refresh();
  expect(getClientState().error).toContain("暫時無法連線至服務");
  expect(getClientState().error).not.toContain("服務已重新啟動");
  expect(getClientState().session?.token).toBe(identity.token);
});

describe("foreground lifecycle recovery", () => {
  function lifecycle() {
    const windowEvents = new Map<string, () => void>();
    const documentEvents = new Map<string, () => void>();
    const doc = {
      visibilityState: "visible",
      addEventListener: (name: string, fn: () => void) =>
        documentEvents.set(name, fn),
    };
    vi.stubGlobal("document", doc);
    vi.stubGlobal("navigator", { onLine: true });
    vi.stubGlobal("window", {
      addEventListener: (name: string, fn: () => void) =>
        windowEvents.set(name, fn),
    });
    return {
      doc,
      show: () => windowEvents.get("pageshow")?.(),
      online: () => windowEvents.get("online")?.(),
      visibility: () => documentEvents.get("visibilitychange")?.(),
    };
  }
  it("deduplicates pageshow, visible and online while preserving a healthy socket", async () => {
    const life = lifecycle();
    await setup();
    const ws = MockSocket.instances[0];
    ws.open();
    ws.receive({ type: "snapshot", room: room(2) });
    let resolve!: (r: Response) => void;
    fetchMock.mockImplementation((path: string) =>
      path === "/api/me"
        ? new Promise((r) => {
            resolve = r;
          })
        : Promise.resolve(json(path === "/api/rooms" ? [] : room(3))),
    );
    fetchMock.mockClear();
    life.show();
    life.visibility();
    life.online();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    resolve(json({ ...identity, roomId: "ROOM01" }));
    await vi.advanceTimersByTimeAsync(0);
    expect(MockSocket.instances).toHaveLength(1);
    expect(ws.readyState).toBe(1);
    expect(
      fetchMock.mock.calls.every(([, options]) => options.method === "GET"),
    ).toBe(true);
    expect(ws.sent).toHaveLength(1);
  });
  it.each([2, 3])(
    "does not poll in the background, but restores closing state %s without fresh joining",
    async (closingState) => {
      const life = lifecycle();
      await setup();
      const ws = MockSocket.instances[0];
      ws.open();
      fetchMock.mockClear();
      life.doc.visibilityState = "hidden";
      life.visibility();
      life.show();
      life.online();
      expect(fetchMock).not.toHaveBeenCalled();
      // Simulate OS-updated transport state whose close event has not yet arrived.
      ws.readyState = closingState;
      life.doc.visibilityState = "visible";
      life.visibility();
      await vi.advanceTimersByTimeAsync(0);
      expect(MockSocket.instances).toHaveLength(2);
      expect(
        fetchMock.mock.calls.every(([, options]) => options.method === "GET"),
      ).toBe(true);
    },
  );
  it("honors released membership on return and cancels delayed recovery instead of rejoining", async () => {
    const life = lifecycle();
    const { getClientState } = await setup();
    const ws = MockSocket.instances[0];
    ws.open();
    ws.close();
    fetchMock.mockImplementation((path: string) =>
      Promise.resolve(
        json(path === "/api/me" ? { ...identity, roomId: null } : []),
      ),
    );
    fetchMock.mockClear();
    life.show();
    await vi.advanceTimersByTimeAsync(2500);
    expect(getClientState().room).toBeNull();
    expect(MockSocket.instances).toHaveLength(1);
    expect(
      fetchMock.mock.calls.filter(([path]) => path === "/api/me"),
    ).toHaveLength(1);
    expect(
      fetchMock.mock.calls.every(([, options]) => options.method === "GET"),
    ).toBe(true);
  });

  it("onclose during foreground membership query invalidates the old query and lets the new recovery win", async () => {
    const life = lifecycle();
    const { getClientState } = await setup();
    const first = MockSocket.instances[0];
    first.open();
    let finishOld!: (value: Response) => void;
    let queries = 0;
    fetchMock.mockImplementation((path: string) => {
      if (path === "/api/me" && ++queries === 1)
        return new Promise((resolve) => {
          finishOld = resolve;
        });
      return Promise.resolve(
        json(
          path === "/api/me"
            ? { ...identity, roomId: "ROOM01" }
            : path === "/api/rooms"
              ? []
              : room(20),
        ),
      );
    });
    fetchMock.mockClear();
    life.show();
    first.close();
    await vi.advanceTimersByTimeAsync(500);
    const second = MockSocket.instances[1];
    second.open();
    second.receive({ type: "snapshot", room: room(21) });
    finishOld(json({ ...identity, roomId: null }));
    await vi.advanceTimersByTimeAsync(2500);
    expect(getClientState().room?.revision).toBe(21);
    expect(getClientState().status).toBe("online");
    expect(MockSocket.instances).toHaveLength(2);
    expect(second.readyState).toBe(1);
    expect(
      fetchMock.mock.calls.filter(([path]) => path === "/api/me"),
    ).toHaveLength(2);
  });
  it("forgetting identity while foreground query is pending cannot resurrect a session or room", async () => {
    const life = lifecycle();
    const { client, getClientState } = await setup(false);
    let finish!: (value: Response) => void;
    fetchMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    fetchMock.mockClear();
    life.show();
    client.forget();
    finish(json({ ...identity, roomId: "ROOM01" }));
    await vi.advanceTimersByTimeAsync(2500);
    expect(getClientState().session).toBeNull();
    expect(getClientState().room).toBeNull();
    expect(MockSocket.instances).toHaveLength(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it("confirmed boot change during foreground query cannot roll the boot back or restore the old room", async () => {
    values.set("bridgego.server.epoch", "boot-one");
    const life = lifecycle();
    const { client, getClientState } = await setup();
    let finish!: (value: Response) => void;
    fetchMock.mockImplementation((path: string) => {
      if (path === "/api/me")
        return new Promise((resolve) => {
          finish = resolve;
        });
      const next = json([]);
      next.headers.set("X-Bridge-Instance-Epoch", "boot-two");
      return Promise.resolve(next);
    });
    fetchMock.mockClear();
    life.show();
    await client.refresh();
    const stale = json({ ...identity, roomId: "ROOM01" });
    stale.headers.set("X-Bridge-Instance-Epoch", "boot-one");
    finish(stale);
    await vi.advanceTimersByTimeAsync(2500);
    expect(getClientState().session).toBeNull();
    expect(getClientState().room).toBeNull();
    expect(values.get("bridgego.server.epoch")).toBe("boot-two");
    expect(getClientState().notice).toContain("服務已重新啟動");
    expect(MockSocket.instances).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe("computer seat commands", () => {
  it("adds and removes through revision-bound idempotent commands", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room(7) });
    const withBot = room(8);
    withBot.players.push({
      ...withBot.players[0],
      playerId: "bot-1",
      displayName: "電腦二",
      seat: 1,
      playerType: "BOT",
      ready: true,
    });
    pendingResponse = Promise.resolve(json({ room: withBot }));
    await client.addComputer(1);
    const add = fetchMock.mock.calls.find(
      (c) => c[0] === "/api/rooms/ROOM01/bots",
    )!;
    expect(add[1].method).toBe("POST");
    expect(JSON.parse(add[1].body)).toMatchObject({
      seat: 1,
      expectedRevision: 7,
      actionId: expect.any(String),
    });
    expect(getClientState().room?.players).toHaveLength(2);
    pendingResponse = Promise.resolve(json({ room: room(9) }));
    await client.removeComputer("bot-1");
    const remove = fetchMock.mock.calls.find(
      (c) => c[0] === "/api/rooms/ROOM01/bots/bot-1",
    )!;
    expect(remove[1].method).toBe("DELETE");
    expect(JSON.parse(remove[1].body)).toMatchObject({
      expectedRevision: 8,
      actionId: expect.any(String),
    });
  });
  it("preserves failed add identity on explicit retry and does not duplicate automatically", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    socket.receive({ type: "snapshot", room: room(3) });
    pendingResponse = Promise.resolve(new Response("", { status: 500 }));
    await client.addComputer(1);
    expect(getClientState().pendingAction).toBe("加入電腦玩家");
    const addCalls = () =>
      fetchMock.mock.calls.filter((c) => c[0] === "/api/rooms/ROOM01/bots");
    expect(addCalls()).toHaveLength(1);
    const body = addCalls()[0][1].body;
    await client.addComputer(2);
    expect(addCalls()).toHaveLength(1);
    pendingResponse = Promise.resolve(json({ room: room(4) }));
    await client.retryCommand();
    expect(addCalls()).toHaveLength(2);
    expect(addCalls()[1][1].body).toBe(body);
  });
  it("refuses nonhost, invalid seat, human removal, active match and offline writes", async () => {
    const { client } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    const snapshot = room(1);
    snapshot.hostPlayerId = "other";
    socket.receive({ type: "snapshot", room: snapshot });
    await client.addComputer(1);
    socket.receive({ type: "snapshot", room: room(2) });
    await client.addComputer(4);
    await client.removeComputer("alice");
    socket.receive({
      type: "snapshot",
      room: { ...room(3), phase: "BIDDING" },
    });
    await client.addComputer(1);
    socket.close();
    await client.addComputer(1);
    expect(
      fetchMock.mock.calls.filter((c) => String(c[0]).includes("/bots")),
    ).toHaveLength(0);
  });
});

describe("private redeal commands", () => {
  it.each([true, false])(
    "submits choice %s with captured deal and revision using PUT",
    async (choice) => {
      const { client } = await setup();
      const socket = MockSocket.instances[0];
      socket.open();
      const current = gameRoom("REDEAL_DECISION");
      socket.receive({ type: "snapshot", room: current });
      fetchMock.mockResolvedValueOnce(
        json({
          outcome: "APPLIED",
          room: { ...current, revision: current.revision + 1 },
        }),
      );
      await client.redeal(choice);
      const [path, options] = fetchMock.mock.calls.at(-1)!;
      expect(path).toBe(
        `/api/rooms/${current.roomId}/games/${current.game!.gameId}/deals/${current.game!.dealId}/redeal`,
      );
      expect(options.method).toBe("PUT");
      expect(JSON.parse(options.body)).toMatchObject({
        redeal: choice,
        expectedRevision: current.revision,
        actionId: expect.any(String),
      });
    },
  );
  it("refreshes a stale deal without replaying the choice on new cards", async () => {
    const { client, getClientState } = await setup();
    const socket = MockSocket.instances[0];
    socket.open();
    const current = gameRoom("REDEAL_DECISION");
    socket.receive({ type: "snapshot", room: current });
    const next = gameRoom("REDEAL_DECISION");
    next.revision = current.revision + 2;
    next.game!.dealId = "new-deal";
    fetchMock
      .mockResolvedValueOnce(
        json({ code: "STALE_DEAL", message: "old deal" }, 409),
      )
      .mockResolvedValueOnce(json(next));
    await client.redeal(true);
    expect(getClientState().room?.game?.dealId).toBe("new-deal");
    expect(
      fetchMock.mock.calls.filter(([path]) => String(path).endsWith("/redeal")),
    ).toHaveLength(1);
    expect(getClientState().pendingAction).toBeNull();
  });
});
