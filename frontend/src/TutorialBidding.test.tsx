import { beforeEach, describe, it, expect, vi } from "vitest";
import { act, create } from "react-test-renderer";
import TutorialBidding, {
  practiceBidDescription,
  tutorialStrains,
} from "./TutorialBidding";
import BidControls from "./BidControls";
import TutorialSpotlight from "./TutorialSpotlight";
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
    expect(button("離開教學").props.disabled).toBe(false);
    expect(tree.root.findByType(TutorialSpotlight).props.target).toBe(
      ".table-player.position-bottom",
    );
    act(() => button("下一步").props.onClick());
    const tutorialPanel = tree.root.findByType(GamePanel);
    const privilegeStep = tree.root.findByType(TutorialSpotlight);
    expect(privilegeStep.props.target).toBe(".privilege-labelled");
    expect(privilegeStep.props.allowInteraction).toBe(false);
    expect(privilegeStep.props.chapter).toBe(2);
    expect(tutorialPanel.props.room.game.privilege.usedByTeam).toEqual({
      NS: true,
      EW: false,
    });
    expect(tutorialPanel.props.room.game.privilege.canChoose).toBe(false);
    expect(JSON.stringify(tree.toJSON())).toContain("這裡不實際投票");
    expect(tree.root.findAllByProps({ "aria-label": "使用特權" })).toHaveLength(
      0,
    );
    expect(tree.root.findByType(BidControls).props.locked).toBe(true);
    act(() => button("下一步").props.onClick());
    expect(
      tree.root.findByType(GamePanel).props.room.game.privilege.usedByTeam,
    ).toBeNull();
    expect(tree.root.findByType(BidControls).props.locked).toBe(false);
    act(() => button("Pass · 不叫").props.onClick());
    expect(JSON.stringify(tree.toJSON())).toContain("與剛才的選擇無關");
    act(() => button("上一步").props.onClick());
    act(() => button("下一步").props.onClick());
    expect(begin).not.toHaveBeenCalled();
    expect(back).not.toHaveBeenCalled();
    act(() => button("繼續出牌示範").props.onClick());
    expect(begin).toHaveBeenCalledOnce();
    act(() => tree.unmount());
  });
});
