import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  acceptStatistics,
  readStatisticsConfig,
  statisticsNotice,
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
  ruleVersion: "four-humans-at-settlement-v1",
  personal: {
    matches: 2,
    completedDeals: 6,
    wins: 1,
    losses: 0,
    draws: 1,
    totalScore: 9,
  },
  overall: { matches: 3, completedDeals: 7, draws: 1 },
};
describe("statistics consent and presentation", () => {
  it("requires the supported consent version only when enabled", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue({
        ok: true,
        json: async () => ({ enabled: true, consentVersion: "unknown" }),
      });
    vi.stubGlobal("fetch", fetch);
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
      consentVersion: "statistics-v1",
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
      consentVersion: "statistics-v1",
    });
    expect(fetch).toHaveBeenCalledWith(
      "/api/statistics/consent",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ consent: true, version: "statistics-v1" }),
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
        consentVersion: "statistics-v1",
      }),
    ).rejects.toThrow();
    fetch.mockClear();
    await expect(
      acceptStatistics("old", {
        enabled: true,
        consentVersion: "statistics-v1",
      }),
    ).rejects.toThrow();
    expect(fetch).not.toHaveBeenCalled();
  });
  it("states mixed human eligibility and manual cleanup without guest stats entry", () => {
    for (const text of [
      "四位不同真人",
      "訪客也計入整體",
      "沒有個人統計或統計入口",
      "私人 Google 試算表",
      "每季由營運者人工清理",
      "並非自動刪除",
      "最後一張牌前",
    ])
      expect(statisticsNotice).toContain(text);
  });
  it("separates personal and overall counts without percentages or identifiers", () => {
    const personal = renderToStaticMarkup(
      <StatisticsContent data={data} tab="personal" />,
    );
    expect(personal).toContain("2026-Q4");
    expect(personal).toContain("完成場次");
    expect(personal).toContain("完成局數");
    expect(personal).toContain("我方累計分數");
    const overall = renderToStaticMarkup(
      <StatisticsContent data={data} tab="overall" />,
    );
    expect(overall).not.toContain("我方累計分數");
    expect(overall).not.toContain("獲勝場次");
    expect(overall).not.toContain("%");
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
