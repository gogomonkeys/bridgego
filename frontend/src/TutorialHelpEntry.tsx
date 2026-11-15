import { useEffect, useRef, useState } from "react";
import { accountRequest } from "./google-login";
import type { TutorialStatus } from "./tutorial";
/** Read-only recovery for a failed initial check or a later feature toggle. */
export default function TutorialHelpEntry({
  token,
  enabled,
  onAvailability,
  onStart,
}: {
  token: string;
  enabled: boolean | null;
  onAvailability: (enabled: boolean) => void;
  onStart: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const active = useRef(true),
    pending = useRef(false);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
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
      if (!active.current) return;
      if (typeof status.enabled !== "boolean")
        throw new Error("Unknown availability");
      onAvailability(status.enabled);
    } catch {
      if (active.current) setError("暫時無法確認，稍後可再試。");
    } finally {
      pending.current = false;
      if (active.current) setBusy(false);
    }
  };
  return (
    <div className="tutorial-help-entry">
      <button
        className="secondary"
        disabled={busy || enabled !== true}
        onClick={onStart}
      >
        開始教學
      </button>
      <button
        className="secondary"
        disabled={busy}
        onClick={() => void refresh()}
      >
        {busy ? "確認中…" : "確認教學狀態"}
      </button>
      {enabled === false && <small>教學暫停開放</small>}
      {error && <p role="status">{error}</p>}
    </div>
  );
}
