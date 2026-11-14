import LeaderboardPanel from "./LeaderboardPanel";
import PersonalStatisticsSummary from "./PersonalStatisticsSummary";
import { taiwanSettlementTime } from "./statistics-format";
import { useEffect, useState } from "react";
import { accountRequest, type AuthStatus } from "./google-login";
import {
  statisticsRetrySeconds,
  statisticsRemainingSeconds,
  type StatisticsResponse,
} from "./statistics";
export function StatisticsContent({
  data,
  tab,
  category = "formal",
  period = "quarter",
}: {
  data: StatisticsResponse;
  tab: "personal" | "overall";
  category?: "formal" | "casual";
  period?: "quarter" | "lifetime";
}) {
  if (data.status === "DISABLED") return <p role="status">統計尚未開放。</p>;
  if (data.status !== "AVAILABLE") {
    const message =
      data.status === "UNAVAILABLE" && data.unavailableReason === "BUSY"
        ? "統計正在讀取中，請稍後再試。"
        : data.status === "UNAVAILABLE" &&
            data.unavailableReason === "RATE_LIMITED"
          ? "統計服務目前忙碌，請稍候再試。不會自動重試。"
          : "目前無法取得統計，請稍後重試。";
    return <p role="alert">{message}</p>;
  }
  const lifetime = tab === "personal" && period === "lifetime";
  const personal = lifetime
    ? category === "formal"
      ? data.lifetimePersonal
      : data.lifetimeCasualPersonal
    : category === "formal"
      ? data.personal
      : data.casualPersonal;
  const cutoff = taiwanSettlementTime(data.quarterEndsAt);
  const counts =
    tab === "personal"
      ? personal
      : category === "formal"
        ? data.overall
        : data.casualOverall;
  const label = category === "formal" ? "正式場" : "休閒場";
  if (!counts)
    return (
      <p role="alert">
        {lifetime
          ? "生涯統計目前無法取得，請稍後重試；不會以本季數字代替。"
          : "統計資料不完整，請稍後重試。"}
      </p>
    );
  return (
    <div
      className="statistics-data"
      aria-label={`${label} · ${tab === "personal" ? "個人" : "整體"}`}
    >
      {lifetime ? (
        <p className="statistics-range">
          生涯累計
          {data.lifetimeTrackedFromQuarter
            ? ` · 自 ${data.lifetimeTrackedFromQuarter} 起`
            : ""}
        </p>
      ) : (
        <div className="statistics-range">
          <p>{data.quarter} · 台灣時間</p>
          {cutoff && <p>結算至 {cutoff}</p>}
        </div>
      )}
      {counts.matches === 0 && (
        <p role="status">
          {lifetime ? "生涯" : "本季"}尚無已確認保存的{label}。
        </p>
      )}
      {tab === "personal" && personal ? (
        <PersonalStatisticsSummary counts={personal} />
      ) : (
        <dl className="statistics-counts">
          <div>
            <dt>總場次</dt>
            <dd>{counts.matches}</dd>
          </div>
          <div>
            <dt>完成場次</dt>
            <dd>{counts.scoredMatches ?? "—"}</dd>
          </div>
          <div>
            <dt>中途離席</dt>
            <dd>{counts.forfeitMatches ?? "—"}</dd>
          </div>
          <div>
            <dt>無勝負場次</dt>
            <dd>{counts.interruptedMatches ?? "—"}</dd>
          </div>
          <div>
            <dt>完成局數</dt>
            <dd>{counts.completedDeals}</dd>
          </div>
          <div>
            <dt>平手場次</dt>
            <dd>{counts.draws}</dd>
          </div>
        </dl>
      )}
      <p className="fine">
        更新於{" "}
        {new Date(data.asOf).toLocaleString("zh-TW", {
          timeZone: "Asia/Taipei",
          hour12: false,
        })}
        （台灣時間）
      </p>
      <p className="fine">
        僅計已保存戰績。<a href="#help">統計與資料說明</a>
      </p>
    </div>
  );
}
export default function StatisticsPanel({
  token,
  onBack,
  initialTab = "personal",
}: {
  token: string;
  initialTab?: "personal" | "leaderboard";
  onBack: () => void;
}) {
  const [tab, setTab] = useState<"personal" | "overall" | "leaderboard">(
    initialTab,
  );
  const [category, setCategory] = useState<"formal" | "casual">("formal");
  const [period, setPeriod] = useState<"quarter" | "lifetime">("quarter");
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{
    token: string;
    data?: StatisticsResponse;
    retryAt?: number;
    error?: string;
  } | null>(null);
  const [clockNow, setClockNow] = useState(() => Date.now());
  const statisticsActive = tab !== "leaderboard";
  const current = result?.token === token ? result : null;
  const retryAt = current?.retryAt ?? 0;
  const remaining = statisticsRemainingSeconds(retryAt, clockNow);
  useEffect(() => {
    if (!retryAt || retryAt <= Date.now()) return;
    const timer = window.setInterval(() => {
      const now = Date.now();
      setClockNow(now);
      if (now >= retryAt) window.clearInterval(timer);
    }, 250);
    return () => window.clearInterval(timer);
  }, [retryAt]);
  useEffect(() => {
    if (!statisticsActive) return;
    let live = true;
    setResult(null);
    void (async () => {
      const auth = await accountRequest<AuthStatus>(
        "/auth/status",
        "GET",
        undefined,
        token,
      );
      if (auth.signedIn !== true) throw new Error("請先登入，再查看統計。");
      const data = await accountRequest<StatisticsResponse>(
        "/statistics",
        "GET",
        undefined,
        token,
      );
      if (live) {
        const now = Date.now();
        setClockNow(now);
        setResult({
          token,
          data,
          retryAt: now + statisticsRetrySeconds(data) * 1000,
        });
      }
    })().catch(() => {
      if (live)
        setResult({
          token,
          error: "目前無法取得統計或確認登入狀態。請重試，或回大廳重新登入。",
        });
    });
    return () => {
      live = false;
    };
  }, [token, attempt, statisticsActive]);
  return (
    <section className="panel statistics-panel" aria-label="戰績統計">
      <h2>戰績統計</h2>
      <StatisticsControls
        tab={tab}
        category={category}
        period={period}
        onTab={setTab}
        onCategory={setCategory}
        onPeriod={setPeriod}
      />
      {tab === "leaderboard" ? (
        <LeaderboardPanel token={token} />
      ) : (
        <>
          <div
            className="statistics-results"
            aria-live="polite"
            aria-busy={!current}
          >
            <div className="statistics-results-heading">
              <h3>
                {tab === "personal"
                  ? `我的${period === "quarter" ? "本季" : "生涯"}戰績`
                  : "本季整體概況"}
              </h3>
              <span className="statistics-category-label">
                {category === "formal" ? "正式場" : "休閒場"}
              </span>
            </div>
            {!current ? (
              <p role="status">正在讀取戰績統計…</p>
            ) : current.error ? (
              <p role="alert">{current.error}</p>
            ) : (
              current.data && (
                <StatisticsContent
                  data={current.data}
                  tab={tab}
                  category={category}
                  period={period}
                />
              )
            )}
          </div>
        </>
      )}
      <div className="statistics-actions">
        {tab !== "leaderboard" && (
          <button
            className="secondary"
            disabled={!current || remaining > 0}
            onClick={() => {
              if (
                !current ||
                statisticsRemainingSeconds(retryAt, Date.now()) > 0
              )
                return;
              setResult(null);
              setAttempt((value) => value + 1);
            }}
          >
            {remaining > 0 ? `${remaining} 秒後可重試` : "重新讀取統計"}
          </button>
        )}
        <button className="text-button" onClick={onBack}>
          回到大廳
        </button>
      </div>
    </section>
  );
}

type StatisticsControlsProps = {
  tab: "personal" | "overall" | "leaderboard";
  category: "formal" | "casual";
  period: "quarter" | "lifetime";
  onTab: (value: "personal" | "overall" | "leaderboard") => void;
  onCategory: (value: "formal" | "casual") => void;
  onPeriod: (value: "quarter" | "lifetime") => void;
};

export function StatisticsControls({
  tab,
  category,
  period,
  onTab,
  onCategory,
  onPeriod,
}: StatisticsControlsProps) {
  return (
    <div className="statistics-controls">
      <div className="statistics-audience" role="group" aria-label="統計範圍">
        {(["personal", "overall", "leaderboard"] as const).map((value) => (
          <button
            type="button"
            key={value}
            aria-pressed={tab === value}
            onClick={() => onTab(value)}
          >
            {value === "personal"
              ? "我的戰績"
              : value === "overall"
                ? "整體概況"
                : "排行榜"}
          </button>
        ))}
      </div>
      {tab !== "leaderboard" && (
        <div className="statistics-filters">
          <div className="statistics-filter">
            <span
              id="statistics-category-caption"
              className="statistics-filter-caption"
            >
              場次
            </span>
            <div
              className="statistics-segment"
              role="group"
              aria-labelledby="statistics-category-caption"
            >
              {(["formal", "casual"] as const).map((value) => (
                <button
                  type="button"
                  key={value}
                  aria-pressed={category === value}
                  onClick={() => onCategory(value)}
                >
                  {value === "formal" ? "正式場" : "休閒場"}
                </button>
              ))}
            </div>
          </div>
          {tab === "personal" && (
            <div className="statistics-filter">
              <span
                id="statistics-period-caption"
                className="statistics-filter-caption"
              >
                期間
              </span>
              <div
                className="statistics-segment"
                role="group"
                aria-labelledby="statistics-period-caption"
              >
                {(["quarter", "lifetime"] as const).map((value) => (
                  <button
                    type="button"
                    key={value}
                    aria-pressed={period === value}
                    onClick={() => onPeriod(value)}
                  >
                    {value === "quarter" ? "本季" : "生涯"}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
