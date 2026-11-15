import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { act, create } from "react-test-renderer";
import TutorialGuide, {
  TutorialInvitation,
  type TutorialLesson,
} from "./TutorialGuide";
describe("isolated tutorial presentation", () => {
  it("offers a choice without persisting identity or starting automatically", () => {
    const start = vi.fn(),
      skip = vi.fn();
    const tree = create(
      <TutorialInvitation busy={false} onStart={start} onSkip={skip} />,
    );
    expect(start).not.toHaveBeenCalled();
    expect(skip).not.toHaveBeenCalled();
    const buttons = tree.root.findAllByType("button");
    act(() => buttons[1].props.onClick());
    expect(skip).toHaveBeenCalledOnce();
    expect(start).not.toHaveBeenCalled();
    act(() => buttons[0].props.onClick());
    expect(start).toHaveBeenCalledOnce();
    tree.unmount();
  });
  it("disables both navigation actions while caller is pending", () => {
    const html = renderToStaticMarkup(
      <TutorialInvitation busy onStart={vi.fn()} onSkip={vi.fn()} />,
    );
    expect(html.match(/disabled=""/g)).toHaveLength(2);
  });
  it.each<TutorialLesson>([
    "FOLLOW_SUIT",
    "WIN_TRICK",
    "TRUMP_WHEN_VOID",
    "FREE_PLAY",
  ])("renders compact accessible %s guidance", (lesson) => {
    const html = renderToStaticMarkup(<TutorialGuide lesson={lesson} />);
    expect(html).toContain('aria-live="polite"');
    expect(html).not.toContain("<button");
    expect(html).not.toContain("積分");
  });
  it("does not falsely require trumping when void", () => {
    const html = renderToStaticMarkup(
      <TutorialGuide lesson="TRUMP_WHEN_VOID" />,
    );
    expect(html).toContain("也可以墊其他花色");
  });
});
