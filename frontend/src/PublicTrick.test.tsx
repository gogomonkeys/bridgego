import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import PublicTrick from "./PublicTrick";
import { playedRoom } from "./test-fixtures";
import type { Play } from "./model";

const plays: Play[] = [
  { seat: 0, card: { suit: "HEART", rank: 10 }, source: "TIMEOUT" },
  { seat: 1, card: { suit: "SPADE", rank: 14 }, source: "MANUAL" },
  { seat: 2, card: { suit: "DIAMOND", rank: 13 }, source: "MANUAL" },
  { seat: 3, card: { suit: "CLUB", rank: 2 }, source: "MANUAL" },
];
describe("portrait public cards and persistent spatial ownership", () => {
  it.each([0, 1, 2, 3, 4])(
    "keeps all four slots with %s public plays",
    (count) => {
      const html = renderToStaticMarkup(
        <PublicTrick
          plays={plays.slice(0, count)}
          roster={playedRoom().game!.roster}
          ownSeat={0}
        />,
      );
      expect(html.match(/class="public-card-slot /g)).toHaveLength(4);
      expect(
        html.match(/class="played-card portrait-card/g) ?? [],
      ).toHaveLength(count);
      expect(html.match(/class="public-card-empty"/g) ?? []).toHaveLength(
        4 - count,
      );
      for (const position of ["top", "left", "right", "bottom"]) {
        expect(html).toContain(`public-card-slot position-${position}`);
      }
    },
  );
  it.each([0, 1, 2, 3])(
    "rotates owners from seat %s without changing source cards",
    (ownSeat) => {
      const html = renderToStaticMarkup(
        <PublicTrick
          plays={plays}
          roster={playedRoom().game!.roster}
          ownSeat={ownSeat}
          winnerSeat={ownSeat}
        />,
      );
      expect(html).toContain(
        `public-card-slot position-bottom" data-seat="${ownSeat}"`,
      );
      expect(html.match(/aria-label="獲勝牌"/g)).toHaveLength(1);
      expect(html.match(/class="played-rank"/g)).toHaveLength(4);
      expect(html.match(/class="played-suit"/g)).toHaveLength(4);
      expect(
        html.match(/class="public-card-pip" aria-hidden="true"/g),
      ).toHaveLength(4);
    },
  );
  it("keeps full player identities accessible without visible name captions", () => {
    const roster = playedRoom().game!.roster;
    roster[0].displayName = "森林裡名字非常長的小兔子";
    const html = renderToStaticMarkup(
      <PublicTrick plays={plays} roster={roster} ownSeat={0} />,
    );
    expect(html).toContain('aria-label="森林裡名字非常長的小兔子出牌 ♥10"');
    expect(html).toContain('aria-label="逾時自動出牌"');
    expect(html).not.toContain("public-card-caption");
    expect(html).not.toContain("public-card-player");
    expect(html.replace(/<[^>]*>/g, "")).not.toContain(roster[0].displayName);
  });
});

describe("caption-free public cards", () => {
  for (const ownSeat of [0, 1, 2, 3]) {
    it.each([0, 1, 2, 3, 4])(
      `retains arrows and accessible owners from seat ${ownSeat} with %s cards`,
      (count) => {
        const roster = playedRoom().game!.roster;
        roster[0].displayName = roster[1].displayName = "森林小兔子";
        roster[2].displayName = "WWWWW";
        roster[3].displayName = "🐰🐻🐼🦊🐱";
        const html = renderToStaticMarkup(
          <PublicTrick
            plays={plays.slice(0, count)}
            roster={roster}
            ownSeat={ownSeat}
          />,
        );
        const visibleText = html.replace(/<[^>]*>/g, "");
        for (const player of roster) {
          expect(visibleText).not.toContain(player.displayName);
        }
        expect(html).toContain("森林小兔子（北）");
        expect(html).toContain("森林小兔子（東）");
        expect(html.match(/class="public-card-position"/g)).toHaveLength(4);
        for (const arrow of ["↑", "←", "→", "↓"]) {
          expect(visibleText).toContain(arrow);
        }
        expect(html.match(/尚未出牌/g) ?? []).toHaveLength(4 - count);
        expect(html).not.toContain('title="森林');
      },
    );
  }
});
