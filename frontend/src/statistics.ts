import { accountRequest } from "./google-login";
export interface StatisticsConfig {
  enabled: boolean;
  consentVersion: string;
}
export interface PersonalStatistics {
  matches: number;
  completedDeals: number;
  wins: number;
  losses: number;
  draws: number;
  totalScore: number;
}
export interface OverallStatistics {
  matches: number;
  completedDeals: number;
  draws: number;
}
export interface StatisticsResponse {
  status: "AVAILABLE" | "DISABLED" | "UNAVAILABLE";
  quarter: string;
  asOf: string;
  ruleVersion: "four-humans-at-settlement-v1";
  personal: PersonalStatistics | null;
  overall: OverallStatistics | null;
}
export const statisticsNotice =
  "繼續遊玩表示同意：結算時由四位不同真人控制、正常完成計分的場次，結果可存入營運者的私人 Google 試算表。訪客也計入整體場次，但不保存訪客名稱或識別碼，也沒有個人統計或統計入口。登入玩家可查看台灣時間本季個人戰績與不含個人識別的整體數字。原始戰績每季由營運者人工清理，並非自動刪除。結算時仍有電腦代打則不計入；曾由電腦代打但最後一張牌前已由真人接手可以計入。";
export async function readStatisticsConfig(): Promise<StatisticsConfig> {
  const response = await fetch("/api/statistics/config", {
    cache: "no-store",
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error("統計設定暫時無法確認");
  const result = (await response.json()) as StatisticsConfig;
  if (
    typeof result.enabled !== "boolean" ||
    (result.enabled && result.consentVersion !== "statistics-v1")
  )
    throw new Error("統計設定需要更新");
  return result;
}
export async function acceptStatistics(
  token: string,
  config: StatisticsConfig,
): Promise<void> {
  if (!config.enabled) return;
  const result = await accountRequest<{ accepted: boolean }>(
    "/statistics/consent",
    "PUT",
    { consent: true, version: config.consentVersion },
    token,
  );
  if (result.accepted !== true) throw new Error("尚未確認資料使用同意");
}
