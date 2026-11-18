import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import WaitingVoicePanel from "./WaitingVoicePanel";
import {
  bindVoiceTransport,
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
    Object.assign(new EventTarget(), { isSecureContext: true }),
  );
  vi.stubGlobal("navigator", { mediaDevices: { getUserMedia: media } });
  vi.stubGlobal("RTCPeerConnection", class {});
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
  expect(client).toContain("function stop() {\n  endVoiceTransport();");
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
  expect(button("靜音")).toBeTruthy();
  act(() => button("靜音").props.onClick());
  expect(track.enabled).toBe(false);
  act(() => button("房間聊天").props.onClick());
  expect(media).toHaveBeenCalledTimes(1);
  expect(button("離開語音")).toBeTruthy();
});
it("chat is plain text, room-scoped, bounded and erased after transport ends", async () => {
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
  act(() => endVoiceTransport());
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
