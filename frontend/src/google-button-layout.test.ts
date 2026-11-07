import { readFileSync } from "node:fs";
import postcss from "postcss";
import { describe, expect, it } from "vitest";

const css = readFileSync(new URL("./style.css", import.meta.url), "utf8");
const sheet = postcss.parse(css);
const hostDeclarations: Record<string, string> = {};
sheet.walkRules(".google-signin-button", (rule) => {
  rule.walkDecls((decl) => {
    hostDeclarations[decl.prop] = decl.value;
  });
});

describe("official Google button host layout", () => {
  it("does not create the implicit vertical scroll container from overflow-x:auto", () => {
    expect(hostDeclarations.overflow).toBe("visible");
    expect(hostDeclarations["overflow-x"]).toBeUndefined();
    expect(hostDeclarations["overflow-y"]).toBeUndefined();
    expect(hostDeclarations.display).toBe("flow-root");
  });

  it("uses the available inline space without fixed height, clipping or scaling", () => {
    expect(hostDeclarations["min-width"]).toBe("0");
    expect(hostDeclarations["max-width"]).toBe("100%");
    for (const property of [
      "width",
      "height",
      "max-height",
      "block-size",
      "max-block-size",
      "clip",
      "clip-path",
      "transform",
      "zoom",
      "contain",
      "pointer-events",
      "visibility",
      "scrollbar-width",
    ])
      expect(hostDeclarations[property]).toBeUndefined();
  });

  it("does not hide scrollbar chrome or restyle the official iframe", () => {
    sheet.walkRules((rule) => {
      if (!rule.selector.includes(".google-signin-button")) return;
      expect(rule.selector).toBe(".google-signin-button");
      expect(rule.selector).not.toMatch(/iframe|scrollbar|:focus/);
    });
    // The native control keeps its own keyboard and accessible-name semantics.
    const login = readFileSync(
      new URL("./google-login.ts", import.meta.url),
      "utf8",
    );
    expect(login).toContain("identity.renderButton(element");
    expect(login).not.toMatch(/(?:iframe|tabindex|aria-hidden).*setAttribute/);
  });
});
