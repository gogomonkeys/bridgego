import { describe, expect, it } from "vitest";
import { renderToStaticMarkup as render } from "react-dom/server";
import PersonalStatisticsSummary from "./PersonalStatisticsSummary";
import type { PersonalStatistics } from "./statistics";
const counts: PersonalStatistics = {
  matches: 5,
  scoredMatches: 3,
  forfeitMatches: 1,
  interruptedMatches: 1,
  completedDeals: 7,
  wins: 2,
  losses: 1,
  draws: 1,
  totalScore: -3,
};
describe("compact personal statistics", () => {
  it("shows four requested primary metrics, with explicit denominator and signed score", () => {
    const html = render(<PersonalStatisticsSummary counts={counts} />);
    for (const pair of [
      "<dt>勝率</dt><dd>50.0%</dd>",
      "<dt>場數</dt><dd>5</dd>",
      "<dt>勝場</dt><dd>2</dd>",
      "<dt>積分</dt><dd>-3</dd>",
    ])
      expect(html).toContain(pair);
    expect(html).toContain("勝場＋敗場＋平手");
    expect(html).toContain("不含中止");
  });
  it("keeps secondary outcome breakdown collapsed initially", () => {
    const html = render(<PersonalStatisticsSummary counts={counts} />);
    expect(html).toContain('<details class="statistics-breakdown">');
    expect(html).not.toContain("<details open");
    expect(html).toContain("<dt>中止（不計勝負）</dt><dd>1</dd>");
  });
});
