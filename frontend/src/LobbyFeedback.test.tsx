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
  request: vi.fn(),
  config: vi.fn(),
}));
vi.mock("./client", () => ({
  useClient: () => mocks.state,
  client: { initialize: vi.fn(), join: mocks.join },
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
    expect(text()).toContain("同名 <script>的牌桌");
    expect(text()).toContain("12345678-1");
    expect(text()).toContain("12345678-2");
    const buttons = tree.root
      .findAllByType("button")
      .filter((b) => b.children.join("") === "入座 →");
    await act(async () => buttons[0].props.onClick());
    expect(mocks.join).toHaveBeenCalledWith(id);
  });

  it("offers guests ranking but no private statistics, and opens only ranking", async () => {
    await enter();
    expect(text()).toContain("排行榜");
    expect(text()).not.toContain("戰績統計");
    const ranking = tree.root
      .findAllByType("button")
      .find((b) =>
        b.findAllByType("span").some((s) => s.children.join("") === "排行榜"),
      )!;
    await act(async () => ranking.props.onClick());
    expect(text()).toContain("Public ranking");
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
