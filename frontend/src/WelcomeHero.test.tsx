import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
vi.mock("./client", () => ({
  client: { initialize: vi.fn() },
  getConnectionDiagnostics: () => ({}),
  useClient: () => ({ initialized: true, session: null, room: null, rooms: [], busy: false, status: "offline", error: null, notice: null }),
}));
import App from "./App";

describe("mobile forest welcome invitation", () => {
  it("keeps the guest form first and the complete invitation with all four friends after it", () => {
    const html = renderToStaticMarkup(<App />);
    expect(html.indexOf('id="guest-name"')).toBeLessThan(html.indexOf('class="welcome-art forest-welcome"'));
    expect(html).toContain("森林裡，留一個位子給你。");
    for (const animal of ["rabbit", "fox", "panda", "bear"]) {
      expect(html).toContain(`/assets/forest/animal-${animal}.webp`);
    }
  });
  it("does not restore the legacy mobile display:none rule", () => {
    const css = readFileSync(new URL("./style.css", import.meta.url), "utf8");
    expect(css).not.toMatch(/\.welcome-art\s*\{[^}]*display:\s*none/);
    expect(css).toContain(".welcome-grid .forest-welcome .animal-avatar.large");
  });
});
