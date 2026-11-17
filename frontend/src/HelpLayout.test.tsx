import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
vi.mock("./client", () => ({
  client: { initialize: vi.fn() },
  getConnectionDiagnostics: () => [],
  useClient: () => ({
    initialized: true,
    session: null,
    room: null,
    rooms: [],
    busy: false,
    status: "offline",
    error: null,
    notice: "已離開房間，準備狀態與席位已立即清除。",
  }),
}));
import App from "./App";

afterEach(() => vi.unstubAllGlobals());

describe("help page layout", () => {
  it("keeps version before aligned help sections without the decorative title", () => {
    vi.stubGlobal("window", { location: { hash: "#help" } });
    const html = renderToStaticMarkup(<App />);
    expect(html).toContain('class="help-page"');
    expect(html).toContain('class="help-version"');
    expect(html).not.toContain("安心入座，一起等候。");
    expect(html.indexOf('class="help-version"')).toBeLessThan(
      html.indexOf('class="pwa-help"'),
    );
    for (const text of [
      "v0.15.2",
      "加入主畫面",
      "保持螢幕開啟",
      "使用說明",
      "回到等候室",
    ])
      expect(html).toContain(text);
  });
  it("keeps tutorial actions in the lobby, not the help page", () => {
    const source = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
    const help = source.slice(
      source.indexOf("<h2>使用說明</h2>"),
      source.indexOf("<DataPrivacyHelp />"),
    );
    expect(help).not.toContain("<TutorialHelpEntry");
    expect(source).toMatch(/page === "help"\s*\? "hidden"/);
    expect(source.match(/<TutorialHelpEntry/g)).toHaveLength(1);
    vi.stubGlobal("window", { location: { hash: "#help" } });
    const html = renderToStaticMarkup(<App />);
    expect(html).not.toContain("開始教學");
    expect(html).not.toContain("確認教學狀態");
    expect(html).toContain("大廳的「新手教學」");
  });
  it("preserves the existing help-page notice outside the statistics change", () => {
    vi.stubGlobal("window", { location: { hash: "#help" } });
    expect(renderToStaticMarkup(<App />)).toContain("已離開房間");
  });
  it("keeps version but omits lobby slogans and leave-room notices on statistics", () => {
    vi.stubGlobal("window", { location: { hash: "#statistics" } });
    const html = renderToStaticMarkup(<App />);
    expect(html).toContain('class="statistics-page"');
    expect(html).toContain("v0.15.2");
    expect(html).not.toContain("四個座位，一起相聚。");
    expect(html).not.toContain("已離開房間");
  });
  it("does not change the welcome page title or apply the help reading column there", () => {
    vi.stubGlobal("window", { location: { hash: "#room" } });
    const html = renderToStaticMarkup(<App />);
    expect(html).not.toContain('class="help-page"');
    expect(html).toContain("四個座位，一起相聚。");
    expect(html).toContain("已離開房間");
  });
});
