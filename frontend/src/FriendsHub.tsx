import { createPortal } from "react-dom";
import { mountFriendsDialog } from "./friends-dialog";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { client } from "./client";
import type { Room } from "./model";
import {
  friendsRequest,
  FriendsError,
  inviteAvailable,
  presenceLabel,
  type FriendsSnapshot,
} from "./friends";
import "./friends.css";
function FriendCodeCard({ code }: { code: string }) {
  const [copyStatus, setCopyStatus] = useState<"idle" | "copying" | "copied" | "manual">("idle");
  useEffect(() => {
    if (copyStatus !== "copied") return;
    const timer = setTimeout(() => setCopyStatus("idle"), 5000);
    return () => clearTimeout(timer);
  }, [copyStatus]);
  const field = useRef<HTMLInputElement>(null);
  const active = useRef(true);
  const copying = useRef(false);
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; };
  }, []);
  const copy = async () => {
    if (copying.current) return;
    copying.current = true;
    setCopyStatus("copying");
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(code);
      if (active.current) setCopyStatus("copied");
    } catch {
      if (!active.current) return;
      setCopyStatus("manual");
      field.current?.focus();
      field.current?.select();
    } finally {
      copying.current = false;
    }
  };
  return (
    <section className="friend-code-card" aria-label="我的好友碼">
      <div className="friend-code-heading">
        <label htmlFor="my-friend-code">我的好友碼</label>
        <button type="button" className="secondary" disabled={copyStatus === "copying"} onClick={() => void copy()}>
          {copyStatus === "copying" ? "複製中…" : "複製"}
        </button>
      </div>
      <input id="my-friend-code" className="friend-code" ref={field} value={code} readOnly onFocus={(event) => event.currentTarget.select()} />
      {copyStatus === "copied" && <small role="status">好友碼已複製</small>}
      {copyStatus === "manual" && <small role="status">無法自動複製，請長按或選取好友碼手動複製。</small>}
    </section>
  );
}
interface Props {
  token: string;
  room: Room | null;
  disabled?: boolean;
  entryTarget?: HTMLElement | null;
  entryPlacement?: "lobby" | "default" | "hidden";
}
export default function FriendsHub({
  token,
  room,
  disabled: externallyDisabled = false,
  entryTarget = null,
  entryPlacement = "default",
}: Props) {
  const playing = !!room && room.phase !== "WAITING";
  const disabled = externallyDisabled || playing;
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const [data, setData] = useState<FriendsSnapshot | null>(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [code, setCode] = useState("");
  const [notice, setNotice] = useState("");
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(""), 5000);
    return () => clearTimeout(timer);
  }, [notice]);
  useEffect(() => {
    if (!open) setNotice("");
  }, [open]);
  const readError = useRef(false),
    uncertainRef = useRef(false);
  const live = useRef(true),
    generation = useRef(0),
    reading = useRef(false),
    mutating = useRef(false),
    available = useRef(true);
  const reload = useCallback(async () => {
    if (
      reading.current ||
      mutating.current ||
      !available.current ||
      disabled ||
      typeof document === "undefined" ||
      document.visibilityState === "hidden"
    )
      return;
    const current = generation.current;
    reading.current = true;
    try {
      const result = await friendsRequest<FriendsSnapshot>(token);
      if (!live.current || current !== generation.current) return;
      setData(result);
      available.current = result.enabled;
      if (readError.current) {
        setError("");
        readError.current = false;
      }
    } catch (cause) {
      if (!live.current || current !== generation.current) return;
      if (cause instanceof FriendsError && cause.unavailable) {
        available.current = false;
        setData(null);
      } else {
        if (!uncertainRef.current) {
          readError.current = true;
          setError(
            cause instanceof Error ? cause.message : "暫時無法讀取好友。",
          );
        }
      }
    } finally {
      reading.current = false;
    }
  }, [token, disabled]);
  useEffect(() => {
    if (typeof document === "undefined") return;
    live.current = true;
    available.current = true;
    void reload();
    const timer = setInterval(() => {
      if (document.visibilityState !== "hidden") void reload();
    }, 20000);
    const visible = () => {
      if (document.visibilityState !== "hidden") void reload();
    };
    const changing = () => {
      if (mutating.current) {
        uncertainRef.current = true;
        setUncertain(true);
      }
      generation.current++;
      available.current = false;
      setData(null);
      setOpen(false);
      setError(uncertainRef.current ? "操作結果尚未確認，請勿重複送出。" : "");
      setNotice("");
      setBusy(false);
      // Account verification can fire for the same session. It is not proof
      // that an uncertain mutation failed, and must never unlock writes.
    };
    const changed = () => {
      available.current = true;
      void reload();
    };
    document.addEventListener("visibilitychange", visible);
    window.addEventListener("bridge-account-changing", changing);
    window.addEventListener("bridge-account-changed", changed);
    return () => {
      if (mutating.current) {
        uncertainRef.current = true;
        setUncertain(true);
        setBusy(false);
        setError("操作結果尚未確認，請勿重複送出。");
      }
      live.current = false;
      generation.current++;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", visible);
      window.removeEventListener("bridge-account-changing", changing);
      window.removeEventListener("bridge-account-changed", changed);
    };
  }, [reload]);
  useEffect(() => {
    void reload();
  }, [room?.roomId, room?.phase, reload]);
  const act = async (
    path: string,
    method = "POST",
    body?: object,
    join = false,
  ) => {
    if (mutating.current || uncertain || disabled || !available.current) return;
    mutating.current = true;
    generation.current++;
    readError.current = false;
    setBusy(true);
    setError("");
    setNotice("");
    const current = generation.current;
    try {
      const result = await friendsRequest<Room | FriendsSnapshot>(
        token,
        path,
        method,
        body,
      );
      if (!live.current || current !== generation.current) return;
      if (join) {
        client.acceptSocialRoom(token, result as Room);
        setOpen(false);
      } else {
        setData(result as FriendsSnapshot);
        if (path === "/initialize") setOpen(true);
        else setNotice(
          path === "/invites" ? "邀請已送出" :
          path === "/requests" ? "好友申請已送出" :
          path.endsWith("/accept") ? "已加入好友" :
          path.endsWith("/reject") ? "已拒絕好友申請" :
          path.endsWith("/ignore") ? "已略過邀請" :
          method === "DELETE" ? "已移除好友" : "",
        );
      }
      setCode("");
    } catch (cause) {
      if (!live.current || current !== generation.current) return;
      setError(cause instanceof Error ? cause.message : "操作失敗。");
      if (cause instanceof FriendsError && cause.uncertain) {
        uncertainRef.current = true;
        setUncertain(true);
      }
    } finally {
      mutating.current = false;
      if (live.current && current === generation.current) {
        setBusy(false);
        void reload();
      }
    }
  };
  useEffect(() => {
    if (
      !open ||
      disabled ||
      !data?.enabled ||
      typeof document === "undefined" ||
      !document.body ||
      !dialogRef.current
    )
      return;
    return mountFriendsDialog(
      dialogRef.current,
      openerRef.current ?? triggerRef.current,
      () => setOpen(false),
    );
  }, [open, disabled, data?.enabled]);
  useEffect(() => {
    if (disabled || entryPlacement === "hidden") setOpen(false);
  }, [disabled, entryPlacement]);
  if (disabled || entryPlacement === "hidden" || !data?.enabled) return null;
  const locked = busy || uncertain;
  const waiting =
    room?.phase === "WAITING" && room.players.length < room.capacity;
  const invitations = data.invites.length > 0 && (
    <div className="friends-invites" aria-label="房間邀請">
      {data.invites.map((invite) => (
        <div className="friends-row" key={invite.inviteId}>
          <span>
            <b>{invite.inviterNickname}</b> 邀你加入「{invite.roomName}」
            {room && <small>請先離開目前房間</small>}
          </span>
          <div className="friends-row-actions">
            <button
              type="button"
              className="primary"
              disabled={locked || !inviteAvailable(invite, room)}
              onClick={() =>
                void act(
                  `/invites/${encodeURIComponent(invite.inviteId)}/join`,
                  "POST",
                  undefined,
                  true,
                )
              }
            >
              加入
            </button>
            <button
              type="button"
              className="secondary"
              disabled={locked}
              onClick={() =>
                void act(
                  `/invites/${encodeURIComponent(invite.inviteId)}/ignore`,
                )
              }
            >
              略過
            </button>
          </div>
        </div>
      ))}
    </div>
  );
  const drawerContent = open ? (
    <div
      className="friends-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget) setOpen(false);
      }}
    >
      <div
        className="panel friends-panel"
        id="friends-panel"
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="friends-title"
        tabIndex={-1}
      >
        <div className="friends-heading">
          <div className="friends-title-group">
            <img className="friends-mascot" src="/assets/forest/animal-rabbit.webp" alt="" aria-hidden="true" draggable={false} />
            <div><h2 id="friends-title">我的好友</h2>
            {room && (
              <small className="friends-room-context">仍在目前房間</small>
            )}
            </div>
          </div>
          <div className="friends-heading-actions">
            <button
              type="button"
              className="secondary"
              data-friends-close
              onClick={() => setOpen(false)}
            >
              關閉
            </button>

          </div>
        </div>
        {(error || notice) && (
          <p className="friends-notice" role="status">
            {error || notice}
          </p>
        )}
        {invitations}
        {data.friendCode && <FriendCodeCard key={data.friendCode} code={data.friendCode} />}
        <form
          className="friends-code-form"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            if (code.trim())
              void act("/requests", "POST", { friendCode: code.trim() });
          }}
        >
          <label htmlFor="friend-code-input">加入好友</label>
          <input
            id="friend-code-input"
            placeholder="輸入對方的好友碼"
            autoComplete="off"
            maxLength={32}
            value={code}
            disabled={locked}
            onChange={(event) => setCode(event.target.value)}
          />
          <button
            className="primary"
            disabled={locked || !code.trim()}
            type="submit"
          >
            送出申請
          </button>
        </form>
        {data.requests.length > 0 && (
          <section aria-label="好友申請">
            <h3>好友申請</h3>
            {data.requests.map((request) => (
              <div className="friends-row" key={request.requestId}>
                <span>
                  {request.nickname}
                  <small>
                    {request.direction === "OUTGOING"
                      ? "等待回覆"
                      : "想加你為好友"}
                  </small>
                </span>
                {request.direction === "INCOMING" && (
                  <div className="friends-row-actions">
                    <button
                      className="primary"
                      disabled={locked}
                      onClick={() =>
                        void act(
                          `/requests/${encodeURIComponent(request.requestId)}/accept`,
                        )
                      }
                    >
                      接受
                    </button>
                    <button
                      className="secondary"
                      disabled={locked}
                      onClick={() =>
                        void act(
                          `/requests/${encodeURIComponent(request.requestId)}/reject`,
                        )
                      }
                    >
                      拒絕
                    </button>
                  </div>
                )}
              </div>
            ))}
          </section>
        )}
        <section aria-label="好友清單">
          <div className="friends-list-heading">
          <h3>好友清單</h3>
            <button
              type="button"
              className="secondary"
              disabled={busy}
              onClick={() => {
                if (!uncertain) setError("");
                void reload();
              }}
            >
              重新讀取
            </button>
          </div>
          {data.friends.length === 0 && <p>還沒有好友</p>}
          {data.friends.map((friend) => (
            <div className="friends-row" key={friend.friendCode}>
              <span>
                {friend.nickname}
                <small
                  className={`friend-presence presence-${friend.presence.toLowerCase()}`}
                >
                  {presenceLabel[friend.presence]}
                </small>
              </span>
              <div className="friends-row-actions">
                {friend.joinable && (
                  <button
                    className="primary"
                    disabled={locked || !!room}
                    onClick={() =>
                      void act(
                        `/${encodeURIComponent(friend.friendCode)}/join`,
                        "POST",
                        undefined,
                        true,
                      )
                    }
                  >
                    加入遊戲
                  </button>
                )}
                {waiting && (
                  <button
                    className="primary"
                    disabled={locked || friend.presence !== "ONLINE"}
                    onClick={() =>
                      void act("/invites", "POST", {
                        friendCode: friend.friendCode,
                        roomId: room!.roomId,
                      })
                    }
                  >
                    邀入房間
                  </button>
                )}
                <details>
                  <summary>更多</summary>
                  <button
                    className="secondary"
                    disabled={locked}
                    onClick={() =>
                      void act(
                        `/${encodeURIComponent(friend.friendCode)}`,
                        "DELETE",
                      )
                    }
                  >
                    移除好友
                  </button>
                </details>
              </div>
            </div>
          ))}
        </section>
        {room && data.tablePlayers?.length > 0 && (
          <section aria-label="同桌玩家">
            <h3>同桌玩家</h3>
            {data.tablePlayers.map((player) => (
              <div className="friends-row" key={player.playerId}>
                <span>{player.nickname}</span>
                <button
                  className="secondary"
                  disabled={
                    locked || !player.eligible || player.relation !== "NONE"
                  }
                  onClick={() =>
                    void act("/requests", "POST", {
                      roomId: room.roomId,
                      playerId: player.playerId,
                    })
                  }
                >
                  {player.relation === "FRIEND"
                    ? "已是好友"
                    : player.relation === "PENDING"
                      ? "已申請"
                      : player.eligible
                        ? "加好友"
                        : "訪客"}
                </button>
              </div>
            ))}
          </section>
        )}

      </div>
    </div>
  ) : null;
  const drawer =
    drawerContent && typeof document !== "undefined" && document.body
      ? createPortal(drawerContent, document.body)
      : drawerContent;
  const entry = (
    <section className="friends-hub" aria-label="好友">
      <div className="friends-entry-row">
        <button
          type="button"
          className="secondary"
          ref={triggerRef}
          aria-expanded={open}
          aria-controls="friends-panel"
          disabled={!data.friendCode && locked}
          onClick={(event) => {
            openerRef.current = event?.currentTarget ?? triggerRef.current;
            if (data.friendCode) setOpen(!open);
            else void act("/initialize");
          }}
        >
          我的好友
          {data.pendingCount > 0 && (
            <span
              className="friends-count"
              aria-label={`${data.pendingCount} 則新邀請`}
            >
              {data.pendingCount}
            </span>
          )}
        </button>
        {data.invites.length > 0 && (
          <button
            type="button"
            className="secondary friends-invite-entry"
            onClick={(event) => {
              openerRef.current = event?.currentTarget ?? triggerRef.current;
              setOpen(true);
            }}
          >
            {data.invites.length} 則房間邀請
          </button>
        )}
      </div>

      {!open && error && (
        <p className="friends-notice" role="status">
          {error}
        </p>
      )}
    </section>
  );
  return (
    <>
      {entryPlacement === "lobby"
        ? entryTarget
          ? createPortal(entry, entryTarget)
          : null
        : entry}
      {drawer}
    </>
  );
}
