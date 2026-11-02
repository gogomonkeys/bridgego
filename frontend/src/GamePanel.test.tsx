import { describe, it, expect } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import GamePanel from "./GamePanel";
import TableSeats from "./TableSeats";
import { gameRoom, playedRoom, completedDeal } from "./test-fixtures";
import { sortedHand, rankLabel, callLabel } from "./game-view";
import type { Room } from "./model";
const render = (room: Room) =>
  renderToStaticMarkup(
    <GamePanel
      room={room}
      online
      busy={false}
      pendingAction={null}
      snapshotReceivedAt={performance.now()}
    />,
  );
describe("v0.5 static presentation, not browser interactions", () => {
  it("renders only supplied private cards and no dummy", () => {
    const html = render(gameRoom());
    expect(html.match(/class="hand-card(?: |")/g)).toHaveLength(13);
    expect(html).toContain("黑桃 A");
    expect(html).not.toContain('aria-label="紅心 A"');
    expect(html).not.toContain("不設夢家");
  });
  it("keeps pending team privilege choices hidden", () => {
    const html = render(gameRoom());
    expect(html).not.toContain('aria-label="公開特權結果"');
    expect(html).toContain('aria-label="特權選擇說明"');
    expect(html).not.toContain("使用特權</strong>");
  });
  it("never invents a no vote for skipped teammate", () => {
    const room = gameRoom();
    room.game!.privilege.canChoose = false;
    const html = render(room);
    expect(html).toContain("你的隊伍已完成決定");
    expect(html).not.toContain("你已選擇");
  });
  it("requires explicit legal level/strain draft before confirming a bid", () => {
    const html = render(gameRoom("BIDDING"));
    expect(html).toContain('aria-label="選擇叫牌"');
    expect(html).toMatch(/<button class="primary" disabled="">確認叫牌/);
    expect(html).toContain("選項不會送出；按確認才叫牌。");
    expect(html).not.toContain("bid-grid");
    expect(html.indexOf('aria-label="選擇叫牌"')).toBeLessThan(
      html.indexOf('aria-label="你的私人手牌"'),
    );
  });
  it("locks bidding while paused and retains own hand", () => {
    const room = gameRoom("BIDDING");
    room.game!.paused = true;
    const html = render(room);
    expect(html).toContain("遊戲暫停");
    expect(html).toContain('<fieldset disabled="">');
    expect(html.match(/class="hand-card(?: |")/g)).toHaveLength(13);
  });
  it("places privilege primary actions centrally before the hand without duplicate lower controls", () => {
    const html = render(gameRoom());
    expect(html.indexOf('aria-label="特權選擇操作"')).toBeLessThan(
      html.indexOf('aria-label="你的私人手牌"'),
    );
    expect(html.match(/aria-label="使用特權"/g)).toHaveLength(1);
    expect(html).not.toContain('class="action-dock"');
  });
  it("shows sampled and frozen server budgets", () => {
    const room = gameRoom();
    room.game!.operationTimer!.remainingMs = 7000;
    expect(render(room)).toContain("7 <small>秒</small>");
    room.game!.operationTimer!.paused = true;
    expect(render(room)).toContain("已暫停");
    expect(render(room)).toContain("不重置倒數");
  });
  it("waits at zero and removes timer in terminal phase", () => {
    const room = gameRoom("BIDDING");
    room.game!.operationTimer!.remainingMs = 0;
    expect(render(room)).toContain("等候伺服器更新結果");
    expect(render(gameRoom("MATCH_RESULT"))).not.toContain('role="timer"');
  });
  it("only labels own privilege timeout from server provenance", () => {
    const room = gameRoom("BIDDING");
    room.game!.privilege = {
      status: "REVEALED",
      yourChoice: false,
      yourChoiceSource: "TIMEOUT",
      canChoose: false,
      usedByTeam: { NS: true, EW: false },
    };
    expect(render(room)).toContain("你的特權選擇已逾時");
    room.game!.privilege.yourChoiceSource = "MANUAL";
    expect(render(room)).not.toContain("你的特權選擇已逾時");
  });
  it("sorts a copy without mutating private hand", () => {
    const hand = gameRoom().you!.ownHand;
    expect(sortedHand(hand)[0].rank).toBe(14);
    expect(hand[0].rank).toBe(2);
    expect(rankLabel(14)).toBe("A");
    expect(callLabel({ type: "PASS", level: null, strain: null })).toBe("Pass");
  });
  it("enables only legal own cards and disables paused play", () => {
    const room = playedRoom();
    expect(render(room)).toMatch(
      /<button type="button" data-card="SPADE-2" class="hand-card [^"]*" aria-label="黑桃 2"/,
    );
    expect(render(room)).toMatch(
      /<button type="button" data-card="SPADE-14" disabled="" class="hand-card [^"]*" aria-label="黑桃 A"/,
    );
    room.game!.paused = true;
    expect(render(room)).toMatch(
      /<button type="button" data-card="SPADE-2" disabled="" class="hand-card [^"]*" aria-label="黑桃 2"/,
    );
  });
  it("renders current public trick with timeout source in correct relative position", () => {
    const room = playedRoom();
    room.game!.play!.currentTrick = [
      { seat: 1, card: { suit: "CLUB", rank: 2 }, source: "TIMEOUT" },
    ];
    const html = render(room);
    expect(html).toMatch(
      /public-card-slot position-left[^]*?bob出牌 ♣2[^]*?played-card portrait-card/,
    );
    expect(html).toContain("逾時自動出牌");
  });
  it("does not stack old scores or history on active table", () => {
    const room = playedRoom();
    room.game!.completedDealResults = [completedDeal()];
    const html = render(room);
    expect(html).toContain('aria-label="四人牌桌"');
    expect(html).not.toContain("第 1 局結算");
    expect(html).not.toContain('aria-label="紀錄與歷史"');
    expect(html).toContain('aria-expanded="false"');
  });
  it("isolates DEAL_RESULT from private hand and table while showing acknowledgments", () => {
    const room = gameRoom("DEAL_RESULT");
    room.game!.completedDealResults = [completedDeal()];
    room.game!.teamTotals = { NS: 7, EW: 0 };
    room.game!.operationTimer = {
      kind: "DEAL_RESULT",
      turnSeat: null,
      remainingMs: 12000,
      paused: false,
    };
    room.game!.resultAcknowledgedSeats = [1, 2];
    room.you!.canAcknowledgeResult = true;
    const html = render(room);
    expect(html).toContain('aria-label="本局結算"');
    expect(html).not.toContain('data-testid="own-hand"');
    expect(html).not.toContain('aria-label="四人牌桌"');
    expect(html).toContain("目前累計：南北 7 分");
    expect(html).toContain("12 <small>秒</small>");
    expect(html).toContain("四人確認或 90 秒期限到");
    expect(html).toContain("下一局特權倒數尚未開始");
    expect(html).toContain("剛好達標");
    expect(html).toContain("特權調整");
    expect(html).toContain("✓ 已確認");
  });
  it("displays the authoritative ninety-second settlement budget and preserves it while paused", () => {
    const room = gameRoom("DEAL_RESULT");
    room.game!.operationTimer = {
      kind: "DEAL_RESULT",
      turnSeat: null,
      remainingMs: 90000,
      paused: false,
    };
    expect(render(room)).toContain("90 <small>秒</small>");
    room.game!.operationTimer.remainingMs = 84000;
    room.game!.operationTimer.paused = true;
    const html = render(room);
    expect(html).toContain("84 <small>秒</small>");
    expect(html).toContain("不重置倒數");
    expect(html).not.toContain("不重新給 20 秒");
  });
  it("disables acknowledgment after own confirmation or while paused", () => {
    const room = gameRoom("DEAL_RESULT");
    room.game!.resultAcknowledgedSeats = [0];
    room.you!.canAcknowledgeResult = false;
    expect(render(room)).toMatch(/<button class="primary" disabled="">已確認/);
    room.game!.resultAcknowledgedSeats = [];
    room.you!.canAcknowledgeResult = true;
    room.game!.paused = true;
    expect(render(room)).toMatch(
      /<button class="primary" disabled="">確認，準備下一局/,
    );
  });
  it("only enables rematch for server-authorized online host", () => {
    const room = gameRoom("MATCH_RESULT");
    room.canRematch = false;
    expect(render(room)).toMatch(
      /<button class="primary" disabled="">再玩一場/,
    );
    room.canRematch = true;
    expect(render(room)).toMatch(/<button class="primary">再玩一場/);
    expect(render(room)).not.toContain('data-testid="own-hand"');
  });
  it("shows pending failure without premature winner", () => {
    const room = gameRoom("FORFEIT_PENDING");
    room.game!.paused = true;
    room.game!.pendingForfeit = {
      reason: "AWAITING_EXISTING_RECONNECT_GRACE",
      failedTeamIds: ["NS"],
      waitingSeats: [1],
    };
    const html = render(room);
    expect(html).toContain("重連結果待裁定");
    expect(html).not.toContain("東西隊獲勝");
    expect(html).not.toContain('aria-label="四人牌桌"');
  });
  it("distinguishes no-contest from scored draw and retains completed score", () => {
    const room = gameRoom("MATCH_RESULT");
    room.game!.completedDealResults = [completedDeal()];
    room.game!.teamTotals = { NS: 7, EW: 0 };
    room.game!.matchResult = {
      outcome: "NO_CONTEST",
      winnerTeamId: null,
      reason: "BOTH_TEAMS_FAILED",
      failedTeamIds: ["NS", "EW"],
    };
    expect(render(room)).toContain("本場無勝負");
    expect(render(room)).toContain("南北 7 分");
    room.game!.matchResult = {
      outcome: "SCORED",
      winnerTeamId: null,
      reason: "THREE_DEALS_COMPLETE",
      failedTeamIds: [],
    };
    expect(render(room)).toContain("三局總分平手");
  });
  it("forfeit winner comes from server, not current points", () => {
    const room = gameRoom("MATCH_RESULT");
    room.game!.matchResult = {
      outcome: "FORFEIT",
      winnerTeamId: "EW",
      reason: "MANUAL_EXIT",
      failedTeamIds: ["NS"],
    };
    expect(render(room)).toContain("東西隊獲勝");
    expect(render(room)).toContain("不以目前積分改判");
  });
  it("attributes departed player through frozen roster", () => {
    const room = gameRoom("MATCH_RESULT");
    room.players = room.players.filter((p) => p.playerId !== "alice");
    room.game!.forfeitEvents = [
      { playerId: "alice", seat: 0, cause: "MANUAL_EXIT" },
    ];
    expect(render(room)).toContain("alice");
    expect(render(room)).toContain("主動退出");
  });
  it.each([0, 1, 2, 3])(
    "shows viewer %i at bottom, teammate top with original directions",
    (own) => {
      const room = playedRoom();
      room.you!.seat = own;
      const html = renderToStaticMarkup(
        <TableSeats room={room} turnSeat={own} />,
      );
      expect(html).toMatch(
        new RegExp(
          `class="table-player position-bottom [^"]*" data-seat="${own}"`,
        ),
      );
      expect(html).toMatch(
        new RegExp(
          `class="table-player position-top [^"]*" data-seat="${(own + 2) % 4}"`,
        ),
      );
      expect(html).toContain("隊友");
      expect(html).toContain("對手");
    },
  );
});

it("waiting seats retain individual ready status after presentation rotation", () => {
  const room = gameRoom("WAITING");
  room.game = null;
  room.you!.seat = 2;
  room.players[0].ready = true;
  room.players[1].ready = false;
  const html = renderToStaticMarkup(<TableSeats room={room} turnSeat={null} />);
  expect(html).toContain("✓ 已準備");
  expect(html).toContain("尚未準備");
  expect(html).toMatch(/position-bottom[^"]*" data-seat="2"/);
});

it("retains accessible offline identity when short-phone CSS uses a status dot", () => {
  const room = playedRoom();
  room.players[1].connected = false;
  const html = renderToStaticMarkup(<TableSeats room={room} turnSeat={0} />);
  expect(html).toContain(
    'class="seat-presence is-offline" title="bob離線" aria-label="bob離線"',
  );
  expect(html).toContain("○ 離線");
  expect(html.match(/class="player-trick-count"/g)).toHaveLength(4);
});
