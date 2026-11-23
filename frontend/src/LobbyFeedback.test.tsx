import type { RoomSummary } from "./model";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, create, type ReactTestRenderer } from "react-test-renderer";
const mocks = vi.hoisted(() => ({
  state: {
    initialized: true,
    session: { token: "guest", playerId: "p", displayName: "Guest" },
    rooms: [] as RoomSummary[],
    room: null,
    busy: false,
    status: "online",
  },
  join: vi.fn(),
  create: vi.fn(),
  forget: vi.fn(),
  refresh: vi.fn(),
  request: vi.fn(),
  config: vi.fn(),
}));
vi.mock("./client", () => ({
  useClient: () => mocks.state,
  client: {
    initialize: vi.fn(),
    join: mocks.join,
    create: mocks.create,
    forget: mocks.forget,
    refresh: mocks.refresh,
  },
  getConnectionDiagnostics: () => [],
}));
vi.mock("./google-login", () => ({ accountRequest: mocks.request }));
vi.mock("./statistics", () => ({ readStatisticsConfig: mocks.config }));
vi.mock("./WelcomeEntry", () => ({
  default: ({ onEnter }: { onEnter: (t: string) => void }) => (
    <button onClick={() => onEnter("guest")}>Enter</button>
  ),
}));
vi.mock("./TutorialFlow", () => ({
  default: ({ onDone }: { onDone: () => void }) => (
    <button onClick={onDone}>Skip</button>
  ),
}));
vi.mock("./TutorialHelpEntry", () => ({
  default: () => <button>開始教學</button>,
}));
vi.mock("./PwaStatus", () => ({ default: () => null }));
vi.mock("./ScreenAwake", () => ({ default: () => null }));
vi.mock("./LeaderboardPanel", () => ({
  default: () => <div>Public ranking</div>,
}));
vi.mock("./StatisticsPanel", () => ({
  default: () => <div>Private statistics</div>,
}));
import App from "./App";
import FriendsHub from "./FriendsHub";
let tree: ReactTestRenderer;
const listeners = new Map<string, () => void>();
const click = async (label: string) => {
  await act(async () =>
    tree.root
      .findAllByType("button")
      .find((b) => b.children.join("") === label)!
      .props.onClick(),
  );
};
const text = () => JSON.stringify(tree.toJSON());
beforeEach(() => {
  vi.stubGlobal("window", {
    location: { hash: "" },
    addEventListener: (n: string, f: () => void) => listeners.set(n, f),
    removeEventListener: (n: string) => listeners.delete(n),
  });
  mocks.state.rooms = [];
  mocks.join.mockReset();
  mocks.create.mockReset();
  mocks.forget.mockReset();
  mocks.refresh.mockReset();
  mocks.state.busy = false;
  mocks.config.mockReset().mockResolvedValue({ enabled: true });
  mocks.request.mockReset().mockResolvedValue({ signedIn: false });
});
afterEach(() => {
  act(() => tree?.unmount());
  vi.unstubAllGlobals();
  listeners.clear();
});
async function enter() {
  await act(async () => {
    tree = create(<App />);
  });
  await click("Enter");
  await click("Skip");
}
describe("lobby feedback authorization", () => {
  it("uses one quiet lobby with two initially collapsed paths and grouped discovery", async () => {
    await enter();
    const createPanel = tree.root.findByProps({ className: "cabins-create" });
    const joinPanel = tree.root.findByProps({ className: "cabins-join" });
    expect(createPanel.type).toBe("details");
    expect(joinPanel.type).toBe("details");
    expect(createPanel.props.open).toBeUndefined();
    expect(joinPanel.props.open).toBeUndefined();
    expect(
      createPanel.findByProps({ className: "room-type-choice" }),
    ).toBeTruthy();
    expect(joinPanel.findByProps({ id: "room-code" })).toBeTruthy();
    expect(
      tree.root
        .findByProps({ className: "cabins-directory" })
        .findByProps({ id: "available-rooms-title" }),
    ).toBeTruthy();
    expect(
      tree.root.findByProps({ className: "cabins-profile" }).children,
    ).toEqual(["Guest"]);
    expect(tree.root.findByProps({ className: "cabins-fox" }).props.src).toBe(
      "/assets/forest/animal-fox.webp",
    );
    expect(
      tree.root.findByProps({ className: "lobby-friends-slot" }),
    ).toBeTruthy();
    expect(text()).not.toContain("四個座位，一起相聚。");
    expect(
      tree.root.findByProps({ className: "cabins-empty" }).children.join(""),
    ).toBe("目前沒有休閒場房間。");
    expect(tree.root.findByType(FriendsHub).props.entryPlacement).toBe("lobby");
    await click("返回身分選擇");
    expect(mocks.forget).toHaveBeenCalledTimes(1);
  });
  it("retains the default 3, other deal counts, shared type, update and full code join", async () => {
    await enter();
    expect(
      tree.root.findByProps({ type: "radio", name: "planned-deals", value: 3 })
        .props.checked,
    ).toBe(true);
    await click("正式場");
    expect(
      tree.root.findByProps({
        type: "radio",
        name: "room-type",
        value: "FORMAL",
      }).props.checked,
    ).toBe(true);
    expect(
      tree.root.findByProps({ className: "cabins-empty" }).children.join(""),
    ).toBe("目前沒有正式場房間。");
    await act(async () =>
      tree.root
        .findByProps({ type: "radio", name: "room-type", value: "CASUAL" })
        .props.onChange(),
    );
    expect(
      tree.root
        .findByProps({ className: "cabins-filters" })
        .findAllByType("button")[0].props["aria-pressed"],
    ).toBe(true);
    await act(async () =>
      tree.root
        .findByProps({ type: "radio", name: "planned-deals", value: 5 })
        .props.onChange(),
    );
    await act(async () =>
      tree.root
        .findByProps({ className: "primary create-room" })
        .props.onClick(),
    );
    expect(mocks.create).toHaveBeenCalledWith(5, "CASUAL");
    await act(async () =>
      tree.root
        .findByProps({ id: "room-code" })
        .props.onChange({ target: { value: "full-room-id" } }),
    );
    const preventDefault = vi.fn();
    await act(async () =>
      tree.root
        .findByProps({ className: "cabins-join" })
        .findByType("form")
        .props.onSubmit({ preventDefault }),
    );
    expect(preventDefault).toHaveBeenCalledOnce();
    expect(mocks.join).toHaveBeenCalledWith("full-room-id");
    await act(async () =>
      tree.root.findByProps({ className: "cabins-refresh" }).props.onClick(),
    );
    expect(mocks.refresh).toHaveBeenCalledOnce();
  });
  it("shows host names and distinct short labels, but sends full ID when joining", async () => {
    const id = "12345678-1111-1111-1111-111111111111";
    const other = "12345678-2222-2222-2222-222222222222";
    mocks.state.rooms = [id, other].map((roomId) => ({
      roomId,
      hostDisplayName: "同名 <script>",
      capacity: 4,
      playerCount: 1,
      state: "WAITING",
      phase: "WAITING",
      joinable: true,
    }));
    await enter();
    expect(text()).toContain("同名 <script>的房間");
    expect(text()).toContain("12345678-1");
    expect(text()).toContain("12345678-2");
    const buttons = tree.root
      .findAllByType("button")
      .filter((b) => b.props.className === "cabins-room-join");
    await act(async () => buttons[0].props.onClick());
    expect(mocks.join).toHaveBeenCalledWith(id);
  });

  it("offers guests ranking but no private statistics, and opens only ranking", async () => {
    await enter();
    expect(text()).toContain("排行榜");
    expect(text()).not.toContain("戰績統計");
    await click("排行榜");
    expect(text()).toContain("Public ranking");
    expect(tree.root.findByType(FriendsHub).props.entryPlacement).toBe(
      "hidden",
    );
    expect(text()).not.toContain("Private statistics");
  });
  it("keeps public ranking during failed private access checks and offers explicit recovery", async () => {
    mocks.request.mockRejectedValue(new Error("offline"));
    await enter();
    expect(text()).toContain("排行榜");
    expect(text()).toContain("統計入口暫時無法確認");
    mocks.request.mockResolvedValue({ signedIn: true });
    await click("重試");
    expect(text()).toContain("戰績統計");
    expect(text()).not.toContain("統計入口暫時無法確認");
  });
  it("invalidates private access on account switching and discards late prior responses", async () => {
    let resolve!: (v: unknown) => void;
    mocks.request.mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    // Do not await the unresolved request: React effect schedules it without blocking act.
    await enter();
    expect(text()).not.toContain("戰績統計");
    await act(async () => listeners.get("bridge-account-changing")!());
    await act(async () => resolve({ signedIn: true }));
    expect(text()).toContain("正在切換身分");
    expect(text()).not.toContain("Private statistics");
  });
});
