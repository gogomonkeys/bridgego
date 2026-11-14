import { useEffect, useRef, useState } from "react";
import { personalWinRate, taiwanSettlementTime } from "./statistics-format";
import { statisticsRemainingSeconds } from "./statistics";
import {
  createLeaderboardReader,
  leaderboardRetrySeconds,
  readLeaderboard,
  type LeaderboardPeriod,
  type LeaderboardReport,
} from "./leaderboard";

export function LeaderboardContent({ data }: { data: LeaderboardReport }) {
  if (data.status === "DISABLED") return <p role="status">排行榜尚未開放。</p>;
  if (data.status !== "AVAILABLE")
    return (
      <p role="alert">
        {data.unavailableReason === "RATE_LIMITED"
          ? "排行榜服務目前忙碌，請稍候再試。不會自動重試。"
          : data.unavailableReason === "BUSY"
            ? "排行榜正在讀取中，請稍後再試。"
            : "目前無法取得排行榜，請稍後重試。"}
      </p>
    );
  const cutoff = taiwanSettlementTime(data.quarterEndsAt);
  return (
    <div className="leaderboard-data">
      <div className="statistics-range">
        <p>
          {data.period === "SEASON" ? `${data.quarter} · 台灣時間` : "生涯累計"}
        </p>
        {data.period === "SEASON" && cutoff && <p>本季統計結算至 {cutoff}</p>}
      </div>
      {data.entries.length === 0 ? (
        <p role="status">目前尚無可排名的已保存戰績。</p>
      ) : (
        <ol className="leaderboard-list" aria-label="積分排名">
          {data.entries.map((entry, index) => (
            <li
              key={`${entry.rank}-${index}`}
              className={
                entry.mine ? "leaderboard-row is-me" : "leaderboard-row"
              }
            >
              <span
                className="leaderboard-rank"
                aria-label={`第 ${entry.rank} 名`}
              >
                {entry.rank}
              </span>
              <div className="leaderboard-player">
                <strong>{entry.nickname}</strong>
                {entry.mine && <span className="leaderboard-me">你</span>}
                <span className="leaderboard-detail">
                  {entry.matches} 場 · 勝率 {personalWinRate(entry)}
                </span>
              </div>
              <div className="leaderboard-score">
                <strong>{entry.totalScore}</strong>
                <span>積分</span>
              </div>
            </li>
          ))}
        </ol>
      )}
      <p className="fine">
        顯示暱稱與正式場戰績。詳細見<a href="#help">使用說明</a>
      </p>
      <p className="fine">
        更新於{" "}
        {new Date(data.asOf).toLocaleString("zh-TW", {
          timeZone: "Asia/Taipei",
          hour12: false,
        })}
        （台灣時間）
      </p>
    </div>
  );
}

export default function LeaderboardPanel({ token }: { token: string }) {
  const [period, setPeriod] = useState<LeaderboardPeriod>("SEASON");
  const category = "FORMAL";
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{
    key: string;
    data?: LeaderboardReport;
    error?: boolean;
  } | null>(null);
  const [retryAt, setRetryAt] = useState(0);
  const [now, setNow] = useState(Date.now);
  const flight = useRef(false);
  const key = `${token}:${period}:${category}:${attempt}`;
  const current = result?.key === key ? result : null;
  const remaining = statisticsRemainingSeconds(retryAt, now);
  useEffect(() => {
    if (!retryAt || retryAt <= Date.now()) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [retryAt]);
  useEffect(() => {
    flight.current = true;
    const reader = createLeaderboardReader(
      () => readLeaderboard(token, period, category),
      (data) => {
        setResult({ key, data });
        setNow(Date.now());
        setRetryAt(Date.now() + leaderboardRetrySeconds(data) * 1000);
        flight.current = false;
      },
      () => {
        setResult({ key, error: true });
        flight.current = false;
      },
    );
    void reader.load();
    return () => reader.dispose();
  }, [key, token, period, category]);
  const locked = !current || remaining > 0;
  return (
    <div className="leaderboard-panel">
      <div className="statistics-filters">
        <div className="statistics-filter">
          <span
            id="leaderboard-period-caption"
            className="statistics-filter-caption"
          >
            期間
          </span>
          <div
            className="statistics-segment"
            role="group"
            aria-labelledby="leaderboard-period-caption"
          >
            {(["SEASON", "LIFETIME"] as const).map((value) => (
              <button
                type="button"
                key={value}
                disabled={locked}
                aria-pressed={period === value}
                onClick={() => setPeriod(value)}
              >
                {value === "SEASON" ? "本季" : "生涯"}
              </button>
            ))}
          </div>
        </div>
      </div>
      <div
        className="statistics-results"
        aria-live="polite"
        aria-busy={!current}
      >
        <div className="statistics-results-heading">
          <h3>{period === "SEASON" ? "本季" : "生涯"}排行榜</h3>
          <span className="statistics-category-label">正式場</span>
        </div>
        {!current ? (
          <p role="status">正在讀取排行榜…</p>
        ) : current.error ? (
          <p role="alert">目前無法取得排行榜或確認登入狀態，請稍後重試。</p>
        ) : (
          current.data && <LeaderboardContent data={current.data} />
        )}
      </div>
      <button
        type="button"
        className="secondary leaderboard-reload"
        disabled={locked}
        onClick={() => {
          if (
            flight.current ||
            !current ||
            statisticsRemainingSeconds(retryAt, Date.now()) > 0
          )
            return;
          flight.current = true;
          setAttempt((value) => value + 1);
        }}
      >
        {remaining > 0 ? `${remaining} 秒後可重試` : "重新讀取排行榜"}
      </button>
    </div>
  );
}
