import { readFileSync } from "node:fs";
import postcss from "postcss";
import { expect, it } from "vitest";

it("scopes the live table theme and prevents layout or control changes", () => {
  const css = readFileSync(
    new URL("./forest-game-theme.css", import.meta.url),
    "utf8",
  );
  const theme = postcss.parse(css);
  const paintOnly = new Set(["background", "border-color", "box-shadow"]);
  let ruleCount = 0;
  theme.walkRules(({ selectors }) => {
    ruleCount++;
    for (const selector of selectors)
      expect(selector).toMatch(/^\.match-workspace\.viewport-game\b/);
  });
  expect(ruleCount).toBeGreaterThan(0);
  theme.walkDecls(({ prop }) => expect(paintOnly.has(prop)).toBe(true));
  theme.walkAtRules(({ name }) => {
    throw new Error(`Unexpected theme at-rule ${name}`);
  });
});
