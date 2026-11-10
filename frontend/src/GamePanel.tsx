import MatchPersistence from "./MatchPersistence";
import { useEffect, useRef, useState } from "react";
import { client } from "./client";
import type { Room } from "./model";
import { DealBreakdown, ForfeitAttribution, MatchSummary } from "./ResultPanel";
import { operationRemainingMs } from "./timer-view";
import { symbols, teamNames } from "./game-view";
import PublicTrick from "./PublicTrick";
import TrumpEffects, { useTrumpEffects } from "./TrumpEffects";
import TableSeats from "./TableSeats";
import OwnTimeoutNotice from "./OwnTimeoutNotice";
import BidControls from "./BidControls";
import CurrentBid from "./CurrentBid";
import OperationClock from "./OperationClock";
import IconTip from "./IconTip";
import PrivilegeBadge from "./PrivilegeBadge";
import HandPanel from "./HandPanel";
import RedealControls from "./RedealControls";
import { playerLabel } from "./player-label";
import { CurrentDealRecords, SettlementRecords } from "./RecordsPanel";

export default function GamePanel({
  room,
  online,
  busy,
  pendingAction,
  snapshotReceivedAt,
  connectionEpoch = 0,
  onHelp,
}: {
  room: Room;
  online: boolean;
  busy: boolean;
  pendingAction: string | null;
  snapshotReceivedAt: number;
  connectionEpoch?: number;
  onHelp?: () => void;
}) {
  const game = room.game;
  const trumpEffects = useTrumpEffects(room, online, connectionEpoch);
  const [confirmExit, setConfirmExit] = useState(false);
  const [recordsMode, setRecordsMode] = useState<
    "current" | "settlements" | null
  >(null);
  const historyOpen = recordsMode !== null;
  const [now, setNow] = useState(Date.now());
  const [monotonicNow, setMonotonicNow] = useState(() => performance.now());
  const recordsRef = useRef<HTMLElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null),
    exitTriggerRef = useRef<HTMLButtonElement>(null),
    historyTriggerRef = useRef<HTMLButtonElement>(null),
    settlementTriggerRef = useRef<HTMLButtonElement>(null),
    historyCloseRef = useRef<HTMLButtonElement>(null);
  const dismissExit = () => {
    setConfirmExit(false);
    requestAnimationFrame(() => exitTriggerRef.current?.focus());
  };
  const closeHistory = () => {
    const trigger =
      recordsMode === "settlements" ? settlementTriggerRef : historyTriggerRef;
    setRecordsMode(null);
    requestAnimationFrame(() => {
      trigger.current?.focus({ preventScroll: true });
    });
  };
  useEffect(() => {
    if (!game?.paused && !game?.operationTimer) return;
    const update = () => {
      setNow(Date.now());
      setMonotonicNow(performance.now());
    };
    update();
    const timer = setInterval(update, 250);
    return () => clearInterval(timer);
  }, [game?.paused, game?.operationTimer]);
  useEffect(() => {
    if (!recordsMode) return;
    const frame = requestAnimationFrame(() => {
      historyCloseRef.current?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [recordsMode]);
  useEffect(() => {
    if (confirmExit) cancelRef.current?.focus();
  }, [confirmExit]);
  if (!game)
    return (
      <section className="panel">
        <h2>正在取得遊戲狀態</h2>
        <button onClick={() => void client.synchronize()}>重新同步</button>
      </section>
    );
  const terminal = room.phase === "MATCH_RESULT" || room.phase === "ABORTED";
  const committedOutcome = terminal || game.matchResult !== null;
  const pending = room.phase === "FORFEIT_PENDING";
  const resultPhase = room.phase === "DEAL_RESULT";
  const collecting = room.phase === "TRICK_RESULT";
  const completedTrick = collecting
    ? game.play?.completedTricks.at(-1)
    : undefined;
  const winner = game.roster.find(
    (player) => player.seat === completedTrick?.winnerSeat,
  );
  const ownTeam = room.you?.teamId;
  const gameplay = !terminal && !pending && !resultPhase;
  const locked =
    busy ||
    !online ||
    game.paused ||
    !!pendingAction ||
    terminal ||
    pending ||
    collecting;

  const myTurn = game.auction.nextSeat === room.you?.seat;
  const playTurn =
    room.phase === "PLAYING" && game.play?.nextSeat === room.you?.seat;
  const turnSeat =
    room.phase === "PLAYING"
      ? (game.play?.nextSeat ?? null)
      : room.phase === "BIDDING"
        ? game.auction.nextSeat
        : null;
  const remaining = operationRemainingMs(
    game.operationTimer,
    snapshotReceivedAt,
    monotonicNow,
  );
  const seconds = remaining === null ? null : Math.ceil(remaining / 1000);
  const phaseLabel = {
    WAITING: "等候室",
    REDEAL_DECISION: "倒牌確認",
    PRIVILEGE_DECISION: "選擇特權",
    BIDDING: "叫牌",
    PLAYING: "出牌",
    TRICK_RESULT: "回合結算",
    DEAL_RESULT: "本局結算",
    FORFEIT_PENDING: "重連裁定",
    MATCH_RESULT: "整場結果",
    ABORTED: "遊戲中止",
  }[room.phase];
  const exit = () => {
    setConfirmExit(false);
    void client.leave();
  };
  return (
    <section
      className={`match-workspace ${gameplay ? "viewport-game" : "result-workspace"}`}
      data-testid="game-panel"
      data-phase={room.phase.toLowerCase()}
    >
      <header className="match-heading">
        <div>
          <h2>
            第 {game.dealIndex} / {game.plannedDeals} 局{" "}
            <span className="phase-tag">{phaseLabel}</span>
          </h2>
        </div>
        <span
          role="status"
          aria-label={
            online
              ? "即時同步"
              : "非即時畫面：多人遊戲需要網路，恢復連線後確認席位。"
          }
          title={online ? undefined : "多人遊戲需要網路，恢復連線後確認席位。"}
          className={`sync-badge ${online ? "" : "away"}`}
        >
          {online ? "● 即時同步" : "○ 非即時畫面"}
        </span>
      </header>
      <section className="match-scoreboard" aria-label="兩隊累計總分">
        {(["NS", "EW"] as const).map((team) => (
          <div
            key={team}
            className={`match-score team-${team} ${team === ownTeam ? "our-score" : ""}`}
          >
            <span>
              <span className="score-team-label">
                {team === ownTeam ? "我方" : "對方"}
                <span className="score-team-name"> · {teamNames[team]}</span>
              </span>
              {game.play && (
                <small className="mobile-team-tricks">
                  本局拿下 {game.play.tricksByTeam[team]} 墩
                </small>
              )}
            </span>
            <strong>
              {game.teamTotals[team]} <small>分</small>
            </strong>
          </div>
        ))}
        <span className="scoreboard-caption" aria-hidden="true">
          累計總分 · 已結算 {game.completedDeals} / {game.plannedDeals} 局
        </span>
      </section>
      {game.paused && !terminal && !pending && (
        <section className="pause-banner" role="status">
          <strong>遊戲暫停 · 等候原玩家重連</strong>
          <p>
            {room.roomType === "FORMAL"
              ? "正式場斷線保留 10 秒，逾時依規則判負，不由電腦接手；雙方皆失敗可能不計勝負。"
              : "斷線保留 10 秒；仍有真人在線時，逾時由電腦代打。所有真人都離線時，10 秒內無人回來則結束本場。"}
          </p>
          {room.allHumansReconnectDeadline != null && (
            <p>
              等候任一真人回來：約{" "}
              {Math.max(
                0,
                Math.ceil((room.allHumansReconnectDeadline - now) / 1000),
              )}{" "}
              秒
            </p>
          )}
          {room.players
            .filter((p) => p.reconnectDeadline !== null)
            .map((p) => (
              <p key={p.playerId}>
                {playerLabel(game.roster, p.seat)}：約{" "}
                {Math.max(0, Math.ceil((p.reconnectDeadline! - now) / 1000))} 秒
              </p>
            ))}
        </section>
      )}
      {pending && (
        <section className="panel outcome-screen">
          <h2>離開／重連結果待裁定</h2>
          <p>
            已判定失敗：
            {game.pendingForfeit?.failedTeamIds
              .map((team) => teamNames[team])
              .join("、")}
          </p>
          <p>
            {game.pendingForfeit?.waitingSeats.length ? (
              <>
                等候原期限：
                {game.pendingForfeit.waitingSeats
                  .map((seat) => playerLabel(game.roster, seat))
                  .join("、")}
                。不新增 10 秒，稍後返回不撤銷已判定失敗。
              </>
            ) : (
              "正在確認雙方離開狀態，避免近同時離開造成錯誤裁定；請等候最終結果。"
            )}
          </p>
          <ForfeitAttribution game={game} />
        </section>
      )}
      {terminal && (
        <section className="panel outcome-screen">
          <MatchSummary game={game} />
          <MatchPersistence
            key={game.gameId}
            roomId={room.roomId}
            gameId={game.gameId}
          />
          <ForfeitAttribution game={game} />
          {room.phase === "ABORTED" && (
            <p>本場遊戲已中止，未完成的局不計分。</p>
          )}
          <div className="rematch-actions">
            <button
              className="primary"
              disabled={busy || !online || !!pendingAction || !room.canRematch}
              onClick={() => void client.rematch()}
            >
              再玩一場 · 回等候室
            </button>
            <p>
              {room.canRematch
                ? "保留在線玩家與原座位，釋出離線席位，全部重新準備。"
                : "等候在線房主重開；原結果不因離開而改變。"}
            </p>
          </div>
        </section>
      )}
      {resultPhase && (
        <section className="panel outcome-screen" aria-label="本局結算">
          <span className="eyebrow">DEAL RESULT · 暫不發新牌</span>
          {game.completedDealResults.at(-1) && (
            <DealBreakdown
              result={game.completedDealResults.at(-1)!}
              roster={game.roster}
            />
          )}
          <p className="team-totals">
            目前累計：南北 {game.teamTotals.NS} 分 · 東西 {game.teamTotals.EW}{" "}
            分
          </p>
          <OperationClock
            timer={game.operationTimer}
            seconds={seconds}
            roster={room.players}
          />
          <div className="ack-seats">
            {game.roster.map((player) => (
              <span
                key={player.seat}
                className={
                  game.resultAcknowledgedSeats.includes(player.seat)
                    ? "acknowledged"
                    : ""
                }
              >
                {playerLabel(game.roster, player.seat)} ·{" "}
                {game.resultAcknowledgedSeats.includes(player.seat)
                  ? "✓ 已確認"
                  : "等待確認"}
              </span>
            ))}
          </div>
          <button
            className="primary"
            disabled={locked || !room.you?.canAcknowledgeResult}
            onClick={() => void client.acknowledgeResult()}
          >
            {game.resultAcknowledgedSeats.includes(room.you?.seat ?? -1)
              ? "已確認，等候其他玩家"
              : "確認，準備下一局"}
          </button>
          <p className="fine">
            四人確認或 90 秒期限到才發新牌；下一局特權倒數尚未開始。
          </p>
        </section>
      )}
      {gameplay && (
        <>
          <section className="table-dashboard" aria-label="本局狀態與操作倒數">
            <div className="table-status-strip">
              {game.redealCount > 0 && (
                <span
                  className="redeal-count"
                  title="本局倒牌與全員 Pass 的累計重發次數"
                >
                  已重發 {game.redealCount} 次
                  {game.lastRedealReason === "HAND_REDEAL"
                    ? " · 最近倒牌"
                    : game.lastRedealReason === "ALL_PASS"
                      ? " · 最近全員 Pass"
                      : ""}
                </span>
              )}
              <p className="contract-chip">
                {game.auction.result
                  ? `定約 ${game.auction.result.level}${symbols[game.auction.result.strain]} · ${teamNames[game.auction.result.teamId]}`
                  : game.auction.highestBid
                    ? `目前 ${game.auction.highestBid.level}${symbols[game.auction.highestBid.strain]}`
                    : "尚未定約"}
              </p>
              {game.play && (
                <span className="trick-number-label">
                  第 {game.play?.trickNumber} / 13 回合
                </span>
              )}
              {game.privilege.status === "REVEALED" &&
                game.privilege.usedByTeam && (
                  <div
                    className="privilege-status privilege-labelled"
                    aria-label="公開特權結果"
                  >
                    <strong className="privilege-title">
                      <PrivilegeBadge
                        active={Object.values(game.privilege.usedByTeam).some(
                          Boolean,
                        )}
                      />
                      特權
                    </strong>
                    <div className="privilege-teams">
                      {(["NS", "EW"] as const).map((team) => (
                        <span
                          key={team}
                          className={
                            game.privilege.usedByTeam![team]
                              ? "privilege-on"
                              : "privilege-off"
                          }
                        >
                          {team === ownTeam ? "我方" : "對方"}：
                          <b>
                            {game.privilege.usedByTeam![team]
                              ? "已使用"
                              : "未使用"}
                          </b>
                        </span>
                      ))}
                    </div>
                    <IconTip
                      icon={<span aria-hidden="true">?</span>}
                      label="特權效果說明"
                    >
                      <span>
                        使用特權：本局獲勝額外 +1 分，落敗扣 1
                        分。兩隊各自計算，未使用者不加減。詳細計分見本局結算。
                      </span>
                    </IconTip>
                  </div>
                )}
            </div>
            <div className="table-turn-feedback">
              {collecting ? (
                <div className="capture-feedback">
                  <h3 className="capture-heading" role="status">
                    <span className="table-action-label">
                      {`★ ${playerLabel(game.roster, completedTrick?.winnerSeat)}贏得本回合`}
                    </span>
                    <small>
                      第 {game.play?.trickNumber} / 13 回合
                      {winner?.teamId === ownTeam ? " · 我方 +1" : " · 對方 +1"}
                    </small>
                  </h3>
                  <p className="collecting-status">
                    {game.paused
                      ? "暫停回合結算 · 等候重連"
                      : "四張牌已出齊 · 稍後繼續"}
                  </p>
                </div>
              ) : (
                <OperationClock
                  timer={game.operationTimer}
                  seconds={seconds}
                  roster={room.players}
                />
              )}
            </div>
          </section>
          <section
            className={`play-surface match-surface phase-${room.phase.toLowerCase()}`}
            aria-label="四人牌桌"
          >
            <TableSeats
              room={room}
              turnSeat={turnSeat}
              winnerSeat={completedTrick?.winnerSeat}
            />
            <div className="table-focus">
              <div className="table-event">
                {(room.phase === "PLAYING" || collecting) && game.play ? (
                  <>
                    <PublicTrick
                      plays={game.play.currentTrick}
                      roster={game.roster}
                      ownSeat={room.you?.seat ?? 0}
                      winnerSeat={completedTrick?.winnerSeat}
                      effects={trumpEffects}
                    />
                  </>
                ) : room.phase === "BIDDING" ? (
                  <CurrentBid game={game} />
                ) : (
                  <h3 className="table-stage-label">
                    {room.phase === "REDEAL_DECISION"
                      ? "倒牌確認"
                      : room.phase === "PRIVILEGE_DECISION"
                        ? "特權選擇"
                        : myTurn
                          ? "請在下方叫牌"
                          : "叫牌中"}
                  </h3>
                )}
              </div>
            </div>
            <TrumpEffects events={trumpEffects} ownSeat={room.you?.seat ?? 0} />
          </section>
          {((room.phase === "BIDDING" && myTurn) ||
            room.phase === "PRIVILEGE_DECISION" ||
            room.phase === "REDEAL_DECISION") && (
            <div className="table-action-dock" aria-label="本回合操作區">
              {room.phase === "REDEAL_DECISION" && (
                <RedealControls
                  key={game.dealId}
                  decision={game.redeal}
                  locked={locked}
                />
              )}
              {room.phase === "BIDDING" && myTurn && (
                <div className="phase-main-controls">
                  <BidControls
                    key={`${game.dealId}:${game.auction.calls.length}`}
                    legalCalls={room.you?.legalCalls ?? []}
                    locked={locked || !myTurn}
                  />
                </div>
              )}
              {room.phase === "PRIVILEGE_DECISION" && (
                <section
                  className="phase-main-controls privilege-actions"
                  aria-label="特權選擇操作"
                >
                  {game.privilege.yourChoice === null &&
                  game.privilege.canChoose ? (
                    <div className="choice-buttons">
                      <button
                        className="primary"
                        disabled={locked}
                        aria-label="使用特權"
                        onClick={() => void client.privilege(true)}
                      >
                        <PrivilegeBadge active /> 使用特權
                      </button>
                      <button
                        className="secondary"
                        disabled={locked}
                        aria-label="不使用特權"
                        onClick={() => void client.privilege(false)}
                      >
                        <PrivilegeBadge /> 不使用特權
                      </button>
                    </div>
                  ) : (
                    <p className="selection-confirmed">
                      {game.privilege.yourChoice === null
                        ? game.paused
                          ? "連線恢復前暫停選擇。"
                          : "你的隊伍已完成決定，無須再選。"
                        : `你已選擇：${game.privilege.yourChoice ? "使用特權" : "不使用特權"}。`}{" "}
                      等候兩隊決定。
                    </p>
                  )}
                  <IconTip icon="?" label="特權選擇說明">
                    <span>
                      每人選一次；同隊任一人使用即成立。隊友及對手的選擇暫不公開，兩隊完成後一起揭曉；逾時不使用。特權會影響本局結算。
                    </span>
                  </IconTip>
                </section>
              )}
            </div>
          )}
          <HandPanel
            key={`${game.gameId}:${game.dealId}:${room.phase}:${game.play?.trickNumber}:${game.play?.nextSeat}:${game.paused}:${online}:${locked}`}
            cards={room.you?.ownHand ?? []}
            legalCards={room.you?.legalCards ?? []}
            enabled={!locked && playTurn}
            collecting={collecting}
            playing={room.phase === "PLAYING" || collecting}
          />
        </>
      )}
      <OwnTimeoutNotice room={room} />
      <footer className="match-utilities">
        <button
          className="secondary"
          ref={historyTriggerRef}
          aria-expanded={recordsMode === "current"}
          onClick={() =>
            setRecordsMode(recordsMode === "current" ? null : "current")
          }
        >
          ▤ 本局紀錄
        </button>
        <button
          className="secondary"
          ref={settlementTriggerRef}
          aria-expanded={recordsMode === "settlements"}
          onClick={() =>
            setRecordsMode(recordsMode === "settlements" ? null : "settlements")
          }
        >
          ▥ 各局結算
        </button>
        {onHelp && (
          <button className="secondary" onClick={onHelp}>
            說明
          </button>
        )}
        <details
          className="match-more"
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.currentTarget.open = false;
              event.currentTarget.querySelector("summary")?.focus();
            }
          }}
        >
          <summary>更多</summary>
          <div className="match-more-panel">
            <small>v0.13.2 · 狀態版本 {room.revision}</small>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => void client.synchronize()}
            >
              重新同步
            </button>
            <button
              className="leave"
              ref={exitTriggerRef}
              disabled={busy || !!pendingAction}
              onClick={() => (committedOutcome ? exit() : setConfirmExit(true))}
            >
              {committedOutcome ? "離開房間" : "退出遊戲"}
            </button>
          </div>
        </details>
      </footer>
      {historyOpen && (
        <div className="dialog-backdrop history-backdrop">
          <section
            className="history-drawer panel"
            role="dialog"
            aria-modal="true"
            ref={recordsRef}
            aria-label={recordsMode === "current" ? "本局紀錄" : "各局結算"}
            onKeyDown={(e) => {
              if (e.key === "Escape") closeHistory();
              if (e.key === "Tab") {
                const targets = [
                  ...e.currentTarget.querySelectorAll<HTMLElement>(
                    'button:not(:disabled), summary, select:not(:disabled), a[href], [tabindex="0"]',
                  ),
                ];
                const first = targets[0],
                  last = targets.at(-1);
                if (e.shiftKey && document.activeElement === first) {
                  e.preventDefault();
                  last?.focus();
                } else if (!e.shiftKey && document.activeElement === last) {
                  e.preventDefault();
                  first?.focus();
                }
              }
            }}
          >
            <div className="section-heading">
              <h2>
                {recordsMode === "current" ? "出牌與叫牌紀錄" : "各局結算"}
              </h2>
              <button
                ref={historyCloseRef}
                className="secondary"
                onClick={closeHistory}
              >
                關閉
              </button>
            </div>
            <p className="fine">查看紀錄不暫停倒數，請留意牌桌輪次。</p>
            {recordsMode === "current" ? (
              <CurrentDealRecords
                key={game.dealId}
                game={game}
                ownTeam={ownTeam}
              />
            ) : (
              <SettlementRecords game={game} />
            )}
          </section>
        </div>
      )}
      {confirmExit && (
        <div className="dialog-backdrop">
          <section
            className="exit-dialog panel"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="exit-title"
            aria-describedby="exit-description"
            onKeyDown={(event) => {
              if (event.key === "Escape") dismissExit();
              if (event.key === "Tab") {
                event.preventDefault();
                const buttons = event.currentTarget.querySelectorAll("button");
                (document.activeElement === buttons[0]
                  ? buttons[1]
                  : buttons[0]
                )?.focus();
              }
            }}
          >
            <h2 id="exit-title">
              {room.roomType === "FORMAL"
                ? "確定退出正式場？"
                : "確定退出並交由電腦接手？"}
            </h2>
            <p id="exit-description">
              {room.roomType === "FORMAL"
                ? "主動退出會放棄本場席位，依規則判我方落敗，不會由電腦接手。雙方皆失敗可能不計勝負；已完成局保留，未完成局不計分。"
                : "主動退出會放棄本場席位，無法取回控制權；若仍有真人在線，電腦會立即接手。最後一位真人退出時，本場結束，未完成的局不計分。"}
            </p>
            <div className="choice-buttons">
              <button
                ref={cancelRef}
                className="secondary"
                onClick={dismissExit}
              >
                繼續遊戲
              </button>
              <button className="danger" disabled={busy} onClick={exit}>
                確認退出
              </button>
            </div>
          </section>
        </div>
      )}
    </section>
  );
}
