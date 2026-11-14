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
      "<dt>總場次</dt><dd>5</dd>",
      "<dt>勝場</dt><dd>2</dd>",
      "<dt>積分</dt><dd>-3</dd>",
    ])
      expect(html).toContain(pair);
    expect(html).toContain("不含中止場");
    expect(html).toContain('href="#help"');
    expect(html).toContain("<dt>完成場次</dt><dd>3</dd>");
    expect(html).toContain("<dt>中途離席</dt><dd>1</dd>");
  });
  it("keeps secondary outcome breakdown collapsed initially", () => {
    const html = render(<PersonalStatisticsSummary counts={counts} />);
    expect(html).toContain('<details class="statistics-breakdown">');
    expect(html).not.toContain("<details open");
    expect(html).toContain("<dt>無勝負場次</dt><dd>1</dd>");
  });
  it("keeps numeric details and links to rules in help", () => {
    const html = render(<PersonalStatisticsSummary counts={counts} />);
    const [visible, details] = html.split(
      '<details class="statistics-breakdown">',
    );
    expect(visible).toContain("勝率不含中止場；積分僅累計已完成局。");
    expect(visible).not.toContain("勝場＋敗場＋平手");
    expect(details).toContain("場次明細");
    expect(details).not.toContain("勝場＋敗場＋平手");
    expect(details).toContain("計算說明");
    expect(details).not.toContain("判負仍可能有正積分");
  });
});
