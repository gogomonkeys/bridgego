import { createPortal } from "react-dom";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type CSSProperties,
} from "react";
import { roomChatViewportStyle } from "./room-chat-viewport";
import { WaitingVoice, type VoiceView } from "./voice";
import { sendVoice, subscribeVoice } from "./voice-wire";
import "./waiting-voice.css";
interface ChatMessage {
  messageId: string;
  name: string;
  text: string;
}
export default function WaitingVoicePanel({
  roomId,
  online,
}: {
  roomId: string;
  online: boolean;
}) {
  // The controller lives with the WAITING room, not with the floating panel's visibility.
  const engine = useRef<WaitingVoice | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const messageLog = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [viewportStyle, setViewportStyle] = useState<CSSProperties>({});
  const [chat, setChat] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [chatError, setChatError] = useState("");
  const [unread, setUnread] = useState(0);
  const openRef = useRef(false);
  const close = useCallback(() => {
    openRef.current = false;
    setOpen(false);
  }, []);
  const [view, setView] = useState<VoiceView>({
    status: "off",
    muted: true,
    message: "",
    playbackBlocked: false,
    peers: [],
  });
  const supported =
    typeof window !== "undefined" &&
    window.isSecureContext &&
    typeof RTCPeerConnection !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia;
  useEffect(() => {
    const clear = () => {
      setChat([]);
      setDraft("");
      setChatError("");
      setUnread(0);
    };
    clear();
    close();
    const voice = new WaitingVoice({
      media: () =>
        navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true },
          video: false,
        }),
      peer: () =>
        new RTCPeerConnection({
          iceServers: [{ urls: "stun:stun.cloudflare.com:3478" }],
        }),
      audio: () => new Audio(),
      send: sendVoice,
      changed: setView,
    });
    engine.current = voice;
    const unsubscribe = subscribeVoice((frame) => {
      if (
        frame.type === "room-chat" &&
        frame.roomId === roomId &&
        typeof frame.messageId === "string" &&
        typeof frame.name === "string" &&
        typeof frame.text === "string" &&
        [...frame.text].length <= 200
      ) {
        const incoming = {
          messageId: frame.messageId,
          name: frame.name,
          text: frame.text,
        };
        setChat((current) =>
          current.some((m) => m.messageId === incoming.messageId)
            ? current
            : [...current, incoming].slice(-50),
        );
        if (!openRef.current) setUnread((count) => Math.min(count + 1, 50));
      } else if (frame.type === "room-chat-error") {
        setChatError(
          frame.code === "CHAT_RATE_LIMITED"
            ? "訊息送得太快，請稍後再說。"
            : "訊息未送出，請確認房間連線。",
        );
      } else if (frame.type === "voice-ended") {
        clear();
        close();
        voice.receive(frame);
      } else voice.receive(frame);
    });
    const leave = () => {
      voice.leave();
      clear();
      close();
    };
    window.addEventListener("pagehide", leave);
    window.addEventListener("bridge-account-changing", leave);
    return () => {
      unsubscribe();
      window.removeEventListener("pagehide", leave);
      window.removeEventListener("bridge-account-changing", leave);
      voice.leave();
      engine.current = null;
    };
  }, [roomId, close]);
  useEffect(() => {
    if (!online) {
      engine.current?.leave("房間連線中斷，麥克風已關閉。", false);
      setChat([]);
      setDraft("");
      setUnread(0);
      close();
    }
  }, [online, close]);
  useEffect(() => {
    if (!open || !dialog.current) return;
    // Non-modal: the room remains usable while the chat is open. No page scroll lock.
    const keydown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        close();
        trigger.current?.focus();
      }
    };
    window.addEventListener("keydown", keydown);
    return () => window.removeEventListener("keydown", keydown);
  }, [open, close]);
  useEffect(() => {
    if (!open) return;
    const viewport = window.visualViewport;
    const update = () => setViewportStyle(roomChatViewportStyle(window));
    update();
    viewport?.addEventListener("resize", update);
    viewport?.addEventListener("scroll", update);
    window.addEventListener("resize", update);
    return () => {
      viewport?.removeEventListener("resize", update);
      viewport?.removeEventListener("scroll", update);
      window.removeEventListener("resize", update);
    };
  }, [open]);
  useEffect(() => {
    if (open && chat.length > 0 && messageLog.current)
      messageLog.current.scrollTop = messageLog.current.scrollHeight;
  }, [chat, open]);
  const active = view.status === "joined";
  const pending = view.status === "permission" || view.status === "joining";
  const microphone = active
    ? view.muted
      ? "已靜音"
      : "麥克風開啟"
    : "語音關閉";
  const send = (event: FormEvent) => {
    event.preventDefault();
    const text = draft.trim();
    if (!online || !text || [...text].length > 200) return;
    if (sendVoice({ type: "room-chat", roomId, text })) {
      setDraft("");
      setChatError("");
    } else setChatError("訊息未送出，請確認房間連線。");
  };
  const panel = open ? (
    <div
      className="room-chat-backdrop"
      style={viewportStyle}
      onClick={(event) => {
        if (event.target === event.currentTarget) close();
      }}
    >
      <div
        className="room-chat-dialog"
        role="dialog"
        aria-modal="false"
        aria-labelledby="room-chat-title"
        tabIndex={-1}
        ref={dialog}
      >
        <header className="room-chat-heading">
          <div>
            <h2 id="room-chat-title">🌿 森林小聊</h2>
          </div>
          <button
            data-friends-close
            className="secondary"
            onClick={close}
            aria-label="收起房間聊天"
          >
            收起
          </button>
        </header>
        <div className="room-chat-scroll" ref={messageLog}>
          <section className="waiting-voice" aria-label="房間語音">
            <div className="waiting-voice-heading">
              <h3>語音</h3>
              <span>{microphone}</span>
            </div>
            <div className="waiting-voice-actions">
              {!active && !pending && (
                <button
                  className="secondary"
                  disabled={!supported || !online}
                  onClick={() => void engine.current?.join()}
                >
                  加入語音
                </button>
              )}
              {pending && (
                <button
                  className="secondary"
                  onClick={() => engine.current?.leave()}
                >
                  取消加入
                </button>
              )}
              {active && (
                <>
                  <button
                    className="secondary"
                    onClick={() => engine.current?.mute()}
                  >
                    {view.muted ? "開啟麥克風" : "靜音"}
                  </button>
                  <button
                    className="secondary"
                    onClick={() => engine.current?.leave()}
                  >
                    離開語音
                  </button>
                </>
              )}
              {view.playbackBlocked && (
                <button
                  className="secondary"
                  onClick={() => engine.current?.resumePlayback()}
                >
                  點一下播放聲音
                </button>
              )}
            </div>
            {active && (
              <ul className="waiting-voice-peers">
                {view.peers.length ? (
                  view.peers.map((p) => (
                    <li key={p.peerId}>
                      <span>{p.name}</span>
                      <small>{p.status}</small>
                    </li>
                  ))
                ) : (
                  <li>等候其他玩家加入語音</li>
                )}
              </ul>
            )}
            {!supported && <small>此瀏覽器無法使用語音，仍可正常遊玩。</small>}
            <div role="status" aria-live="polite">
              {view.message}
            </div>
            {!active && <small>語音直連可能讓同房玩家得知你的公開 IP。</small>}
          </section>
          <div
            className="room-chat-log"
            role="log"
            aria-label="房間訊息"
            aria-live="polite"
            aria-relevant="additions"
          >
            {chat.length ? (
              chat.map((m) => (
                <p key={m.messageId}>
                  <strong>{m.name}</strong>
                  <span>{m.text}</span>
                </p>
              ))
            ) : (
              <p className="room-chat-empty">和同桌朋友打聲招呼吧！</p>
            )}
          </div>
        </div>
        <div className="room-chat-composer">
          <form className="room-chat-form" onSubmit={send}>
            <label className="sr-only" htmlFor="room-chat-input">
              傳送房間訊息
            </label>
            <input
              id="room-chat-input"
              value={draft}
              onChange={(e) =>
                setDraft([...e.target.value].slice(0, 200).join(""))
              }
              placeholder="說點什麼…"
              autoComplete="off"
              disabled={!online}
            />
            <button
              className="primary"
              disabled={!online || !draft.trim()}
              type="submit"
            >
              傳送
            </button>
          </form>
          {chatError && (
            <small role="status" className="room-chat-error">
              {chatError}
            </small>
          )}
        </div>
      </div>
    </div>
  ) : null;
  return (
    <div className="room-chat-entry">
      <button
        ref={trigger}
        className="secondary"
        onClick={() => {
          openRef.current = true;
          setOpen(true);
          setUnread(0);
        }}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        房間聊天{unread > 0 ? ` · ${unread}` : ""}
      </button>
      {active && (
        <button
          className="room-chat-mic-toggle"
          aria-pressed={!view.muted}
          aria-label={
            view.muted ? "已靜音，開啟麥克風" : "麥克風開啟，點按靜音"
          }
          title={view.muted ? "開啟麥克風" : "靜音"}
          onClick={() => engine.current?.mute()}
        >
          <span aria-hidden="true">{view.muted ? "◌" : "●"}</span>
          <span role="status">{microphone}</span>
        </button>
      )}
      {pending && (
        <span className="room-chat-mic" role="status">
          正在加入語音…
        </span>
      )}
      {typeof document !== "undefined" && document.body
        ? createPortal(panel, document.body)
        : panel}
    </div>
  );
}
