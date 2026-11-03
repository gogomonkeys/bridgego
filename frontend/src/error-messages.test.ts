import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { errorMessages } from "./error-messages";

const backend = new URL(
  "../../backend/src/main/java/com/bridge/room/",
  import.meta.url,
);
const source = readdirSync(backend)
  .filter((name) => name.endsWith(".java"))
  .map((name) => readFileSync(new URL(name, backend), "utf8"))
  .join("\n");
const codes = new Set([
  ...Array.from(
    source.matchAll(
      /(?:\bfail\(|\bfailure\(response,)\s*\d{3}\s*,\s*"([A-Z_]+)"/g,
    ),
    (match) => match[1],
  ),
  ...Array.from(source.matchAll(/"code",\s*"([A-Z_]+)"/g), (match) => match[1]),
  ...Array.from(
    source.matchAll(/\breject\(c,\s*"([A-Z_]+)"/g),
    (match) => match[1],
  ),
]);
const catalog = JSON.parse(
  readFileSync(new URL("../../docs/api/errors.json", import.meta.url), "utf8"),
) as {
  applicationErrors: Array<{ code: string; automaticMutationRetry: boolean }>;
};

describe("stable error catalog", () => {
  it("covers every current server application code without silently dropping new errors", () => {
    expect(
      new Set(catalog.applicationErrors.map((entry) => entry.code)),
    ).toEqual(codes);
    for (const code of codes) expect(errorMessages[code], code).toBeTruthy();
  });
  it("never marks arbitrary mutations safe for automatic replay", () => {
    for (const entry of catalog.applicationErrors)
      expect(entry.automaticMutationRetry, entry.code).toBe(false);
  });
  it("maps current stale identifiers while retaining old client aliases", () => {
    expect(errorMessages.STALE_GAME).toBe(errorMessages.WRONG_GAME);
    expect(errorMessages.STALE_DEAL).toBe(errorMessages.WRONG_DEAL);
  });
  it("does not label a play-turn error as bidding only", () => {
    expect(errorMessages.NOT_YOUR_TURN).toContain("回合");
    expect(errorMessages.NOT_YOUR_TURN).not.toContain("叫牌");
  });
});
