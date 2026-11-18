import { errorMessages } from "./error-messages";
import type { VoiceFrame } from "./voice-wire";
export interface VoicePeerView {
  peerId: string;
  name: string;
  status: string;
}
export interface VoiceView {
  status: "off" | "permission" | "joining" | "joined";
  muted: boolean;
  message: string;
  playbackBlocked: boolean;
  peers: VoicePeerView[];
}
export interface VoiceDependencies {
  media: () => Promise<MediaStream>;
  peer: () => RTCPeerConnection;
  audio: () => HTMLAudioElement;
  send: (frame: VoiceFrame) => boolean;
  changed: (view: VoiceView) => void;
}
interface Peer {
  id: string;
  name: string;
  pc: RTCPeerConnection;
  audio: HTMLAudioElement;
  status: string;
  candidates: RTCIceCandidateInit[];
  queue: Promise<void>;
  timer: ReturnType<typeof setTimeout>;
  blocked: boolean;
  terminal: boolean;
}
const stopStream = (stream: MediaStream | null) =>
  stream?.getTracks().forEach((track) => track.stop());
/** Audio-only, opt-in, ephemeral mesh. A cancelled permission prompt can never reopen capture. */
export class WaitingVoice {
  private generation = 0;
  private requestId = "";
  private selfId = "";
  private stream: MediaStream | null = null;
  private peers = new Map<string, Peer>();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private view: VoiceView = {
    status: "off",
    muted: true,
    message: "",
    playbackBlocked: false,
    peers: [],
  };
  constructor(private deps: VoiceDependencies) {}
  private update(patch: Partial<VoiceView> = {}) {
    this.view = {
      ...this.view,
      ...patch,
      peers: [...this.peers.values()].map((p) => ({
        peerId: p.id,
        name: p.name,
        status: p.status,
      })),
      playbackBlocked: [...this.peers.values()].some((p) => p.blocked),
    };
    this.deps.changed(this.view);
  }
  async join() {
    if (this.view.status !== "off") return;
    const epoch = ++this.generation;
    this.requestId = crypto.randomUUID();
    this.update({
      status: "permission",
      message: "請允許麥克風，或取消加入。",
    });
    // Permission prompts can remain unanswered indefinitely: bound local joining state.
    this.timer = setTimeout(
      () => this.leave("尚未完成麥克風授權，請再按加入。"),
      30000,
    );
    try {
      const stream = await this.deps.media();
      if (epoch !== this.generation) {
        stopStream(stream);
        return;
      }
      this.stream = stream;
      stream.getAudioTracks().forEach((track) => {
        track.enabled = false;
        track.onended = () => {
          if (epoch === this.generation)
            this.leave("麥克風已中斷。請重新加入。");
        };
      });
      if (!stream.getAudioTracks().length) throw new Error("NO_AUDIO");
      clearTimeout(this.timer);
      this.update({ status: "joining", message: "正在加入語音…" });
      this.timer = setTimeout(
        () => this.leave("語音連線逾時，可再按加入。"),
        10000,
      );
      if (!this.deps.send({ type: "voice-join", requestId: this.requestId }))
        this.leave("房間連線尚未就緒。");
    } catch {
      if (epoch === this.generation)
        this.leave("無法使用麥克風，請檢查瀏覽器權限。");
    }
  }
  leave(message = "", notify = true) {
    if (
      notify &&
      (this.view.status === "joining" || this.view.status === "joined")
    )
      this.deps.send({
        type: "voice-leave",
        ...(this.selfId ? { peerId: this.selfId } : {}),
      });
    ++this.generation;
    clearTimeout(this.timer);
    this.selfId = "";
    this.requestId = "";
    stopStream(this.stream);
    this.stream = null;
    for (const id of this.peers.keys()) this.drop(id);
    this.update({ status: "off", muted: true, message });
  }
  mute() {
    if (this.view.status !== "joined") return;
    const muted = !this.view.muted;
    this.stream?.getAudioTracks().forEach((track) => {
      track.enabled = !muted;
    });
    this.update({ muted });
  }
  private drop(id: string) {
    const peer = this.peers.get(id);
    if (!peer) return;
    this.peers.delete(id);
    clearTimeout(peer.timer);
    peer.pc.onicecandidate = null;
    peer.pc.ontrack = null;
    peer.pc.onconnectionstatechange = null;
    peer.pc.close();
    peer.audio.pause();
    peer.audio.srcObject = null;
  }
  private alive(peer: Peer, epoch: number) {
    return (
      epoch === this.generation &&
      this.peers.get(peer.id) === peer &&
      !peer.terminal
    );
  }
  private failPeer(peer: Peer) {
    if (this.peers.get(peer.id) !== peer) return;
    clearTimeout(peer.timer);
    peer.terminal = true;
    peer.blocked = false;
    peer.pc.onicecandidate = null;
    peer.pc.ontrack = null;
    peer.pc.onconnectionstatechange = null;
    peer.status = "無法連線";
    peer.pc.close();
    peer.audio.pause();
    peer.audio.srcObject = null;
    this.update({ message: "部分網路無法直連，不影響繼續遊戲。" });
  }
  private signal(peer: Peer, signal: Record<string, unknown>, epoch: number) {
    if (!this.alive(peer, epoch)) return;
    if (
      !this.deps.send({
        type: "voice-signal",
        from: this.selfId,
        to: peer.id,
        signal,
      })
    )
      this.leave("房間連線已中斷。", false);
  }
  private play(peer: Peer) {
    const epoch = this.generation;
    if (!this.alive(peer, epoch)) return;
    void peer.audio
      .play()
      .then(() => {
        if (!this.alive(peer, epoch)) return;
        peer.blocked = false;
        this.update();
      })
      .catch(() => {
        if (!this.alive(peer, epoch)) return;
        peer.blocked = true;
        this.update();
      });
  }
  resumePlayback() {
    for (const peer of this.peers.values()) if (peer.blocked) this.play(peer);
  }
  private enqueue(peer: Peer, fn: () => Promise<void>) {
    peer.queue = peer.queue.then(fn).catch(() => this.failPeer(peer));
  }
  private add(id: string, name: string) {
    const epoch = this.generation;
    const pc = this.deps.peer(),
      audio = this.deps.audio();
    audio.autoplay = true;
    audio.setAttribute("playsinline", "");
    const peer: Peer = {
      id,
      name,
      pc,
      audio,
      status: "連線中",
      candidates: [],
      queue: Promise.resolve(),
      blocked: false,
      terminal: false,
      timer: setTimeout(() => this.failPeer(peer), 20000),
    };
    this.peers.set(id, peer);
    for (const track of this.stream!.getAudioTracks())
      pc.addTrack(track, this.stream!);
    pc.onicecandidate = (event) => {
      if (event.candidate)
        this.signal(
          peer,
          { type: "candidate", ...event.candidate.toJSON() },
          epoch,
        );
    };
    pc.ontrack = (event) => {
      if (!this.alive(peer, epoch)) return;
      // Never render/accept remote video as part of this feature.
      if (event.track.kind !== "audio") {
        event.track.stop();
        return;
      }
      audio.srcObject = new MediaStream([event.track]);
      this.play(peer);
    };
    pc.onconnectionstatechange = () => {
      if (!this.alive(peer, epoch)) return;
      if (pc.connectionState === "connected") {
        clearTimeout(peer.timer);
        peer.status = "已連線";
        this.update();
      } else if (
        pc.connectionState === "failed" ||
        pc.connectionState === "disconnected"
      )
        this.failPeer(peer);
    };
    if (this.selfId < id)
      this.enqueue(peer, async () => {
        const offer = await pc.createOffer();
        if (!this.alive(peer, epoch)) return;
        await pc.setLocalDescription(offer);
        this.signal(
          peer,
          { type: "offer", sdp: pc.localDescription?.sdp },
          epoch,
        );
      });
  }
  receive(frame: VoiceFrame) {
    if (frame.type === "voice-ended") {
      if (this.view.status !== "off")
        this.leave(
          frame.reason === "GAME_STARTED"
            ? ""
            : "語音已中斷；重新連線後可自行加入。",
          false,
        );
      return;
    }
    if (this.view.status === "off" || this.view.status === "permission") return;
    if (frame.type === "voice-error") {
      // A departing peer can race our last candidate; roster cleanup handles that harmless case.
      if (frame.code === "VOICE_PEER_GONE") return;
      this.leave(
        errorMessages[String(frame.code)] ?? "語音暫時無法加入，請稍後再試。",
      );
      return;
    }
    if (frame.type === "voice-state") {
      if (
        frame.requestId !== this.requestId ||
        typeof frame.selfId !== "string" ||
        !Array.isArray(frame.peers)
      )
        return;
      if (this.selfId && this.selfId !== frame.selfId) return;
      if (frame.peers.length > 3) {
        this.leave("語音人數異常。");
        return;
      }
      this.selfId = frame.selfId;
      clearTimeout(this.timer);
      if (this.view.status === "joining") {
        this.stream?.getAudioTracks().forEach((track) => {
          track.enabled = true;
        });
        this.update({ status: "joined", muted: false, message: "" });
      }
      const valid = frame.peers.filter(
        (p): p is { peerId: string; name: string } =>
          !!p &&
          typeof p === "object" &&
          typeof p.peerId === "string" &&
          typeof p.name === "string" &&
          p.peerId !== this.selfId,
      );
      const ids = new Set(valid.map((p) => p.peerId));
      for (const id of this.peers.keys()) if (!ids.has(id)) this.drop(id);
      try {
        for (const p of valid)
          if (!this.peers.has(p.peerId)) this.add(p.peerId, p.name);
      } catch {
        this.leave("這個瀏覽器暫時無法建立語音連線。");
      }
      this.update();
    } else if (frame.type === "voice-signal" && this.view.status === "joined") {
      if (frame.to !== this.selfId) return;
      const peer = this.peers.get(String(frame.from));
      const signal = frame.signal as Record<string, unknown> | undefined;
      if (
        !peer ||
        peer.terminal ||
        !signal ||
        peer.pc.connectionState === "closed"
      )
        return;
      const epoch = this.generation,
        pc = peer.pc;
      this.enqueue(peer, async () => {
        if (!this.alive(peer, epoch)) return;
        if (signal.type === "candidate") {
          const candidate: RTCIceCandidateInit = {
            candidate: String(signal.candidate ?? ""),
            sdpMid: signal.sdpMid as string | null,
            sdpMLineIndex: signal.sdpMLineIndex as number | null,
          };
          if (pc.remoteDescription) await pc.addIceCandidate(candidate);
          else if (peer.candidates.length < 64) peer.candidates.push(candidate);
        } else if (
          (signal.type === "offer" || signal.type === "answer") &&
          typeof signal.sdp === "string"
        ) {
          if (/^m=(?:video|application)\s/m.test(signal.sdp))
            throw new Error("AUDIO_ONLY");
          if (signal.type === "offer" && this.selfId < peer.id) return;
          if (
            signal.type === "answer" &&
            pc.signalingState !== "have-local-offer"
          )
            return;
          await pc.setRemoteDescription({ type: signal.type, sdp: signal.sdp });
          if (!this.alive(peer, epoch)) return;
          for (const candidate of peer.candidates.splice(0)) {
            if (!this.alive(peer, epoch)) return;
            await pc.addIceCandidate(candidate);
          }
          if (signal.type === "offer") {
            const answer = await pc.createAnswer();
            if (!this.alive(peer, epoch)) return;
            await pc.setLocalDescription(answer);
            this.signal(
              peer,
              { type: "answer", sdp: pc.localDescription?.sdp },
              epoch,
            );
          }
        }
      });
    }
  }
}
