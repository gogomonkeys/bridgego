import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { act, create } from "react-test-renderer";
import TutorialSpotlight, { spotlightLayout } from "./TutorialSpotlight";
describe("tutorial spotlight", () => {
  it("places a phone popup below a compact target without covering it", () => {
    const r = spotlightLayout(
      { top: 230, left: 20, width: 350, height: 160 },
      390,
      844,
      180,
    );
    expect(r.dialog.top).toBeGreaterThan(390);
    expect(r.dialog.left + r.dialog.width).toBeLessThanOrEqual(390);
    expect(r.dialog.top + 180).toBeLessThanOrEqual(844);
  });
  it("uses the available area above a lower target", () => {
    const r = spotlightLayout(
      { top: 600, left: 16, width: 340, height: 100 },
      390,
      844,
      200,
    );
    expect(r.dialog.top + 200).toBeLessThan(600);
  });
  it("keeps the popup accessible if target has not rendered or is offscreen", () => {
    expect(spotlightLayout(null, 320, 568, 180).dialog.top).toBe(376);
    expect(
      spotlightLayout(
        { top: -500, left: 10, width: 200, height: 100 },
        320,
        568,
        180,
      ).hole,
    ).toBeNull();
  });
  it("renders safely without a document and navigation only invokes supplied callbacks", () => {
    const back = vi.fn(),
      next = vi.fn(),
      exit = vi.fn();
    const node = (
      <TutorialSpotlight
        target=".hand-panel"
        title="跟花"
        stepLabel="4 / 6"
        onPrevious={back}
        onNext={next}
        onExit={exit}
      >
        <p>跟著首引花色出牌。</p>
      </TutorialSpotlight>
    );
    expect(renderToStaticMarkup(node)).toContain('role="dialog"');
    let tree!: ReturnType<typeof create>;
    act(() => {
      tree = create(node);
    });
    const buttons = tree.root.findAllByType("button");
    act(() => buttons[0].props.onClick());
    act(() => buttons[1].props.onClick());
    act(() => buttons[2].props.onClick());
    expect(back).toHaveBeenCalledOnce();
    expect(next).toHaveBeenCalledOnce();
    expect(exit).toHaveBeenCalledOnce();
    act(() => tree.unmount());
  });
});
