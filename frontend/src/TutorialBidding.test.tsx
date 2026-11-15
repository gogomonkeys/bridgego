import { beforeEach, describe, it, expect, vi } from "vitest";
import { act, create } from "react-test-renderer";
import TutorialBidding, {
  practiceBidDescription,
  tutorialStrains,
} from "./TutorialBidding";
import BidControls from "./BidControls";
import GamePanel from "./GamePanel";
beforeEach(() =>
  vi.stubGlobal("window", {
    matchMedia: () => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  }),
);
describe("shared bidding lesson", () => {
  it("covers all 35 legal initial bids", () => {
    const choices = [];
    for (let level = 1; level <= 7; level++)
      for (const strain of tutorialStrains) {
        const text = practiceBidDescription(level, strain);
        choices.push(text);
        expect(text).toContain(`目標 ${6 + level} 墩`);
        expect(text).toContain("若成為定約");
      }
    expect(new Set(choices).size).toBe(35);
    expect(() => practiceBidDescription(8, "SPADE")).toThrow();
  });
  it("shares actual table and controls; navigation is local and exit remains usable", () => {
    const begin = vi.fn(),
      back = vi.fn();
    let tree!: ReturnType<typeof create>;
    act(() => {
      tree = create(
        <TutorialBidding busy={false} onBegin={begin} onBack={back} />,
      );
    });
    const button = (name: string) =>
      tree.root
        .findAllByType("button")
        .find((b) => b.children.join("") === name)!;
    expect(tree.root.findByType(GamePanel)).toBeTruthy();
    expect(tree.root.findByType(BidControls).props.locked).toBe(true);
    expect(button("返回教學選擇").props.disabled).toBe(false);
    act(() => button("下一步").props.onClick());
    act(() => button("下一步").props.onClick());
    expect(tree.root.findByType(BidControls).props.locked).toBe(false);
    act(() => button("Pass · 不叫").props.onClick());
    expect(JSON.stringify(tree.toJSON())).toContain("與剛才的選擇無關");
    act(() => button("上一步").props.onClick());
    act(() => button("下一步").props.onClick());
    expect(begin).not.toHaveBeenCalled();
    expect(back).not.toHaveBeenCalled();
    act(() => button("開始出牌教學").props.onClick());
    expect(begin).toHaveBeenCalledOnce();
    act(() => tree.unmount());
  });
});
