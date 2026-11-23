import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
vi.mock("./client", () => ({
  client: { initialize: vi.fn() },
  getConnectionDiagnostics: () => ({}),
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

describe("mobile forest welcome invitation", () => {
  it("places the decorative grass hero before both accessible login paths", () => {
    const html = renderToStaticMarkup(<App />);
    expect(html).toContain("/assets/forest/welcome-grass.webp");
    expect(html).toContain('alt="" loading="eager"');
    expect(html.indexOf("woodland-entry-hero")).toBeLessThan(
      html.indexOf("進入大廳前的選擇"),
    );
    expect(html).toContain("林間留了位子，來打一局吧。");
    expect(html).toContain("訪客");
    expect(html).toContain("Google");
    expect(html).not.toContain('class="welcome-art forest-welcome"');
  });
  it("does not restore the legacy mobile display:none rule", () => {
    const css = readFileSync(new URL("./style.css", import.meta.url), "utf8");
    expect(css).not.toMatch(/\.welcome-art\s*\{[^}]*display:\s*none/);
    expect(css).toContain(".welcome-grid .forest-welcome .animal-avatar.large");
  });
});
