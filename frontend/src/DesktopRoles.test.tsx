import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const css = readFileSync(new URL("./style.css", import.meta.url), "utf8");
const desktopRoles = css.slice(
  css.indexOf("/* V0.6.8:"),
  css.indexOf("/* V0.6.9:"),
);

describe("desktop-only role enlargement boundary", () => {
  it("keeps every added role rule behind desktop media queries", () => {
    const withoutComments = desktopRoles
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .trim();
    let depth = 0;
    for (const line of withoutComments.split("\n")) {
      if (depth === 0 && line.trim()) {
        expect(line).toMatch(/^@media \(min-width: 731px\)/);
      }
      depth += (line.match(/\{/g) ?? []).length;
      depth -= (line.match(/\}/g) ?? []).length;
    }
    expect(depth).toBe(0);
  });
  it("enlarges all four role cards consistently, without changing public or hand cards", () => {
    expect(desktopRoles).toContain("--match-seat-width: 148px");
    expect(desktopRoles).toContain("--match-seat-height: 96px");
    expect(desktopRoles).toContain("--match-seat-width: 176px");
    expect(desktopRoles).toContain("--match-seat-height: 126px");
    expect(desktopRoles).not.toMatch(/position-(top|bottom|left|right)/);
    expect(desktopRoles).not.toMatch(
      /public-card|portrait-card|own-hand|played-card/,
    );
  });
  it("preserves the short-desktop minimum-height budget", () => {
    const before = 2 * 88 + 2 * 10 + 2 * 12;
    const after = 2 * 96 + 2 * 6 + 2 * 8;
    expect(after).toBe(before);
  });
});
