import { useEffect, useState, useSyncExternalStore } from "react";
import type { Room } from "./model";
import { createTransientNotice } from "./transient-notice";

/** Show only our own disclosed timeout, near the transition into bidding. */
export function ownPrivilegeTimeoutKey(room: Room): string | null {
  const game = room.game;
  if (
    room.phase !== "BIDDING" ||
    !room.you ||
    !game ||
    game.privilege.status !== "REVEALED" ||
    game.privilege.yourChoiceSource !== "TIMEOUT" ||
    game.privilege.yourChoice !== false
  )
    return null;
  return JSON.stringify([
    room.roomId,
    game.gameId,
    game.dealId,
    game.redealCount,
    room.you.playerId,
  ]);
}

export default function OwnTimeoutNotice({ room }: { room: Room }) {
  const key = ownPrivilegeTimeoutKey(room);
  const [notice] = useState(() => createTransientNotice(key));
  const visibleKey = useSyncExternalStore(
    notice.subscribe,
    notice.getSnapshot,
    notice.getSnapshot,
  );
  useEffect(() => {
    notice.activate(key);
    return notice.stop;
  }, [notice, key]);
  if (key === null || key !== visibleKey) return null;
  return (
    <p className="own-timeout-notice" role="status" aria-live="polite">
      你的特權選擇已逾時，已自動設為「不使用」。
    </p>
  );
}
