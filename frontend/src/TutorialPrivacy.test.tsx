import { it, expect } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import DataPrivacyHelp from "./DataPrivacyHelp";
it("explains tutorial choice storage and stats exclusion in help", () => {
  const html = renderToStaticMarkup(<DataPrivacyHelp />);
  expect(html).toContain("訪客僅記在本機");
  expect(html).toContain("教學牌局不納入任何統計或排行榜");
  expect(html).toContain("申請刪除帳號時一併刪除暱稱、信箱與教學提示偏好");
});
