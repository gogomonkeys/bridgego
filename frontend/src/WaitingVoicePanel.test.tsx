import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import WaitingVoicePanel from "./WaitingVoicePanel";
import {
  bindVoiceTransport,
  setRoomChatScope,
  clearRoomChat,
  endVoiceTransport,
  receiveVoiceFrame,
} from "./voice-wire";
let tree: ReactTestRenderer | undefined;
let track: { enabled: boolean; stop: ReturnType<typeof vi.fn> };
const send = vi.fn((frame: Record<string, unknown>) => Boolean(frame)),
  media = vi.fn();
function button(text: string) {
  return tree!.root
    .findAllByType("button")
    .find((b) => b.children.includes(text))!;
}
beforeEach(() => {
  track = { enabled: true, stop: vi.fn() };
  send.mockClear();
  media.mockReset();
  media.mockResolvedValue({
    getTracks: () => [track],
    getAudioTracks: () => [track],
  });
  vi.stubGlobal(
    "window",
    Object.assign(new EventTarget(), {
      isSecureContext: true,
      innerWidth: 393,
      innerHeight: 852,
    }),
  );
  vi.stubGlobal("navigator", { mediaDevices: { getUserMedia: media } });
  vi.stubGlobal("RTCPeerConnection", class {});
  clearRoomChat();
  setRoomChatScope("test-session", "r1");
  bindVoiceTransport(send);
});
afterEach(() => {
  if (tree) act(() => tree!.unmount());
  tree = undefined;
  endVoiceTransport();
  vi.unstubAllGlobals();
});
it("mount is silent; user opts in; room change and unmount close capture", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  expect(media).not.toHaveBeenCalled();
  act(() => button("房間聊天").props.onClick());
  await act(async () => button("加入語音").props.onClick());
  expect(media).toHaveBeenCalledWith({
    audio: { echoCancellation: true, noiseSuppression: true },
    video: false,
  });
  act(() =>
    receiveVoiceFrame({
      type: "voice-state",
      requestId: send.mock.calls[0][0].requestId,
      selfId: "a",
      peers: [],
    }),
  );
  expect(button("靜音")).toBeTruthy();
  expect(track.enabled).toBe(true);
  await act(async () => tree!.update(<WaitingVoicePanel roomId="r2" online />));
  expect(track.stop).toHaveBeenCalled();
  act(() => button("房間聊天").props.onClick());
  expect(button("加入語音")).toBeTruthy();
  expect(media).toHaveBeenCalledTimes(1);
});
it("transport end stops immediately and reconnect never silently joins", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  await act(async () => button("加入語音").props.onClick());
  act(() => endVoiceTransport());
  expect(track.stop).toHaveBeenCalled();
  act(() => button("房間聊天").props.onClick());
  expect(button("加入語音")).toBeTruthy();
  await act(async () =>
    tree!.update(<WaitingVoicePanel roomId="r1" online={false} />),
  );
  await act(async () => tree!.update(<WaitingVoicePanel roomId="r1" online />));
  expect(media).toHaveBeenCalledTimes(1);
});
it("page hide closes capture while permission is pending", async () => {
  let resolve!: (v: unknown) => void;
  media.mockReturnValue(
    new Promise((r) => {
      resolve = r;
    }),
  );
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  act(() => {
    button("加入語音").props.onClick();
  });
  act(() => {
    window.dispatchEvent(new Event("pagehide"));
  });
  await act(async () =>
    resolve({ getTracks: () => [track], getAudioTracks: () => [track] }),
  );
  expect(track.stop).toHaveBeenCalled();
  expect(send).not.toHaveBeenCalled();
});
it("actual app mounts voice only in waiting controls and wire handles stop/start", () => {
  const app = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
  expect(app.indexOf("<WaitingVoicePanel")).toBeGreaterThan(
    app.indexOf("waiting-surface"),
  );
  expect(app.indexOf("<WaitingVoicePanel")).toBeLessThan(
    app.indexOf("<ComputerPlayers"),
  );
  const client = readFileSync(new URL("./client.ts", import.meta.url), "utf8");
  expect(client).toContain(
    'if (accepted.phase !== "WAITING") endVoiceTransport("GAME_STARTED")',
  );
  expect(client).toContain(
    'function stop() {\n  endVoiceTransport("SESSION_ENDED");',
  );
  expect(client).toContain("socket !== ws) return;\n    endVoiceTransport();");
});
it("closing floating panel keeps opted-in voice and the active microphone indicator", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  await act(async () => button("加入語音").props.onClick());
  act(() =>
    receiveVoiceFrame({
      type: "voice-state",
      requestId: send.mock.calls[0][0].requestId,
      selfId: "a",
      peers: [],
    }),
  );
  act(() => button("收起").props.onClick());
  expect(track.stop).not.toHaveBeenCalled();
  expect(tree!.root.findAllByProps({ role: "dialog" })).toHaveLength(0);
  expect(JSON.stringify(tree!.toJSON())).toContain("麥克風開啟");
  const mic = tree!.root.findByProps({ "aria-label": "麥克風開啟，點按靜音" });
  expect(mic).toBeTruthy();
  expect(
    tree!.root.findByProps({ className: "room-chat-entry" }).props[
      "data-voice"
    ],
  ).toBe("live");
  expect(
    tree!.root
      .findByProps({ className: "room-chat-open" })
      .findByProps({ role: "status" }).children,
  ).toContain("麥克風開啟");
  expect(mic.findByType("svg").props["aria-hidden"]).toBe("true");
  act(() => mic.props.onClick());
  expect(track.enabled).toBe(false);
  expect(
    tree!.root.findByProps({ className: "room-chat-entry" }).props[
      "data-voice"
    ],
  ).toBe("muted");
  act(() => button("房間聊天").props.onClick());
  expect(media).toHaveBeenCalledTimes(1);
  expect(button("離開語音")).toBeTruthy();
});
it("chat is plain text, room-scoped, bounded and erased after explicit session end", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  act(() =>
    receiveVoiceFrame({
      type: "room-chat",
      roomId: "other",
      messageId: "bad",
      name: "Stranger",
      text: "secret",
    }),
  );
  expect(JSON.stringify(tree!.toJSON())).not.toContain("secret");
  act(() =>
    receiveVoiceFrame({
      type: "room-chat",
      roomId: "r1",
      messageId: "one",
      name: "Friend",
      text: "<img src=x onerror=alert(1)>",
    }),
  );
  expect(tree!.root.findAllByType("img")).toHaveLength(0);
  expect(JSON.stringify(tree!.toJSON())).toContain(
    "<img src=x onerror=alert(1)>",
  );
  for (let i = 0; i < 60; i++)
    act(() =>
      receiveVoiceFrame({
        type: "room-chat",
        roomId: "r1",
        messageId: String(i),
        name: "Friend",
        text: `line-${i}`,
      }),
    );
  const log = tree!.root.findByProps({ role: "log" });
  expect(log.findAllByType("p")).toHaveLength(50);
  expect(JSON.stringify(tree!.toJSON())).not.toContain("onerror=alert");
  act(() => endVoiceTransport("SESSION_ENDED"));
  act(() => button("房間聊天").props.onClick());
  expect(
    tree!.root.findByProps({ role: "log" }).findAllByType("strong"),
  ).toHaveLength(0);
});
it("text sends independently of microphone and no automatic replay on error", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  act(() =>
    tree!.root
      .findByType("input")
      .props.onChange({ target: { value: "Hello" } }),
  );
  act(() =>
    tree!.root.findByType("form").props.onSubmit({ preventDefault: vi.fn() }),
  );
  expect(send).toHaveBeenCalledWith({
    type: "room-chat",
    roomId: "r1",
    text: "Hello",
  });
  expect(media).not.toHaveBeenCalled();
  act(() =>
    receiveVoiceFrame({ type: "room-chat-error", code: "CHAT_RATE_LIMITED" }),
  );
  expect(send).toHaveBeenCalledTimes(1);
  expect(JSON.stringify(tree!.toJSON())).toContain("訊息送得太快");
});
it("nonmodal floating UI preserves page interaction and limits phone height", () => {
  const source = readFileSync(
    new URL("./WaitingVoicePanel.tsx", import.meta.url),
    "utf8",
  );
  const css = readFileSync(
    new URL("./waiting-voice.css", import.meta.url),
    "utf8",
  );
  expect(source).toContain('aria-modal="false"');
  expect(source).not.toContain("mountFriendsDialog");
  expect(css).toContain("pointer-events: none");
  expect(css).toContain("45dvh");
});

it("keeps only concise pre-join privacy copy in the chat panel", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  const before = JSON.stringify(tree!.toJSON());
  expect(before).toContain("語音直連可能讓同房玩家得知你的公開 IP。");
  expect(before).not.toContain("不保存歷史紀錄");
  expect(before).not.toContain("開局自動結束");
  expect(media).not.toHaveBeenCalled();
  await act(async () => button("加入語音").props.onClick());
  act(() =>
    receiveVoiceFrame({
      type: "voice-state",
      requestId: send.mock.calls[0][0].requestId,
      selfId: "a",
      peers: [],
    }),
  );
  expect(JSON.stringify(tree!.toJSON())).not.toContain("語音直連可能");
  expect(JSON.stringify(tree!.toJSON())).toContain("麥克風開啟");
  expect(button("離開語音")).toBeTruthy();
});
it("keyboard resize and pan keep composer inside the visible viewport without restarting voice", async () => {
  const viewport = Object.assign(new EventTarget(), {
    width: 393,
    height: 852,
    offsetTop: 0,
    offsetLeft: 0,
  });
  Object.assign(window, { visualViewport: viewport });
  const remove = vi.spyOn(viewport, "removeEventListener");
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  await act(async () => button("加入語音").props.onClick());
  act(() =>
    receiveVoiceFrame({
      type: "voice-state",
      requestId: send.mock.calls[0][0].requestId,
      selfId: "a",
      peers: [],
    }),
  );
  act(() => {
    Object.assign(viewport, { height: 280, offsetTop: 120 });
    viewport.dispatchEvent(new Event("resize"));
    viewport.dispatchEvent(new Event("scroll"));
  });
  const style = tree!.root.findByProps({ className: "room-chat-backdrop" })
    .props.style;
  expect(style["--room-chat-visible-height"]).toBe("280px");
  expect(style["--room-chat-visible-top"]).toBe("120px");
  expect(media).toHaveBeenCalledTimes(1);
  expect(track.stop).not.toHaveBeenCalled();
  expect(
    tree!.root
      .findByProps({ className: "room-chat-composer" })
      .findByType("input"),
  ).toBeTruthy();
  act(() => button("收起").props.onClick());
  expect(remove).toHaveBeenCalledWith("resize", expect.any(Function));
  expect(remove).toHaveBeenCalledWith("scroll", expect.any(Function));
  expect(track.stop).not.toHaveBeenCalled();
});
it("header and composer are outside the single shrinking scroll track", () => {
  const css = readFileSync(
    new URL("./waiting-voice.css", import.meta.url),
    "utf8",
  );
  expect(css).toContain("grid-template-rows: auto minmax(0, 1fr) auto");
  expect(css).toMatch(
    /\.room-chat-dialog \.room-chat-heading\s*\{[^}]*min-height: 44px/,
  );
  expect(css).toMatch(
    /\.room-chat-scroll\s*\{[^}]*min-height: 0;[^}]*overflow-y: auto/,
  );
  expect(css).toContain("--room-chat-visible-height");
});

it("preserves received chat over pagehide, offline and temporary remount without restoring microphone", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() =>
    receiveVoiceFrame({
      type: "room-chat",
      roomId: "r1",
      messageId: "sleep",
      name: "Friend",
      text: "Before sleep",
    }),
  );
  act(() => {
    window.dispatchEvent(new Event("pagehide"));
  });
  act(() => endVoiceTransport());
  await act(async () =>
    tree!.update(<WaitingVoicePanel roomId="r1" online={false} />),
  );
  act(() => tree!.unmount());
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  expect(JSON.stringify(tree!.toJSON())).toContain("Before sleep");
  expect(JSON.stringify(tree!.toJSON())).not.toContain("房間連線中斷");
  expect(media).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalled();
});
it("clears disconnected hint when connection returns in same panel", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online={false} />);
  });
  act(() => button("房間聊天").props.onClick());
  expect(JSON.stringify(tree!.toJSON())).toContain("房間連線中斷");
  await act(async () => tree!.update(<WaitingVoicePanel roomId="r1" online />));
  expect(JSON.stringify(tree!.toJSON())).not.toContain("房間連線中斷");
  expect(media).not.toHaveBeenCalled();
});

it("server terminal voice event erases memory before snapshot and cannot resurrect on remount", async () => {
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() =>
    receiveVoiceFrame({
      type: "room-chat",
      roomId: "r1",
      messageId: "old",
      name: "Friend",
      text: "Earlier waiting chat",
    }),
  );
  act(() => tree!.unmount());
  // No mounted listener and no subsequent room snapshot: wire owns terminal clearing.
  act(() => receiveVoiceFrame({ type: "voice-ended", reason: "GAME_STARTED" }));
  setRoomChatScope("test-session", "r1");
  await act(async () => {
    tree = create(<WaitingVoicePanel roomId="r1" online />);
  });
  act(() => button("房間聊天").props.onClick());
  expect(JSON.stringify(tree!.toJSON())).not.toContain("Earlier waiting chat");
});

it("shows a decorative yellow warning before the unchanged public IP notice", async () => {
  await act(async () => { tree = create(<WaitingVoicePanel roomId="r1" online />); });
  act(() => button("房間聊天").props.onClick());
  const notice = tree!.root.findByProps({ className: "voice-privacy-notice" });
  const icon = notice.findByType("svg");
  expect(icon.props["aria-hidden"]).toBe("true");
  expect(icon.findAllByType("path")[0].props.fill).toBe("#f5c84c");
  expect(notice.findByType("span").children).toEqual(["語音直連可能讓同房玩家得知你的公開 IP。"]);
  expect(media).not.toHaveBeenCalled();
});
it("shares equal entrance dimensions and leaf motif without showing an empty guest slot", () => {
  const css = readFileSync(new URL("./waiting-voice.css", import.meta.url), "utf8");
  expect(css).toContain("--room-entry-width: 112px");
  expect(css).toContain("--room-entry-height: 64px");
  expect(css).toContain(".friends-entry-row > button:first-child::before");
  expect(css).toContain("width: calc(var(--room-entry-width) - 2px)");
  expect(css).toMatch(/\.room-friends-slot:empty\s*\{\s*display: none/);
});

it("uses the chat forest surface, border and focus treatment for the friend entrance", () => {
  const css = readFileSync(new URL("./waiting-voice.css", import.meta.url), "utf8");
  expect(css).toContain("--room-entry-surface: #f3f6e9");
  expect(css).toContain("--room-entry-border: #b6c8a8");
  expect(css.match(/background: var\(--room-entry-surface\)/g)).toHaveLength(2);
  expect(css).toContain(".friends-entry-row > button:first-child:focus-visible");
  expect(css).toContain("outline-offset: -4px");
});


it("keeps compact entrances plus a 48px microphone within a 280px room toolbar", () => {
  const css = readFileSync(new URL("./waiting-voice.css", import.meta.url), "utf8");
  const width = Number(css.match(/--room-entry-width: (\d+)px/)![1]);
  expect(width * 2 + 48 + 8).toBeLessThanOrEqual(280);
  expect(css).toContain("flex: 0 0 48px");
  expect(css).toContain("min-width: 48px");
  expect(css).toMatch(/room-social-tools\s*\{[^}]*flex-wrap: wrap/s);
});
