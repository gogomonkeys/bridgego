import { accountRequest, type AuthStatus } from "./google-login";
export type LeaderboardPeriod = "SEASON" | "LIFETIME";
export type LeaderboardCategory = "FORMAL";
export interface LeaderboardEntry {
  rank: number;
  nickname: string;
  totalScore: number;
  matches: number;
  wins: number;
  losses: number;
  draws: number;
  winRate: number | null;
  mine: boolean;
}
export interface LeaderboardReport {
  status: "AVAILABLE" | "DISABLED" | "UNAVAILABLE";
  quarter: string;
  asOf: string;
  quarterEndsAt?: string;
  period: LeaderboardPeriod;
  category: LeaderboardCategory;
  entries: LeaderboardEntry[];
  unavailableReason?: string | null;
  retryAfterSeconds?: number | null;
}
export function leaderboardRetrySeconds(data: LeaderboardReport): number {
  const seconds = data.retryAfterSeconds;
  return data.status === "UNAVAILABLE" &&
    (data.unavailableReason === "BUSY" ||
      data.unavailableReason === "RATE_LIMITED") &&
    typeof seconds === "number" &&
    Number.isFinite(seconds) &&
    seconds > 0
    ? Math.min(3600, Math.ceil(seconds))
    : 0;
}
async function fetchLeaderboard(
  token: string,
  period: LeaderboardPeriod,
  category: LeaderboardCategory,
): Promise<LeaderboardReport> {
  const auth = await accountRequest<AuthStatus>(
    "/auth/status",
    "GET",
    undefined,
    token,
  );
  if (auth.signedIn !== true) throw new Error("Sign-in required");
  const report = await accountRequest<LeaderboardReport>(
    `/statistics/leaderboard?period=${period}&category=${category}`,
    "GET",
    undefined,
    token,
  );
  if (
    report.period !== period ||
    report.category !== category ||
    !["AVAILABLE", "DISABLED", "UNAVAILABLE"].includes(report.status) ||
    !Array.isArray(report.entries)
  )
    throw new Error("Invalid leaderboard response");
  if (
    report.status === "AVAILABLE" &&
    report.entries.some(
      (entry) =>
        !Number.isSafeInteger(entry.rank) ||
        entry.rank < 1 ||
        typeof entry.nickname !== "string" ||
        !entry.nickname.trim() ||
        typeof entry.mine !== "boolean" ||
        !Number.isSafeInteger(entry.totalScore) ||
        [entry.matches, entry.wins, entry.losses, entry.draws].some(
          (n) => !Number.isSafeInteger(n) || n < 0,
        ) ||
        entry.wins + entry.losses + entry.draws > entry.matches,
    )
  )
    throw new Error("Invalid leaderboard entries");
  return report;
}
// Share only in-flight reads (including React StrictMode remounts), never saved records.
const pendingReads = new Map<string, Promise<LeaderboardReport>>();
export function readLeaderboard(
  token: string,
  period: LeaderboardPeriod,
  category: LeaderboardCategory,
): Promise<LeaderboardReport> {
  if (category !== "FORMAL")
    return Promise.reject(new Error("Formal leaderboard only"));
  const key = JSON.stringify([token, period, category]);
  const pending = pendingReads.get(key);
  if (pending) return pending;
  const request = fetchLeaderboard(token, period, category);
  pendingReads.set(key, request);
  void request.then(
    () => pendingReads.delete(key),
    () => pendingReads.delete(key),
  );
  return request;
}

/** One request per view; closing or switching filters/accounts invalidates late results. */
export function createLeaderboardReader(
  read: () => Promise<LeaderboardReport>,
  onResult: (data: LeaderboardReport) => void,
  onError: () => void,
) {
  let disposed = false;
  let running = false;
  return {
    async load() {
      if (disposed || running) return;
      running = true;
      try {
        const result = await read();
        if (!disposed) onResult(result);
      } catch {
        if (!disposed) onError();
      } finally {
        running = false;
      }
    },
    dispose() {
      disposed = true;
    },
  };
}
