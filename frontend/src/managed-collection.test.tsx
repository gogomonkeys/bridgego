import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
import {
  canLinkAccount,
  canRecoverAccount,
  canEnterAfterAccountLink,
  entryAccountPurpose,
  isManagedCollection,
  managedLoginConsent,
} from "./entry-account";
import type { AuthConfig } from "./google-login";
import PrivacyNotice from "./PrivacyNotice";
import DataPrivacyHelp from "./DataPrivacyHelp";
import { persistenceLabels } from "./MatchPersistence";
const managed: AuthConfig = {
  enabled: true,
  clientId: "existing-client",
  verificationEnabled: true,
  collectionMode: "managed",
  collectionReady: true,
  privacyContact: "jody.yycc@gmail.com",
  retention: "帳號保留；個人戰績按台灣季度人工清理。",
};
describe("managed optional Google collection", () => {
  it("offers ordinary linking without tester or verified-workflow flags", () => {
    expect(isManagedCollection(managed)).toBe(true);
    expect(canLinkAccount(managed)).toBe(true);
    expect(entryAccountPurpose(managed)).toBe("link");
    expect(canRecoverAccount(managed)).toBe(false);
    expect(managed).not.toHaveProperty("erasureWorkflowVerified");
    expect(managed).not.toHaveProperty("quarterPolicyVerified");
  });
  it("never substitutes verification-only testing when managed collection is unavailable", () => {
    for (const patch of [
      { enabled: false },
      { verificationEnabled: false },
      { collectionReady: false },
      { clientId: "" },
      { privacyContact: "" },
      { retention: "" },
    ]) {
      const config = { ...managed, ...patch };
      expect(canLinkAccount(config)).toBe(false);
      expect(entryAccountPurpose(config)).toBeNull();
    }
    for (const patch of [
      { enabled: "true" },
      { collectionReady: "true" },
      { privacyContact: 3 },
      { retention: {} },
      { clientId: null },
    ])
      expect(
        canLinkAccount({ ...managed, ...patch } as unknown as AuthConfig),
      ).toBe(false);
  });
  it("uses existing successful link completion checks, never advances verification or stale sessions", () => {
    expect(canEnterAfterAccountLink(true, "guest", "guest", false, true)).toBe(
      true,
    );
    expect(canEnterAfterAccountLink(false, "guest", "guest", false, true)).toBe(
      false,
    );
    expect(canEnterAfterAccountLink(true, "old", "new", false, true)).toBe(
      false,
    );
    expect(canEnterAfterAccountLink(true, "guest", "guest", true, true)).toBe(
      false,
    );
    expect(canEnterAfterAccountLink(true, "guest", "guest", false, false)).toBe(
      false,
    );
  });
  it("keeps normal consent concise and links to truthful detailed policy", () => {
    for (const text of [
      "Google 識別碼",
      "遊戲帳號",
      "合資格戰績",
      "私人 Google 試算表",
      "帳號保留",
      "每季人工清理",
      "解除連結不是刪除",
    ])
      expect(managedLoginConsent).toContain(text);
    expect(managedLoginConsent).not.toMatch(/核准|測試|實驗/);
    for (const file of ["WelcomeEntry.tsx", "GoogleAccount.tsx"]) {
      const source = readFileSync(new URL(file, import.meta.url), "utf8");
      expect(source).toContain("managedLoginConsent");
      expect(source).toContain('href="#help"');
    }
    const account = readFileSync(
      new URL("GoogleAccount.tsx", import.meta.url),
      "utf8",
    );
    expect(account).toContain("!managed &&");
    expect(account).toContain("同意並使用 Google 登入");
  });
  it("managed notice does not describe an allowlist or certified deletion", () => {
    const html = renderToStaticMarkup(
      <PrivacyNotice
        managed
        privacy={{
          contact: managed.privacyContact,
          retention: managed.retention,
        }}
      />,
    );
    for (const text of [
      "帳號資料與刪除說明",
      "全場真人皆已登入並同意",
      "只要場內有訪客，整場不保存",
      "帳號保留",
      "每季人工清理",
      "版本歷史",
      "備份",
      "沒有保證不遺失的持久佇列",
      "jody.yycc@gmail.com",
      "不因登入開放就視為已完成實機驗收",
    ])
      expect(html).toContain(text);
    expect(html).not.toContain("核准測試者");
    expect(html).not.toContain("封閉測試");
  });
  it("help distinguishes normal, restricted and legacy experiment scopes truthfully", () => {
    const html = renderToStaticMarkup(<DataPrivacyHelp />);
    for (const text of [
      "正常帳號資料保存",
      "帳號持續保留",
      "不含個人識別的整體季度數字可依政策保留",
      "目前彙總仍隱藏",
      "不代表所有帳號刪除或季度清理已完成實機驗收",
      "Asia/Taipei",
      "SUPPRESSED",
      "版本歷史",
      "單人一場限定實驗",
      "若使用封閉測試模式",
    ])
      expect(html).toContain(text);
    expect(persistenceLabels.DISABLED).not.toContain("封閉測試");
  });
});
