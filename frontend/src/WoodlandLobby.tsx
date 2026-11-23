import { type FormEvent, type ReactNode } from "react";
import RoomTypeChoice, { RoomTypeBadge, roomTypeLabel } from "./RoomTypeChoice";
import { roomTitle, shortRoomId } from "./room-display";
import type { RoomSummary, RoomType } from "./model";
import "./woodland-lobby-compact.css";

type DealCount = 1 | 3 | 5;
interface WoodlandLobbyProps {
  rooms: RoomSummary[];
  roomType: RoomType;
  busy: boolean;
  code: string;
  plannedDeals: DealCount;
  onRoomType: (type: RoomType) => void;
  onCode: (code: string) => void;
  onDeals: (count: DealCount) => void;
  onCreate: () => void;
  onJoin: (id: string) => void;
  onRefresh: () => void;
  onHelp: () => void;
  onForget: () => void;
  tools: ReactNode;
  accessNotice?: ReactNode;
}

const cabinTitle = (host?: string | null) =>
  host?.trim() ? roomTitle(host).replace(/牌桌$/, "房間") : "遊戲房間";

export default function WoodlandLobby({
  rooms,
  roomType,
  busy,
  code,
  plannedDeals,
  onRoomType,
  onCode,
  onDeals,
  onCreate,
  onJoin,
  onRefresh,
  onHelp,
  onForget,
  tools,
  accessNotice,
}: WoodlandLobbyProps) {
  const visibleRooms = rooms.filter(
    (room) => (room.roomType ?? "CASUAL") === roomType,
  );
  const roomIds = rooms.map((room) => room.roomId);
  const titleCounts = new Map<string, number>();
  for (const room of visibleRooms) {
    const title = cabinTitle(room.hostDisplayName);
    titleCounts.set(title, (titleCounts.get(title) ?? 0) + 1);
  }
  const submitCode = (event: FormEvent) => {
    event.preventDefault();
    if (!busy && code.trim()) onJoin(code);
  };

  return (
    <div className="woodland-cabins-lobby">
      <div className="cabins-hero">
        <div className="cabins-hero-copy">
          <h2>林間留了位子。</h2>
          <p>來和朋友打一局吧。</p>
        </div>
        <img
          src="/assets/forest/animal-rabbit.webp"
          className="cabins-rabbit"
          alt=""
          draggable={false}
        />
        <img
          src="/assets/forest/animal-fox.webp"
          className="cabins-fox"
          alt=""
          draggable={false}
        />
      </div>
      <div className="cabins-paths">
        <details className="cabins-create">
          <summary>
            <span aria-hidden="true">＋</span>建立房間
          </summary>
          <div className="cabins-setup">
            <RoomTypeChoice
              value={roomType}
              disabled={busy}
              onChange={onRoomType}
            />
            <fieldset className="deal-count-choice" disabled={busy}>
              <legend>本場局數</legend>
              {([1, 3, 5] as const).map((count) => (
                <label key={count}>
                  <input
                    type="radio"
                    name="planned-deals"
                    value={count}
                    checked={plannedDeals === count}
                    onChange={() => onDeals(count)}
                  />
                  {count} 局{count === 3 ? "（預設）" : ""}
                </label>
              ))}
            </fieldset>
            <button
              type="button"
              className="primary create-room"
              disabled={busy}
              onClick={onCreate}
            >
              ＋ 建立{roomTypeLabel(roomType)}房間
            </button>
          </div>
        </details>
        <details className="cabins-join">
          <summary>
            用房間代碼加入
            <span className="cabins-arrow" aria-hidden="true" />
          </summary>
          <form className="cabins-setup" onSubmit={submitCode}>
            <label htmlFor="room-code">房間代碼</label>
            <div className="cabins-code-row">
              <input
                id="room-code"
                value={code}
                onChange={(event) => onCode(event.target.value)}
                placeholder="輸入朋友的代碼"
                maxLength={64}
                required
              />
              <button
                type="submit"
                className="secondary"
                disabled={busy || !code.trim()}
              >
                加入房間
              </button>
            </div>
          </form>
        </details>
      </div>
      <section
        className="cabins-directory"
        aria-labelledby="available-rooms-title"
      >
        <div className="cabins-directory-heading">
          <h2 id="available-rooms-title">房間列表</h2>
          <button
            type="button"
            className="cabins-refresh"
            disabled={busy}
            onClick={onRefresh}
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
              focusable="false"
            >
              <path d="M5 9a8 8 0 1 1-.4 6M5 4v5h5" />
            </svg>
            更新
          </button>
        </div>
        <div className="cabins-filters" role="group" aria-label="場別">
          {(["CASUAL", "FORMAL"] as const).map((type) => (
            <button
              type="button"
              key={type}
              aria-pressed={type === roomType}
              disabled={busy}
              onClick={() => onRoomType(type)}
            >
              {type === roomType && <span aria-hidden="true">✓</span>}
              {roomTypeLabel(type)}
            </button>
          ))}
        </div>
        {visibleRooms.length ? (
          <ul
            className="cabins-room-list"
            aria-label={`${roomTypeLabel(roomType)}房間`}
          >
            {visibleRooms.map((room) => {
              const title = cabinTitle(room.hostDisplayName);
              const showCode =
                !room.hostDisplayName?.trim() ||
                (titleCounts.get(title) ?? 0) > 1;
              const stateLabel =
                room.state === "FINISHED"
                  ? "已結束"
                  : room.state === "ACTIVE"
                    ? "遊戲中"
                    : "等待中";
              const buttonLabel =
                room.state !== "WAITING"
                  ? stateLabel
                  : !room.joinable
                    ? "已滿"
                    : "入座";
              return (
                <li key={room.roomId} className="cabins-room">
                  <svg
                    className="cabins-room-icon"
                    viewBox="0 0 32 32"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinejoin="round"
                    aria-hidden="true"
                    focusable="false"
                  >
                    <path d="M3 15 16 4l13 11M7 12v16h18V12M13 28V18h6v10" />
                  </svg>
                  <div className="cabins-room-info">
                    <strong className="room-display-title">{title}</strong>
                    <span className="cabins-screenreader">
                      <RoomTypeBadge type={room.roomType} />
                    </span>
                    <span className="cabins-room-meta">
                      <span>{stateLabel}</span>
                      <span>
                        {room.playerCount}／{room.capacity || 4} 人
                      </span>
                      <span>{room.plannedDeals ?? 3} 局</span>
                    </span>
                    {showCode && (
                      <small className="cabins-room-code" title={room.roomId}>
                        #{shortRoomId(room.roomId, roomIds)}
                      </small>
                    )}
                  </div>
                  <button
                    type="button"
                    className="cabins-room-join"
                    aria-label={`${buttonLabel}：${title}${showCode ? ` #${shortRoomId(room.roomId, roomIds)}` : ""}`}
                    disabled={busy || !room.joinable}
                    onClick={() => onJoin(room.roomId)}
                  >
                    {buttonLabel}
                    {buttonLabel === "入座" && (
                      <span className="cabins-arrow" aria-hidden="true" />
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="cabins-empty" role="status">
            目前沒有{roomTypeLabel(roomType)}房間。
          </p>
        )}
      </section>
      <section className="cabins-links" aria-label="大廳功能">
        <div className="cabins-tools">{tools}</div>
        {accessNotice}
      </section>
      <div className="cabins-footer">
        <button type="button" onClick={onHelp}>
          使用說明
        </button>
        <button type="button" onClick={onForget} disabled={busy}>
          返回身分選擇
        </button>
      </div>
    </div>
  );
}
