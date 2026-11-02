import { describe, it, expect } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { CurrentDealRecords, SettlementRecords } from "./RecordsPanel";
import { PreviousMatchSummary, TrickRecord } from "./ResultPanel";
import GamePanel from "./GamePanel";
import TableSeats from "./TableSeats";
import IconTip from "./IconTip";
import { playedRoom, completedDeal } from "./test-fixtures";
import type { Trick, MatchArchive } from "./model";

const trick: Trick = {
  cardsVisible: true,
  number: 1,
  leaderSeat: 0,
  winnerSeat: 2,
  plays: [0, 1, 2, 3].map((seat) => ({
    seat,
    source: "MANUAL",
    card: { suit: "SPADE", rank: [2, 3, 14, 5][seat] },
  })),
};
const renderGame = (room = playedRoom()) =>
  renderToStaticMarkup(
    <GamePanel
      room={room}
      online
      busy={false}
      pendingAction={null}
      snapshotReceivedAt={performance.now()}
    />,
  );
describe("v0.5 capture and records presentation", () => {
  it("retains all four public cards and highlights only the server winner during capture", () => {
    const room = playedRoom();
    room.phase = "TRICK_RESULT";
    room.game!.play!.nextSeat = null;
    room.game!.play!.currentTrick = trick.plays;
    room.game!.play!.completedTricks = [trick];
    room.game!.operationTimer = {
      kind: "TRICK_RESULT",
      turnSeat: null,
      remainingMs: 2000,
      paused: false,
    };
    const html = renderGame(room);
    expect(html.match(/class="played-card /g)).toHaveLength(4);
    expect(html.match(/aria-label="獲勝牌"/g)).toHaveLength(1);
    expect(html).toContain("carol贏得本回合");
    expect(html).toContain("我方 +1");
    expect(html).toContain("本回合結算中，下一回合尚未開始");
    expect(html).not.toContain("輪到你出牌");
    expect(html).not.toMatch(/<button type="button" class="hand-card/);
  });
  it("keeps final capture on the table even when final score metadata is already committed", () => {
    const room = playedRoom();
    room.phase = "TRICK_RESULT";
    room.game!.play!.currentTrick = trick.plays;
    room.game!.play!.completedTricks = [trick];
    room.game!.play!.trickNumber = 13;
    room.game!.completedDeals = 3;
    room.game!.matchResult = {
      outcome: "SCORED",
      winnerTeamId: "NS",
      reason: "THREE_DEALS_COMPLETE",
      failedTeamIds: [],
    };
    const html = renderGame(room);
    expect(html).toContain('aria-label="四人牌桌"');
    expect(html).not.toContain("再玩一場");
    expect(html).toContain("離開房間");
    expect(html).not.toContain("退出遊戲");
  });
  it("shows each player's own count rather than copying team total", () => {
    const room = playedRoom();
    room.game!.play!.completedTricks = [
      trick,
      { ...trick, number: 2, winnerSeat: 0 },
    ];
    const html = renderToStaticMarkup(
      <TableSeats room={room} turnSeat={null} winnerSeat={2} />,
    );
    expect(html).toContain('aria-label="alice本局拿下 1 墩"');
    expect(html).toContain('aria-label="carol本局拿下 1 墩"');
    expect(html).toContain('aria-label="bob本局拿下 0 墩"');
    expect(html.match(/trick-winner/g)).toHaveLength(1);
  });
  it("shows live match totals separately from current deal tricks", () => {
    const room = playedRoom();
    room.game!.teamTotals = { NS: 7, EW: 2 };
    room.game!.completedDeals = 1;
    const html = renderGame(room);
    expect(html).toContain('aria-label="兩隊累計總分"');
    expect(html).toContain("累計總分 · 已結算 1 / 3 局");
    expect(html).toContain("7 <small>分</small>");
    expect(html).toContain("▤ 本局紀錄");
    expect(html).toContain("▥ 各局結算");
  });
  it("marks own-team tricks by text plus styling and marks the exact winning card", () => {
    const own = renderToStaticMarkup(
      <TrickRecord trick={trick} ownTeam="NS" />,
    );
    expect(own).toContain("trick-record our-trick");
    expect(own).toContain("✓ 我方");
    expect(own.match(/class="winning-card"/g)).toHaveLength(1);
    const opponent = renderToStaticMarkup(
      <TrickRecord trick={trick} ownTeam="EW" />,
    );
    expect(opponent).not.toContain("✓ 我方");
  });
  it("current records show all completed tricks, not settled score trees or rules", () => {
    const game = playedRoom().game!;
    game.play!.completedTricks = [trick, { ...trick, number: 2 }];
    game.completedDealResults = [completedDeal()];
    const html = renderToStaticMarkup(
      <CurrentDealRecords game={game} ownTeam="NS" />,
    );
    expect(html.match(/data-trick=/g)).toHaveLength(2);
    expect(html.indexOf('data-trick="2"')).toBeLessThan(
      html.indexOf('data-trick="1"'),
    );
    expect(html).not.toContain("本局特權與規則");
    expect(html).not.toContain("基本分");
    expect(html).not.toContain("<details");
  });
  it("settlements select one deal rather than rendering three nested dropdown trees", () => {
    const game = playedRoom().game!;
    game.completedDealResults = [1, 2, 3].map((n) => ({
      ...completedDeal(),
      dealId: `d${n}`,
      dealIndex: n,
    }));
    const html = renderToStaticMarkup(<SettlementRecords game={game} />);
    expect(html).toContain("第 3 局結算");
    expect(html).not.toContain("第 1 局結算");
    expect(html.match(/class="deal-breakdown"/g)).toHaveLength(1);
    expect(html).not.toContain("<details");
    expect(html).not.toContain("<table");
  });
  it("prior match stays compact while preserving server forfeit outcome", () => {
    const match: MatchArchive = {
      gameId: "old",
      roster: [],
      teamTotals: { NS: 100, EW: 0 },
      completedDealResults: [completedDeal()],
      forfeitEvents: [],
      matchResult: {
        outcome: "FORFEIT",
        winnerTeamId: "EW",
        reason: "MANUAL_EXIT",
        failedTeamIds: ["NS"],
      },
    };
    const html = renderToStaticMarkup(<PreviousMatchSummary match={match} />);
    expect(html).toContain("東西隊獲勝");
    expect(html).toContain("棄權判定");
    expect(html).not.toContain("基本分");
    expect(html).not.toContain("13 回合");
  });
  it("icon help has a named keyboard/tap button instead of hover-only content", () => {
    const html = renderToStaticMarkup(
      <IconTip icon="✦" label="特權說明">
        規則
      </IconTip>,
    );
    expect(html).toContain('type="button"');
    expect(html).toContain('aria-label="特權說明"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).not.toContain("title=");
  });
});
