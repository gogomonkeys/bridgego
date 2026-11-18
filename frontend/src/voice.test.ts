import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WaitingVoice, type VoiceView } from "./voice";
import type { VoiceFrame } from "./voice-wire";
const tick = async () => {
  for (let i = 0; i < 12; i++) await Promise.resolve();
};
function fixture() {
  const track = {
    enabled: true,
    stop: vi.fn(),
    onended: null as (() => void) | null,
  };
  const stream = {
    getTracks: () => [track],
    getAudioTracks: () => [track],
  } as unknown as MediaStream;
  const pcs: ReturnType<typeof pcFactory>[] = [],
    audios: ReturnType<typeof audioFactory>[] = [];
  function pcFactory() {
    return {
      addTrack: vi.fn(),
      close: vi.fn(),
      onicecandidate: null as null | ((e: unknown) => void),
      ontrack: null as null | ((e: unknown) => void),
      onconnectionstatechange: null as null | (() => void),
      createOffer: vi.fn(async () => ({ type: "offer", sdp: "audio" })),
      createAnswer: vi.fn(async () => ({ type: "answer", sdp: "audio" })),
      setLocalDescription: vi.fn(async () => {}),
      setRemoteDescription: vi.fn(async () => {}),
      addIceCandidate: vi.fn(async () => {}),
      remoteDescription: null as unknown,
      localDescription: { sdp: "audio" },
      connectionState: "new",
      signalingState: "stable",
    };
  }
  function audioFactory() {
    return {
      autoplay: false,
      setAttribute: vi.fn(),
      play: vi.fn(async () => {}),
      pause: vi.fn(),
      srcObject: null as unknown,
    };
  }
  const media = vi.fn(async () => stream),
    send = vi.fn((frame: VoiceFrame) => Boolean(frame));
  let view: VoiceView = {
    status: "off",
    muted: true,
    message: "",
    playbackBlocked: false,
    peers: [],
  };
  const voice = new WaitingVoice({
    media,
    send,
    changed: (v) => {
      view = v;
    },
    peer: () => {
      const pc = pcFactory();
      pcs.push(pc);
      return pc as unknown as RTCPeerConnection;
    },
    audio: () => {
      const audio = audioFactory();
      audios.push(audio);
      return audio as unknown as HTMLAudioElement;
    },
  });
  async function joined(
    selfId = "a",
    peers: Array<{ peerId: string; name: string }> = [],
  ) {
    await voice.join();
    const requestId = [...send.mock.calls]
      .reverse()
      .find(([f]) => f.type === "voice-join")![0].requestId;
    voice.receive({ type: "voice-state", requestId, selfId, peers });
    await tick();
    return requestId;
  }
  return {
    voice,
    media,
    send,
    stream,
    track,
    pcs,
    audios,
    joined,
    get view() {
      return view;
    },
  };
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe("waiting-room opt-in voice", () => {
  it("never asks for microphone before join; disables tracks until acknowledged", async () => {
    const f = fixture();
    expect(f.media).not.toHaveBeenCalled();
    await f.voice.join();
    expect(f.track.enabled).toBe(false);
    expect(f.view.status).toBe("joining");
    f.voice.leave();
    expect(f.track.stop).toHaveBeenCalled();
  });
  it("denied permission is local, with no signaling", async () => {
    const f = fixture();
    f.media.mockRejectedValue(new Error("NotAllowedError"));
    await f.voice.join();
    expect(f.view.status).toBe("off");
    expect(f.send).not.toHaveBeenCalled();
    expect(f.view.message).toContain("權限");
  });
  it("late permission after cancelling stops tracks, never joins", async () => {
    const f = fixture();
    let resolve!: (s: MediaStream) => void;
    f.media.mockReturnValue(
      new Promise((r) => {
        resolve = r;
      }),
    );
    const join = f.voice.join();
    f.voice.leave();
    resolve(f.stream);
    await join;
    expect(f.track.stop).toHaveBeenCalled();
    expect(f.send).not.toHaveBeenCalled();
    expect(f.view.status).toBe("off");
  });
  it("bounded pending permission and join timeout never auto retry", async () => {
    const f = fixture();
    f.media.mockReturnValue(new Promise(() => {}));
    void f.voice.join();
    vi.advanceTimersByTime(30000);
    expect(f.view.status).toBe("off");
    expect(f.media).toHaveBeenCalledTimes(1);
    const g = fixture();
    await g.voice.join();
    vi.advanceTimersByTime(10000);
    expect(g.track.stop).toHaveBeenCalled();
    expect(g.view.status).toBe("off");
  });
  it("mute changes outgoing audio without reconnecting", async () => {
    const f = fixture();
    await f.joined();
    expect(f.track.enabled).toBe(true);
    f.voice.mute();
    expect(f.track.enabled).toBe(false);
    f.voice.mute();
    expect(f.track.enabled).toBe(true);
    f.voice.leave();
  });
  it.each(["GAME_STARTED", "DISCONNECTED"])(
    "%s stops all media/peers without auto rejoin",
    async (reason) => {
      const f = fixture();
      await f.joined("a", [{ peerId: "b", name: "朋友" }]);
      f.voice.receive({ type: "voice-ended", reason });
      expect(f.track.stop).toHaveBeenCalled();
      expect(f.pcs[0].close).toHaveBeenCalled();
      expect(f.audios[0].pause).toHaveBeenCalled();
      expect(f.audios[0].srcObject).toBe(null);
      expect(f.view.status).toBe("off");
      vi.advanceTimersByTime(60000);
      expect(f.media).toHaveBeenCalledTimes(1);
    },
  );
  it("ignores late state ack and stale recipient signals", async () => {
    const f = fixture();
    await f.voice.join();
    const old = f.send.mock.calls[0][0].requestId;
    f.voice.leave();
    await f.voice.join();
    f.voice.receive({
      type: "voice-state",
      requestId: old,
      selfId: "a",
      peers: [],
    });
    expect(f.view.status).toBe("joining");
    const current = [...f.send.mock.calls]
      .reverse()
      .find(([v]) => v.type === "voice-join")![0].requestId;
    f.voice.receive({
      type: "voice-state",
      requestId: current,
      selfId: "new",
      peers: [{ peerId: "a", name: "Friend" }],
    });
    f.voice.receive({
      type: "voice-signal",
      from: "a",
      to: "old",
      signal: { type: "offer", sdp: "audio" },
    });
    await tick();
    expect(f.pcs[0].setRemoteDescription).not.toHaveBeenCalled();
    f.voice.leave();
  });
  it("four peers means three connections; deterministic sole offerer", async () => {
    const f = fixture();
    await f.joined("b", [
      { peerId: "a", name: "A" },
      { peerId: "c", name: "C" },
      { peerId: "d", name: "D" },
    ]);
    expect(f.pcs).toHaveLength(3);
    expect(f.pcs[0].createOffer).not.toHaveBeenCalled();
    expect(f.pcs[1].createOffer).toHaveBeenCalledTimes(1);
    expect(f.pcs[2].createOffer).toHaveBeenCalledTimes(1);
    f.voice.leave();
  });
  it("late offer cannot send after leave", async () => {
    const f = fixture();
    await f.joined("a");
    // first pc operation is deferred until microtask; synchronous leave must invalidate it.
    const requestId = f.send.mock.calls[0][0].requestId;
    f.voice.receive({
      type: "voice-state",
      requestId,
      selfId: "a",
      peers: [{ peerId: "b", name: "B" }],
    });
    f.voice.leave();
    await tick();
    expect(f.send.mock.calls.some(([x]) => x.type === "voice-signal")).toBe(
      false,
    );
  });
  it("connection timeout is terminal until explicit leave/rejoin", async () => {
    const f = fixture();
    await f.joined("a", [{ peerId: "b", name: "B" }]);
    const oldCallback = f.pcs[0].onconnectionstatechange!;
    vi.advanceTimersByTime(20000);
    expect(f.view.peers[0].status).toBe("無法連線");
    f.pcs[0].connectionState = "connected";
    oldCallback();
    expect(f.view.peers[0].status).toBe("無法連線");
    expect(f.pcs[0].onicecandidate).toBe(null);
    f.voice.leave();
  });
  it("autoplay rejection exposes explicit playback action", async () => {
    const f = fixture();
    await f.joined("a", [{ peerId: "b", name: "B" }]);
    vi.stubGlobal(
      "MediaStream",
      class {
        constructor(public tracks: unknown[]) {}
      },
    );
    f.audios[0].play.mockRejectedValueOnce(new Error("NotAllowedError"));
    f.pcs[0].ontrack!({ track: { kind: "audio" } });
    await tick();
    expect(f.view.playbackBlocked).toBe(true);
    f.voice.resumePlayback();
    await tick();
    expect(f.view.playbackBlocked).toBe(false);
    f.voice.leave();
  });
  it("queues bounded early ICE then flushes before answer", async () => {
    const f = fixture();
    await f.joined("z", [{ peerId: "a", name: "A" }]);
    for (let i = 0; i < 70; i++)
      f.voice.receive({
        type: "voice-signal",
        from: "a",
        to: "z",
        signal: {
          type: "candidate",
          candidate: "x",
          sdpMid: "0",
          sdpMLineIndex: 0,
        },
      });
    for (let i = 0; i < 160; i++) await Promise.resolve();
    expect(f.pcs[0].addIceCandidate).not.toHaveBeenCalled();
    f.voice.receive({
      type: "voice-signal",
      from: "a",
      to: "z",
      signal: { type: "offer", sdp: "audio" },
    });
    for (let i = 0; i < 200; i++) await Promise.resolve();
    expect(f.pcs[0].addIceCandidate).toHaveBeenCalledTimes(64);
    expect(f.pcs[0].createAnswer).toHaveBeenCalled();
    f.voice.leave();
  });
  it("does not accept video or data-channel offers", async () => {
    const f = fixture();
    await f.joined("z", [{ peerId: "a", name: "A" }]);
    f.voice.receive({
      type: "voice-signal",
      from: "a",
      to: "z",
      signal: { type: "offer", sdp: "m=video 9 RTP" },
    });
    await tick();
    expect(f.pcs[0].setRemoteDescription).not.toHaveBeenCalled();
    expect(f.view.peers[0].status).toBe("無法連線");
    f.voice.leave();
  });
});
