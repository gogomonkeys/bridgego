import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import MatchPersistence from "./MatchPersistence";
it("describes consented four-human mixed guest games without asserting all guest matches are excluded", () => {
  const html = renderToStaticMarkup(
    <MatchPersistence roomId="fixture-room" gameId="fixture-game" />,
  );
  for (const text of [
    "所有玩家先同意",
    "結算時為四位不同真人",
    "包含訪客",
    "不保存訪客名稱或識別碼",
    "統計未開放時沿用原模式",
    "是否保存仍以本場狀態為準",
    "保存採盡力而為",
  ])
    expect(html).toContain(text);
  expect(html).not.toContain("只要有訪客，整場不保存");
});
