import { afterEach, describe, expect, it, vi } from "vitest";
import { act, create } from "react-test-renderer";
import type { ReactNode } from "react";
import TutorialSpotlight, {
  sameSpotlightLayout,
  spotlightLayout,
  spotlightBounds,
} from "./TutorialSpotlight";
vi.mock("react-dom", () => ({ createPortal: (content: ReactNode) => content }));
afterEach(() => vi.unstubAllGlobals());
describe("tutorial target position tracking", () => {
  it("distinguishes movement from unchanged geometry without relying on resize", () => {
    const a = spotlightLayout(
      { top: 100, left: 20, width: 80, height: 70, radius: 12 },
      390,
      844,
      190,
    );
    const moved = spotlightLayout(
      { top: 130, left: 20, width: 80, height: 70, radius: 12 },
      390,
      844,
      190,
    );
    expect(sameSpotlightLayout(a, a)).toBe(true);
    expect(sameSpotlightLayout(a, moved)).toBe(false);
  });
  it("follows parent-driven movement and cancels tracking on exit", () => {
    let top = 100,
      id = 0;
    const frames = new Map<number, FrameRequestCallback>();
    const target = {
      getBoundingClientRect: () => ({ top, left: 20, width: 80, height: 70 }),
      scrollIntoView: vi.fn(),
    };
    const add = vi.fn(),
      remove = vi.fn();
    vi.stubGlobal("window", {
      innerWidth: 390,
      innerHeight: 844,
      requestAnimationFrame: (cb: FrameRequestCallback) => {
        frames.set(++id, cb);
        return id;
      },
      cancelAnimationFrame: (key: number) => frames.delete(key),
      addEventListener: add,
      removeEventListener: remove,
      getComputedStyle: () => ({ borderTopLeftRadius: "12px" }),
      visualViewport: { addEventListener: add, removeEventListener: remove },
    });
    vi.stubGlobal("document", {
      body: {},
      querySelector: () => target,
      addEventListener: add,
      removeEventListener: remove,
    });
    vi.stubGlobal("ResizeObserver", undefined);
    let tree!: ReturnType<typeof create>;
    act(() => {
      tree = create(
        <TutorialSpotlight
          target=".table-player"
          title="認識牌桌"
          onExit={vi.fn()}
        >
          <p>說明</p>
        </TutorialSpotlight>,
        {
          createNodeMock: () => ({
            getBoundingClientRect: () => ({ height: 190 }),
            focus: vi.fn(),
            querySelectorAll: () => [],
          }),
        },
      );
    });
    const ring = () =>
      tree.root.findByProps({ className: "tutorial-spotlight-ring" }).props
        .style;
    expect(ring().top).toBe(100);
    // Parent layout moves the same-size target; no resize/scroll event occurs.
    top = 160;
    act(() => {
      const queued = [...frames.values()];
      frames.clear();
      queued.forEach((cb) => cb(16));
    });
    expect(ring().top).toBe(160);
    expect(ring().height).toBe(70);
    expect(ring().borderRadius).toBe(12);
    expect(target.scrollIntoView).toHaveBeenCalledTimes(1);
    act(() => tree.unmount());
    expect(frames.size).toBe(0);
  });
});

it("includes cards and badges extending beyond the trick container with border clearance", () => {
  expect(
    spotlightBounds(
      { left: 100, top: 200, width: 120, height: 160 },
      [
        { left: 94, top: 220, width: 40, height: 60 },
        { left: 210, top: 240, width: 40, height: 60 },
        { left: 100, top: 192, width: 20, height: 18 },
      ],
      8,
    ),
  ).toEqual({ left: 86, top: 184, width: 172, height: 184, radius: undefined });
});
