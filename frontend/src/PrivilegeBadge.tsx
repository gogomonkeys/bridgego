import { useId } from "react";
/** A drawn card-and-spark seal, shared by privilege labels and actions. */
export default function PrivilegeBadge({
  active = false,
}: {
  active?: boolean;
}) {
  const gradientId = useId();
  return (
    <svg
      className={`privilege-badge ${active ? "active" : ""}`}
      viewBox="0 0 40 40"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <linearGradient id={gradientId} x2="0.8" y2="1">
          <stop stopColor={active ? "#ffe5a0" : "#fffaf0"} />
          <stop offset="1" stopColor={active ? "#e4af4a" : "#e3d8bf"} />
        </linearGradient>
      </defs>
      <circle
        cx="20"
        cy="20"
        r="18"
        fill={`url(#${gradientId})`}
        stroke={active ? "#9c6627" : "#a48f70"}
      />
      <rect
        x="10"
        y="11"
        width="15"
        height="21"
        rx="3"
        transform="rotate(-13 17 21)"
        fill="#f8f2df"
        stroke="#8b6846"
      />
      <rect
        x="15"
        y="8"
        width="15"
        height="22"
        rx="3"
        transform="rotate(9 22 19)"
        fill="#fffdf4"
        stroke="#8b6846"
      />
      <path
        d="m23 12 1.6 4.3 4.4 1.7-4.4 1.6L23 24l-1.6-4.4L17 18l4.4-1.7Z"
        fill={active ? "#bb7b27" : "#8d9c83"}
      />
    </svg>
  );
}
