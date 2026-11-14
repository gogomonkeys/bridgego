import { beforeEach, describe, expect, it, vi } from "vitest";
import { accountRequest } from "./google-login";
import {
  createLeaderboardReader,
  leaderboardRetrySeconds,
  readLeaderboard,
  type LeaderboardReport,
} from "./leaderboard";
vi.mock("./google-login", () => ({ accountRequest: vi.fn() }));
const report: LeaderboardReport = {
  status: "AVAILABLE",
  quarter: "2026-Q4",
  asOf: "2026-11-14T00:00:00Z",
  period: "SEASON",
  category: "FORMAL",
  entries: [],
};
beforeEach(() => vi.resetAllMocks());
describe("leaderboard read boundary", () => {
  it("requires authentication before requesting statistics", async () => {
    vi.mocked(accountRequest).mockResolvedValueOnce({ signedIn: false });
    await expect(readLeaderboard("t", "SEASON", "FORMAL")).rejects.toThrow(
      "Sign-in required",
    );
    expect(accountRequest).toHaveBeenCalledTimes(1);
  });
  it("never requests casual rankings, even from an untyped caller", async () => {
    await expect(
      readLeaderboard("t", "SEASON", "CASUAL" as "FORMAL"),
    ).rejects.toThrow("Formal leaderboard only");
    expect(accountRequest).not.toHaveBeenCalled();
  });
  it("rejects casual response instead of mixing it into formal results", async () => {
    vi.mocked(accountRequest)
      .mockResolvedValueOnce({ signedIn: true })
      .mockResolvedValueOnce({ ...report, category: "CASUAL" });
    await expect(readLeaderboard("t", "SEASON", "FORMAL")).rejects.toThrow(
      "Invalid leaderboard response",
    );
  });
  it("requests exact category and period and preserves backend ranks", async () => {
    vi.mocked(accountRequest)
      .mockResolvedValueOnce({ signedIn: true })
      .mockResolvedValueOnce(report);
    expect(await readLeaderboard("t", "SEASON", "FORMAL")).toEqual(report);
    expect(accountRequest).toHaveBeenLastCalledWith(
      "/statistics/leaderboard?period=SEASON&category=FORMAL",
      "GET",
      undefined,
      "t",
    );
  });
  it("shares same-view in-flight reads across remounts without persisting statistics", async () => {
    let resolve!: (value: unknown) => void;
    vi.mocked(accountRequest)
      .mockImplementationOnce(
        () =>
          new Promise((r) => {
            resolve = r;
          }),
      )
      .mockResolvedValueOnce(report);
    const first = readLeaderboard("dedup", "SEASON", "FORMAL");
    const second = readLeaderboard("dedup", "SEASON", "FORMAL");
    expect(second).toBe(first);
    resolve({ signedIn: true });
    await first;
    expect(accountRequest).toHaveBeenCalledTimes(2);
  });
  it("rejects cross-period or invalid results rather than showing wrong statistics", async () => {
    vi.mocked(accountRequest)
      .mockResolvedValueOnce({ signedIn: true })
      .mockResolvedValueOnce({ ...report, period: "LIFETIME" });
    await expect(readLeaderboard("t", "SEASON", "FORMAL")).rejects.toThrow(
      "Invalid leaderboard response",
    );
  });
  it("rejects impossible counts", async () => {
    vi.mocked(accountRequest)
      .mockResolvedValueOnce({ signedIn: true })
      .mockResolvedValueOnce({
        ...report,
        entries: [
          {
            rank: 1,
            nickname: "Player",
            totalScore: 0,
            matches: 1,
            wins: 2,
            losses: 0,
            draws: 0,
            mine: true,
          },
        ],
      });
    await expect(readLeaderboard("t", "SEASON", "FORMAL")).rejects.toThrow(
      "Invalid leaderboard entries",
    );
  });
  it("honors only finite positive server busy/rate-limit cooldown", () => {
    expect(
      leaderboardRetrySeconds({
        ...report,
        status: "UNAVAILABLE",
        unavailableReason: "RATE_LIMITED",
        retryAfterSeconds: 2.2,
      }),
    ).toBe(3);
    expect(
      leaderboardRetrySeconds({
        ...report,
        status: "UNAVAILABLE",
        unavailableReason: "BUSY",
        retryAfterSeconds: Infinity,
      }),
    ).toBe(0);
    expect(leaderboardRetrySeconds({ ...report, retryAfterSeconds: 5 })).toBe(
      0,
    );
  });
});
describe("request lifecycle", () => {
  it("suppresses repeated clicks and discards a response after disposal", async () => {
    let resolve!: (r: LeaderboardReport) => void;
    const read = vi.fn(
      () =>
        new Promise<LeaderboardReport>((r) => {
          resolve = r;
        }),
    );
    const receive = vi.fn();
    const error = vi.fn();
    const reader = createLeaderboardReader(read, receive, error);
    const pending = reader.load();
    await reader.load();
    expect(read).toHaveBeenCalledTimes(1);
    reader.dispose();
    resolve(report);
    await pending;
    await reader.load();
    expect(receive).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(read).toHaveBeenCalledTimes(1);
  });
  it("reports failure once without automatic retry", async () => {
    const read = vi.fn().mockRejectedValue(new Error("network"));
    const error = vi.fn();
    const reader = createLeaderboardReader(read, vi.fn(), error);
    await reader.load();
    expect(read).toHaveBeenCalledTimes(1);
    expect(error).toHaveBeenCalledTimes(1);
  });
});
