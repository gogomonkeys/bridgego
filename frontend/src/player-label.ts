import type { Game } from "./model";
import { seatNames } from "./game-view";
export function playerLabel(
  roster: Game["roster"] = [],
  seat: number | null | undefined,
): string {
  if (seat === null || seat === undefined) return "玩家";
  const player = roster.find((p) => p.seat === seat);
  if (!player) return `${seatNames[seat]}位玩家`;
  const duplicate = roster.some(
    (p) => p.seat !== seat && p.displayName === player.displayName,
  );
  return `${player.displayName}${duplicate ? `（${seatNames[seat]}）` : ""}`;
}
