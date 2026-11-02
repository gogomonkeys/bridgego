export type TablePosition = "bottom" | "left" | "top" | "right";
// Presentation rotation only. Server seat indices, turn order and teams never change.
export function tablePosition(seat: number, ownSeat: number): TablePosition {
  return (["bottom", "left", "top", "right"] as const)[
    (seat - ownSeat + 4) % 4
  ];
}
export function seatRelationship(
  seat: number,
  ownSeat: number,
): "你" | "隊友" | "對手" {
  return seat === ownSeat
    ? "你"
    : (seat - ownSeat + 4) % 4 === 2
      ? "隊友"
      : "對手";
}
export const compassNames = ["北 N", "東 E", "南 S", "西 W"];
