import type { Game } from "./model";
import { strainNames, symbols } from "./game-view";
import { playerLabel } from "./player-label";

/** Public auction state, never the player's unsubmitted bid selection. */
export default function CurrentBid({ game }: { game: Game }) {
  const bid = game.auction.highestBid;
  const bidder = bid ? playerLabel(game.roster, bid.seat) : null;
  return (
    <section
      className="current-bid-panel"
      aria-label="牌桌目前最高叫牌"
      aria-live="polite"
      aria-atomic="true"
    >
      <span className="current-bid-title">目前最高叫牌</span>
      {bid ? (
        <>
          <strong
            className={`current-bid-value ${bid.strain === "HEART" || bid.strain === "DIAMOND" ? "bid-red" : ""}`}
            aria-label={`${bid.level} ${strainNames[bid.strain] ?? bid.strain}`}
          >
            <span>{bid.level}</span>
            <span
              className={
                bid.strain === "NO_TRUMP" ? "current-bid-no-trump" : ""
              }
              aria-hidden="true"
            >
              {bid.strain === "NO_TRUMP"
                ? "無王"
                : (symbols[bid.strain] ?? bid.strain)}
            </span>
          </strong>
          <span className="current-bid-bidder" title={`${bidder} 叫牌`}>
            {bidder} 叫牌
          </span>
        </>
      ) : (
        <strong className="current-bid-empty">尚未有人叫牌</strong>
      )}
    </section>
  );
}
