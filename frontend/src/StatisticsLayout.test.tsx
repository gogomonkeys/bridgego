import { describe, expect, it } from "vitest";
import { renderToStaticMarkup as render } from "react-dom/server";
import { readFileSync } from "node:fs";
import StatisticsPanel, { StatisticsControls } from "./StatisticsPanel";
const noop = () => {};
describe("compact linked statistics presentation", () => {
  it.each(["formal", "casual"] as const)(
    "keeps %s selection separate and equal-style without primary sizing",
    (category) => {
      const html = render(
        <StatisticsControls
          tab="personal"
          category={category}
          period="lifetime"
          onTab={noop}
          onCategory={noop}
          onPeriod={noop}
        />,
      );
      expect(html).toContain("我的戰績");
      expect(html).toContain("整體概況");
      expect(html).toContain("場次");
      expect(html).toContain("期間");
      expect(html).toContain(
        `aria-pressed="true">${category === "formal" ? "正式場" : "休閒場"}</button>`,
      );
      expect(html).toContain('aria-pressed="true">生涯</button>');
      expect(html).not.toContain('class="primary"');
      expect(html).not.toContain('class="secondary"');
    },
  );
  it("offers no lifetime switch for overall statistics", () => {
    const html = render(
      <StatisticsControls
        tab="overall"
        category="casual"
        period="lifetime"
        onTab={noop}
        onCategory={noop}
        onPeriod={noop}
      />,
    );
    expect(html).not.toContain("生涯");
    expect(html).not.toContain("statistics-period-caption");
    expect(html).toContain('aria-pressed="true">整體概況</button>');
  });
  it("shows the selected data context even while loading", () => {
    const html = render(<StatisticsPanel token="test" onBack={noop} />);
    expect(html).toContain("我的本季戰績");
    expect(html).toContain('class="statistics-category-label">正式場');
    expect(html).toContain('aria-busy="true"');
    expect(html).toContain("正在讀取戰績統計");
  });
  it("defines fixed segmented dimensions and a two-column mobile metric grid", () => {
    const css = readFileSync(new URL("./style.css", import.meta.url), "utf8");
    expect(css).toContain(".statistics-panel .statistics-segment button");
    expect(css).toContain("grid-template-columns: repeat(2,minmax(0,1fr))");
    expect(css).toContain(".statistics-page > .help-version");
  });
});
