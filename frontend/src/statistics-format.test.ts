import { describe, expect, it } from "vitest";
import { personalWinRate, taiwanSettlementTime } from "./statistics-format";
describe("personal statistics formatting", () => {
  it("uses wins plus losses plus draws, and does not imply a zero percent rate without games", () => {
    expect(personalWinRate({ wins: 2, losses: 1, draws: 1 })).toBe("50.0%");
    expect(personalWinRate({ wins: 0, losses: 0, draws: 0 })).toBe("—");
    expect(personalWinRate({ wins: 0, losses: 2, draws: 0 })).toBe("0.0%");
    expect(personalWinRate({ wins: 1, losses: 2, draws: 0 })).toBe("33.3%");
  });
  it.each([
    ["2026-12-31T16:00:00Z", "2027年01月01日00時"],
    ["2027-03-31T16:00:00Z", "2027年04月01日00時"],
    ["2027-06-30T16:00:00Z", "2027年07月01日00時"],
    ["2027-09-30T16:00:00Z", "2027年10月01日00時"],
  ])("formats %s as Taiwan quarter cutoff", (instant, label) => {
    expect(taiwanSettlementTime(instant)).toBe(label);
  });
  it("does not invent a settlement time for missing or invalid data", () => {
    expect(taiwanSettlementTime()).toBeNull();
    expect(taiwanSettlementTime("bad")).toBeNull();
  });
});
