import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { playerLabel } from "./player-label";
import { TrickRecord } from "./ResultPanel";
import { AuctionRecords } from "./RecordsPanel";
import HandPanel, { canSelectCard } from "./HandPanel";
import GamePanel from "./GamePanel";
import { gameRoom, playedRoom } from "./test-fixtures";
import type { AuctionRound, Trick } from "./model";
const trick: Trick = {
  number: 1,
  leaderSeat: 0,
  winnerSeat: 1,
  cardsVisible: false,
  plays: [{ seat: 0, card: { suit: "HEART", rank: 14 }, source: "MANUAL" }],
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
afterEach(() => vi.unstubAllGlobals());
describe("v0.6 forest, hidden records and deliberate mobile selection", () => {
  it("fails closed when hidden payload accidentally retains cards", () => {
    const html = renderToStaticMarkup(
      <TrickRecord trick={trick} roster={playedRoom().game!.roster} />,
    );
    expect(html).toContain("牌面蓋起");
    expect(html).not.toContain("♥");
    expect(html).not.toContain("winning-card");
    expect(html).toContain("bob贏得本回合");
  });
  it("also hides legacy/missing visibility markers", () => {
    const html = renderToStaticMarkup(
      <TrickRecord trick={{ ...trick, cardsVisible: undefined }} />,
    );
    expect(html).toContain("本回合四張牌已蓋起");
    expect(html).not.toContain("♥");
  });
  it("only explicit visible histories expose faces", () => {
    const html = renderToStaticMarkup(
      <TrickRecord trick={{ ...trick, cardsVisible: true }} />,
    );
    expect(html).toContain("♥ A");
    expect(html).not.toContain("牌面蓋起");
  });
  it("uses immutable roster names and disambiguates duplicates", () => {
    const roster = playedRoom().game!.roster;
    expect(playerLabel(roster, 1)).toBe("bob");
    roster[0].displayName = "小白";
    roster[2].displayName = "小白";
    expect(playerLabel(roster, 0)).toBe("小白（北）");
    expect(playerLabel(roster, 2)).toBe("小白（南）");
    expect(playerLabel(roster, null)).toBe("玩家");
  });
  it("names the active turn once rather than stacking repeated prompts", () => {
    const room = playedRoom();
    room.game!.play!.nextSeat = 1;
    room.game!.play!.leaderSeat = 1;
    room.game!.operationTimer!.turnSeat = 1;
    const html = renderGame(room);
    expect(html).not.toContain("等候bob出牌");
    expect(html).toContain("輪到bob出牌 · 倒數中");
    expect(html).not.toContain("等候bob首引");
    expect(html.match(/class="public-card-empty"/g)).toHaveLength(4);
    expect(html).not.toContain("東位出牌");
  });
  it("privilege is self-explanatory without opening help", () => {
    const html = renderGame();
    expect(html).toContain("privilege-badge");
    expect(html).toContain("特權</strong>");
    expect(html).toContain("我方：<b>未使用</b>");
    expect(html).toContain("對方：<b>未使用</b>");
  });
  it("pending privilege never labels the hidden opponent decision", () => {
    const html = renderGame(gameRoom());
    expect(html).toContain("使用特權</button>");
    expect(html).not.toContain("公開特權結果");
    expect(html).not.toContain("對方：<b>");
  });
  it("retains and labels pass redeals and every player call", () => {
    const base: AuctionRound = {
      dealId: "old",
      dealIndex: 1,
      redealCount: 0,
      dealerSeat: 0,
      calls: [
        { seat: 1, type: "PASS", level: null, strain: null, source: "TIMEOUT" },
      ],
      result: null,
      status: "ALL_PASS",
    };
    const html = renderToStaticMarkup(
      <AuctionRecords
        rounds={[
          base,
          { ...base, dealId: "new", redealCount: 1, status: "CONTRACT" },
        ]}
        roster={playedRoom().game!.roster}
      />,
    );
    expect(html).toContain("全員 Pass · 已重發");
    expect(html).toContain("重發 1");
    expect(html).toContain("bob");
    expect(html.match(/逾時自動/g)).toHaveLength(2);
  });
  it("mobile shows all13 full-size card elements and explicit disabled confirmation until selection", () => {
    vi.stubGlobal("window", { matchMedia: () => ({ matches: true }) });
    const r = playedRoom();
    const html = renderToStaticMarkup(
      <HandPanel
        cards={r.you!.ownHand}
        legalCards={r.you!.legalCards}
        enabled
        collecting={false}
        playing
      />,
    );
    expect(html).toContain("overlap-hand");
    expect(html.match(/data-card=/g)).toHaveLength(13);
    expect(html).toContain("展開手牌");
    expect(html).toContain('disabled="">出這張牌');
    expect(html).toContain("選牌不會送出");
  });
  it("legal selection guard rejects stale, illegal and locked cards", () => {
    const card = { suit: "HEART" as const, rank: 2 };
    expect(canSelectCard(card, [card], true)).toBe(true);
    expect(canSelectCard(card, [card], false)).toBe(false);
    expect(canSelectCard(card, [], true)).toBe(false);
    expect(canSelectCard({ ...card, rank: 3 }, [card], true)).toBe(false);
  });
  it("uses four real asset slots without exposing images as named controls", () => {
    const html = renderGame();
    for (const animal of ["rabbit", "fox", "panda", "bear"])
      expect(html).toContain(`/assets/forest/animal-${animal}.webp`);
    expect(html.match(/alt="" draggable="false"/g)).toHaveLength(4);
  });
});
