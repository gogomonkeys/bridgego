import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { gameRoom, playedRoom } from "./test-fixtures";
import type { Room, RoomSummary } from "./model";
const mocks = vi.hoisted(() => ({
  state: { initialized: true, session: { displayName: "Alice", playerId: "alice" }, room: null as Room | null, rooms: [] as RoomSummary[], busy: false, status: "online", error: null, notice: null },
}));
vi.mock("./client", () => ({
  client: { initialize: vi.fn() },
  getConnectionDiagnostics: () => ({}),
  useClient: () => mocks.state,
}));
import App from "./App";
import GamePanel from "./GamePanel";
import { MatchSummary } from "./ResultPanel";
afterEach(() => { mocks.state.room = null; mocks.state.rooms = []; mocks.state.busy = false; });
describe("room deal count", () => {
  it("offers 1, 3 and 5 deals with 3 selected by default", () => {
    const html = renderToStaticMarkup(<App />);
    for (const count of [1, 3, 5]) expect(html).toContain(`value="${count}"`);
    expect(html).toMatch(/name="planned-deals" checked="" value="3"/);
    expect(html).toContain("本場局數");
  });
  it("disables the choice while a room creation is pending", () => {
    mocks.state.busy = true;
    expect(renderToStaticMarkup(<App />)).toContain('class="deal-count-choice" disabled=""');
  });
  it.each([1, 3, 5] as const)("shows %s deals in the waiting room and lobby", (count) => {
    mocks.state.room = { ...gameRoom(), phase: "WAITING", game: null, plannedDeals: count };
    expect(renderToStaticMarkup(<App />)).toContain(`本場 ${count} 局 · 尚未開局`);
    mocks.state.room = null;
    mocks.state.rooms = [{ roomId: "ROOM", capacity: 4, playerCount: 1, state: "WAITING", phase: "WAITING", joinable: true, plannedDeals: count }];
    expect(renderToStaticMarkup(<App />)).toContain(`1 / 4 位玩家 · ${count} 局`);
  });
  it.each([1, 3, 5] as const)("uses %s for current and completed game progress", (count) => {
    const room = playedRoom();
    room.game!.plannedDeals = count;
    const html = renderToStaticMarkup(<GamePanel room={room} online busy={false} pendingAction={null} snapshotReceivedAt={performance.now()} />);
    expect(html).toContain(`第 1 / ${count} 局`);
    expect(html).toContain(`已結算 0 / ${count} 局`);
  });
  it.each([1, 3, 5] as const)("uses %s for final count and tied score", (count) => {
    const game = playedRoom().game!;
    game.plannedDeals = count;
    game.completedDeals = count;
    game.matchResult = { outcome: "SCORED", winnerTeamId: null, reason: count === 3 ? "THREE_DEALS_COMPLETE" : "PLANNED_DEALS_COMPLETE", failedTeamIds: [] };
    const html = renderToStaticMarkup(<MatchSummary game={game} />);
    expect(html).toContain(`${count} 局總分平手`);
    expect(html).toContain(`已完成 ${count} / ${count} 局`);
    expect(html).toContain(count === 3 ? "三局皆已完成" : `${count} 局皆已完成`);
  });
});
