import type { Room } from "./model";
/** Social entries belong only to the lobby and a room that is still waiting. */
export function friendsEntryPlacement(
  page: "room" | "help" | "statistics" | "leaderboard",
  room: Pick<Room, "phase"> | null,
): "hidden" | "lobby" {
  return page === "room" && (!room || room.phase === "WAITING")
    ? "lobby"
    : "hidden";
}
