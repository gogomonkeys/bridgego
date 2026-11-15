import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import ContractBadge from "./ContractBadge";
import { gameRoom } from "./test-fixtures";
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
      expect(html).toContain("3 階");
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
