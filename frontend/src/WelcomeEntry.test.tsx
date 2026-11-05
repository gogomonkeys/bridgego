import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import WelcomeEntry from "./WelcomeEntry";
const session = { token: "local", playerId: "alice", displayName: "小白" };
describe("compact entry presentation", () => {
  it("offers accurately named verification and a guest alternative", () => {
    const html = renderToStaticMarkup(
      <WelcomeEntry session={null} busy={false} onEnter={vi.fn()} />,
    );
    expect(html).toContain('id="guest-name"');
    expect(html).toContain(">Google 驗證</button>");
    expect(html).toContain(">以訪客身分遊玩</button>");
    expect(html).toContain("目前僅驗證身分，不建立帳號連結或保存對局");
    expect(html).not.toContain("使用 Google 登入");
    expect(html).not.toContain("<script");
    expect(html).not.toContain("<iframe");
  });
  it("restored sessions keep their nickname and still require an explicit choice", () => {
    const enter = vi.fn();
    const html = renderToStaticMarkup(
      <WelcomeEntry session={session} busy={false} onEnter={enter} />,
    );
    expect(html).toContain("你好，小白");
    expect(html).not.toContain('id="guest-name"');
    expect(html).toContain("以訪客身分遊玩");
    expect(enter).not.toHaveBeenCalled();
  });
  it("hides long privacy policy in closed details instead of leading with collection", () => {
    const html = renderToStaticMarkup(
      <WelcomeEntry session={session} busy={false} onEnter={vi.fn()} />,
    );
    expect(html).toContain(
      '<details class="entry-details"><summary>隱私與資料使用說明</summary>',
    );
    expect(html).not.toContain("<details open");
    expect(html.indexOf("Google 驗證</button>")).toBeLessThan(
      html.indexOf("封閉測試保存與刪除政策"),
    );
  });
});
