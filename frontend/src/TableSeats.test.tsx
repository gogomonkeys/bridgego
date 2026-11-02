import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import TableSeats from "./TableSeats";
import { playedRoom } from "./test-fixtures";

describe("individual captured tricks use 墩, not turn progress", () => {
  for (const seat of [0, 1, 2, 3]) {
    it.each([0, 1, 9, 10, 13])(`shows seat ${seat}'s %s captures`, (count) => {
      const room = playedRoom();
      room.players[seat].displayName = "森林小兔子";
      room.game!.roster[seat].displayName = "森林小兔子";
      room.game!.play!.completedTricks = Array.from(
        { length: count },
        (_, i) => ({
          cardsVisible: false,
          number: i + 1,
          leaderSeat: 0,
          winnerSeat: seat,
          plays: [],
        }),
      );
      const html = renderToStaticMarkup(
        <TableSeats room={room} turnSeat={null} winnerSeat={seat} />,
      );
      expect(html).toContain(`aria-label="森林小兔子本局拿下 ${count} 墩"`);
      expect(html).toContain(
        `<b>${count}</b> <span class="trick-count-unit">墩</span>`,
      );
      expect(html.match(/class="trick-count-unit">墩/g)).toHaveLength(4);
      expect(html).not.toContain("回合");
      expect(html).toContain("trick-winner");
    });
  }
});
