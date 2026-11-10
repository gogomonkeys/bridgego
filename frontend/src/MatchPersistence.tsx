import { useEffect, useState } from "react";
import { accountRequest } from "./google-login";
export type PersistenceStatus =
  | "DISABLED"
  | "PENDING"
  | "CONFIRMED"
  | "CONFLICT"
  | "FAILED"
  | "REJECTED"
  | "UNKNOWN";
export const persistenceLabels: Record<PersistenceStatus, string> = {
  DISABLED: "本場未啟用保存，或不在資料收集範圍內。",
  PENDING: "場次正在保存，尚未確認完成。",
  CONFIRMED: "本場紀錄已確認保存。",
  CONFLICT: "場次紀錄有衝突，尚未確認正確保存。",
  FAILED: "本場保存未能確認，可能尚未保存。",
  REJECTED: "本場未排入保存：可能超出收集期間或範圍，或佇列已滿。",
  UNKNOWN: "無法確認本場是否已保存。",
};
export default function MatchPersistence({
  roomId,
  gameId,
}: {
  roomId: string;
  gameId: string;
}) {
  const [status, setStatus] = useState<PersistenceStatus>("UNKNOWN");
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const read = async () => {
      try {
        const response = await accountRequest<{ status: string }>(
          `/rooms/${encodeURIComponent(roomId)}/games/${encodeURIComponent(gameId)}/persistence`,
        );
        if (!live) return;
        const next = Object.prototype.hasOwnProperty.call(
          persistenceLabels,
          response.status,
        )
          ? (response.status as PersistenceStatus)
          : "UNKNOWN";
        setStatus(next);
        if (next === "PENDING") timer = setTimeout(() => void read(), 3000);
      } catch {
        if (live) setStatus("UNKNOWN");
      }
    };
    void read();
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [roomId, gameId, refresh]);
  return (
    <section className="match-persistence" aria-label="本場保存狀態">
      <p role="status">{persistenceLabels[status]}</p>
      <p className="fine">
        保存狀態不影響本場勝負。資料使用與統計規則請見
        <a href="#help">使用說明</a>。
      </p>
      {status !== "CONFIRMED" && status !== "PENDING" && (
        <button
          className="text-button"
          onClick={() => setRefresh((value) => value + 1)}
        >
          重新確認保存狀態
        </button>
      )}
    </section>
  );
}
