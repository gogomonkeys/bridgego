import { useEffect, useRef, useState } from "react";
import { accountRequest } from "./google-login";
import type { TutorialStatus } from "./tutorial";
/** Read-only recovery for a failed initial check or a later feature toggle. */
export default function TutorialHelpEntry({
  token,
  enabled,
  onAvailability,
  onStart,
  direct = false,
}: {
  token: string;
  enabled: boolean | null;
  onAvailability: (enabled: boolean) => void;
  onStart: () => void;
  direct?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const currentToken = useRef(token);
  currentToken.current = token;
  const active = useRef(true),
    pending = useRef(false);
  useEffect(() => {
    active.current = true;
    pending.current = false;
    setBusy(false);
    setError("");
    return () => {
      active.current = false;
    };
  }, [token]);
  const refresh = async () => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError("");
    try {
      const status = await accountRequest<TutorialStatus>(
        "/tutorial/status",
        "GET",
        undefined,
        token,
      );
      if (!active.current || currentToken.current !== token) return;
      if (typeof status.enabled !== "boolean")
        throw new Error("Unknown availability");
      onAvailability(status.enabled);
      if (direct && status.enabled) onStart();
    } catch {
      if (active.current && currentToken.current === token)
        setError("暫時無法確認，稍後可再試。");
    } finally {
      if (active.current && currentToken.current === token) {
        pending.current = false;
        setBusy(false);
      }
    }
  };
  return (
    <div className="tutorial-help-entry">
      <button
        className="secondary"
        disabled={busy || (!direct && enabled !== true)}
        onClick={direct ? () => void refresh() : onStart}
      >
        {direct ? (busy ? "確認中…" : "新手教學") : "開始教學"}
      </button>
      {!direct && (
        <button
          className="secondary"
          disabled={busy}
          onClick={() => void refresh()}
        >
          {busy ? "確認中…" : "確認教學狀態"}
        </button>
      )}
      {enabled === false && <small>教學暫停開放</small>}
      {error && <p role="status">{error}</p>}
    </div>
  );
}
