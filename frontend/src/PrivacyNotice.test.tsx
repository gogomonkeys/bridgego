import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import PrivacyNotice, { privacyReady } from "./PrivacyNotice";
import { canLinkAccount } from "./GoogleAccount";
import type { AuthConfig } from "./google-login";
const configured: AuthConfig = {
  enabled: true,
  clientId: "test",
  verificationEnabled: true,
  collectionMode: "restricted",
  collectionReady: true,
  privacyContact: "測試負責窗口",
  retention: "測試用期限政策",
};
describe("restricted-test privacy readiness", () => {
  it("fails closed without actual contact and retention metadata", () => {
    const html = renderToStaticMarkup(<PrivacyNotice />);
    expect(html).toContain("刪除管道尚未設定，資料保存不可啟用");
    expect(html).toContain("保存期限尚未設定，資料保存不可啟用");
    expect(privacyReady()).toBe(false);
    for (const config of [
      null,
      { ...configured, privacyContact: " " },
      { ...configured, retention: "" },
      { ...configured, collectionReady: false },
      { ...configured, enabled: false },
      { ...configured, collectionMode: undefined },
    ])
      expect(canLinkAccount(config)).toBe(false);
    expect(canLinkAccount(configured)).toBe(true);
  });
  it("uses only configured policy values and distinguishes unlink from verified deletion", () => {
    const html = renderToStaticMarkup(
      <PrivacyNotice
        privacy={{
          contact: configured.privacyContact,
          retention: configured.retention,
        }}
      />,
    );
    expect(html).toContain("測試負責窗口");
    expect(html).toContain("測試用期限政策");
    expect(html).toContain("解除 Google 連結不是刪除");
    expect(html).toContain("先驗證帳號歸屬");
    expect(html).toContain("同場其他玩家");
    expect(html).toContain("備份");
    expect(html).toContain("只要場內有訪客或未核准者，整場都不保存");
    expect(html).not.toContain("mailto:");
  });
});
