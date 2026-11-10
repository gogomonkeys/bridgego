import PersonalStatisticsSummary from "./PersonalStatisticsSummary";
import { taiwanSettlementTime } from "./statistics-format";
import { useEffect, useState } from "react";
import { accountRequest, type AuthStatus } from "./google-login";
import type { StatisticsResponse } from "./statistics";
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
  if (data.status !== "AVAILABLE")
    return <p role="alert">目前無法取得統計，請稍後重試。</p>;
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
    <>
      <h3>
        {label} · {tab === "personal" ? "個人" : "整體"}
      </h3>
      {lifetime ? (
        <div>
          <p>生涯累計 · 正式／休閒分開計算</p>
          <p className="fine">
            {data.lifetimeTrackedFromQuarter
              ? `已記錄範圍自 ${data.lifetimeTrackedFromQuarter} 起。`
              : "尚無可確認的生涯起始季度。"}
            僅含新版已同意保存的紀錄，不補算舊未同意或已清除資料。
          </p>
        </div>
      ) : (
        <>
          <p>台灣時間本季 · {data.quarter}</p>
          {cutoff && (
            <p className="statistics-cutoff">結算至 {cutoff}（台灣時間）</p>
          )}
          <p className="fine">以上為下一季切換時間，並非自動清理時間。</p>
        </>
      )}
      <p className="fine">
        資料截至{" "}
        {new Date(data.asOf).toLocaleString("zh-TW", {
          timeZone: "Asia/Taipei",
          hour12: false,
        })}
        （台灣時間）
      </p>
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
            <dt>已定案場次</dt>
            <dd>{counts.matches}</dd>
          </div>
          <div>
            <dt>正常完成</dt>
            <dd>{counts.scoredMatches ?? "—"}</dd>
          </div>
          <div>
            <dt>判負場次</dt>
            <dd>{counts.forfeitMatches ?? "—"}</dd>
          </div>
          <div>
            <dt>中止（不計勝負）</dt>
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
        僅計入已確認保存的結果；正式場與休閒場分開計算。訪客只計入整體，沒有個人統計。保存失敗或結果未確認的場次不計入，詳細規則見使用說明。
      </p>
    </>
  );
}
export default function StatisticsPanel({
  token,
  onBack,
}: {
  token: string;
  onBack: () => void;
}) {
  const [tab, setTab] = useState<"personal" | "overall">("personal");
  const [category, setCategory] = useState<"formal" | "casual">("formal");
  const [period, setPeriod] = useState<"quarter" | "lifetime">("quarter");
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{
    token: string;
    data?: StatisticsResponse;
    error?: string;
  } | null>(null);
  useEffect(() => {
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
      if (live) setResult({ token, data });
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
  }, [token, attempt]);
  const current = result?.token === token ? result : null;
  return (
    <section className="panel statistics-panel" aria-label="戰績統計">
      <h2>戰績統計</h2>
      <div className="statistics-tabs" role="group" aria-label="統計範圍">
        <button
          className={tab === "personal" ? "primary" : "secondary"}
          aria-pressed={tab === "personal"}
          onClick={() => setTab("personal")}
        >
          個人
        </button>
        <button
          className={tab === "overall" ? "primary" : "secondary"}
          aria-pressed={tab === "overall"}
          onClick={() => setTab("overall")}
        >
          整體
        </button>
      </div>
      <div className="statistics-tabs" role="group" aria-label="場次類型">
        {(["formal", "casual"] as const).map((value) => (
          <button
            key={value}
            className={category === value ? "primary" : "secondary"}
            aria-pressed={category === value}
            onClick={() => setCategory(value)}
          >
            {value === "formal" ? "正式場" : "休閒場"}
          </button>
        ))}
      </div>
      {tab === "personal" && (
        <div
          className="statistics-tabs statistics-period"
          role="group"
          aria-label="個人統計期間"
        >
          {(["quarter", "lifetime"] as const).map((value) => (
            <button
              key={value}
              className={period === value ? "primary" : "secondary"}
              aria-pressed={period === value}
              onClick={() => setPeriod(value)}
            >
              {value === "quarter" ? "本季" : "生涯"}
            </button>
          ))}
        </div>
      )}
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
      <button
        className="secondary"
        disabled={!current}
        onClick={() => {
          setResult(null);
          setAttempt((value) => value + 1);
        }}
      >
        重新讀取統計
      </button>{" "}
      <button className="text-button" onClick={onBack}>
        回到大廳
      </button>
    </section>
  );
}
