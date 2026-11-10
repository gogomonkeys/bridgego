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
          <dt>場數</dt>
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
      <p className="fine">
        勝率＝勝場
        ÷（勝場＋敗場＋平手），含判負、不含中止。場數包含所有已定案場次；積分為各場我方結算分數的累計，未完成局不計分；判負仍可能保有正積分。
      </p>
      <details className="statistics-breakdown">
        <summary>場次明細</summary>
        <dl className="statistics-counts">
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
      </details>
    </>
  );
}
