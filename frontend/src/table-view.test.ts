import { describe, it, expect } from "vitest";
import { tablePosition, seatRelationship } from "./table-view";
describe("relative table geometry leaves server identity unchanged", () => {
  it.each([0, 1, 2, 3])(
    "maps view %i without swapping teams or left/right neighbors",
    (own) => {
      expect(tablePosition(own, own)).toBe("bottom");
      expect(tablePosition((own + 1) % 4, own)).toBe("left");
      expect(tablePosition((own + 2) % 4, own)).toBe("top");
      expect(tablePosition((own + 3) % 4, own)).toBe("right");
      expect(seatRelationship(own, own)).toBe("你");
      expect(seatRelationship((own + 2) % 4, own)).toBe("隊友");
      expect(seatRelationship((own + 1) % 4, own)).toBe("對手");
      expect(
        new Set([0, 1, 2, 3].map((seat) => tablePosition(seat, own))).size,
      ).toBe(4);
    },
  );
});

// Confirmed 左喊右攻, viewed from each possible own seat.
it.each([0, 1, 2, 3])(
  "keeps leftward bidding and rightward play distinct for view %i",
  (own) => {
    expect(
      [0, 1, 2, 3].map((offset) => tablePosition((own + offset) % 4, own)),
    ).toEqual(["bottom", "left", "top", "right"]);
    expect(
      [1, 2, 3, 4].map((offset) => tablePosition((own + 3 * offset) % 4, own)),
    ).toEqual(["right", "top", "left", "bottom"]);
  },
);
