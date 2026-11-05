import { describe, expect, it, vi } from "vitest";
import { nicknameValidation } from "./nickname";
import { readFileSync } from "node:fs";

describe("five visible-character guest names", () => {
  it.each([
    "小",
    "一二三四五",
    "abcde",
    "😀😀😀😀😀",
    "👨‍👩‍👧‍👦".repeat(5),
    "e\u0301".repeat(5),
    "🇹🇼".repeat(5),
  ])("accepts %s without splitting characters", (name) => {
    const result = nicknameValidation(name);
    expect(result.error).toBeNull();
    expect(result.count).toBeLessThanOrEqual(5);
  });
  it.each([
    "",
    "　  ",
    "一二三四五六",
    "abcdef",
    "😀".repeat(6),
    "👨‍👩‍👧‍👦".repeat(6),
    "a\nb",
    "\t小白",
  ])("rejects %s", (name) => {
    expect(nicknameValidation(name).error).not.toBeNull();
  });
  it("normalizes NBSP/full-width/BOM boundaries and combining characters consistently", () => {
    expect(nicknameValidation("\uFEFF\u00a0　e\u0301　\u00a0")).toEqual({
      normalized: "é",
      count: 1,
      error: null,
    });
  });
  it("does not truncate an over-limit pasted name", () => {
    const result = nicknameValidation("一二三四五六");
    expect(result.normalized).toBe("一二三四五六");
    expect(result.count).toBe(6);
  });
  it("handles unavailable grapheme support explicitly instead of miscounting emoji", () => {
    vi.stubGlobal("Intl", { ...Intl, Segmenter: undefined });
    try {
      expect(nicknameValidation("小白").error).toContain("更新瀏覽器");
    } finally {
      vi.unstubAllGlobals();
    }
  });
  it("keeps composition intact, guards submit, and does not impose UTF-16 maxlength", () => {
    const source = readFileSync(new URL("./WelcomeEntry.tsx", import.meta.url), "utf8");
    const input = source.slice(
      source.indexOf('id="guest-name"'),
      source.indexOf('placeholder="例如：小白"'),
    );
    expect(input).toContain("onCompositionStart");
    expect(input).toContain("onCompositionEnd");
    expect(input).not.toContain("maxLength");
    expect(source).toContain("if (!session && (composing || nickname.error)) return;");
  });
});
