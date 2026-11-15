import type { Game, Team } from "./model";
import { strainNames, symbols, teamNames } from "./game-view";
import { playerLabel } from "./player-label";

/** Progress belongs to the settled contract's team, never just its holder. */
export default function ContractBadge({
  game,
  ownTeam,
}: {
  game: Game;
  ownTeam?: Team;
}) {
  const result = game.auction.result;
  if (!result) return null;
  const name = playerLabel(game.roster, result.holderSeat);
  const side = ownTeam
    ? result.teamId === ownTeam
      ? "我方"
      : "對方"
    : teamNames[result.teamId];
  const target = 6 + result.level;
  const taken = game.play?.tricksByTeam[result.teamId] ?? 0;
  return (
    <div className="contract-badge" aria-label="本局得標與王牌">
      <div className="contract-heading">
        <span className="contract-holder" title={`${side}得標・${name}`}>
          {side}得標・<strong>{name}</strong>
        </span>
        <span className="contract-divider" aria-hidden="true">
          ｜
        </span>
        <span
          className={`contract-trump ${["HEART", "DIAMOND"].includes(result.strain) ? "bid-red" : ""}`}
        >
          {result.strain === "NO_TRUMP" ? (
            "無王"
          ) : (
            <>
              王牌{" "}
              <span
                role="img"
                aria-label={strainNames[result.strain] ?? result.strain}
              >
                {symbols[result.strain]}
              </span>
            </>
          )}
        </span>
      </div>
      <div
        className="contract-progress"
        aria-label={`${side}團隊目標 ${target} 墩，目前 ${taken} 墩`}
      >
        <span>目標{target}墩</span>
        <span>
          目前{taken}/{target}墩
        </span>
      </div>
    </div>
  );
}
