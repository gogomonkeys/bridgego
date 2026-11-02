import type { Card, Call } from "./model";
export const seatNames = ["北", "東", "南", "西"];
export const teamNames = { NS: "南北隊", EW: "東西隊" };
export const suitOrder = ["SPADE", "HEART", "DIAMOND", "CLUB"] as const;
export const symbols: Record<string, string> = {
  CLUB: "♣",
  DIAMOND: "♦",
  HEART: "♥",
  SPADE: "♠",
  NO_TRUMP: "NT",
};
export const strainNames: Record<string, string> = {
  CLUB: "梅花",
  DIAMOND: "方塊",
  HEART: "紅心",
  SPADE: "黑桃",
  NO_TRUMP: "無王",
};
export function rankLabel(rank: number) {
  return (
    ({ 11: "J", 12: "Q", 13: "K", 14: "A" } as Record<number, string>)[rank] ??
    String(rank)
  );
}
export function sortedHand(hand: Card[]) {
  return [...hand].sort(
    (a, b) =>
      suitOrder.indexOf(a.suit) - suitOrder.indexOf(b.suit) || b.rank - a.rank,
  );
}
export function callLabel(call: Call) {
  return call.type === "PASS"
    ? "Pass"
    : `${call.level} ${symbols[call.strain ?? ""] ?? call.strain}`;
}
