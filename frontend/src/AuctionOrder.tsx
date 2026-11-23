import type { Game } from "./model";
import { seatNames, strainNames, symbols } from "./game-view";
import "./auction-order.css";

type PublicAuction = Pick<Game, "dealerSeat" | "auction" | "roster">;

/** A per-seat summary of the current auction, not the ordered call history. */
export default function AuctionOrder({
  game,
  ownSeat,
  placement,
}: {
  game: PublicAuction;
  ownSeat?: number;
  placement: "table" | "below";
}) {
  const latestBySeat = new Map(
    game.auction.calls.map((call) => [call.seat, call]),
  );
  return (
    <section
      className={`auction-order auction-order--${placement}`}
      aria-label="叫牌順序"
    >
      <h3>
        叫牌順序 <small>各席最近一叫</small>
      </h3>
      <ol>
        {[0, 1, 2, 3].map((offset) => {
          const seat = (game.dealerSeat + offset) % 4;
          const name = game.roster.find(
            (player) => player.seat === seat,
          )?.displayName;
          const call = latestBySeat.get(seat);
          const isCurrent = game.auction.nextSeat === seat;
          const currentText = seat === ownSeat ? "待叫" : "叫牌中";
          const isRed = call?.strain === "HEART" || call?.strain === "DIAMOND";
          const strain = call?.strain ?? "";
          return (
            <li
              key={seat}
              data-auction-seat={seat}
              aria-current={isCurrent ? "step" : undefined}
            >
              <span
                className="auction-order-seat"
                title={name ? `${seatNames[seat]} · ${name}` : seatNames[seat]}
              >
                <span>{seatNames[seat]}</span>
                {name && <span className="auction-order-name">{name}</span>}
              </span>
              <span className="auction-order-value">
                {call?.type === "BID" ? (
                  <strong className={isRed ? "auction-order-red" : undefined}>
                    {call.level}
                    <span aria-label={strainNames[strain]} role="img">
                      {symbols[strain]}
                    </span>
                  </strong>
                ) : call?.type === "PASS" ? (
                  <strong>Pass</strong>
                ) : !isCurrent ? (
                  <span className="auction-order-empty">未叫</span>
                ) : null}
                {isCurrent && (
                  <strong className="auction-order-current">
                    {currentText}
                  </strong>
                )}
              </span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
