import { describe, expect, it } from "vitest";
import { friendsEntryPlacement } from "./friends-entry-placement";
import type { Phase } from "./model";

describe("friends entries only in lobby and waiting room", () => {
  it("retains both supported places", () => {
    expect(friendsEntryPlacement("room", null)).toBe("lobby");
    expect(friendsEntryPlacement("room", { phase: "WAITING" })).toBe("lobby");
  });
  it.each(["help", "statistics", "leaderboard"] as const)("hides on %s", page => {
    expect(friendsEntryPlacement(page, null)).toBe("hidden");
    expect(friendsEntryPlacement(page, {phase:"WAITING"})).toBe("hidden");
    expect(friendsEntryPlacement(page, {phase:"MATCH_RESULT"})).toBe("hidden");
  });
  it.each(["REDEAL_DECISION", "PRIVILEGE_DECISION", "BIDDING", "PLAYING", "TRICK_RESULT", "DEAL_RESULT", "FORFEIT_PENDING", "MATCH_RESULT", "ABORTED"] as Phase[])("hides room phase %s", phase => {
    expect(friendsEntryPlacement("room", {phase})).toBe("hidden");
  });
});
