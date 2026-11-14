import type { PersonalStatistics } from "./statistics";
import { personalWinRate } from "./statistics-format";

export default function PersonalStatisticsSummary({
  counts,
}: {
  counts: PersonalStatistics;
}) {
  return (
    <>
      <dl className="statistics-counts personal-statistics-highlights">
        <div>
          <dt>勝率</dt>
          <dd>{personalWinRate(counts)}</dd>
        </div>
        <div>
          <dt>總場次</dt>
          <dd>{counts.matches}</dd>
        </div>
        <div>
          <dt>勝場</dt>
          <dd>{counts.wins}</dd>
        </div>
        <div>
          <dt>積分</dt>
          <dd>{counts.totalScore}</dd>
        </div>
      </dl>
      <p className="fine">勝率不含中止場；積分僅累計已完成局。</p>
      <details className="statistics-breakdown">
        <summary>場次明細</summary>
        <dl className="statistics-counts">
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
            <dt>落敗場次</dt>
            <dd>{counts.losses}</dd>
          </div>
          <div>
            <dt>平手場次</dt>
            <dd>{counts.draws}</dd>
          </div>
          <div>
            <dt>完成局數</dt>
            <dd>{counts.completedDeals}</dd>
          </div>
        </dl>
        <p className="fine">
          <a href="#help">計算說明</a>
        </p>
      </details>
    </>
  );
}
