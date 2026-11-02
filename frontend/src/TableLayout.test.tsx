import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import GamePanel from "./GamePanel";
import TableSeats from "./TableSeats";
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

describe("redistributed table layout structure (browser geometry tested separately)", () => {
  it.each(["PRIVILEGE_DECISION", "BIDDING", "PLAYING"] as const)(
    "keeps four independent seat regions in %s",
    (phase) => {
      const room = phase === "PLAYING" ? playedRoom() : gameRoom(phase);
      const html = render(room);
      for (const position of ["top", "left", "right", "bottom"]) {
        expect(
          html.match(
            new RegExp(`class="table-player position-${position} `, "g"),
          ),
        ).toHaveLength(1);
      }
      const tableStart = html.indexOf('aria-label="四人牌桌"');
      const tableEnd = html.indexOf("</section>", tableStart);
      const table = html.slice(tableStart, tableEnd);
      expect(html.indexOf('class="table-dashboard"')).toBeLessThan(tableStart);
      expect(table).toContain('class="table-event"');
      expect(table).not.toContain('aria-label="操作倒數"');
      expect(table).not.toContain('aria-label="選擇叫牌"');
      expect(table).not.toContain('aria-label="特權選擇操作"');
      expect(table).not.toContain('aria-label="公開特權結果"');
      if (phase !== "PLAYING") {
        expect(html.indexOf('class="table-action-dock"')).toBeGreaterThan(
          tableEnd,
        );
        expect(html.indexOf('class="table-action-dock"')).toBeLessThan(
          html.indexOf('aria-label="你的私人手牌"'),
        );
      }
      expect(html.match(/class="seat-portrait"/g)).toHaveLength(4);
      expect(html.match(/class="seat-identity"/g)).toHaveLength(4);
    },
  );
  it("keeps empty waiting seats and full player names available", () => {
    const room = gameRoom();
    room.phase = "WAITING";
    room.game = null;
    room.players = room.players.slice(0, 1);
    room.players[0].displayName = "森林裡名字很長很長的小兔子";
    const html = renderToStaticMarkup(
      <TableSeats room={room} turnSeat={null} />,
    );
    expect(html.match(/class="table-player /g)).toHaveLength(4);
    expect(html).toContain("等待入座");
    expect(html).toContain('class="player-name-tip"');
    expect(html).toContain("森林裡名字很長很長的小兔子");
  });
});

describe("phase dock containment and readable public cards", () => {
  it("keeps an already selected privilege outside the four-seat table", () => {
    const room = gameRoom("PRIVILEGE_DECISION");
    room.game!.privilege.yourChoice = true;
    room.game!.privilege.canChoose = false;
    const html = render(room);
    const tableStart = html.indexOf('aria-label="四人牌桌"');
    const tableEnd = html.indexOf("</section>", tableStart);
    expect(html.indexOf('class="selection-confirmed"')).toBeGreaterThan(
      tableEnd,
    );
    expect(html).toContain("你已選擇：使用特權。");
    expect(html).not.toContain('aria-label="不使用特權"');
    expect(html).toContain('aria-label="特權選擇說明"');
  });
  it("labels every public card with its player and complete suit/rank", () => {
    const room = playedRoom();
    room.game!.play!.currentTrick = [
      { seat: 0, card: { suit: "SPADE", rank: 14 }, source: "MANUAL" },
      { seat: 1, card: { suit: "HEART", rank: 10 }, source: "TIMEOUT" },
      { seat: 2, card: { suit: "CLUB", rank: 13 }, source: "MANUAL" },
      { seat: 3, card: { suit: "DIAMOND", rank: 2 }, source: "MANUAL" },
    ];
    const html = render(room);
    expect(html).toContain('aria-label="alice出牌 ♠A"');
    expect(html).toContain('aria-label="bob出牌 ♥10"');
    expect(html).toContain('aria-label="carol出牌 ♣K"');
    expect(html).toContain('aria-label="dan出牌 ♦2"');
    expect(html.match(/class="played-suit"/g)).toHaveLength(4);
    expect(html.match(/class="played-rank"/g)).toHaveLength(4);
    expect(html).toContain('aria-label="逾時自動出牌"');
  });
});
