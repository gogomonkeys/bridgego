import type { Call } from "./model";
export interface BidSelection {
  level: number | null;
  strain: string | null;
}
// An invalidated draft never becomes an automatic bid or a different chosen bid.
export function validBidSelection(
  selection: BidSelection,
  legal: Call[],
): BidSelection {
  const level = legal.some(
    (call) => call.type === "BID" && call.level === selection.level,
  )
    ? selection.level
    : null;
  const strain =
    level !== null &&
    legal.some(
      (call) =>
        call.type === "BID" &&
        call.level === level &&
        call.strain === selection.strain,
    )
      ? selection.strain
      : null;
  return { level, strain };
}
export function selectedLegalBid(
  selection: BidSelection,
  legal: Call[],
): Call | null {
  return (
    legal.find(
      (call) =>
        call.type === "BID" &&
        call.level === selection.level &&
        call.strain === selection.strain,
    ) ?? null
  );
}
