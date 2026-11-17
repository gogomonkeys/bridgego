import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { TutorialInvitation } from "./TutorialGuide";
import TutorialSpotlight from "./TutorialSpotlight";
import TutorialBidding from "./TutorialBidding";

describe("forest tutorial presentation", () => {
  it("welcomes with local decorative rabbit and concise optional practice", () => {
    const html = renderToStaticMarkup(<TutorialInvitation busy={false} onStart={vi.fn()} onSkip={vi.fn()} />);
    expect(html).toContain("一起認識牌桌吧！");
    expect(html).toContain("animal-rabbit.webp");
    expect(html).toContain("不計時，隨時能回看或離開。");
    expect(html).toContain("稍後再說");
  });
  it.each(["認識牌桌", "認識叫牌", "試著叫牌", "王牌與目標", "跟著首家花色", "誰贏得這一墩？", "再跟一張牌", "缺花時可以切王", "你已掌握基本玩法"])("keeps fox and navigation on %s", (title) => {
    const html = renderToStaticMarkup(<TutorialSpotlight target=".own-hand-panel" title={title} progress="1 / 9" onPrevious={vi.fn()} onNext={vi.fn()} onExit={vi.fn()}><p>教學說明</p></TutorialSpotlight>);
    expect(html).toContain("animal-fox.webp");
    expect(html).toContain("小狐狸帶你學");
    expect(html).toContain(title);
    expect(html).toContain('aria-label="教學進度 1 / 9"');
    expect(html).toContain("上一步");
    expect(html).toContain("下一步");
    expect(html).toContain("離開教學");
  });
  it("keeps the actual shared table beneath the guide", () => {
    const html = renderToStaticMarkup(<TutorialBidding busy={false} onBegin={vi.fn()} onBack={vi.fn()} />);
    expect(html).toContain("table-player position-bottom");
    expect(html).toContain("tutorial-companion");
  });
});

it("reserves scroll only for an active target and removes it at completion", () => {
  const active = renderToStaticMarkup(<TutorialSpotlight target=".trick-positions" title="回看" onExit={vi.fn()}><p>說明</p></TutorialSpotlight>);
  const complete = renderToStaticMarkup(<TutorialSpotlight target="" complete title="完成" onExit={vi.fn()}><p>說明</p></TutorialSpotlight>);
  expect(active).toContain('class="tutorial-scroll-space"');
  expect(complete).not.toContain('class="tutorial-scroll-space"');
});
