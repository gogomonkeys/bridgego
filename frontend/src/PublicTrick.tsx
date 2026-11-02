import type { Game, Play } from "./model";
import type { TrumpEffect } from "./trump-effects";
import { rankLabel, symbols } from "./game-view";
import { playerLabel } from "./player-label";
import { tablePosition } from "./table-view";

const positionGlyphs = { top: "↑", left: "←", right: "→", bottom: "↓" };
const offsets = [2, 1, 3, 0] as const;
/** Four persistent slots: cards do not move when another player plays.
 * Narrow/short screens switch the same slots to a compact two-row layout.
 * Only the current public trick is supplied; hidden history is never read here.
 */
export default function PublicTrick({
  plays,
  roster,
  ownSeat,
  winnerSeat,
  effects = [],
}: {
  plays: Play[];
  roster: Game["roster"];
  ownSeat: number;
  winnerSeat?: number;
  effects?: TrumpEffect[];
}) {
  return (
    <div className="trick-positions portrait-trick" aria-label="本回合出牌">
      {offsets.map((offset) => {
        const seat = (ownSeat + offset) % 4;
        const position = tablePosition(seat, ownSeat);
        const play = plays.find((item) => item.seat === seat);
        const label = playerLabel(roster, seat);
        const won = winnerSeat === seat;
        const effect = effects.find((item) => item.seat === seat);
        return (
          <div
            className={`public-card-slot position-${position}`}
            key={seat}
            data-seat={seat}
          >
            {play ? (
              <div
                aria-label={`${label}出牌 ${symbols[play.card.suit]}${rankLabel(play.card.rank)}`}
                className={`played-card portrait-card ${won ? "winning-card" : ""} ${play.card.suit === "HEART" || play.card.suit === "DIAMOND" ? "red" : ""}`}
              >
                <strong className="public-card-index">
                  <span className="played-rank">
                    {rankLabel(play.card.rank)}
                  </span>
                  <span className="played-suit">{symbols[play.card.suit]}</span>
                </strong>
                <span className="public-card-pip" aria-hidden="true">
                  {symbols[play.card.suit]}
                </span>
                {play.source === "TIMEOUT" && (
                  <small
                    className="card-timeout"
                    title="逾時自動出牌"
                    aria-label="逾時自動出牌"
                  >
                    ⏱
                  </small>
                )}
              </div>
            ) : (
              <div
                className="public-card-empty"
                aria-label={`${label}尚未出牌`}
              />
            )}
            {play && effect && (
              <span
                key={effect.id}
                className={`trump-card-flare trump-${effect.kind}`}
                aria-hidden="true"
              />
            )}
            <span className="public-card-corner">
              <span className="public-card-position" aria-hidden="true">
                {positionGlyphs[position]}
              </span>
              {won && (
                <b className="winner-mark" aria-label="獲勝牌">
                  ★
                </b>
              )}
            </span>
          </div>
        );
      })}
    </div>
  );
}
