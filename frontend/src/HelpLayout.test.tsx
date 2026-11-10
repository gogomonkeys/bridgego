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
    notice: null,
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
      "v0.13.0",
      "加入主畫面",
      "保持螢幕開啟",
      "使用說明",
      "回到等候室",
    ])
      expect(html).toContain(text);
  });
  it("does not change the welcome page title or apply the help reading column there", () => {
    vi.stubGlobal("window", { location: { hash: "#room" } });
    const html = renderToStaticMarkup(<App />);
    expect(html).not.toContain('class="help-page"');
    expect(html).toContain("四個座位，一起相聚。");
  });
});
