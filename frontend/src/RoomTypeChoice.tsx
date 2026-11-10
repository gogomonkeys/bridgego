import type { RoomType } from "./model";
export const roomTypeLabel = (type?: RoomType) =>
  type === "FORMAL" ? "正式場" : "休閒場";

export function RoomTypeBadge({ type }: { type?: RoomType }) {
  return (
    <span
      className={`room-type-badge ${type === "FORMAL" ? "formal" : "casual"}`}
    >
      {roomTypeLabel(type)}
    </span>
  );
}

export default function RoomTypeChoice({
  value,
  disabled,
  onChange,
}: {
  value: RoomType;
  disabled: boolean;
  onChange: (type: RoomType) => void;
}) {
  return (
    <fieldset className="room-type-choice" disabled={disabled}>
      <legend>選擇場次類型</legend>
      <div className="room-type-options">
        {(["FORMAL", "CASUAL"] as const).map((type) => (
          <label key={type} className={value === type ? "selected" : ""}>
            <input
              type="radio"
              name="room-type"
              value={type}
              checked={value === type}
              onChange={() => onChange(type)}
            />
            <span>
              <strong>{roomTypeLabel(type)}</strong>
              <small>
                {type === "FORMAL"
                  ? "四位真人競技，不可加入電腦"
                  : "輕鬆同樂，可加入電腦"}
              </small>
            </span>
          </label>
        ))}
      </div>
      <p>類型在開房後固定，本頁房間列表會顯示所選類型。</p>
    </fieldset>
  );
}
