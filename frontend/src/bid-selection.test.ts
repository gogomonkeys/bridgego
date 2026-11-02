import { describe, it, expect } from "vitest";
import { validBidSelection, selectedLegalBid } from "./bid-selection";
import type { Call } from "./model";
const legal: Call[] = [
  { type: "PASS", level: null, strain: null },
  { type: "BID", level: 2, strain: "HEART" },
  { type: "BID", level: 2, strain: "SPADE" },
  { type: "BID", level: 3, strain: "CLUB" },
];
describe("explicit bid draft is presentation only", () => {
  it("empty draft does not auto-select or submit first legal call", () => {
    expect(validBidSelection({ level: null, strain: null }, legal)).toEqual({
      level: null,
      strain: null,
    });
    expect(selectedLegalBid({ level: null, strain: null }, legal)).toBeNull();
  });
  it("level alone is not a bid", () => {
    expect(validBidSelection({ level: 2, strain: null }, legal)).toEqual({
      level: 2,
      strain: null,
    });
    expect(selectedLegalBid({ level: 2, strain: null }, legal)).toBeNull();
  });
  it("exact authorized pair returns only that server call", () => {
    expect(selectedLegalBid({ level: 2, strain: "HEART" }, legal)).toBe(
      legal[1],
    );
    expect(selectedLegalBid({ level: 2, strain: "CLUB" }, legal)).toBeNull();
  });
  it("a newer highest bid clears invalid strain without substituting another", () => {
    const newer = legal.filter((c) => c.strain !== "HEART");
    expect(validBidSelection({ level: 2, strain: "HEART" }, newer)).toEqual({
      level: 2,
      strain: null,
    });
  });
  it("a changed turn or removed level clears both draft fields", () => {
    expect(validBidSelection({ level: 2, strain: "HEART" }, [])).toEqual({
      level: null,
      strain: null,
    });
    expect(validBidSelection({ level: 7, strain: "HEART" }, legal)).toEqual({
      level: null,
      strain: null,
    });
  });
  it("changing level cannot carry an illegal strain across levels", () => {
    expect(validBidSelection({ level: 3, strain: "HEART" }, legal)).toEqual({
      level: 3,
      strain: null,
    });
  });
});
