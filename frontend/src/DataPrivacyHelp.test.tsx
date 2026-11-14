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
      "登入後填寫暱稱",
      "不會被本次輸入的名稱覆寫",
      "沒有個人統計或統計入口",
      "私人 Google 試算表",
      "每季人工清理",
      "不是自動定時清除",
      "未完成局不計分",
    ])
      expect(html).toContain(text);
    expect(html).not.toContain("入口會先顯示資料使用說明");
  });
  it("centralizes metrics and discloses automatic formal-only ranking", () => {
    const html = renderToStaticMarkup(<DataPrivacyHelp />);
    for (const text of [
      "總場次＝完成場次＋中途離席＋無勝負場次",
      "包含平手",
      "不代表查看統計的人就是離席者",
      "中途結束前已完成的局",
      "積分為已完成局結算加減分的累計",
      "判負仍可能有正積分",
      "勝場數 ÷（勝場數＋敗場數＋平手場數）",
      "無勝負場次不算入分母",
      "結果未知或保存失敗不算入統計",
      "訪客只納入整體數字",
      "不代表屆時已自動清理資料",
      "生涯累計跨季保留",
      "正式場排行榜",
      "本季／生涯，只計正式場",
      "先按積分由高到低排序",
      "同分再比較勝率",
      "並列名次",
      "前 100 名及自己的位置",
      "積分可為負數",
      "Google 登入玩家自動列入，不需另行報名",
      "訪客與休閒場不參與排名",
      "不顯示信箱、Google 識別碼或內部帳號編號",
      "選擇登入表示同意這項使用",
      "不變更季度清理或生涯保存規則",
      "同一 Google 帳號仍視為同一人",
      "已有暱稱就直接沿用",
    ])
      expect(html).toContain(text);
    expect(html).not.toContain("每次登入以當次遊戲暱稱");
    expect(html).not.toContain("登入不更換遊戲暱稱");
    expect(html).not.toContain("參與方式與公開同意流程尚未定案");
    expect(html).not.toContain("正式／休閒排行榜");
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
