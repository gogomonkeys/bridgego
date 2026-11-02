import { useState } from "react";
import type { AuctionRound, Game, Team } from "./model";
import { callLabel } from "./game-view";
import { playerLabel } from "./player-label";
import { DealBreakdown, TrickRecord } from "./ResultPanel";

export function AuctionRecords({
  rounds,
  roster,
}: {
  rounds: AuctionRound[];
  roster: Game["roster"];
}) {
  return (
    <div className="auction-rounds">
      {rounds.length ? (
        rounds.map((round) => (
          <section
            className="auction-round"
            key={`${round.dealId}:${round.redealCount}`}
          >
            <h3>
              {round.redealCount ? `重發 ${round.redealCount}` : "首次發牌"}
              <span>
                {round.status === "ALL_PASS"
                  ? "全員 Pass · 已重發"
                  : round.status === "CONTRACT"
                    ? "定約完成"
                    : "叫牌進行中"}
              </span>
            </h3>
            {round.calls.length ? (
              <ol className="auction-history">
                {round.calls.map((call, index) => (
                  <li key={index}>
                    <span className="call-order">{index + 1}</span>
                    <span>{playerLabel(roster, call.seat)}</span>
                    <strong>
                      {callLabel(call)}
                      {call.source === "TIMEOUT" && (
                        <small className="timeout-source">逾時自動</small>
                      )}
                    </strong>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="empty-record">還沒有叫牌。</p>
            )}
          </section>
        ))
      ) : (
        <p className="empty-record">這一局尚無可用的叫牌紀錄。</p>
      )}
    </div>
  );
}

export function CurrentDealRecords({
  game,
  ownTeam,
}: {
  game: Game;
  ownTeam?: Team;
}) {
  const [section, setSection] = useState<"tricks" | "auction">(
    game.play ? "tricks" : "auction",
  );
  const [selectedIndex, setSelectedIndex] = useState(game.dealIndex);
  const selected = game.completedDealResults.find(
    (deal) => deal.dealIndex === selectedIndex,
  );
  const current = selectedIndex === game.dealIndex;
  const tricks = current
    ? (game.play?.completedTricks ?? [])
    : (selected?.completedTricks ?? []);
  const rounds =
    game.auctionRounds?.filter((round) => round.dealIndex === selectedIndex) ??
    selected?.auctionRounds ??
    (current
      ? [
          {
            dealId: game.dealId,
            dealIndex: game.dealIndex,
            redealCount: game.redealCount,
            dealerSeat: game.dealerSeat,
            calls: game.auction.calls,
            result: game.auction.result,
            status: game.auction.result
              ? ("CONTRACT" as const)
              : ("IN_PROGRESS" as const),
          },
        ]
      : []);
  const indexes = [
    ...new Set([
      ...game.completedDealResults.map((d) => d.dealIndex),
      game.dealIndex,
    ]),
  ];
  const hidden = tricks.some((t) => t.cardsVisible !== true);
  return (
    <div className="current-deal-records">
      <div className="record-switch deal-switch" aria-label="選擇紀錄局別">
        {indexes.map((index) => (
          <button
            type="button"
            key={index}
            aria-pressed={selectedIndex === index}
            onClick={() => setSelectedIndex(index)}
          >
            第 {index} 局{index === game.dealIndex ? " · 本局" : ""}
          </button>
        ))}
      </div>
      <div className="record-switch" aria-label="紀錄分類">
        <button
          type="button"
          aria-pressed={section === "tricks"}
          onClick={() => setSection("tricks")}
        >
          ▤ 回合紀錄 · {tricks.length} 回合
        </button>
        <button
          type="button"
          aria-pressed={section === "auction"}
          onClick={() => setSection("auction")}
        >
          ♧ 叫牌
        </button>
      </div>
      {section === "tricks" ? (
        <>
          <p className="record-legend">
            <span>{hidden ? "牌面已蓋起 · 本局結算後開放" : "★ 獲勝牌"}</span>
            <span className="our-trick-label">✓ 我方獲勝</span>
          </p>
          {tricks.length ? (
            [...tricks]
              .reverse()
              .map((trick) => (
                <TrickRecord
                  key={trick.number}
                  trick={trick}
                  ownTeam={ownTeam}
                  roster={game.roster}
                />
              ))
          ) : (
            <p className="empty-record">
              還沒有完成的回合。四張牌出齊後會顯示回合紀錄。
            </p>
          )}
        </>
      ) : (
        <AuctionRecords rounds={rounds} roster={game.roster} />
      )}
    </div>
  );
}

export function SettlementRecords({ game }: { game: Game }) {
  const [selected, setSelected] = useState<string | null>(null);
  const result =
    game.completedDealResults.find((deal) => deal.dealId === selected) ??
    game.completedDealResults.at(-1);
  return (
    <div className="settlement-records">
      <div className="record-switch deal-switch" aria-label="選擇結算局別">
        {game.completedDealResults.map((deal) => (
          <button
            type="button"
            key={deal.dealId}
            aria-pressed={result?.dealId === deal.dealId}
            onClick={() => setSelected(deal.dealId)}
          >
            第 {deal.dealIndex} 局
          </button>
        ))}
      </div>
      {result ? (
        <DealBreakdown result={result} roster={game.roster} />
      ) : (
        <p className="empty-record">第一局結束後，這裡會保留各局的計分明細。</p>
      )}
    </div>
  );
}
