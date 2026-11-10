import { accountRequest } from "./google-login";
export interface StatisticsConfig {
  enabled: boolean;
  consentVersion: string;
}
export interface PersonalStatistics {
  matches: number;
  scoredMatches?: number;
  forfeitMatches?: number;
  interruptedMatches?: number;
  completedDeals: number;
  wins: number;
  losses: number;
  draws: number;
  totalScore: number;
}
export interface OverallStatistics {
  matches: number;
  scoredMatches?: number;
  forfeitMatches?: number;
  interruptedMatches?: number;
  completedDeals: number;
  draws: number;
}
export interface StatisticsResponse {
  status: "AVAILABLE" | "DISABLED" | "UNAVAILABLE";
  quarter: string;
  asOf: string;
  quarterEndsAt?: string;
  lifetimeCoverage?: "RECORDED_SINCE_FEATURE";
  lifetimeTrackedFromQuarter?: string | null;
  lifetimePersonal?: PersonalStatistics | null;
  lifetimeCasualPersonal?: PersonalStatistics | null;
  ruleVersion: "fixed-room-type-v3";
  personal: PersonalStatistics | null;
  overall: OverallStatistics | null;
  casualPersonal: PersonalStatistics | null;
  casualOverall: OverallStatistics | null;
}
export const statisticsNotice =
  "繼續遊玩表示同意：正式場與休閒場的已定案結果（含判負與中止）、已完成局的分數及每局耗時，可存入營運者的私人 Google 試算表。類型在開房時固定；正式場限四位真人，休閒場可加入電腦，兩類分開統計。正式場開局後主動離開或斷線逾時會依規則判負；無法裁定勝負則標示中止，不計勝負。未完成局不計分。訪客也計入整體場次，但不保存訪客名稱或識別碼，也沒有個人統計或統計入口。登入玩家可查看台灣時間本季個人戰績與不含個人識別的整體數字。生涯統計依帳號跨季保留累計數字，刪除帳號時一併移除。僅累計新版已同意保存的紀錄，不補算舊未同意或已清除資料。原始戰績每季由營運者人工清理，並非自動刪除。";
export async function readStatisticsConfig(): Promise<StatisticsConfig> {
  const response = await fetch("/api/statistics/config", {
    cache: "no-store",
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error("統計設定暫時無法確認");
  const result = (await response.json()) as StatisticsConfig;
  if (
    typeof result.enabled !== "boolean" ||
    (result.enabled && result.consentVersion !== "statistics-v4")
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
