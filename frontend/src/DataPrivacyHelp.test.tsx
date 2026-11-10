import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import DataPrivacyHelp from "./DataPrivacyHelp";
describe("help data policy", () => {
  it("keeps the entry's detailed statistics disclosure in help", () => {
    const html = renderToStaticMarkup(<DataPrivacyHelp />);
    for (const text of [
      "資料使用說明集中於本頁",
      "選擇繼續遊玩表示同意正式場與休閒場保存",
      "正式場限四位真人",
      "每局耗時",
      "訪客與登入玩家可同場計算",
      "訪客不保存名稱或識別碼",
      "已驗證信箱（如有）",
      "信箱不向其他玩家公開",
      "每次登入以當次遊戲暱稱",
      "沒有個人統計或統計入口",
      "私人 Google 試算表",
      "每季人工清理",
      "不是自動定時清除",
      "未完成局不計分",
    ])
      expect(html).toContain(text);
    expect(html).not.toContain("入口會先顯示資料使用說明");
  });
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
      "本季統計另依已確認保存的合資格新場次計算",
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
