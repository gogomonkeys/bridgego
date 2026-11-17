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
  it("does not ask same-room invitees to leave or send a join request", async () => {
    const room = { ...gameRoom(), phase: "WAITING" as const };
    mocks.request.mockResolvedValue({ ...sample, invites: [{ inviteId: "same", inviterNickname: "Friend", roomName: "Table", roomId: room.roomId, expiresAt: "2099-01-01T00:00:00Z" }] });
    await mount(room);
    await act(async () => button("我的好友").props.onClick());
    expect(JSON.stringify(tree.toJSON())).toContain("你已在此房間，無需重新加入");
    expect(JSON.stringify(tree.toJSON())).not.toContain("請先離開目前房間");
    expect(button("已在房間").props.disabled).toBe(true);
    expect(mocks.adopt).not.toHaveBeenCalled();
    expect(mocks.request.mock.calls.every(call => call.length === 1)).toBe(true);
  });
  it("keeps the leave-first restriction for a different room", async () => {
    mocks.request.mockResolvedValue({ ...sample, invites: [{ inviteId: "other", inviterNickname: "Friend", roomName: "Table", roomId: "different", expiresAt: "2099-01-01T00:00:00Z" }] });
    await mount({ ...gameRoom(), phase: "WAITING" });
    await act(async () => button("我的好友").props.onClick());
    expect(JSON.stringify(tree.toJSON())).toContain("請先離開目前房間");
    expect(button("加入").props.disabled).toBe(true);
  });
  it("disables sending another invitation to a friend already in the room", async () => {
    mocks.request.mockResolvedValue({ ...sample, friends: [{ ...sample.friends[0], sameRoom: true }] });
    const room = { ...gameRoom(), phase: "WAITING" as const, players: gameRoom().players.slice(0, 2) };
    await mount(room);
    await act(async () => button("我的好友").props.onClick());
    expect(button("已在房間").props.disabled).toBe(true);
    expect(button("邀入房間")).toBeUndefined();
  });
  it("places reload with the list, removes only the drawer help link, and exposes selectable code", async () => {
    await mount();
    await act(async () => button("我的好友").props.onClick());
    expect(tree.root.findByProps({ className: "friends-list-heading" }).findAllByType("button")[0].children).toContain("重新讀取");
    expect(JSON.stringify(tree.toJSON())).not.toContain("好友使用說明");
    const code = tree.root.findByProps({ id: "my-friend-code" });
    expect(code.props.readOnly).toBe(true);
    expect(code.props.value).toBe("ME");
    const select = vi.fn();
    code.props.onFocus({ currentTarget: { select } });
    expect(select).toHaveBeenCalledOnce();
  });
  it("confirms successful copy without changing server data", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    await mount();
    await act(async () => button("我的好友").props.onClick());
    const count = mocks.request.mock.calls.length;
    await act(async () => button("複製").props.onClick());
    expect(writeText).toHaveBeenCalledWith("ME");
    expect(tree.root.findAllByProps({ role: "status" }).some(n => n.children.includes("好友碼已複製"))).toBe(true);
    expect(mocks.request).toHaveBeenCalledTimes(count);
  });
  it.each(["unavailable", "denied"])("offers manual copy when clipboard is %s", async (mode) => {
    vi.stubGlobal("navigator", mode === "unavailable" ? {} : { clipboard: { writeText: vi.fn().mockRejectedValue(new Error("denied")) } });
    await mount();
    await act(async () => button("我的好友").props.onClick());
    await act(async () => button("複製").props.onClick());
    expect(JSON.stringify(tree.toJSON())).toContain("無法自動複製，請長按或選取好友碼手動複製。");
    expect(tree.root.findByProps({ id: "my-friend-code" }).props.value).toBe("ME");
  });
  it("does not apply a late copy result after closing and reopening", async () => {
    let finish!: () => void;
    vi.stubGlobal("navigator", { clipboard: { writeText: () => new Promise<void>(r => { finish = r; }) } });
    await mount();
    await act(async () => button("我的好友").props.onClick());
    await act(async () => button("複製").props.onClick());
    await act(async () => button("關閉").props.onClick());
    await act(async () => button("我的好友").props.onClick());
    await act(async () => finish());
    expect(JSON.stringify(tree.toJSON())).not.toContain("好友碼已複製");
    expect(button("複製").props.disabled).toBe(false);
  });
  it("shows specific brief success feedback without clearing uncertain warnings", async () => {
    const room = { ...gameRoom(), phase: "WAITING" as const, capacity: 4 as const, players: [] };
    await mount(room);
    await act(async () => button("我的好友").props.onClick());
    await act(async () => button("邀入房間").props.onClick());
    expect(JSON.stringify(tree.toJSON())).toContain("邀請已送出");
    expect(JSON.stringify(tree.toJSON())).not.toContain("已完成");
    await act(async () => { vi.advanceTimersByTime(5001); });
    expect(JSON.stringify(tree.toJSON())).not.toContain("邀請已送出");
    mocks.request.mockRejectedValueOnce(new FriendsError("結果未確認，請勿重送", true));
    await act(async () => button("邀入房間").props.onClick());
    await act(async () => { vi.advanceTimersByTime(5001); });
    expect(JSON.stringify(tree.toJSON())).toContain("結果未確認，請勿重送");
    expect(button("邀入房間").props.disabled).toBe(true);
  });
  it("keeps room invites actionable in the closed entry without success notices", async () => {
    mocks.request.mockResolvedValue({ ...sample, invites: [{ inviteId: "in", inviterNickname: "Friend", roomName: "Table", roomId: "r", expiresAt: "later" }] });
    await mount();
    expect(button("我的好友")).toBeTruthy();
    const invite = tree.root.findByProps({ className: "secondary friends-invite-entry" });
    await act(async () => invite.props.onClick());
    expect(tree.root.findByProps({ role: "dialog" })).toBeTruthy();
    await act(async () => button("關閉").props.onClick());
    expect(tree.root.findAllByProps({ role: "dialog" })).toHaveLength(0);
    expect(tree.root.findAllByProps({ className: "friends-notice" })).toHaveLength(0);
  });
  it.each(["REDEAL_DECISION", "PRIVILEGE_DECISION", "BIDDING", "DEAL_RESULT", "PLAYING", "TRICK_RESULT", "FORFEIT_PENDING", "MATCH_RESULT", "ABORTED"] as const)("hides all social entries during %s", async (phase) => {
    await mount(gameRoom(phase));
    expect(tree.toJSON()).toBeNull();
  });
  it("closes an open waiting-room panel on game start and keeps it closed on return", async () => {
    await mount(gameRoom("WAITING"));
    await act(async () => button("我的好友").props.onClick());
    expect(tree.root.findAllByProps({ role: "dialog" })).toHaveLength(1);
    await act(async () => { tree.update(<FriendsHub token="test" room={gameRoom("BIDDING")} />); });
    expect(tree.toJSON()).toBeNull();
    await act(async () => { tree.update(<FriendsHub token="test" room={gameRoom("WAITING")} />); });
    expect(button("我的好友")).toBeTruthy();
    expect(tree.root.findAllByProps({ role: "dialog" })).toHaveLength(0);
  });
  it("anchors waiting-room social controls inside room controls instead of above the table", () => {
    const app = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
    const controls = app.slice(app.indexOf('<aside className="panel room-controls">'));
    expect(controls).toContain('<div className="room-friends-slot" ref={setFriendsEntryTarget} />');
    expect(app).toContain("entryPlacement={friendsEntryPlacement(page, state.room)}");
  });
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
    await act(async () => {
      tree.update(<FriendsHub token="test" room={null} entryPlacement="hidden" />);
    });
    expect(tree.toJSON()).toBeNull();
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
    await act(async () => button("我的好友").props.onClick());
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
  it("closes a panel on hidden pages and does not reopen on return", async () => {
    await mount();
    await act(async () => button("我的好友").props.onClick());
    expect(tree.root.findAllByProps({ role: "dialog" })).toHaveLength(1);
    await act(async () => tree.update(<FriendsHub token="test" room={null} entryPlacement="hidden" />));
    expect(tree.toJSON()).toBeNull();
    await act(async () => tree.update(<FriendsHub token="test" room={null} />));
    expect(button("我的好友")).toBeDefined();
    expect(tree.root.findAllByProps({ role: "dialog" })).toHaveLength(0);
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
