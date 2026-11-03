import type { Room } from "./model";
import { compassNames, seatRelationship, tablePosition } from "./table-view";
import AnimalAvatar from "./AnimalAvatar";
import { playerLabel } from "./player-label";
import { teamNames } from "./game-view";
export default function TableSeats({
  room,
  turnSeat,
  winnerSeat,
}: {
  room: Room;
  turnSeat: number | null;
  winnerSeat?: number;
}) {
  const ownSeat = room.you?.seat ?? 0;
  return (
    <>
      {[0, 1, 2, 3].map((seat) => {
        const player = room.players.find((p) => p.seat === seat);
        const roster = room.players;
        const label = playerLabel(roster, seat);
        const takeover = player?.controlMode === "BOT_TAKEOVER";
        const offline = player?.connected === false && !takeover;
        const captured =
          room.game?.play?.completedTricks.filter(
            (trick) => trick.winnerSeat === seat,
          ).length ?? 0;
        return (
          <article
            key={seat}
            className={`table-player position-${tablePosition(seat, ownSeat)} team-${seat % 2 === 0 ? "NS" : "EW"} ${seat === turnSeat ? "active-turn" : ""} ${seat === winnerSeat ? "trick-winner" : ""}`}
            data-seat={seat}
            aria-label={`${compassNames[seat]} ${seatRelationship(seat, ownSeat)}`}
          >
            <div className="seat-portrait">
              <AnimalAvatar seat={seat} />
              {room.phase !== "WAITING" && (
                <span
                  className="player-trick-count"
                  aria-label={`${player?.displayName ?? seat}本局拿下 ${captured} 墩`}
                >
                  <b>{captured}</b> <span className="trick-count-unit">墩</span>
                </span>
              )}
            </div>
            <div className="seat-identity">
              <span className="player-direction">
                {compassNames[seat]} · <b>{seatRelationship(seat, ownSeat)}</b>
              </span>
              <details className="player-name-tip">
                <summary
                  className="player-name"
                  aria-label={player ? label : "等待入座"}
                >
                  {player ? label : "等待入座"}
                </summary>
                <span>{player ? label : "等待入座"}</span>
              </details>
              <small className="seat-team">
                {teamNames[seat % 2 === 0 ? "NS" : "EW"]}{" "}
                {player?.playerId === room.hostPlayerId ? "· 房主" : ""}
              </small>
              <small
                className={`seat-presence ${offline ? "is-offline" : ""}`}
                title={offline ? `${label}離線` : undefined}
                aria-label={offline ? `${label}離線` : undefined}
              >
                {player
                  ? takeover
                    ? "電腦代打"
                    : player.playerType === "BOT"
                      ? room.phase === "WAITING"
                        ? "電腦 · 已準備"
                        : seat === turnSeat
                          ? "電腦 · 輪到"
                          : "電腦玩家"
                      : player.connected
                        ? room.phase === "WAITING"
                          ? player.ready
                            ? "✓ 已準備"
                            : "尚未準備"
                          : seat === turnSeat
                            ? "● 目前輪到"
                            : "● 在線"
                        : player.controlMode === "RECONNECTING"
                          ? "○ 重連中"
                          : "○ 離線"
                  : "空位"}
              </small>
            </div>
          </article>
        );
      })}
    </>
  );
}
