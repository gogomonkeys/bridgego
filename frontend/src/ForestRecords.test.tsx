import { describe, expect, it } from "vitest";
import { renderToStaticMarkup as render } from "react-dom/server";
import { readFileSync } from "node:fs";
import StatisticsPanel from "./StatisticsPanel";
import LeaderboardPanel from "./LeaderboardPanel";

describe("forest record presentation", () => {
  it("uses a decorative forest companion without replacing statistics context", () => {
    const html = render(<StatisticsPanel token="fixture" onBack={() => {}} />);
    expect(html).toContain("animal-bear.webp");
    expect(html).toContain('alt="" aria-hidden="true"');
    expect(html).toContain("戰績統計");
    expect(html).toContain("我的本季戰績");
    expect(html).toContain("每一局，都是新的成長。");
  });
  it("keeps leaderboard context and both actions with its decorative fox", () => {
    const html = render(<LeaderboardPanel token="fixture" onBack={() => {}} />);
    expect(html).toContain("animal-fox.webp");
    expect(html).toContain("本季排行榜");
    expect(html).toContain("正式場");
    expect(html.match(/回到大廳/g)).toHaveLength(1);
    expect(html).toContain("重新讀取排行榜");
  });
  it("scopes the visual treatment and keeps wrapping actions and visible focus", () => {
    const css = readFileSync(new URL("./forest-records.css", import.meta.url), "utf8");
    expect(css).toContain(".panel.statistics-panel");
    expect(css).toContain(".statistics-panel .statistics-actions");
    expect(css).toContain("flex-wrap: wrap");
    expect(css).toContain("min-height: 44px");
    expect(css).toContain("button:focus-visible");
  });
});
