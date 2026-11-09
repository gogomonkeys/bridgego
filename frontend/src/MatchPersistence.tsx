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
        統計開放時，所有玩家先同意資料使用、正常完成計分且結算時為四位不同真人的場次可保存，包含訪客；不保存訪客名稱或識別碼。統計未開放時沿用原模式的保存範圍。是否保存仍以本場狀態為準。保存採盡力而為，沒有保證不遺失的持久佇列，服務重啟或寫入失敗可能遺失紀錄。此狀態不改變本場勝負，也不表示先前訪客場次已補登至
        Google 帳號。
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
