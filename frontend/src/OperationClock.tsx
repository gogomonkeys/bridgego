import type { OperationTimer, Game } from "./model";
import { playerLabel } from "./player-label";
export default function OperationClock({
  timer,
  seconds,
  roster = [],
}: {
  timer: OperationTimer | null;
  seconds: number | null;
  roster?: Game["roster"];
}) {
  if (!timer || seconds === null) return null;
  const label =
    timer.kind === "REDEAL_DECISION"
      ? "倒牌確認"
      : timer.kind === "TRICK_RESULT"
        ? "回合結果展示"
        : timer.kind === "PRIVILEGE"
          ? "特權選擇"
          : timer.kind === "DEAL_RESULT"
            ? "局間確認"
            : `輪到${playerLabel(roster, timer.turnSeat)}${timer.kind === "PLAY_TURN" ? "出牌" : "叫牌"}`;
  return (
    <section
      className={`turn-clock ${timer.paused ? "paused" : ""} ${seconds === 0 ? "timer-zero" : ""}`}
      aria-label="操作倒數"
    >
      <strong title={`${label} · ${timer.paused ? "已暫停" : "倒數中"}`}>
        {label} · {timer.paused ? "已暫停" : "倒數中"}
      </strong>
      <span role="timer" aria-live="off">
        {seconds} <small>秒</small>
      </span>
      {(timer.kind !== "DEAL_RESULT" || timer.paused || seconds === 0) && (
        <p>
          {timer.paused
            ? "保留剩餘操作時間，重連後接續；不重置倒數。"
            : seconds === 0
              ? "等候伺服器更新結果；此頁不會自行送出操作。"
              : timer.kind === "TRICK_RESULT"
                ? "展示四張牌與本回合贏家，下一回合尚未開始。"
                : timer.kind === "REDEAL_DECISION"
                  ? "符合資格者共用倒數；未選者逾時保留手牌。"
                  : timer.kind === "PRIVILEGE"
                    ? "未選者逾時由後端視為不使用。"
                    : timer.kind === "BID_TURN"
                      ? "逾時由後端自動 Pass。"
                      : "逾時由後端自動出最低合法牌。"}
        </p>
      )}
      {timer.kind !== "DEAL_RESULT" && (
        <small>後端統一判定 · 重新整理不重置</small>
      )}
    </section>
  );
}
