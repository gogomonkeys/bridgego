import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup as render } from "react-dom/server";
import type { Room, RoomSummary } from "./model";
import { playedRoom } from "./test-fixtures";
import RoomTypeChoice from "./RoomTypeChoice";
import ComputerPlayers from "./ComputerPlayers";
import GamePanel from "./GamePanel";
import { MatchSummary } from "./ResultPanel";
const mocks = vi.hoisted(() => ({
  state: {
    initialized: true,
    session: { displayName: "Alice", playerId: "alice" },
    room: null as Room | null,
    rooms: [] as RoomSummary[],
    busy: false,
    status: "online",
    error: null,
    notice: null,
  },
}));
vi.mock("./client", () => ({
  client: { initialize: vi.fn() },
  getConnectionDiagnostics: () => [],
  useClient: () => mocks.state,
}));
vi.mock("./entry-flow", () => ({ needsEntry: () => false }));
import App from "./App";
afterEach(() => {
  mocks.state.room = null;
  mocks.state.rooms = [];
});
const noop = () => {};
describe("fixed room categories", () => {
  it("offers accessible choices with the selected fixed category and pending disable", () => {
    const html = render(
      <RoomTypeChoice value="FORMAL" disabled onChange={noop} />,
    );
    expect(html).toContain('disabled=""');
    expect(html).toContain('name="room-type" checked="" value="FORMAL"');
    expect(html).toContain("四位真人競技，不可加入電腦");
    expect(html).toContain("類型在開房後固定");
  });
  it("filters lobby to selected casual type, preserving old room compatibility", () => {
    const base = {
      capacity: 4 as const,
      playerCount: 1,
      state: "WAITING" as const,
      phase: "WAITING" as const,
      joinable: true,
    };
    mocks.state.rooms = [
      { ...base, roomId: "FORMAL_ONLY", roomType: "FORMAL" },
      { ...base, roomId: "CASUAL_ONLY", roomType: "CASUAL" },
      { ...base, roomId: "LEGACY_ROOM" },
    ];
    const html = render(<App />);
    expect(html).not.toContain("FORMAL_ONLY");
    expect(html).toContain("CASUAL_ONLY");
    expect(html).toContain("LEGACY_ROOM");
    expect(html).toContain('aria-label="休閒場房間"');
  });
  it("shows fixed formal waiting label and never computer management", () => {
    const room = playedRoom();
    room.phase = "WAITING";
    room.game = null;
    room.roomType = "FORMAL";
    room.hostPlayerId = "alice";
    mocks.state.room = room;
    const html = render(<App />);
    expect(html).toContain("正式場");
    expect(html).toContain("四位真人準備後");
    expect(html).not.toContain("找電腦朋友入座");
    expect(
      render(
        <ComputerPlayers
          room={room}
          playerId="alice"
          disabled={false}
          onAdd={noop}
          onRemove={noop}
        />,
      ),
    ).toBe("");
  });
  it("keeps copy, chat, reconnect state and add/remove actions in the illustrated waiting room", () => {
    const room = playedRoom();
    room.roomType = "CASUAL";
    room.roomId = "12345678-full-room-code";
    room.phase = "WAITING";
    room.game = null;
    room.allReady = false;
    room.plannedDeals = 5;
    room.players.pop();
    room.players[1] = { ...room.players[1], ready: false, connected: false, controlMode: "RECONNECTING" };
    room.players[2] = { ...room.players[2], playerType: "BOT" };
    mocks.state.room = room;
    const html = render(<App />);
    expect(html).toContain('class="play-surface waiting-surface waiting-round-table"');
    expect(html).toContain('class="waiting-oval" aria-hidden="true"');
    expect(html).toContain('title="12345678-full-room-code"');
    expect(html).toContain("複製代碼");
    expect(html).toContain("房間聊天");
    expect(html).toContain("本場 5 局 · 尚未開局");
    expect(html).toContain("北 N · ");
    expect(html).toContain("重連中");
    expect(html).toContain("＋ 電腦");
    expect(html).toContain("移除電腦");
    expect(html).toMatch(/<button class="primary start-game" disabled=""[^>]*>開始遊戲 · 房主/);
    expect(html).toContain("連線資訊");
    expect(html).toContain("離開房間");
  });

  it("shows the non-host their rotated seat and wait notice without start or bot management", () => {
    const room = playedRoom();
    room.roomType = "CASUAL";
    room.phase = "WAITING";
    room.game = null;
    room.hostPlayerId = "bob";
    room.canStart = true;
    room.you = { ...room.you!, seat: 2 };
    [room.players[0].seat, room.players[2].seat] = [2, 0];
    mocks.state.room = room;
    const html = render(<App />);
    expect(html).toContain('position-bottom team-NS');
    expect(html).toContain('data-seat="2" aria-label="南 S 你"');
    expect(html).toContain('data-seat="0" aria-label="北 N 隊友"');
    expect(html).toContain("四位玩家已就緒");
    expect(html).toContain("不會自動開局");
    expect(html).toContain("取消準備");
    expect(html).toContain("房間聊天");
    expect(html).not.toContain('class="primary start-game"');
    expect(html).not.toContain("找電腦朋友入座");
  });

  it("never promises a computer takeover while a formal game is paused", () => {
    const room = playedRoom();
    room.roomType = "FORMAL";
    room.game!.paused = true;
    const html = render(
      <GamePanel
        room={room}
        online
        busy={false}
        pendingAction={null}
        snapshotReceivedAt={performance.now()}
      />,
    );
    expect(html).toContain("不由電腦接手");
    expect(html).not.toContain("逾時由電腦代打");
  });
  it("labels departure loss rather than a normal score result", () => {
    const game = playedRoom().game!;
    game.roomType = "FORMAL";
    game.matchResult = {
      outcome: "FORFEIT",
      winnerTeamId: "EW",
      reason: "MANUAL_EXIT",
      failedTeamIds: ["NS"],
    };
    const html = render(<MatchSummary game={game} />);
    expect(html).toContain("主動離開判負");
    expect(html).toContain("正式場");
    expect(html).toContain("不以目前積分改判");
  });
});
