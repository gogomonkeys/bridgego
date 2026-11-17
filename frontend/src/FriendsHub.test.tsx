import { readFileSync } from "node:fs";
import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import FriendsHub from "./FriendsHub";
import { FriendsError, type FriendsSnapshot } from "./friends";
import { gameRoom } from "./test-fixtures";
const mocks = vi.hoisted(() => ({
  request: vi.fn(),
  adopt: vi.fn(),
  portal: vi.fn((node: unknown) => node),
}));
vi.mock("react-dom", async (original) => ({
  ...(await original<typeof import("react-dom")>()),
  createPortal: mocks.portal,
}));
vi.mock("./friends", async (original) => ({
  ...(await original<typeof import("./friends")>()),
  friendsRequest: mocks.request,
}));
vi.mock("./client", () => ({ client: { acceptSocialRoom: mocks.adopt } }));
const sample: FriendsSnapshot = {
  enabled: true,
  friendCode: "ME",
  friends: [
    {
      friendCode: "FR",
      nickname: "Friend",
      presence: "ONLINE",
      joinable: true,
    },
  ],
  requests: [
    {
      requestId: "rq",
      friendCode: "RQ",
      nickname: "Pending",
      direction: "INCOMING",
    },
  ],
  invites: [],
  pendingCount: 1,
  tablePlayers: [
    { playerId: "bob", nickname: "Bob", eligible: true, relation: "NONE" },
  ],
};
let tree: ReactTestRenderer;
function button(label: string) {
  return tree.root
    .findAllByType("button")
    .find((b) => b.children.some((c) => c === label))!;
}
async function mount(room: ReturnType<typeof gameRoom> | null = null) {
  await act(async () => {
    tree = create(<FriendsHub token="test" room={room} />);
  });
}
beforeEach(() => {
  vi.useFakeTimers();
  mocks.request.mockReset();
  mocks.adopt.mockReset();
  mocks.portal.mockClear();
  mocks.request.mockResolvedValue(sample);
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal(
    "document",
    Object.assign(new EventTarget(), { visibilityState: "visible" }),
  );
});
afterEach(() => {
  if (tree) act(() => tree.unmount());
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe("friends panel", () => {
  it("hides guests and stops polling", async () => {
    mocks.request.mockResolvedValue({ ...sample, enabled: false });
    await mount();
    expect(tree.toJSON()).toBeNull();
    await act(async () => {
      vi.advanceTimersByTime(60000);
    });
    expect(mocks.request).toHaveBeenCalledTimes(1);
  });
  it("uses an explicit enable click and no unsolicited initialization", async () => {
    mocks.request.mockResolvedValue({ ...sample, friendCode: "" });
    await mount();
    expect(mocks.request).toHaveBeenCalledTimes(1);
    await act(async () => {
      button("我的好友").props.onClick();
    });
    expect(mocks.request).toHaveBeenCalledWith(
      "test",
      "/initialize",
      "POST",
      undefined,
    );
  });
  it("only shows requests in a user-opened dialog, never an unsolicited popup", async () => {
    await mount();
    expect(JSON.stringify(tree.toJSON())).not.toContain("Pending");
    await act(async () => {
      button("我的好友").props.onClick();
    });
    expect(JSON.stringify(tree.toJSON())).toContain("Pending");
    expect(tree.root.findAll((n) => n.props.role === "dialog")).toHaveLength(1);
    expect(button("接受")).toBeDefined();
  });
  it("offers waiting-room invitations without leaving that room", async () => {
    const room = gameRoom("WAITING");
    room.players = room.players.slice(0, 3);
    await mount(room);
    await act(async () => {
      button("我的好友").props.onClick();
    });
    expect(button("加入遊戲").props.disabled).toBe(true);
    await act(async () => {
      button("邀入房間").props.onClick();
    });
    expect(mocks.request).toHaveBeenCalledWith("test", "/invites", "POST", {
      friendCode: "FR",
      roomId: room.roomId,
    });
    expect(mocks.adopt).not.toHaveBeenCalled();
  });
  it("accepts a join snapshot without sending a second join", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    const room = gameRoom("WAITING");
    mocks.request.mockImplementation((_t, p) =>
      Promise.resolve(p?.endsWith("/join") ? room : sample),
    );
    await act(async () => {
      button("加入遊戲").props.onClick();
    });
    expect(mocks.adopt).toHaveBeenCalledWith("test", room);
    expect(
      mocks.request.mock.calls.filter((c) => c[1]?.endsWith("/join")),
    ).toHaveLength(1);
  });
  it("locks mutations after an uncertain request, even if read succeeds", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    mocks.request.mockImplementation((_t, p) =>
      p
        ? Promise.reject(new FriendsError("結果未確認", true))
        : Promise.resolve(sample),
    );
    await act(async () => {
      button("接受").props.onClick();
    });
    expect(button("接受").props.disabled).toBe(true);
    expect(JSON.stringify(tree.toJSON())).toContain("結果未確認");
  });
  it("does not send background heartbeats", async () => {
    await mount();
    Object.assign(document, { visibilityState: "hidden" });
    await act(async () => {
      vi.advanceTimersByTime(60000);
    });
    expect(mocks.request).toHaveBeenCalledTimes(1);
  });
  it("clears friends immediately while changing Google account", async () => {
    await mount();
    await act(async () => {
      window.dispatchEvent(new Event("bridge-account-changing"));
    });
    expect(tree.toJSON()).toBeNull();
  });
  it("ignores a stale read that began before accepting a request", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    let resolveRead!: (value: FriendsSnapshot) => void;
    mocks.request.mockImplementation((_token, path) =>
      path
        ? Promise.resolve({ ...sample, requests: [], pendingCount: 0 })
        : new Promise<FriendsSnapshot>((resolve) => {
            resolveRead = resolve;
          }),
    );
    await act(async () => {
      button("重新讀取").props.onClick();
    });
    await act(async () => {
      button("接受").props.onClick();
    });
    await act(async () => {
      resolveRead(sample);
    });
    expect(JSON.stringify(tree.toJSON())).not.toContain("Pending");
  });
  it("suppresses double-click requests while a mutation is pending", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    mocks.request.mockImplementation(() => new Promise(() => {}));
    const before = mocks.request.mock.calls.length;
    await act(async () => {
      const accept = button("接受");
      accept.props.onClick();
      accept.props.onClick();
    });
    expect(mocks.request.mock.calls.length).toBe(before + 1);
  });
  it("keeps an unknown mutation locked through focus, reread and same-token account verification", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    mocks.request.mockImplementation((_token, path) =>
      path
        ? Promise.reject(new FriendsError("結果未確認", true))
        : Promise.resolve(sample),
    );
    await act(async () => {
      button("接受").props.onClick();
    });
    await act(async () => {
      window.dispatchEvent(new Event("focus"));
      button("重新讀取").props.onClick();
    });
    expect(button("接受").props.disabled).toBe(true);
    await act(async () => {
      window.dispatchEvent(new Event("bridge-account-changing"));
    });
    expect(tree.toJSON()).toBeNull();
    await act(async () => {
      window.dispatchEvent(new Event("bridge-account-changed"));
    });
    await act(async () => {
      button("我的好友").props.onClick();
    });
    expect(button("接受").props.disabled).toBe(true);
    expect(JSON.stringify(tree.toJSON())).toContain("操作結果尚未確認");
    const app = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
    expect(app).toMatch(/<FriendsHub\s+key=\{state.session.token\}/);
  });
  it("retains uncertainty when account verification interrupts an in-flight mutation", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    let finish!: (value: FriendsSnapshot) => void;
    mocks.request.mockImplementation((_token, path) =>
      path
        ? new Promise<FriendsSnapshot>((resolve) => {
            finish = resolve;
          })
        : Promise.resolve(sample),
    );
    await act(async () => {
      button("接受").props.onClick();
    });
    await act(async () => {
      window.dispatchEvent(new Event("bridge-account-changing"));
      window.dispatchEvent(new Event("bridge-account-changed"));
    });
    await act(async () => {
      finish(sample);
    });
    await act(async () => {
      vi.advanceTimersByTime(20000);
    });
    await act(async () => {
      button("我的好友").props.onClick();
    });
    expect(button("接受").props.disabled).toBe(true);
  });
  it("closes the user-opened sheet without leaving the waiting room or changing data", async () => {
    await mount(gameRoom("WAITING"));
    expect(
      tree.root.findAll((node) => node.props.role === "dialog"),
    ).toHaveLength(0);
    await act(async () => {
      button("我的好友").props.onClick();
    });
    const dialog = tree.root.find((node) => node.props.role === "dialog");
    expect(dialog.props["aria-modal"]).toBe("true");
    expect(button("接受").props.className).toBe("primary");
    const calls = mocks.request.mock.calls.length;
    await act(async () => {
      button("關閉").props.onClick();
    });
    expect(
      tree.root.findAll((node) => node.props.role === "dialog"),
    ).toHaveLength(0);
    expect(mocks.request.mock.calls.length).toBe(calls);
    expect(mocks.adopt).not.toHaveBeenCalled();
  });
  it("keeps multiple room invites compact until the user opens the sheet", async () => {
    mocks.request.mockResolvedValue({
      ...sample,
      invites: [
        {
          inviteId: "one",
          inviterNickname: "First",
          roomName: "A",
          expiresAt: "2099-01-01T00:00:00Z",
        },
        {
          inviteId: "two",
          inviterNickname: "Second",
          roomName: "B",
          expiresAt: "2099-01-01T00:00:00Z",
        },
      ],
    });
    await mount();
    expect(JSON.stringify(tree.toJSON())).not.toContain("First");
    const invitationButton = tree.root
      .findAllByType("button")
      .find((node) => node.props.className?.includes("friends-invite-entry"))!;
    await act(async () => {
      invitationButton.props.onClick();
    });
    expect(JSON.stringify(tree.toJSON())).toContain("First");
    expect(JSON.stringify(tree.toJSON())).toContain("Second");
    expect(
      tree.root
        .findAllByType("button")
        .filter((node) => node.children.includes("加入")),
    ).toHaveLength(2);
  });
  it("shows an unknown result rather than permanent busy after network interruption", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    let finish!: (value: FriendsSnapshot) => void;
    mocks.request.mockImplementation((_token, path) =>
      path
        ? new Promise<FriendsSnapshot>((resolve) => {
            finish = resolve;
          })
        : Promise.resolve(sample),
    );
    await act(async () => {
      button("接受").props.onClick();
    });
    await act(async () => {
      tree.update(<FriendsHub token="test" room={null} disabled />);
    });
    expect(tree.toJSON()).toBeNull();
    await act(async () => {
      tree.update(<FriendsHub token="test" room={null} />);
    });
    await act(async () => {
      finish(sample);
    });
    await act(async () => {
      button("我的好友").props.onClick();
    });
    expect(button("接受").props.disabled).toBe(true);
    expect(button("重新讀取").props.disabled).toBe(false);
    expect(JSON.stringify(tree.toJSON())).toContain("操作結果尚未確認");
  });
  it("wires the actual opener into the focus lifecycle", () => {
    const source = readFileSync(
      new URL("./FriendsHub.tsx", import.meta.url),
      "utf8",
    );
    expect(source).toMatch(
      /mountFriendsDialog\(\s*dialogRef.current,\s*openerRef.current \?\? triggerRef.current/,
    );
  });
  it("moves only the entry portal while preserving the same-token unknown write lock", async () => {
    await mount();
    await act(async () => {
      button("我的好友").props.onClick();
    });
    mocks.request.mockImplementation((_token, path) =>
      path
        ? Promise.reject(new FriendsError("結果未確認", true))
        : Promise.resolve(sample),
    );
    await act(async () => {
      button("接受").props.onClick();
    });
    const target = {} as HTMLElement;
    await act(async () => {
      tree.update(
        <FriendsHub
          token="test"
          room={null}
          entryTarget={target}
          entryPlacement="lobby"
        />,
      );
    });
    expect(mocks.portal).toHaveBeenCalledWith(expect.anything(), target);
    expect(button("接受").props.disabled).toBe(true);
    await act(async () => {
      tree.update(
        <FriendsHub
          token="test"
          room={gameRoom("WAITING")}
          entryTarget={null}
        />,
      );
    });
    expect(button("接受").props.disabled).toBe(true);
    expect(button("我的好友")).toBeDefined();
    const app = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
    expect(app).toContain("entryTarget={friendsEntryTarget}");
    expect(app).toContain('className="lobby-friends-slot"');
    expect(app).toContain("ref={setFriendsEntryTarget}");
  });
  it("does not flash an old-location entry while the lobby mount target is pending", async () => {
    await act(async () => {
      tree = create(
        <FriendsHub
          token="test"
          room={null}
          entryPlacement="lobby"
          entryTarget={null}
        />,
      );
    });
    expect(tree.toJSON()).toBeNull();
    const target = {} as HTMLElement;
    await act(async () => {
      tree.update(
        <FriendsHub
          token="test"
          room={null}
          entryPlacement="lobby"
          entryTarget={target}
        />,
      );
    });
    expect(button("我的好友")).toBeDefined();
    expect(mocks.portal).toHaveBeenCalledWith(expect.anything(), target);
  });
  it("hides the help-page entry without unmounting the social session", async () => {
    await mount();
    await act(async () => {
      tree.update(
        <FriendsHub token="test" room={null} entryPlacement="hidden" />,
      );
    });
    expect(tree.toJSON()).toBeNull();
    await act(async () => {
      tree.update(<FriendsHub token="test" room={gameRoom("WAITING")} />);
    });
    expect(button("我的好友")).toBeDefined();
  });
});
