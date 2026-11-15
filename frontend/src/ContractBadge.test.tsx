import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import ContractBadge from "./ContractBadge";
import { gameRoom, playedRoom } from "./test-fixtures";
describe("settled contract badge", () => {
  it("never calls an unfinished highest bidder the winner", () => {
    const game = gameRoom("BIDDING").game!;
    game.auction.highestBid = { seat: 1, level: 3, strain: "HEART" };
    expect(renderToStaticMarkup(<ContractBadge game={game} />)).toBe("");
  });
  it.each(["HEART", "DIAMOND", "SPADE", "CLUB", "NO_TRUMP"])(
    "shows authoritative holder and %s without actions",
    (strain) => {
      const game = gameRoom("PLAYING").game!;
      game.auction.result = {
        type: "CONTRACT",
        holderSeat: 2,
        teamId: "NS",
        level: 3,
        strain,
      };
      game.auction.highestBid = { seat: 1, level: 1, strain: "CLUB" };
      const html = renderToStaticMarkup(<ContractBadge game={game} />);
      expect(html).toContain("carol");
      expect(html).not.toContain("bob");
      expect(html).toContain("目標9墩");
      expect(html).not.toContain("<button");
      if (strain === "NO_TRUMP") {
        expect(html).toContain("無王");
        expect(html).not.toContain("NT");
      }
      if (["HEART", "DIAMOND"].includes(strain))
        expect(html).toContain("bid-red");
    },
  );
  it("escapes nickname markup", () => {
    const game = gameRoom("PLAYING").game!;
    game.roster[0].displayName = "<script>bad</script>";
    game.auction.result = {
      type: "CONTRACT",
      holderSeat: 0,
      teamId: "NS",
      level: 1,
      strain: "SPADE",
    };
    expect(renderToStaticMarkup(<ContractBadge game={game} />)).not.toContain(
      "<script>",
    );
  });
});

describe("compact contract team progress", () => {
  it.each(["NS", "EW"] as const)(
    "labels from %s viewer and counts the holder's team",
    (ownTeam) => {
      const game = playedRoom().game!;
      game.auction.result!.level = 3;
      game.play!.tricksByTeam = { NS: 4, EW: 2 };
      game.play!.completedTricks = [
        {
          number: 1,
          leaderSeat: 0,
          winnerSeat: 3,
          plays: [],
          cardsVisible: false,
        },
      ];
      const html = renderToStaticMarkup(
        <ContractBadge game={game} ownTeam={ownTeam} />,
      );
      expect(html).toContain(ownTeam === "EW" ? "我方得標" : "對方得標");
      expect(html).toContain("目前2/9墩");
      expect(html).not.toContain("目前1/9墩");
      expect(html).not.toContain("目前4/9墩");
      expect(html).toContain("團隊目標 9 墩，目前 2 墩");
    },
  );
  it("keeps over-target tricks and handles a missing play snapshot", () => {
    const game = playedRoom().game!;
    game.play!.tricksByTeam.EW = 10;
    expect(
      renderToStaticMarkup(<ContractBadge game={game} ownTeam="NS" />),
    ).toContain("目前10/8墩");
    game.play = null;
    expect(renderToStaticMarkup(<ContractBadge game={game} />)).toContain(
      "目前0/8墩",
    );
  });
});
