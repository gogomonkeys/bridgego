import { useId, useState, useRef, type ReactNode } from "react";

/** Tap/keyboard help, not a hover-only tooltip. */
export default function IconTip({
  icon,
  label,
  children,
  active = false,
}: {
  icon: ReactNode;
  label: string;
  children: ReactNode;
  active?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const close = () => {
    setOpen(false);
    trigger.current?.focus();
  };
  return (
    <span className={`icon-tip ${active ? "is-active" : ""}`}>
      <button
        type="button"
        className="icon-tip-trigger"
        ref={trigger}
        aria-label={label}
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen(!open)}
        onKeyDown={(event) => {
          if (event.key === "Escape") close();
        }}
      >
        <span aria-hidden="true">{icon}</span>
      </button>
      {open && (
        <span
          className="icon-tip-content"
          id={id}
          role="note"
          onKeyDown={(event) => {
            if (event.key === "Escape") close();
          }}
        >
          <strong>{label}</strong>
          {children}
          <button type="button" onClick={close} aria-label={`關閉${label}提示`}>
            知道了
          </button>
        </span>
      )}
    </span>
  );
}
