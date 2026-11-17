import { it, expect } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import DataPrivacyHelp from "./DataPrivacyHelp";
it("explains tutorial choice storage and stats exclusion in help", () => {
  const html = renderToStaticMarkup(<DataPrivacyHelp />);
  expect(html).toContain("訪客僅記在本機");
  expect(html).toContain("教學牌局不納入任何統計或排行榜");
  expect(html).toContain("刪除範圍包含你的帳號對應、暱稱、信箱、教學提示偏好");
});
