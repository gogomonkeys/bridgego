import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import WelcomeEntry from "./WelcomeEntry";
const session = { token: "local", playerId: "alice", displayName: "小白" };
describe("compact entry presentation", () => {
  it("keeps login unavailable until config confirms its purpose, with a guest alternative", () => {
    const html = renderToStaticMarkup(
      <WelcomeEntry session={null} busy={false} onEnter={vi.fn()} />,
    );
    expect(html).toContain('id="guest-name"');
    expect(html).toContain('class="primary" disabled="">正在確認 Google 服務…');
    expect(html).toContain(">以訪客身分遊玩</button>");
    expect(html).not.toContain("已登入");
    expect(html).not.toContain("<script");
    expect(html).not.toContain("<iframe");
  });
  it("restored sessions keep their nickname and require an explicit choice", () => {
    const enter = vi.fn();
    const html = renderToStaticMarkup(
      <WelcomeEntry session={session} busy={false} onEnter={enter} />,
    );
    expect(html).toContain("你好，小白");
    expect(html).not.toContain('id="guest-name"');
    expect(html).toContain("以訪客身分遊玩");
    expect(enter).not.toHaveBeenCalled();
  });
  it("links to help instead of putting long privacy/deletion text on entry", () => {
    const html = renderToStaticMarkup(
      <WelcomeEntry session={session} busy={false} onEnter={vi.fn()} />,
    );
    expect(html).toContain('<a href="#help">使用說明：資料保存與刪除</a>');
    expect(html).not.toContain("季度清理");
    expect(html).not.toContain("先驗證帳號歸屬");
    expect(html).not.toContain("版本歷史");
    expect(html).not.toContain("<details");
  });
});
