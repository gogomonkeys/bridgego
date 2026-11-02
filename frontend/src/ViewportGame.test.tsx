import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import GamePanel from "./GamePanel";
import { gameRoom, playedRoom } from "./test-fixtures";
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

describe("viewport game workspace", () => {
  it("keeps score, table and private hand in explicit layout regions", () => {
    const html = render(playedRoom());
    expect(html).toContain("match-workspace viewport-game");
    expect(html).toContain('data-phase="playing"');
    expect(html.indexOf('aria-label="四人牌桌"')).toBeLessThan(
      html.indexOf('aria-label="你的私人手牌"'),
    );
    expect(html).not.toContain("僅你可見");
  });
  it("does not occupy hand space with an inactive bidding panel", () => {
    const room = gameRoom("BIDDING");
    room.game!.auction.nextSeat = (room.you!.seat + 1) % 4;
    const html = render(room);
    expect(html).not.toContain('aria-label="選擇叫牌"');
    expect(html).toContain('aria-label="你的私人手牌"');
  });
  it("retains explicit bidding controls for the acting player", () => {
    const room = gameRoom("BIDDING");
    room.game!.auction.nextSeat = room.you!.seat;
    const html = render(room);
    expect(html).toContain('aria-label="選擇叫牌"');
    expect(html).toContain("確認叫牌");
    expect(html).toContain("Pass · 不叫");
  });
  it("keeps state version and recovery actions inside collapsed more details", () => {
    const html = render(playedRoom());
    const start = html.indexOf('<details class="match-more">');
    const end = html.indexOf("</details>", start);
    expect(start).toBeGreaterThan(-1);
    expect(html.slice(start, end)).toContain("狀態版本");
    expect(html.slice(start, end)).toContain("重新同步");
    expect(html.slice(start, end)).toContain("退出遊戲");
    expect(html).not.toContain('<details class="match-more" open');
  });
});
