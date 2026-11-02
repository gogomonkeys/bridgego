import { readFileSync } from "node:fs";
import { expect, it } from "vitest";

it("subtracts the body-owned block insets from both fixed and short-screen match heights", () => {
  const css = readFileSync(new URL("./style.css", import.meta.url), "utf8");
  const available = "calc(100dvh - var(--bridge-safe-top) - var(--bridge-safe-bottom))";
  expect(css).toContain("height: " + available);
  expect(css).toContain("min-height: " + available);
  expect(css).not.toMatch(/(?:^|\n)\s*(?:min-)?height: 100dvh;/);
  expect(css).toContain("padding: var(--bridge-safe-top) var(--bridge-safe-right)");
  for (const [viewport, top, bottom] of [[844, 0, 0], [844, 47, 34], [568, 20, 20]]) {
    expect(top + (viewport - top - bottom) + bottom).toBe(viewport);
  }
});
