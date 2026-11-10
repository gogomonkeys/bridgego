import type { PersonalStatistics } from "./statistics";

/** A tie remains a decided match. Interrupted/no-contest matches are excluded. */
export function personalWinRate(
  counts: Pick<PersonalStatistics, "wins" | "losses" | "draws">,
): string {
  const decided = counts.wins + counts.losses + counts.draws;
  if (!Number.isFinite(decided) || decided <= 0) return "—";
  return `${((counts.wins / decided) * 100).toFixed(1)}%`;
}

/** Format a server-provided instant; browser timezone never changes the quarter label. */
export function taiwanSettlementTime(instant?: string | null): string | null {
  if (!instant || !Number.isFinite(Date.parse(instant))) return null;
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Taipei",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(instant));
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value;
  return `${value("year")}年${value("month")}月${value("day")}日${value("hour")}時`;
}
