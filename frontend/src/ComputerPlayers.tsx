import type { Room } from "./model";
import { compassNames, seatRelationship } from "./table-view";

export default function ComputerPlayers({
  room,
  playerId,
  disabled,
  onAdd,
  onRemove,
}: {
  room: Room;
  playerId: string;
  disabled: boolean;
  onAdd: (seat: number) => void;
  onRemove: (playerId: string) => void;
}) {
  if (room.phase !== "WAITING" || room.hostPlayerId !== playerId) return null;
  const ownSeat = room.you?.seat ?? 0;
  return (
    <section className="computer-controls" aria-label="電腦玩家管理">
      <h3>找電腦朋友入座</h3>
      <p>補上空位就能玩。電腦會自動準備，開局後不能替換。</p>
      <ul>
        {[0, 1, 2, 3]
          .filter((seat) => seat !== ownSeat)
          .map((seat) => {
            const player = room.players.find((p) => p.seat === seat);
            const place = `${compassNames[seat]} · ${seatRelationship(seat, ownSeat)}`;
            return (
              <li key={seat}>
                <div>
                  <span>{place}</span>
                  <strong>{player?.displayName ?? "空位"}</strong>
                </div>
                {!player ? (
                  <button
                    type="button"
                    className="secondary"
                    disabled={disabled}
                    aria-label={`在${place}加入電腦玩家`}
                    onClick={() => onAdd(seat)}
                  >
                    ＋ 電腦
                  </button>
                ) : player.playerType === "BOT" ? (
                  <button
                    type="button"
                    className="secondary"
                    disabled={disabled}
                    aria-label={`移除${player.displayName}（${place}）`}
                    onClick={() => onRemove(player.playerId)}
                  >
                    移除電腦
                  </button>
                ) : (
                  <span className="human-seat-label">真人玩家</span>
                )}
              </li>
            );
          })}
      </ul>
    </section>
  );
}
