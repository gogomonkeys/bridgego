import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import DataPrivacyHelp from "./DataPrivacyHelp";
const help = () => renderToStaticMarkup(<DataPrivacyHelp />);
describe("concise current data policy", () => {
  it("preserves optional sign-in, fixed identity, consent and guest boundaries", () => {
    const html = help();
    for (const text of [
      "Google 登入為選用",
      "訪客也能完整遊玩",
      "同一 Google 帳號仍視為同一人",
      "已有暱稱就直接沿用",
      "不補登舊訪客場次",
      "選擇繼續遊玩表示同意正式場與休閒場保存",
      "訪客與登入玩家可同場計算",
      "每局耗時",
      "未完成局不計分",
      "結果未知或保存失敗不算入統計",
      "訪客只納入整體數字",
      "不保存名稱或識別碼",
      "沒有個人統計或統計入口",
      "可查看排行榜但不列名",
    ])
      expect(html).toContain(text);
  });
  it("identifies stored data and does not expose private Google identity", () => {
    const html = help();
    for (const text of [
      "私人 Google 試算表",
      "已驗證信箱（如有）",
      "不保存 Google 名稱、頭像、登入憑證或完整手牌",
      "信箱不向其他玩家公開",
      "教學牌局不納入任何統計或排行榜",
    ])
      expect(html).toContain(text);
  });
  it("retains metrics, formal-only automatic ranking and tie rules", () => {
    const html = help();
    for (const text of [
      "總場次＝完成場次＋中途離席＋無勝負場次",
      "完成場次包含平手",
      "不一定是你離席",
      "積分為已完成局結算加減分的累計",
      "中途結束前已完成的局",
      "判負仍可能有正積分",
      "勝場數 ÷（勝場數＋敗場數＋平手場數）",
      "無勝負場次不算入分母",
      "生涯累計跨季保留",
      "本季／生涯，只計正式場",
      "Google 登入玩家自動列入，不需另行報名",
      "訪客與休閒場不參與排名",
      "選擇登入表示同意這項使用",
      "不顯示信箱、Google 識別碼或內部帳號編號",
      "顯示前 100 名",
      "登入玩家另可查看自己的位置",
      "先按積分由高到低排序",
      "同分再比較未四捨五入的勝率",
      "並列名次",
      "積分可為負數",
      "不變更季度清理或生涯保存規則",
    ])
      expect(html).toContain(text);
  });
  it("explains friends visibility, retention and transient presence without an opt-in claim", () => {
    const html = help();
    for (const text of [
      "登入後即依近期連線判斷在線狀態",
      "不必先開啟「我的好友」",
      "好友碼與帳號關聯、申請、好友關係及防重送紀錄，跨季保留",
      "申請雙方可見暱稱與好友碼",
      "好友可見在線／遊戲中狀態及可加入的等待房",
      "拒絕申請或移除好友會刪除該關係，但保留防重送紀錄",
      "刪除帳號時移除該帳號的社交資料",
      "房邀資料包含邀請人暱稱、房間名稱與到期時間",
      "在線狀態與房邀只留伺服器記憶體",
      "房邀 60 秒有效",
      "沒有離線推播",
    ])
      expect(html).toContain(text);
  });
  it("preserves manual quarters, deletion contact and limits without guaranteeing erasure", () => {
    const html = help();
    for (const text of [
      'href="mailto:jody.yycc@gmail.com"',
      "Asia/Taipei",
      "1、4、7、10 月 1 日切季",
      "原始個人戰績每季人工清理",
      "自動季度清理尚未啟用",
      "季度清理保留帳號對應",
      "沒有保證不遺失的持久佇列",
      "不代表所有帳號刪除或季度清理已完成實機驗收",
      "先驗證帳號歸屬",
      "假名化識別雜湊",
      "不能重新註冊",
      "解除 Google 連結不是刪除資料",
      "同場他人資料、他人操作摘要",
      "版本歷史、備份、匯出檔",
      "不保證所有歷史副本完全清除",
    ])
      expect(html).toContain(text);
  });
  it("removes obsolete test-mode instructions and keeps details folded", () => {
    const html = help();
    for (const text of [
      "僅限測試模式的補充說明",
      "單人一場限定實驗",
      "Google 驗證（測試）",
      "若使用封閉測試模式",
      "SUPPRESSED",
    ])
      expect(html).not.toContain(text);
    expect(html.match(/<details>/g)).toHaveLength(2);
    expect(html).not.toContain("<details open");
  });
});
