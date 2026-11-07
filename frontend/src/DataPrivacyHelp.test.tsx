import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import DataPrivacyHelp from "./DataPrivacyHelp";
describe("help data policy", () => {
  it("discloses bounded experimental collection without claiming deletion readiness", () => {
    const html = renderToStaticMarkup(<DataPrivacyHelp />);
    for (const text of [
      "單人一場限定實驗",
      "事先指定的一個 Google",
      "刪除與季度清理仍待實機驗收",
      "其他人與訪客不保存",
      "不會自動另開保存名額",
      "實驗結束或期限到達不等於資料已刪除",
      "刪除測試可能失敗",
    ])
      expect(html).toContain(text);
  });
  it("describes public contact, Taiwan quarter, manual cleanup and retained accounts", () => {
    const html = renderToStaticMarkup(<DataPrivacyHelp />);
    for (const text of [
      'href="mailto:jody.yycc@gmail.com"',
      "finishedAt",
      "Asia/Taipei",
      "手動執行",
      "季度清理保留帳號",
      "SUPPRESSED",
      "不發布統計數字",
    ])
      expect(html).toContain(text);
  });
  it("does not promise guaranteed saving or erasure of every copy", () => {
    const html = renderToStaticMarkup(<DataPrivacyHelp />);
    for (const text of [
      "沒有保證不遺失的持久佇列",
      "版本歷史",
      "備份",
      "匯出檔",
      "假名化識別雜湊",
      "不能重新註冊",
      "先驗證帳號歸屬",
      "收集預設關閉",
      "Google 驗證（測試）",
    ])
      expect(html).toContain(text);
  });
});
