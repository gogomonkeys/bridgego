import type { Game } from "./model";
import { strainNames, symbols } from "./game-view";
import { playerLabel } from "./player-label";

/** Only a settled auction result identifies the contract holder. */
export default function ContractBadge({ game }: { game: Game }) {
  const result = game.auction.result;
  if (!result) return null;
  const name = playerLabel(game.roster, result.holderSeat);
  const strain = strainNames[result.strain] ?? result.strain;
  return (
    <div className="contract-badge" aria-label="本局得標與王牌">
      <span className="contract-holder" title={`得標：${name}`}>
        得標 <strong>{name}</strong>
      </span>
      <span
        className={`contract-trump ${["HEART", "DIAMOND"].includes(result.strain) ? "bid-red" : ""}`}
      >
        {result.strain === "NO_TRUMP" ? (
          "無王"
        ) : (
          <>
            王 <span aria-hidden="true">{symbols[result.strain]}</span> {strain}
          </>
        )}
      </span>
      <span>{result.level} 階</span>
    </div>
  );
}
