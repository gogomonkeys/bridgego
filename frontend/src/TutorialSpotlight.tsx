import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import "./tutorial-spotlight.css";
type Box = { top: number; left: number; width: number; height: number };
export function spotlightLayout(
  target: Box | null,
  width: number,
  height: number,
  dialogHeight: number,
) {
  const dw = Math.min(360, Math.max(0, width - 24));
  const dh = Math.min(dialogHeight, Math.max(0, height - 24));
  const left = Math.max(12, (width - dw) / 2);
  if (!target)
    return {
      hole: null,
      dialog: { top: Math.max(12, height - dh - 12), left, width: dw },
    };
  const x = Math.max(4, target.left - 5),
    y = Math.max(4, target.top - 5);
  const right = Math.min(width - 4, target.left + target.width + 5);
  const bottom = Math.min(height - 4, target.top + target.height + 5);
  const hole =
    right > x && bottom > y
      ? { left: x, top: y, width: right - x, height: bottom - y }
      : null;
  const below = target.top + target.height + 12,
    above = target.top - dh - 12;
  const top =
    below + dh <= height - 12
      ? below
      : above >= 12
        ? above
        : Math.max(12, height - dh - 12);
  return { hole, dialog: { top, left, width: dw } };
}
type Layout = ReturnType<typeof spotlightLayout>;
/** Navigation is local; caller owns all legal game actions. */
export default function TutorialSpotlight({
  target,
  title,
  children,
  stepLabel,
  progress,
  stepKey,
  busy = false,
  onPrevious,
  onNext,
  nextLabel = "下一步",
  onExit,
  interactive = false,
  allowInteraction,
}: {
  target: string;
  title: string;
  children: ReactNode;
  stepLabel?: string;
  progress?: string;
  stepKey?: string | number;
  busy?: boolean;
  allowInteraction?: boolean;
  onPrevious?: () => void;
  onNext?: () => void;
  nextLabel?: string;
  onExit: () => void;
  interactive?: boolean;
}) {
  interactive = allowInteraction ?? interactive;
  stepLabel = progress ?? stepLabel;
  const dialogRef = useRef<HTMLElement>(null);
  const [layout, setLayout] = useState<Layout | null>(null);
  useEffect(() => {
    if (typeof document === "undefined" || typeof window === "undefined")
      return;
    let raf = 0;
    const getTarget = () => {
      try {
        return document.querySelector<HTMLElement>(target);
      } catch {
        return null;
      }
    };
    const measure = () => {
      const box = getTarget()?.getBoundingClientRect();
      const dh = dialogRef.current?.getBoundingClientRect().height || 190;
      setLayout(
        spotlightLayout(
          box
            ? {
                top: box.top,
                left: box.left,
                width: box.width,
                height: box.height,
              }
            : null,
          window.innerWidth,
          window.innerHeight,
          dh,
        ),
      );
    };
    const update = () => {
      window.cancelAnimationFrame(raf);
      raf = window.requestAnimationFrame(measure);
    };
    const element = getTarget();
    if (element && typeof element.scrollIntoView === "function")
      element.scrollIntoView({
        block: "center",
        inline: "nearest",
        behavior: "instant",
      });
    if (element) {
      const box = element.getBoundingClientRect();
      const dh = dialogRef.current?.getBoundingClientRect().height || 190;
      const fitsBelow = box.bottom + dh + 24 <= window.innerHeight;
      const fitsAbove = box.top - dh - 12 >= 12;
      if (
        !fitsBelow &&
        !fitsAbove &&
        box.height + dh + 36 <= window.innerHeight &&
        typeof element.scrollIntoView === "function"
      ) {
        element.scrollIntoView({
          block: "start",
          inline: "nearest",
          behavior: "instant",
        });
        window.scrollBy?.({ top: -12, behavior: "instant" });
      }
    }
    measure();
    dialogRef.current?.focus({ preventScroll: true });
    const observer =
      typeof ResizeObserver !== "undefined" ? new ResizeObserver(update) : null;
    if (element) observer?.observe(element);
    if (dialogRef.current) observer?.observe(dialogRef.current);
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    window.visualViewport?.addEventListener("resize", update);
    return () => {
      observer?.disconnect();
      window.cancelAnimationFrame(raf);
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
      window.visualViewport?.removeEventListener("resize", update);
    };
  }, [target, title, stepKey]);
  useEffect(() => {
    if (typeof document === "undefined") return;
    const keys = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) {
        event.preventDefault();
        onExit();
        return;
      }
      if (event.key !== "Tab") return;
      const nodes: HTMLElement[] = [];
      const query =
        'button:not(:disabled), input:not(:disabled), select:not(:disabled), [href], [tabindex="0"]';
      if (interactive && !busy) {
        try {
          nodes.push(
            ...(document
              .querySelector(target)
              ?.querySelectorAll<HTMLElement>(query) || []),
          );
        } catch {
          /* invalid or missing target stays safe */
        }
      }
      nodes.push(
        ...(dialogRef.current?.querySelectorAll<HTMLElement>(query) || []),
      );
      const visible = nodes.filter((n) => n.getClientRects().length > 0);
      if (!visible.length) {
        event.preventDefault();
        dialogRef.current?.focus();
        return;
      }
      const index = visible.indexOf(document.activeElement as HTMLElement);
      const next = event.shiftKey
        ? index <= 0
          ? visible.length - 1
          : index - 1
        : (index + 1) % visible.length;
      event.preventDefault();
      visible[next].focus();
    };
    document.addEventListener("keydown", keys);
    return () => document.removeEventListener("keydown", keys);
  }, [target, interactive, busy, onExit]);
  const hole = layout?.hole;
  const content = (
    <div className="tutorial-spotlight-layer">
      {hole ? (
        <>
          <div
            className="tutorial-shade"
            style={{ top: 0, left: 0, right: 0, height: hole.top }}
          />
          <div
            className="tutorial-shade"
            style={{
              top: hole.top + hole.height,
              left: 0,
              right: 0,
              bottom: 0,
            }}
          />
          <div
            className="tutorial-shade"
            style={{
              top: hole.top,
              left: 0,
              width: hole.left,
              height: hole.height,
            }}
          />
          <div
            className="tutorial-shade"
            style={{
              top: hole.top,
              left: hole.left + hole.width,
              right: 0,
              height: hole.height,
            }}
          />
          <div
            className="tutorial-spotlight-ring"
            style={{
              ...hole,
              pointerEvents: interactive && !busy ? "none" : "auto",
            }}
          />
        </>
      ) : (
        <div className="tutorial-shade tutorial-shade-full" />
      )}
      <aside
        ref={dialogRef}
        className="tutorial-popup"
        role="dialog"
        tabIndex={-1}
        aria-modal="false"
        aria-label={title}
        style={layout?.dialog}
      >
        <div className="tutorial-popup-heading">
          <strong>{title}</strong>
          {stepLabel && <small>{stepLabel}</small>}
        </div>
        <div className="tutorial-popup-copy" aria-live="polite">
          {children}
        </div>
        <nav aria-label="教學步驟" className="tutorial-popup-actions">
          <button
            type="button"
            disabled={busy || !onPrevious}
            onClick={onPrevious}
          >
            上一步
          </button>
          {onNext && (
            <button
              type="button"
              className="primary"
              disabled={busy}
              onClick={onNext}
            >
              {nextLabel}
            </button>
          )}
          <button
            type="button"
            className="tutorial-popup-exit"
            disabled={busy}
            onClick={onExit}
          >
            離開教學
          </button>
        </nav>
      </aside>
    </div>
  );
  return typeof document !== "undefined" && document.body
    ? createPortal(content, document.body)
    : content;
}
