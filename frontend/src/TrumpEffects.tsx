import { useEffect, useState, useSyncExternalStore } from "react";
import type { Room } from "./model";
import { createTrumpEffects, type TrumpEffect } from "./trump-effects";
import { tablePosition } from "./table-view";

export function useTrumpEffects(
  room: Room,
  online: boolean,
  connectionEpoch: number,
) {
  const [store] = useState(() =>
    createTrumpEffects(room, online, connectionEpoch),
  );
  const events = useSyncExternalStore(
    store.subscribe,
    store.getSnapshot,
    store.getSnapshot,
  );
  useEffect(() => {
    store.update(room, online, connectionEpoch);
    return store.stop;
  }, [store, room, online, connectionEpoch]);
  const trickKey = `${JSON.stringify([room.roomId, room.game?.gameId, room.game?.dealId])}:${room.game?.play?.trickNumber ?? null}`;
  return events.filter(
    (event) =>
      online &&
      !room.game?.paused &&
      event.epoch === connectionEpoch &&
      event.trickKey === trickKey,
  );
}

/** Empty corner lanes keep big lettering clear of cards, avatars and controls. */
export default function TrumpEffects({
  events,
  ownSeat,
}: {
  events: TrumpEffect[];
  ownSeat: number;
}) {
  return (
    <div className="trump-effects-layer" aria-live="polite" aria-atomic="false">
      {events.map((event) => (
        <div
          key={event.id}
          className={`trump-announcement trump-${event.kind} trump-lane-${tablePosition(event.seat, ownSeat)}`}
          data-effect-seat={event.seat}
          data-effect-kind={event.kind}
        >
          <span className="trump-effect-word">
            {event.kind === "wash" ? "洗王" : "切王"}
          </span>
          <span className="trump-effect-direction" aria-hidden="true">
            {
              { top: "↑", right: "→", bottom: "↓", left: "←" }[
                tablePosition(event.seat, ownSeat)
              ]
            }
          </span>
        </div>
      ))}
    </div>
  );
}
