import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import GamePanel from "./GamePanel";
import type { Room } from "./model";
import { gameRoom } from "./test-fixtures";

function render(room: Room, tutorial = false) {
  return renderToStaticMarkup(
    <GamePanel
      room={room}
      online
      busy={false}
      pendingAction={null}
      snapshotReceivedAt={0}
      tutorial={
        tutorial
          ? {
              guide: null,
              onPlay: async () => {},
              onBid: () => {},
              onExit: () => {},
            }
          : undefined
      }
    />,
  );
}

function auctionRoom() {
  const room = gameRoom("BIDDING");
  const game = room.game!;
  game.dealerSeat = 3;
  game.auction.nextSeat = 3;
  game.auction.calls = [
    { seat: 3, type: "BID", level: 1, strain: "CLUB", source: "MANUAL" },
    { seat: 0, type: "PASS", level: null, strain: null, source: "MANUAL" },
    { seat: 1, type: "PASS", level: null, strain: null, source: "TIMEOUT" },
    { seat: 2, type: "BID", level: 1, strain: "SPADE", source: "MANUAL" },
  ];
  game.auction.highestBid = { seat: 2, level: 1, strain: "SPADE" };
  return room;
}

function firstPlacement(html: string) {
  return (
    html.match(
      /<section class="auction-order[^"]*"[^>]*>.*?<\/section>/,
    )?.[0] ?? ""
  );
}

describe("live auction order strip", () => {
  it("keeps the first caller fixed and shows another player's current turn alongside their latest call", () => {
    const room = auctionRoom();
    const markup = firstPlacement(render(room));
    expect(
      [...markup.matchAll(/data-auction-seat="(\d)"/g)].map((m) => m[1]),
    ).toEqual(["3", "0", "1", "2"]);
    expect(markup).toMatch(
      /data-auction-seat="3" aria-current="step".*?1.*?♣.*?叫牌中/,
    );
    expect(markup).not.toContain("待叫");
    expect(markup).toContain("各席最近一叫");
    expect(markup).toContain("Pass");
    expect(markup).not.toContain("TIMEOUT");
    expect(render(room)).toContain("牌桌目前最高叫牌");
  });

  it("shows only the latest call per seat as the auction loops, or 未叫 when the auction is fresh", () => {
    const room = auctionRoom();
    const game = room.game!;
    game.auction.calls.push({
      seat: 3,
      type: "BID",
      level: 2,
      strain: "NO_TRUMP",
      source: "MANUAL",
    });
    game.auction.nextSeat = 0;
    const markup = firstPlacement(render(room));
    expect(markup).toMatch(/data-auction-seat="3".*?2.*?NT/);
    expect(markup).not.toContain("♣");
    expect(markup).toMatch(
      /data-auction-seat="0" aria-current="step".*?Pass.*?待叫/,
    );
    game.auctionRounds = [
      {
        dealId: "old",
        dealIndex: 1,
        redealCount: 0,
        dealerSeat: 3,
        calls: game.auction.calls,
        result: null,
        status: "ALL_PASS",
      },
    ];
    game.auction.calls = [];
    game.auction.highestBid = null;
    game.auction.nextSeat = 3;
    const fresh = firstPlacement(render(room));
    expect(fresh).not.toContain("Pass");
    expect(fresh.match(/未叫/g)).toHaveLength(3);
    expect(fresh).toMatch(/data-auction-seat="3" aria-current="step".*?叫牌中/);
  });

  it("does not show the strip during actual privilege choice or play or during practice", () => {
    expect(render(gameRoom("PRIVILEGE_DECISION"))).not.toContain(
      'aria-label="叫牌順序"',
    );
    expect(render(gameRoom("PLAYING"))).not.toContain('aria-label="叫牌順序"');
    expect(render(auctionRoom(), true)).not.toContain('aria-label="叫牌順序"');
  });
});
