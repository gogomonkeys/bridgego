import { useEffect, useState } from "react";
import { accountRequest, type AuthStatus } from "./google-login";
import type { StatisticsResponse } from "./statistics";
export function StatisticsContent({
  data,
  tab,
}: {
  data: StatisticsResponse;
  tab: "personal" | "overall";
}) {
  if (data.status === "DISABLED") return <p role="status">統計尚未開放。</p>;
  if (data.status !== "AVAILABLE")
    return <p role="alert">目前無法取得統計，請稍後重試。</p>;
  const counts = data[tab];
  if (!counts) return <p role="alert">統計資料不完整，請稍後重試。</p>;
  return (
    <>
      <p>台灣時間本季 · {data.quarter}</p>
      <p className="fine">
        資料截至{" "}
        {new Date(data.asOf).toLocaleString("zh-TW", {
          timeZone: "Asia/Taipei",
          hour12: false,
        })}
        （台灣時間）
      </p>
      {counts.matches === 0 && (
        <p role="status">本季尚無已確認保存的合資格場次。</p>
      )}
      <dl className="statistics-counts">
        <div>
          <dt>完成場次</dt>
          <dd>{counts.matches}</dd>
        </div>
        <div>
          <dt>完成局數</dt>
          <dd>{counts.completedDeals}</dd>
        </div>
        <div>
          <dt>平手場次</dt>
          <dd>{counts.draws}</dd>
        </div>
        {tab === "personal" && data.personal && (
          <>
            <div>
              <dt>獲勝場次</dt>
              <dd>{data.personal.wins}</dd>
            </div>
            <div>
              <dt>落敗場次</dt>
              <dd>{data.personal.losses}</dd>
            </div>
            <div>
              <dt>我方累計分數</dt>
              <dd>{data.personal.totalScore}</dd>
            </div>
          </>
        )}
      </dl>
      <p className="fine">
        一場可包含 1、3 或 5
        局。僅計入新規則下已確認保存、正常完成計分且結算時為四位不同真人的場次。訪客可計入整體，但不建立個人統計；舊紀錄缺少結算控制者資訊不補算。保存失敗或尚未確認的場次不計入。
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
    <section className="panel statistics-panel" aria-label="本季戰績統計">
      <h2>本季戰績統計</h2>
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
      {!current ? (
        <p role="status">正在讀取本季統計…</p>
      ) : current.error ? (
        <p role="alert">{current.error}</p>
      ) : (
        current.data && <StatisticsContent data={current.data} tab={tab} />
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
