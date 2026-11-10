import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import GameHelp from "./GameHelp";

describe("concise game guide", () => {
  it("retains the current scoring and essential round rules", () => {
    const html = renderToStaticMarkup(<GameHelp />);
    for (const text of [
      "1、3 或 5 局",
      "房主開局",
      "本局耗時從首次發牌到最後一墩計分",
      "包含重發與斷線等待，不含最後展示及局間等待",
      "不設夢家",
      "共 13 墩",
      "少於 5 點",
      "恰好 5 點且沒有 A",
      "♣ → ♦ → ♥ → ♠ → NT",
      "有首引花色就必須跟牌",
      "L + 2 分",
      "不設扣分上限",
      "防守方得 1 分",
      "落敗 −1 分",
    ])
      expect(html).toContain(text);
  });
  it("folds secondary information without removing reconnection limits", () => {
    const html = renderToStaticMarkup(<GameHelp />);
    expect(html).toContain(
      "<details><summary>操作倒數、斷線與牌局紀錄</summary>",
    );
    expect(html).not.toContain("<details open");
    for (const text of [
      "等候室沒有斷線保留期",
      "保留手牌 10 秒",
      "已出牌不回溯",
      "最低合法牌",
      "伺服器重啟會清除訪客與房間資料",
    ])
      expect(html).toContain(text);
  });
});
