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
        <summary>場次明細與計算方式</summary>
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
        <ul className="fine statistics-explanation">
          <li>勝率＝勝場 ÷（勝場＋敗場＋平手），含判負、不含中止。</li>
          <li>
            總場次＝完成場次＋中途離席＋無勝負場次；未確認保存的結果不計。
          </li>
          <li>
            中途離席指因離席判負的場次，並非本人離席次數；包含隊友或對手離席造成的判負。無勝負場次不計勝敗。
          </li>
          <li>
            積分為各場我方已完成局的結算分數總和；未完成局不計，判負仍可能有正積分。
          </li>
        </ul>
      </details>
    </>
  );
}
