import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import {
  canEnterAfterAccountLink,
  canLinkAccount,
  entryAccountPurpose,
  experimentNotice,
} from "./entry-account";
import type { AuthConfig } from "./google-login";
const configured: AuthConfig = {
  enabled: true,
  clientId: "test-only",
  verificationEnabled: true,
  collectionMode: "restricted",
  collectionReady: true,
  privacyContact: "jody.yycc@gmail.com",
  retention: "test-only policy",
};
describe("entry account purpose and completion", () => {
  it("offers linking only when restricted config and policy are complete", () => {
    expect(entryAccountPurpose(configured)).toBe("link");
    for (const patch of [
      { enabled: false },
      { collectionReady: false },
      { collectionMode: undefined },
      { privacyContact: "" },
      { retention: " " },
    ]) {
      expect(canLinkAccount({ ...configured, ...patch })).toBe(false);
      expect(entryAccountPurpose({ ...configured, ...patch })).toBe("verify");
    }
    for (const config of [
      null,
      { ...configured, clientId: " " },
      { ...configured, verificationEnabled: false },
    ])
      expect(entryAccountPurpose(config)).toBeNull();
  });
  it("fails closed with malformed config fields", () => {
    for (const config of [
      { ...configured, clientId: null },
      { ...configured, enabled: "true" },
      { ...configured, privacyContact: 1 },
      { ...configured, retention: {} },
    ])
      expect(canLinkAccount(config as unknown as AuthConfig)).toBe(false);
    expect(
      entryAccountPurpose({ verificationEnabled: true } as AuthConfig),
    ).toBeNull();
  });
  it("only exact backend signedIn true can enter for the same mounted, unseated identity", () => {
    expect(
      canEnterAfterAccountLink(true, "current", "current", false, true),
    ).toBe(true);
    for (const status of [
      false,
      undefined,
      null,
      "true",
      1,
      {},
      { signedIn: true },
    ])
      expect(
        canEnterAfterAccountLink(status, "current", "current", false, true),
      ).toBe(false);
    expect(canEnterAfterAccountLink(true, "old", "new", false, true)).toBe(
      false,
    );
    expect(
      canEnterAfterAccountLink(true, "current", "current", true, true),
    ).toBe(false);
    expect(
      canEnterAfterAccountLink(true, "current", "current", false, false),
    ).toBe(false);
  });
});

describe("single tester experiment admission display", () => {
  const now = Date.parse("2026-11-07T12:00:00Z");
  const experiment: AuthConfig = {
    ...configured,
    collectionMode: "single_tester_experiment",
    collectionReady: false,
    experimentReady: true,
    experimentExpiresAt: "2026-11-07T13:00:00Z",
    experimentMaxMatches: 1,
  };
  it("allows only the explicit experimental contract while production readiness remains false", () => {
    expect(canLinkAccount(experiment, now)).toBe(true);
    expect(entryAccountPurpose(experiment, now)).toBe("link");
    expect(experiment.collectionReady).toBe(false);
    for (const patch of [
      { collectionReady: true },
      { experimentReady: false },
      { enabled: false },
      { experimentMaxMatches: undefined },
      { experimentExpiresAt: null },
      { experimentExpiresAt: "not-a-date" },
      { experimentExpiresAt: "2026-11-07T13:00:00" },
      { experimentExpiresAt: "2026-11-07T12:00:00Z" },
      { privacyContact: "" },
      { retention: "" },
    ]) {
      expect(canLinkAccount({ ...experiment, ...patch }, now)).toBe(false);
      expect(entryAccountPurpose({ ...experiment, ...patch }, now)).toBe(
        "verify",
      );
    }
  });
  it("rejects malformed or unknown experimental modes and readiness without silently trusting truthy data", () => {
    for (const patch of [
      { experimentReady: "true" },
      { experimentMaxMatches: "1" },
      { experimentMaxMatches: 2 },
      { experimentExpiresAt: 1790000000000 },
      { collectionReady: undefined },
      { collectionMode: "experiment" },
    ])
      expect(
        canLinkAccount(
          { ...experiment, ...patch } as unknown as AuthConfig,
          now,
        ),
      ).toBe(false);
  });
});

describe("experiment notices", () => {
  it("distinguishes expiry, admission failure and verification-only without claiming erasure", () => {
    expect(experimentNotice(configured)).toBeNull();
    expect(
      experimentNotice(
        {
          ...configured,
          collectionMode: "single_tester_experiment",
          experimentExpiresAt: "2026-11-07T12:00:00Z",
        },
        Date.parse("2026-11-07T12:00:00Z"),
      ),
    ).toContain("不等於資料已刪除");
    expect(
      experimentNotice({
        ...configured,
        collectionMode: "single_tester_experiment",
      }),
    ).toContain("不接受新登入");
  });
});

it("cancels an unsubmitted linking prompt after admission expires without promising to retract submission", () => {
  for (const file of ["WelcomeEntry.tsx", "GoogleAccount.tsx"]) {
    const source = readFileSync(new URL(`./${file}`, import.meta.url), "utf8");
    expect(source).toContain('phase === "loading" || phase === "ready"');
    expect(source).toContain('purpose === "link"');
    expect(source).toMatch(/current\?\.cancel\(\)/);
  }
});

it("preselects one deal only on actual experimental account entry", () => {
  const app = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
  const entry = readFileSync(
    new URL("./WelcomeEntry.tsx", import.meta.url),
    "utf8",
  );
  expect(app).toContain("if (experiment) setPlannedDeals(1)");
  expect(entry).toContain("enter.current(token, experimentRef.current)");
  expect(entry).toContain("enter.current(expectedToken)");
});
