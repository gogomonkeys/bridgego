import type {
  DealResult,
  Game,
  Play,
  Trick,
  Team,
  MatchArchive,
} from "./model";
import { playerLabel } from "./player-label";
import { rankLabel, symbols, teamNames } from "./game-view";
export function PublicCards({
  plays,
  winnerSeat,
  roster = [],
}: {
  plays: Play[];
  winnerSeat?: number;
  roster?: Game["roster"];
}) {
  return (
    <ol className="public-cards">
      {plays.map((play) => (
        <li
          key={play.seat}
          className={play.seat === winnerSeat ? "winning-card" : ""}
        >
          <span>
            {playerLabel(roster, play.seat)}
            {play.seat === winnerSeat && (
              <b className="winner-mark" aria-label="獲勝牌">
                ★
              </b>
            )}
          </span>
          <strong
            className={
              play.card.suit === "HEART" || play.card.suit === "DIAMOND"
                ? "red"
                : ""
            }
          >
            {symbols[play.card.suit]} {rankLabel(play.card.rank)}
          </strong>
          {play.source === "TIMEOUT" && <small>逾時自動出牌</small>}
          {play.source === "BOT" && <small>電腦出牌</small>}
        </li>
      ))}
    </ol>
  );
}
export function TrickRecord({
  trick,
  ownTeam,
  roster = [],
}: {
  trick: Trick;
  ownTeam?: Team;
  roster?: Game["roster"];
}) {
  const ourTrick =
    ownTeam !== undefined &&
    (trick.winnerSeat % 2 === 0 ? "NS" : "EW") === ownTeam;
  return (
    <div
      className={`trick-record ${ourTrick ? "our-trick" : ""}`}
      data-trick={trick.number}
    >
      <p>
        第 {trick.number} 回合 · {playerLabel(roster, trick.leaderSeat)}首引 ·{" "}
        <strong>★ {playerLabel(roster, trick.winnerSeat)}贏得本回合</strong>
        {ourTrick && <span className="our-trick-label">✓ 我方</span>}
      </p>
      {trick.cardsVisible === true ? (
        <PublicCards
          plays={trick.plays}
          winnerSeat={trick.winnerSeat}
          roster={roster}
        />
      ) : (
        <div
          className="covered-trick"
          aria-label="本回合四張牌已蓋起，結算後開放"
        >
          <div className="card-back-stack" aria-hidden="true">
            {[0, 1, 2, 3].map((i) => (
              <span key={i}>
                <svg viewBox="0 0 24 24">
                  <path d="m12 4 8 8-8 8-8-8Z" />
                  <circle cx="12" cy="12" r="2" />
                </svg>
              </span>
            ))}
          </div>
          <span>
            回合已結束 · 牌面蓋起<small>本局結算後可回顧</small>
          </span>
        </div>
      )}
    </div>
  );
}
export function DealBreakdown({
  result,
  roster = [],
}: {
  result: DealResult;
  roster?: Game["roster"];
}) {
  return (
    <div className="deal-breakdown">
      <h3>第 {result.dealIndex} 局結算</h3>
      <p>
        定約 {result.contract.level} {symbols[result.contract.strain]} ·{" "}
        {teamNames[result.contract.teamId]} ·{" "}
        {playerLabel(roster, result.contract.holderSeat)}叫到
      </p>
      <div className="score-cards">
        {(["NS", "EW"] as const).map((team) => {
          const score = result.scores[team];
          return (
            <section
              className={`score-card team-${team}`}
              key={team}
              aria-label={`${teamNames[team]}計分明細`}
            >
              <div className="score-card-heading">
                <strong>{teamNames[team]}</strong>
                <span>{score.won ? "★ 本局勝" : "本局負"}</span>
              </div>
              <p className="score-card-total">
                <b>{score.total}</b> 分{" "}
                <small>▤ 拿下 {result.tricksByTeam[team]} 墩</small>
              </p>
              <dl>
                <div>
                  <dt>基本分</dt>
                  <dd>{score.base}</dd>
                </div>
                <div>
                  <dt>剛好達標</dt>
                  <dd>{score.exactTargetBonus}</dd>
                </div>
                <div>
                  <dt>失約扣分</dt>
                  <dd>{score.penalty}</dd>
                </div>
                <div>
                  <dt>特權調整 {score.privilegeUsed ? "✦" : "—"}</dt>
                  <dd>{score.privilegeAdjustment}</dd>
                </div>
              </dl>
            </section>
          );
        })}
      </div>
    </div>
  );
}
export function MatchSummary({
  game,
}: {
  game: Pick<
    Game,
    | "matchResult"
    | "completedDeals"
    | "plannedDeals"
    | "teamTotals"
    | "completedDealResults"
  >;
}) {
  const result = game.matchResult;
  if (!result) return null;
  const reason = {
    THREE_DEALS_COMPLETE: "三局皆已完成",
    MANUAL_EXIT: "有玩家主動退出",
    RECONNECT_TIMEOUT: "重連期限屆滿",
    BOTH_TEAMS_FAILED: "兩隊均未完成有效重連／退出",
  }[result.reason];
  return (
    <section className="contract-result" aria-label="整場結果" role="status">
      <span className="eyebrow">MATCH RESULT</span>
      <h3>
        {result.outcome === "NO_CONTEST"
          ? "本場無勝負"
          : result.winnerTeamId
            ? `${teamNames[result.winnerTeamId]}獲勝`
            : "三局總分平手"}
      </h3>
      <p>
        {reason}。
        {result.outcome === "FORFEIT" &&
          "依退出／重連失敗規則判負，不以目前積分改判。"}
      </p>
      {result.failedTeamIds.length > 0 && (
        <p>
          判定失敗隊伍：
          {result.failedTeamIds.map((team) => teamNames[team]).join("、")}
        </p>
      )}
      <p>
        已完成 {game.completedDeals} / 3 局 · 南北 {game.teamTotals.NS} 分 ·
        東西 {game.teamTotals.EW} 分
      </p>
      <p>已完成的分數保留；未完成的局不計分。之後離開或斷線不改變此結果。</p>
    </section>
  );
}

export function ForfeitAttribution({
  game,
}: {
  game: Pick<Game, "roster" | "forfeitEvents">;
}) {
  if (!game.forfeitEvents.length) return null;
  return (
    <section className="own-timeout-notice" aria-label="退出與重連失敗紀錄">
      <strong>裁定事件</strong>
      {game.forfeitEvents.map((event, index) => {
        const member = game.roster.find(
          (player) => player.playerId === event.playerId,
        );
        return (
          <p key={index}>
            {playerLabel(game.roster, event.seat)} ·{" "}
            {member ? teamNames[member.teamId] : ""}：
            {event.cause === "MANUAL_EXIT" ? "主動退出" : "重連逾時"}
          </p>
        );
      })}
    </section>
  );
}

/** Previous match is intentionally a compact outcome, not another history tree. */
export function PreviousMatchSummary({ match }: { match: MatchArchive }) {
  const result = match.matchResult;
  return (
    <section className="last-match-archive" aria-label="上一場結果">
      <span className="eyebrow">上一場結果</span>
      <strong>
        {result.outcome === "NO_CONTEST"
          ? "無勝負"
          : result.winnerTeamId
            ? `${teamNames[result.winnerTeamId]}獲勝`
            : "平手"}
        {result.outcome === "FORFEIT" && <small> · 棄權判定</small>}
      </strong>
      <div className="previous-totals">
        <span>
          南北 <b>{match.teamTotals.NS}</b> 分
        </span>
        <span>
          東西 <b>{match.teamTotals.EW}</b> 分
        </span>
      </div>
    </section>
  );
}
