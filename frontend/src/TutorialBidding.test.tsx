import { describe, it, expect, vi } from "vitest";
import { act, create } from "react-test-renderer";
import TutorialBidding, {
  practiceBidDescription,
  tutorialStrains,
} from "./TutorialBidding";
describe("local bidding lesson", () => {
  it("covers all 35 legal initial bids with correct target and trump", () => {
    const choices = [];
    for (let level = 1; level <= 7; level++)
      for (const strain of tutorialStrains) {
        const text = practiceBidDescription(level, strain);
        choices.push(text);
        expect(text).toContain(`目標 ${6 + level} 墩`);
        expect(text).toContain("若成為定約");
        expect(text).toContain(strain === "NO_TRUMP" ? "無王" : "為王");
      }
    expect(new Set(choices).size).toBe(35);
    expect(() => practiceBidDescription(8, "SPADE")).toThrow();
  });
  it("offers seven levels, five strains and pass without starting a real session", () => {
    const begin = vi.fn(),
      back = vi.fn();
    const tree = create(
      <TutorialBidding busy={false} onBegin={begin} onBack={back} />,
    );
    expect(tree.root.findAllByType("option")).toHaveLength(12);
    const pass = tree.root
      .findAllByType("button")
      .find((b) => b.children.join("") === "Pass")!;
    act(() => pass.props.onClick());
    expect(begin).not.toHaveBeenCalled();
    expect(back).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain(
      "這次不加叫，之後輪到你仍可再叫",
    );
    expect(JSON.stringify(tree.toJSON())).toContain("與剛才的選擇無關");
    act(() =>
      tree.root
        .findAllByType("button")
        .find((b) => b.children.join("") === "開始出牌教學")!
        .props.onClick(),
    );
    expect(begin).toHaveBeenCalledOnce();
    tree.unmount();
  });
  it("can leave or skip practice without calling begin", () => {
    const begin = vi.fn(),
      back = vi.fn();
    const tree = create(
      <TutorialBidding busy={false} onBegin={begin} onBack={back} />,
    );
    act(() =>
      tree.root
        .findAllByType("button")
        .find((b) => b.children.join("") === "略過叫牌練習")!
        .props.onClick(),
    );
    expect(JSON.stringify(tree.toJSON())).toContain("電腦 2 定約 1♠");
    act(() =>
      tree.root
        .findAllByType("button")
        .find((b) => b.children.join("") === "返回教學選擇")!
        .props.onClick(),
    );
    expect(begin).not.toHaveBeenCalled();
    expect(back).toHaveBeenCalledOnce();
    tree.unmount();
  });
});
