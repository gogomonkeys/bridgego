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

describe("contract holder seat marker", () => {
  it("marks only the authoritative holder and retains individual captures", () => {
    const room = playedRoom();
    room.game!.play!.tricksByTeam.EW = 5;
    room.game!.play!.completedTricks = [
      {
        number: 1,
        leaderSeat: 0,
        winnerSeat: 3,
        plays: [],
        cardsVisible: false,
      },
    ];
    const html = renderToStaticMarkup(<TableSeats room={room} turnSeat={0} />);
    expect(html.match(/class="seat-contract-marker"/g)).toHaveLength(1);
    expect(html).toContain('aria-label="dan本局得標"');
    expect(html).toContain('aria-label="dan本局拿下 1 墩"');
    expect(html).not.toContain('aria-label="dan本局拿下 5 墩"');
  });
  it("never marks the highest bidder before the auction settles", () => {
    const room = playedRoom();
    room.game!.auction.result = null;
    room.game!.auction.highestBid = { seat: 1, level: 2, strain: "CLUB" };
    expect(
      renderToStaticMarkup(<TableSeats room={room} turnSeat={1} />),
    ).not.toContain("seat-contract-marker");
  });
});

it.each([0, 1, 2, 3])(
  "keeps holder %s out of identity flow for every viewpoint",
  (holder) => {
    const room = playedRoom();
    room.you!.seat = (holder + 1) % 4;
    room.game!.auction.result!.holderSeat = holder;
    room.players[holder].displayName = "名字很長的森林小兔子";
    const html = renderToStaticMarkup(
      <TableSeats room={room} turnSeat={holder} />,
    );
    expect(html).toMatch(
      /data-seat="\d"[^>]*><span class="seat-contract-marker"/,
    );
    expect(html.match(/class="seat-contract-marker"/g)).toHaveLength(1);
    expect(html).not.toMatch(
      /class="seat-identity"(?:(?!<\/article>)[\s\S])*class="seat-contract-marker"/,
    );
    expect(html).toContain("active-turn");
  },
);
