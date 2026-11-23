import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import WelcomeEntry from "./WelcomeEntry";

const readiness = vi.hoisted(() => ({
  ready: false,
  statistics: null as null | { enabled: boolean },
  auth: null,
  phase: "waiting",
}));

vi.mock("./use-entry-readiness", () => ({
  useEntryReadiness: () => readiness,
}));

describe("cold welcome entry", () => {
  it("retains a local fallback if the hero request fails, then enables guest play when ready", async () => {
    const props = { session: null, busy: false, onEnter: vi.fn() };
    let entry!: ReactTestRenderer;
    try {
      await act(async () => {
        entry = create(<WelcomeEntry {...props} />);
      });
      const image = { hidden: false };
      const guest = () =>
        entry.root.findByProps({ className: "secondary entry-continue" });
      expect(guest().props.disabled).toBe(true);
      expect(JSON.stringify(entry.toJSON())).toContain("服務可能正在喚醒");
      const hero = entry.root.findByType("img");
      expect(hero.props.src).toBe("/assets/forest/welcome-grass.webp");
      hero.props.onError({ currentTarget: image });
      expect(image.hidden).toBe(true);
      const css = readFileSync(
        new URL("./welcome-lobby-theme.css", import.meta.url),
        "utf8",
      );
      expect(css).toMatch(/\.woodland-entry-hero\s*\{[^}]+radial-gradient/);
      expect(css).toMatch(
        /\.woodland-entry-hero img\[hidden\]\s*\{\s*display:\s*none/,
      );
      readiness.ready = true;
      readiness.statistics = { enabled: false };
      readiness.phase = "ready";
      await act(async () => {
        entry.update(<WelcomeEntry {...props} />);
      });
      expect(guest().props.disabled).toBe(false);
      expect(JSON.stringify(entry.toJSON())).not.toContain("服務可能正在喚醒");
      expect(props.onEnter).not.toHaveBeenCalled();
    } finally {
      if (entry)
        await act(async () => {
          entry.unmount();
        });
    }
  });
});
