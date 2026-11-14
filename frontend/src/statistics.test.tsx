import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  acceptStatistics,
  readStatisticsConfig,
  statisticsNotice,
  statisticsRetrySeconds,
  statisticsRemainingSeconds,
  type StatisticsResponse,
} from "./statistics";
import { StatisticsContent } from "./StatisticsPanel";
vi.mock("./client", () => ({
  getClientState: () => ({ session: { token: "current" } }),
}));
afterEach(() => vi.unstubAllGlobals());
const data: StatisticsResponse = {
  status: "AVAILABLE",
  quarter: "2026-Q4",
  asOf: "2026-11-09T12:00:00Z",
  ruleVersion: "fixed-room-type-v3",
  personal: {
    matches: 2,
    completedDeals: 6,
    wins: 1,
    losses: 0,
    draws: 1,
    totalScore: 9,
  },
  overall: { matches: 3, completedDeals: 7, draws: 1 },
  casualPersonal: {
    matches: 4,
    completedDeals: 8,
    wins: 2,
    losses: 1,
    draws: 1,
    totalScore: 42,
  },
  casualOverall: { matches: 5, completedDeals: 9, draws: 2 },
};
describe("statistics consent and presentation", () => {
  it("distinguishes transient unavailable reasons without exposing counts or suggesting re-login", () => {
    for (const [reason, message] of [
      ["BUSY", "統計正在讀取中"],
      ["RATE_LIMITED", "統計服務目前忙碌"],
      ["STORAGE_UNAVAILABLE", "目前無法取得統計"],
    ] as const) {
      const html = renderToStaticMarkup(
        <StatisticsContent
          data={{
            ...data,
            status: "UNAVAILABLE",
            unavailableReason: reason,
            retryAfterSeconds: 60,
          }}
          tab="overall"
        />,
      );
      expect(html).toContain(message);
      expect(html).not.toContain("<dd>");
      expect(html).not.toContain("重新登入");
    }
    const available = renderToStaticMarkup(
      <StatisticsContent
        data={{
          ...data,
          unavailableReason: "RATE_LIMITED",
          retryAfterSeconds: 60,
        }}
        tab="overall"
      />,
    );
    expect(available).not.toContain("目前忙碌");
  });
  it("only honors finite positive retry delays for transient unavailable responses", () => {
    const busy = {
      ...data,
      status: "UNAVAILABLE" as const,
      unavailableReason: "BUSY" as const,
      retryAfterSeconds: 2,
    };
    expect(statisticsRetrySeconds(busy)).toBe(2);
    expect(
      statisticsRetrySeconds({
        ...busy,
        unavailableReason: "RATE_LIMITED",
        retryAfterSeconds: 60,
      }),
    ).toBe(60);
    expect(statisticsRetrySeconds({ ...busy, retryAfterSeconds: 3601 })).toBe(
      3600,
    );
    expect(statisticsRetrySeconds({ ...busy, retryAfterSeconds: 0.5 })).toBe(1);
    for (const value of [undefined, null, NaN, Infinity, -2, 0])
      expect(
        statisticsRetrySeconds({ ...busy, retryAfterSeconds: value }),
      ).toBe(0);
    expect(statisticsRetrySeconds({ ...busy, status: "AVAILABLE" })).toBe(0);
    expect(
      statisticsRetrySeconds({
        ...busy,
        unavailableReason: "STORAGE_UNAVAILABLE",
      }),
    ).toBe(0);
    expect(
      statisticsRetrySeconds({ ...busy, unavailableReason: undefined }),
    ).toBe(0);
  });
  it("accepts explicit null metadata from available and disabled server responses", () => {
    for (const status of ["AVAILABLE", "DISABLED", "UNAVAILABLE"] as const) {
      expect(
        statisticsRetrySeconds({
          ...data,
          status,
          unavailableReason: null,
          retryAfterSeconds: null,
        }),
      ).toBe(0);
    }
  });
  it("counts down from the deadline without going negative after a suspended tab resumes", () => {
    expect(statisticsRemainingSeconds(62000, 2000)).toBe(60);
    expect(statisticsRemainingSeconds(62000, 2501)).toBe(60);
    expect(statisticsRemainingSeconds(62000, 61001)).toBe(1);
    expect(statisticsRemainingSeconds(62000, 62000)).toBe(0);
    expect(statisticsRemainingSeconds(62000, 99999)).toBe(0);
  });

  it.each(["formal", "casual"] as const)(
    "simplifies %s overall counts without double-counting or changing stored outcomes",
    (category) => {
      const counts = {
        matches: 7,
        scoredMatches: 3,
        forfeitMatches: 2,
        interruptedMatches: 2,
        completedDeals: 11,
        draws: 1,
      };
      const result = { ...data, overall: counts, casualOverall: counts };
      const html = renderToStaticMarkup(
        <StatisticsContent data={result} tab="overall" category={category} />,
      );
      expect(html).toContain("<dt>完成場次</dt><dd>3</dd>");
      expect(html).toContain("<dt>無勝負場次</dt><dd>2</dd>");
      expect(html).toContain("<dt>完成局數</dt><dd>11</dd>");
      expect(html).toContain("<dt>平手場次</dt><dd>1</dd>");
      expect(html).not.toContain("<dt>已定案場次</dt>");
      expect(html).toContain("<dt>總場次</dt><dd>7</dd>");
      expect(html).toContain("<dt>中途離席</dt><dd>2</dd>");
      expect((html.match(/<dt>/g) ?? []).length).toBe(6);
      expect(html).toContain("統計與資料說明");
      expect(html).toContain('href="#help"');
      expect(result.overall).toEqual(counts);
    },
  );
  it("does not substitute total finalized matches for an unknown normal-completion count", () => {
    const html = renderToStaticMarkup(
      <StatisticsContent data={data} tab="overall" />,
    );
    expect(html).toContain("<dt>完成場次</dt><dd>—</dd>");
  });

  it("separates normal, forfeit and interrupted outcomes without inventing missing counts", () => {
    const result: StatisticsResponse = {
      ...data,
      personal: {
        ...data.personal!,
        matches: 7,
        scoredMatches: 3,
        forfeitMatches: 2,
        interruptedMatches: 2,
      },
    };
    const html = renderToStaticMarkup(
      <StatisticsContent data={result} tab="personal" />,
    );
    expect(html).toContain("<dt>完成場次</dt><dd>3</dd>");
    expect(html).toContain("<dt>中途離席</dt><dd>2</dd>");
    expect(html).toContain("<dt>無勝負場次</dt><dd>2</dd>");
    const legacy = renderToStaticMarkup(
      <StatisticsContent data={data} tab="personal" />,
    );
    expect(legacy).toContain("<dt>完成場次</dt><dd>—</dd>");
  });
  it("uses independent lifetime counts and retains formal/casual separation", () => {
    const lifetime: StatisticsResponse = {
      ...data,
      lifetimeCoverage: "RECORDED_SINCE_FEATURE",
      lifetimeTrackedFromQuarter: "2026-Q4",
      quarterEndsAt: "2026-12-31T16:00:00Z",
      lifetimePersonal: { ...data.personal!, matches: 10, totalScore: 123 },
      lifetimeCasualPersonal: {
        ...data.casualPersonal!,
        matches: 20,
        totalScore: 456,
      },
    };
    const formal = renderToStaticMarkup(
      <StatisticsContent data={lifetime} tab="personal" period="lifetime" />,
    );
    expect(formal).toContain("<dt>積分</dt><dd>123</dd>");
    expect(formal).not.toContain("<dd>456</dd>");
    expect(formal).toContain("自 2026-Q4 起");
    expect(formal).not.toContain("結算至");
    const casual = renderToStaticMarkup(
      <StatisticsContent
        data={lifetime}
        tab="personal"
        category="casual"
        period="lifetime"
      />,
    );
    expect(casual).toContain("<dt>積分</dt><dd>456</dd>");
    const quarter = renderToStaticMarkup(
      <StatisticsContent data={lifetime} tab="personal" />,
    );
    expect(quarter).toContain("結算至 2027年01月01日00時");
    expect(quarter).toContain("統計與資料說明");
    expect(quarter).toContain("<dt>積分</dt><dd>9</dd>");
    const overall = renderToStaticMarkup(
      <StatisticsContent data={lifetime} tab="overall" period="lifetime" />,
    );
    expect(overall).toContain("2026-Q4 · 台灣時間");
    expect(overall).not.toContain("<dd>123</dd>");
  });
  it("never falls back to current-quarter counts when lifetime data is absent", () => {
    const html = renderToStaticMarkup(
      <StatisticsContent data={data} tab="personal" period="lifetime" />,
    );
    expect(html).toContain("不會以本季數字代替");
    expect(html).not.toContain("<dd>9</dd>");
  });
  it("requires the supported consent version only when enabled", async () => {
    const fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ enabled: true, consentVersion: "unknown" }),
    });
    vi.stubGlobal("fetch", fetch);
    await expect(readStatisticsConfig()).rejects.toThrow();
    fetch.mockResolvedValue({
      ok: true,
      json: async () => ({ enabled: true, consentVersion: "statistics-v1" }),
    });
    await expect(readStatisticsConfig()).rejects.toThrow();
    fetch.mockResolvedValue({
      ok: true,
      json: async () => ({ enabled: false }),
    });
    await expect(readStatisticsConfig()).resolves.toMatchObject({
      enabled: false,
    });
  });
  it("does not acknowledge disabled collection", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await acceptStatistics("current", {
      enabled: false,
      consentVersion: "statistics-v4",
    });
    expect(fetch).not.toHaveBeenCalled();
  });
  it("submits explicit acknowledgement with current session, rejects unconfirmed outcome", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ accepted: true }) });
    vi.stubGlobal("fetch", fetch);
    await acceptStatistics("current", {
      enabled: true,
      consentVersion: "statistics-v4",
    });
    expect(fetch).toHaveBeenCalledWith(
      "/api/statistics/consent",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ consent: true, version: "statistics-v4" }),
        headers: expect.objectContaining({ Authorization: "Bearer current" }),
      }),
    );
    fetch.mockResolvedValue({
      ok: true,
      json: async () => ({ accepted: false }),
    });
    await expect(
      acceptStatistics("current", {
        enabled: true,
        consentVersion: "statistics-v4",
      }),
    ).rejects.toThrow();
    fetch.mockClear();
    await expect(
      acceptStatistics("old", {
        enabled: true,
        consentVersion: "statistics-v4",
      }),
    ).rejects.toThrow();
    expect(fetch).not.toHaveBeenCalled();
  });
  it("states mixed human eligibility and manual cleanup without guest stats entry", () => {
    for (const text of [
      "四位真人",
      "正式場",
      "休閒場",
      "類型在開房時固定",
      "訪客也計入整體",
      "沒有個人統計或統計入口",
      "私人 Google 試算表",
      "每局耗時",
      "每季由營運者人工清理",
      "並非自動刪除",
      "未完成局不計分",
    ])
      expect(statisticsNotice).toContain(text);
  });
  it("separates personal and overall counts with personal win rate but without identifiers", () => {
    const personal = renderToStaticMarkup(
      <StatisticsContent data={data} tab="personal" />,
    );
    expect(personal).toContain("2026-Q4");
    expect(personal).toContain("總場次");
    expect(personal).toContain("完成局數");
    expect(personal).toContain("積分");
    const overall = renderToStaticMarkup(
      <StatisticsContent data={data} tab="overall" />,
    );
    expect(overall).not.toContain("我方累計分數");
    expect(overall).not.toContain("獲勝場次");
    expect(overall).not.toContain("%");
  });
  it("keeps formal and casual personal scores and overall counters separate", () => {
    const formal = renderToStaticMarkup(
      <StatisticsContent data={data} tab="personal" category="formal" />,
    );
    const casual = renderToStaticMarkup(
      <StatisticsContent data={data} tab="personal" category="casual" />,
    );
    expect(formal).toContain("正式場 · 個人");
    expect(formal).toContain("<dd>9</dd>");
    expect(formal).not.toContain("<dd>42</dd>");
    expect(casual).toContain("休閒場 · 個人");
    expect(casual).toContain("<dd>42</dd>");
    expect(casual).not.toContain("<dd>9</dd>");
    const overall = renderToStaticMarkup(
      <StatisticsContent data={data} tab="overall" category="casual" />,
    );
    expect(overall).toContain("休閒場 · 整體");
    expect(overall).toContain("<dt>完成局數</dt><dd>9</dd>");
    expect(overall).not.toContain("<dd>42</dd>");
    expect(overall).not.toContain("獲勝場次");
    expect(
      renderToStaticMarkup(
        <StatisticsContent
          data={{ ...data, casualPersonal: null }}
          tab="personal"
          category="casual"
        />,
      ),
    ).toContain("資料不完整");
    expect(
      renderToStaticMarkup(
        <StatisticsContent
          data={{
            ...data,
            casualOverall: { matches: 0, completedDeals: 0, draws: 0 },
          }}
          tab="overall"
          category="casual"
        />,
      ),
    ).toContain("本季尚無已確認保存的休閒場");
  });
  it("distinguishes zero, unavailable, disabled and missing counts", () => {
    expect(
      renderToStaticMarkup(
        <StatisticsContent
          data={{
            ...data,
            overall: { matches: 0, completedDeals: 0, draws: 0 },
          }}
          tab="overall"
        />,
      ),
    ).toContain("本季尚無");
    for (const status of ["UNAVAILABLE", "DISABLED"] as const)
      expect(
        renderToStaticMarkup(
          <StatisticsContent data={{ ...data, status }} tab="overall" />,
        ),
      ).not.toContain("本季尚無");
    expect(
      renderToStaticMarkup(
        <StatisticsContent data={{ ...data, personal: null }} tab="personal" />,
      ),
    ).toContain("資料不完整");
  });
});
