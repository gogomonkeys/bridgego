import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import LeaderboardPanel, { LeaderboardContent } from "./LeaderboardPanel";
import StatisticsPanel, { StatisticsControls } from "./StatisticsPanel";
import type { LeaderboardReport } from "./leaderboard";
const base: LeaderboardReport = {
  status: "AVAILABLE",
  period: "SEASON",
  category: "FORMAL",
  quarter: "2026-Q4",
  asOf: "2026-11-14T00:00:00Z",
  quarterEndsAt: "2026-12-31T16:00:00Z",
  entries: [
    {
      rank: 1,
      nickname: "<script>name</script>",
      totalScore: -3,
      matches: 4,
      wins: 1,
      losses: 1,
      draws: 0,
      winRate: 0.5,
      mine: true,
    },
    {
      rank: 1,
      nickname: "Another",
      totalScore: -3,
      matches: 2,
      wins: 1,
      losses: 1,
      draws: 0,
      winRate: 0.5,
      mine: false,
    },
  ],
};
describe("leaderboard presentation", () => {
  it("groups reload and return in one spaced action row", () => {
    const html = renderToStaticMarkup(
      <LeaderboardPanel token="test" onBack={() => {}} />,
    );
    const actions = html.slice(
      html.indexOf('class="statistics-actions leaderboard-actions"'),
    );
    expect(actions).toContain("重新讀取排行榜");
    expect(actions).toContain("回到大廳");
    expect(html.match(/回到大廳/g)).toHaveLength(1);
  });
  it("keeps only one return action in embedded leaderboard", () => {
    const html = renderToStaticMarkup(
      <StatisticsPanel
        token="test"
        initialTab="leaderboard"
        onBack={() => {}}
      />,
    );
    expect(html.match(/回到大廳/g)).toHaveLength(1);
    expect(html.match(/statistics-actions/g)).toHaveLength(1);
  });
  it("renders server ranking, score, safe nickname, own marker and consistent win rate", () => {
    const html = renderToStaticMarkup(<LeaderboardContent data={base} />);
    expect(html.match(/第 1 名/g)).toHaveLength(2);
    expect(html).toContain("&lt;script&gt;");
    expect(html).not.toContain("<script>");
    expect(html).toContain("50.0%");
    expect(html).toContain(">你<");
    expect(html).toContain(">-3<");
    expect(html).toContain("2027年01月01日00時");
    expect(html).toContain('href="#help"');
    expect(html).toContain("使用說明");
    expect(html).not.toContain("排名與資料說明");
    expect(html).not.toContain("勝率以勝場除以");
  });
  it("shows the short formal-only disclosure and help link", () => {
    const html = renderToStaticMarkup(<LeaderboardContent data={base} />);
    expect(html).toContain("顯示暱稱與正式場戰績。詳細見");
    expect(html).toContain('href="#help">使用說明</a>');
  });
  it("does not show a season deadline on lifetime", () => {
    expect(
      renderToStaticMarkup(
        <LeaderboardContent data={{ ...base, period: "LIFETIME" }} />,
      ),
    ).not.toContain("本季統計結算至");
  });
  it("distinguishes empty, unavailable, disabled and does not expose stale entries", () => {
    expect(
      renderToStaticMarkup(
        <LeaderboardContent data={{ ...base, entries: [] }} />,
      ),
    ).toContain("尚無可排名");
    const disabled = renderToStaticMarkup(
      <LeaderboardContent data={{ ...base, status: "DISABLED" }} />,
    );
    expect(disabled).toContain("尚未開放");
    expect(disabled).not.toContain("Another");
    const unavailable = renderToStaticMarkup(
      <LeaderboardContent
        data={{
          ...base,
          status: "UNAVAILABLE",
          unavailableReason: "RATE_LIMITED",
        }}
      />,
    );
    expect(unavailable).toContain("不會自動重試");
    expect(unavailable).not.toContain("Another");
  });
  it("adds one leaderboard tab without duplicate filter controls", () => {
    const html = renderToStaticMarkup(
      <StatisticsControls
        tab="leaderboard"
        category="formal"
        period="quarter"
        onTab={() => {}}
        onCategory={() => {}}
        onPeriod={() => {}}
      />,
    );
    expect(html).toContain("排行榜");
    expect(html).not.toContain("statistics-filters");
  });
});

describe("direct lobby leaderboard entry", () => {
  it("opens leaderboard immediately without displaying personal statistics", () => {
    const html = renderToStaticMarkup(
      <StatisticsPanel
        token="test"
        initialTab="leaderboard"
        onBack={() => {}}
      />,
    );
    expect(html).toContain("本季排行榜");
    expect(html).not.toContain("休閒場");
    expect(html).not.toContain("leaderboard-category-caption");
    expect(html).toContain("本季");
    expect(html).toContain("生涯");
    expect(html).toContain("正在讀取排行榜");
    expect(html).not.toContain("我的本季戰績");
  });
  it("keeps the normal statistics entry on personal statistics", () => {
    const html = renderToStaticMarkup(
      <StatisticsPanel token="test" onBack={() => {}} />,
    );
    expect(html).toContain("我的本季戰績");
    expect(html).not.toContain("本季排行榜");
  });
  it("separates session leaderboard access from private authenticated statistics", () => {
    const source = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
    const block = source.slice(
      source.indexOf("{canSeeStatistics && ("),
      source.indexOf("<RoomTypeChoice"),
    );
    expect(block).toContain('navigate("statistics")');
    expect(block).toContain('navigate("leaderboard")');
    expect(source).toContain('page === "statistics" && canSeeStatistics');
    expect(source).toContain('page === "leaderboard" && canSeeLeaderboard');
    expect(source).toContain('initialTab="personal"');
    expect(source).toContain(
      '(route === "statistics" || route === "leaderboard") && state.room',
    );
  });
});
