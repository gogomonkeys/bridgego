import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, create, type ReactTestRenderer } from "react-test-renderer";
import TutorialFlow from "./TutorialFlow";
import { accountRequest } from "./google-login";
import type { TutorialSnapshot } from "./tutorial";
vi.mock("./google-login", () => ({ accountRequest: vi.fn() }));
const request = vi.mocked(accountRequest);
const game: TutorialSnapshot = {
  sessionId: "practice",
  revision: 0,
  lesson: "FOLLOW_SUIT",
  hand: [
    { suit: "CLUB", rank: 2 },
    { suit: "SPADE", rank: 2 },
  ],
  legalCards: [{ suit: "CLUB", rank: 2 }],
  currentTrick: [],
  completedTricks: [],
};
let tree: ReactTestRenderer;
const flush = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
};
const button = (text: string) =>
  tree.root.findAllByType("button").find((b) => b.children.join("") === text)!;
beforeEach(() => {
  request.mockReset();
  vi.stubGlobal("localStorage", {
    getItem: vi.fn().mockReturnValue(null),
    setItem: vi.fn(),
  });
});
async function mount(
  signedIn = false,
  snapshot: TutorialSnapshot | null = null,
  force = false,
) {
  request.mockResolvedValueOnce({
    enabled: true,
    signedIn,
    offered: signedIn,
    game: snapshot,
  });
  const done = vi.fn();
  await act(async () => {
    tree = create(<TutorialFlow token="token" force={force} onDone={done} />);
  });
  await flush();
  return done;
}
describe("tutorial flow", () => {
  it.each([false, true])(
    "disabled tutorial skips invitation without acknowledging guest preference (force=%s)",
    async (force) => {
      request.mockResolvedValueOnce({
        enabled: false,
        signedIn: false,
        offered: false,
        game: null,
      });
      const done = vi.fn(),
        availability = vi.fn();
      await act(async () => {
        tree = create(
          <TutorialFlow
            token="token"
            force={force}
            onDone={done}
            onAvailability={availability}
          />,
        );
      });
      await flush();
      expect(done).toHaveBeenCalledOnce();
      expect(availability).toHaveBeenCalledWith(false);
      expect(button("開始教學")).toBeUndefined();
      expect(localStorage.setItem).not.toHaveBeenCalled();
      expect(request.mock.calls.every((c) => c[1] === "GET")).toBe(true);
    },
  );
  it("disabled tutorial still lets an existing session play and exit", async () => {
    request.mockResolvedValueOnce({
      enabled: false,
      signedIn: false,
      offered: false,
      game,
    });
    const done = vi.fn();
    await act(async () => {
      tree = create(<TutorialFlow token="token" onDone={done} />);
    });
    await flush();
    expect(done).not.toHaveBeenCalled();
    act(() => tree.root.findByProps({ "aria-label": "♣2" }).props.onClick());
    request.mockResolvedValueOnce({ ...game, revision: 1 });
    await act(async () => button("出 ♣2").props.onClick());
    request.mockResolvedValueOnce({ exited: true });
    await act(async () => button("離開教學").props.onClick());
    expect(done).toHaveBeenCalledOnce();
    expect(localStorage.setItem).not.toHaveBeenCalled();
  });
  it("a rollback during start is recovered read-only then returns to lobby", async () => {
    const done = await mount();
    request.mockRejectedValueOnce(new Error("TUTORIAL_DISABLED"));
    act(() => button("開始教學").props.onClick());
    act(() => button("Pass").props.onClick());
    await act(async () => button("開始出牌教學").props.onClick());
    request.mockResolvedValueOnce({
      enabled: false,
      signedIn: false,
      offered: false,
      game: null,
    });
    await act(async () => button("讀取教學狀態").props.onClick());
    expect(done).toHaveBeenCalledOnce();
    expect(localStorage.setItem).not.toHaveBeenCalled();
    expect(request.mock.calls.filter((c) => c[1] === "POST")).toHaveLength(1);
  });
  it("offers unseen guests a choice and remembers skip on device", async () => {
    const done = await mount();
    act(() => button("先進大廳").props.onClick());
    expect(localStorage.setItem).toHaveBeenCalledWith(
      "bridge-tutorial-offered-v1",
      "yes",
    );
    expect(done).toHaveBeenCalledOnce();
    expect(request).toHaveBeenCalledTimes(1);
  });
  it("does not pretend to remember a Google account on this device", async () => {
    const done = await mount(true);
    expect(done).toHaveBeenCalledOnce();
    expect(localStorage.setItem).not.toHaveBeenCalled();
  });
  it("allows Google manual entry from help", async () => {
    const done = await mount(true, null, true);
    expect(done).not.toHaveBeenCalled();
    expect(button("開始教學")).toBeTruthy();
  });
  it("persists unseen Google skip through the server, never device storage", async () => {
    request.mockResolvedValueOnce({
      enabled: true,
      signedIn: true,
      offered: false,
      game: null,
    });
    const done = vi.fn();
    await act(async () => {
      tree = create(<TutorialFlow token="token" onDone={done} />);
    });
    await flush();
    request.mockResolvedValueOnce({
      enabled: true,
      signedIn: true,
      offered: true,
      game: null,
    });
    await act(async () => button("先進大廳").props.onClick());
    expect(request).toHaveBeenLastCalledWith(
      "/tutorial/choice",
      "POST",
      { start: false },
      "token",
    );
    expect(done).toHaveBeenCalledOnce();
    expect(localStorage.setItem).not.toHaveBeenCalled();
  });
  it("does not classify failed account lookup as guest", async () => {
    request.mockRejectedValueOnce(new Error("offline"));
    await act(async () => {
      tree = create(<TutorialFlow token="token" onDone={vi.fn()} />);
    });
    await flush();
    expect(tree.root.findAllByType("h2")).toHaveLength(0);
    expect(localStorage.setItem).not.toHaveBeenCalled();
  });
  it("prevents illegal selection and submits only selected legal card with revision", async () => {
    await mount(false, game);
    const cards = tree.root.findAllByProps({ "aria-label": "♠2" });
    expect(cards[0].props.disabled).toBe(true);
    act(() => tree.root.findByProps({ "aria-label": "♣2" }).props.onClick());
    request.mockResolvedValueOnce({ ...game, revision: 1 });
    await act(async () => button("出 ♣2").props.onClick());
    expect(request).toHaveBeenLastCalledWith(
      "/tutorial/play",
      "POST",
      {
        sessionId: "practice",
        expectedRevision: 0,
        card: { suit: "CLUB", rank: 2 },
      },
      "token",
    );
  });
  it("does not resend unknown play and only reads state for recovery", async () => {
    await mount(false, game);
    act(() => tree.root.findByProps({ "aria-label": "♣2" }).props.onClick());
    request.mockRejectedValueOnce(new Error("timeout"));
    await act(async () => button("出 ♣2").props.onClick());
    expect(button("讀取教學狀態")).toBeTruthy();
    request.mockResolvedValueOnce({
      enabled: true,
      signedIn: false,
      offered: false,
      game: { ...game, revision: 1 },
    });
    await act(async () => button("讀取教學狀態").props.onClick());
    await flush();
    expect(request.mock.calls.filter((c) => c[1] === "POST")).toHaveLength(1);
  });
  it("returns to lobby after lost exit response is confirmed absent", async () => {
    const done = await mount(false, game);
    request.mockRejectedValueOnce(new Error("lost response"));
    await act(async () => button("離開教學").props.onClick());
    expect(done).not.toHaveBeenCalled();
    request.mockResolvedValueOnce({
      enabled: true,
      signedIn: false,
      offered: false,
      game: null,
    });
    await act(async () => button("讀取教學狀態").props.onClick());
    expect(done).toHaveBeenCalledOnce();
    expect(request.mock.calls.filter((c) => c[1] === "POST")).toHaveLength(1);
  });
  it("returns to lobby after Google skip is confirmed without repeating write", async () => {
    request.mockResolvedValueOnce({
      enabled: true,
      signedIn: true,
      offered: false,
      game: null,
    });
    const done = vi.fn();
    await act(async () => {
      tree = create(<TutorialFlow token="token" onDone={done} />);
    });
    await flush();
    request.mockRejectedValueOnce(new Error("lost response"));
    await act(async () => button("先進大廳").props.onClick());
    request.mockRejectedValueOnce(new Error("read unavailable"));
    await act(async () => button("讀取教學狀態").props.onClick());
    expect(done).not.toHaveBeenCalled();
    request.mockResolvedValueOnce({
      enabled: true,
      signedIn: true,
      offered: true,
      game: null,
    });
    await act(async () => button("讀取教學狀態").props.onClick());
    expect(done).toHaveBeenCalledOnce();
    expect(request.mock.calls.filter((c) => c[1] === "POST")).toHaveLength(1);
  });
  it("keeps all four previous cards visible alongside the new lead", async () => {
    await mount(false, {
      ...game,
      lesson: "WIN_TRICK",
      currentTrick: [
        { seat: 1, card: { suit: "HEART", rank: 14 }, source: "BOT" },
      ],
      completedTricks: [
        {
          number: 1,
          leaderSeat: 1,
          winnerSeat: 1,
          plays: [
            { seat: 1, card: { suit: "CLUB", rank: 14 }, source: "BOT" },
            { seat: 0, card: { suit: "CLUB", rank: 2 }, source: "MANUAL" },
            { seat: 3, card: { suit: "CLUB", rank: 5 }, source: "BOT" },
            { seat: 2, card: { suit: "CLUB", rank: 4 }, source: "BOT" },
          ],
        },
      ],
    });
    const previous = tree.root.findByProps({ "aria-label": "上一墩出牌" });
    expect(
      previous.findAllByProps({ className: "tutorial-public-slot" }),
    ).toHaveLength(4);
    expect(
      previous.findAllByType("small").map((x) => x.children.join("")),
    ).toContain("電腦1 · 勝");
    expect(
      tree.root
        .findByProps({ "aria-label": "本墩出牌" })
        .findAllByProps({ className: "tutorial-public-slot" }),
    ).toHaveLength(1);
  });
  it("runs bidding locally then explicitly starts the independent fixed deal", async () => {
    await mount();
    act(() => button("開始教學").props.onClick());
    act(() =>
      tree.root
        .findByProps({ "aria-label": "叫牌階數" })
        .props.onChange({ target: { value: "7" } }),
    );
    act(() =>
      tree.root
        .findByProps({ "aria-label": "叫牌花色" })
        .props.onChange({ target: { value: "NO_TRUMP" } }),
    );
    act(() => button("確認叫牌").props.onClick());
    expect(JSON.stringify(tree.toJSON())).toContain("目標 13 墩，無王");
    expect(JSON.stringify(tree.toJSON())).toContain("與剛才的選擇無關");
    expect(request.mock.calls.filter((c) => c[1] === "POST")).toHaveLength(0);
    expect(localStorage.setItem).not.toHaveBeenCalled();
    request.mockResolvedValueOnce(game);
    await act(async () => button("開始出牌教學").props.onClick());
    expect(request).toHaveBeenLastCalledWith("/tutorial", "POST", {}, "token");
    expect(button("先選一張牌")).toBeTruthy();
    expect(tree.root.findAllByProps({ "aria-label": "叫牌練習" })).toHaveLength(
      0,
    );
  });
  it("blocks duplicate start while pending", async () => {
    await mount();
    request.mockReturnValueOnce(new Promise(() => {}));
    act(() => button("開始教學").props.onClick());
    act(() => button("確認叫牌").props.onClick());
    const start = button("開始出牌教學");
    act(() => {
      start.props.onClick();
      start.props.onClick();
    });
    expect(request.mock.calls.filter((c) => c[0] === "/tutorial")).toHaveLength(
      1,
    );
  });
  it("exits only after server confirms and does not submit another card", async () => {
    const done = await mount(false, {
      ...game,
      lesson: "COMPLETE",
      hand: [],
      legalCards: [],
    });
    expect(button("先選一張牌")).toBeUndefined();
    request.mockResolvedValueOnce({ exited: true });
    await act(async () => button("進入大廳").props.onClick());
    expect(request).toHaveBeenLastCalledWith(
      "/tutorial/exit",
      "POST",
      { sessionId: "practice" },
      "token",
    );
    expect(done).toHaveBeenCalledOnce();
  });
  it("ignores responses after leaving", async () => {
    let resolve!: (value: unknown) => void;
    request.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const done = vi.fn();
    await act(async () => {
      tree = create(<TutorialFlow token="token" onDone={done} />);
    });
    act(() => tree.unmount());
    resolve({ signedIn: true });
    await flush();
    expect(done).not.toHaveBeenCalled();
  });
});
