import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import CurrentBid from "./CurrentBid";
import GamePanel from "./GamePanel";
import OperationClock from "./OperationClock";
import { gameRoom, playedRoom } from "./test-fixtures";
import type { Room } from "./model";

const renderPanel = (room: Room) =>
  renderToStaticMarkup(
    <GamePanel
      room={room}
      online
      busy={false}
      pendingAction={null}
      snapshotReceivedAt={performance.now()}
    />,
  );
const renderBid = (room: Room) =>
  renderToStaticMarkup(<CurrentBid game={room.game!} />);
const clock = (
  kind: "DEAL_RESULT" | "BID_TURN",
  seconds = 90,
  paused = false,
) =>
  renderToStaticMarkup(
    <OperationClock
      timer={{ kind, remainingMs: seconds * 1000, paused, turnSeat: 0 }}
      seconds={seconds}
    />,
  );

describe("table current highest bid", () => {
  it.each(["CLUB", "DIAMOND", "HEART", "SPADE", "NO_TRUMP"])(
    "shows authoritative 1–7 %s bids, never an unsubmitted selection",
    (strain) => {
      for (let level = 1; level <= 7; level++) {
        const room = gameRoom("BIDDING");
        room.game!.auction.highestBid = { seat: 1, level, strain };
        room.you!.legalCalls = [{ type: "BID", level: 7, strain: "NO_TRUMP" }];
        const html = renderBid(room);
        expect(html).toContain("目前最高叫牌");
        expect(html).toContain(`<span>${level}</span>`);
        expect(html).toContain("bob 叫牌");
        expect(html).not.toContain("尚未有人叫牌");
        expect(html).not.toContain("定約");
        if (strain === "NO_TRUMP") expect(html).toContain("無王");
        if (strain === "HEART" || strain === "DIAMOND")
          expect(html).toContain("bid-red");
      }
    },
  );
  it("keeps the highest bid after Pass and clears it on an all-pass redeal", () => {
    const room = gameRoom("BIDDING");
    room.game!.auction.highestBid = { seat: 1, level: 3, strain: "CLUB" };
    const before = renderBid(room);
    room.game!.auction.calls.push({
      seat: 2,
      type: "PASS",
      level: null,
      strain: null,
      source: "TIMEOUT",
    });
    expect(renderBid(room)).toBe(before);
    room.game!.dealId = "redeal-two";
    room.game!.auction.highestBid = null;
    room.game!.auction.calls = [];
    const empty = renderBid(room);
    expect(empty).toContain("尚未有人叫牌");
    expect(empty).not.toContain("bob");
    expect(empty).not.toContain("undefined");
  });
  it("shows the same center bid on own/other turn while retaining the upper-left bid", () => {
    const room = gameRoom("BIDDING");
    room.game!.auction.highestBid = { seat: 1, level: 3, strain: "CLUB" };
    for (const nextSeat of [0, 1]) {
      room.game!.auction.nextSeat = nextSeat;
      const html = renderPanel(room);
      expect(html).toContain('aria-label="牌桌目前最高叫牌"');
      expect(html).toContain("目前 3♣");
      expect(html).toContain("bob 叫牌");
    }
  });
  it("has no auction center outside bidding and preserves trump-effect layer", () => {
    for (const room of [gameRoom(), playedRoom(), gameRoom("DEAL_RESULT")]) {
      expect(renderPanel(room)).not.toContain('aria-label="牌桌目前最高叫牌"');
    }
    expect(renderPanel(playedRoom())).toContain("trump-effects-layer");
  });
  it("retains full five-character/legacy bidder name access without inventing identity", () => {
    const room = gameRoom("BIDDING");
    room.game!.roster[1].displayName = "森林小動物";
    room.game!.auction.highestBid = { seat: 1, level: 7, strain: "NO_TRUMP" };
    expect(renderBid(room)).toContain('title="森林小動物 叫牌"');
    expect(renderBid(room)).toContain("森林小動物 叫牌");
  });
});

describe("settlement countdown copy", () => {
  it("removes only redundant settlement explanation/footer, preserving 90 seconds", () => {
    const html = clock("DEAL_RESULT");
    expect(html).toContain("90 <small>秒</small>");
    expect(html).toContain("局間確認");
    expect(html).not.toContain("四人確認可提前繼續");
    expect(html).not.toContain("後端統一判定");
    expect(html).not.toContain("<p>");
    const room = gameRoom("DEAL_RESULT");
    expect(renderPanel(room)).toContain("四人確認或 90 秒期限到");
    expect(renderPanel(room)).toContain("確認，準備下一局");
  });
  it("retains paused and zero-second feedback without restoring the footer", () => {
    expect(clock("DEAL_RESULT", 84, true)).toContain("保留剩餘操作時間");
    expect(clock("DEAL_RESULT", 0)).toContain("等候伺服器更新結果");
    expect(clock("DEAL_RESULT", 84, true)).not.toContain("後端統一判定");
  });
  it("does not remove instructions from other operation phases", () => {
    expect(clock("BID_TURN", 20)).toContain("逾時由後端自動 Pass");
    expect(clock("BID_TURN", 20)).toContain("後端統一判定");
  });
});
