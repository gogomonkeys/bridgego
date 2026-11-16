import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import "./tutorial-spotlight.css";
type Box = {
  top: number;
  left: number;
  width: number;
  height: number;
  radius?: number;
};
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
  const x = Math.max(4, target.left),
    y = Math.max(4, target.top);
  const right = Math.min(width - 4, target.left + target.width);
  const bottom = Math.min(height - 4, target.top + target.height);
  const hole =
    right > x && bottom > y
      ? {
          left: x,
          top: y,
          width: right - x,
          height: bottom - y,
          borderRadius: target.radius ?? 14,
        }
      : null;
  const below = target.top + target.height + 12,
    above = target.top - dh - 12;
  const top =
    below + dh <= height - 12
      ? below
      : above >= 12
        ? above
        : target.top - dh - 6 >= 8
          ? target.top - dh - 6
          : target.top + target.height + 6 + dh <= height - 8
            ? target.top + target.height + 6
            : Math.max(12, height - dh - 12);
  return { hole, dialog: { top, left, width: dw } };
}
/** Only asks for a one-time placement correction when both blocks can fit. */
export function spotlightNeedsAdjustment(
  target: Box,
  height: number,
  dialogHeight: number,
) {
  return (
    target.top + target.height + dialogHeight + 24 > height &&
    target.top - dialogHeight - 12 < 12 &&
    target.height + dialogHeight + 36 <= height
  );
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
  complete = false,
}: {
  complete?: boolean;
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
    let adjustmentPending = false;
    let adjustmentBudget = 2;
    let userInteracted = false;
    const stopAdjustment = () => {
      userInteracted = true;
    };
    const getTarget = () => {
      if (!target) return null;
      try {
        return document.querySelector<HTMLElement>(target);
      } catch {
        return null;
      }
    };
    const measure = (allowAdjustment = false) => {
      const element = getTarget();
      let box = element?.getBoundingClientRect();
      const measuredHeight = dialogRef.current?.getBoundingClientRect().height;
      const dh = measuredHeight || 190;
      // The first measurement can precede the popup's final width/text wrap.
      // ResizeObserver may correct placement, but scrolling itself never does.
      // Stop correcting as soon as the person interacts, and cap attempts.
      if (
        allowAdjustment &&
        measuredHeight &&
        element &&
        box &&
        !userInteracted &&
        adjustmentBudget > 0 &&
        spotlightNeedsAdjustment(box, window.innerHeight, dh) &&
        typeof element.scrollIntoView === "function"
      ) {
        adjustmentBudget--;
        element.scrollIntoView({
          block: "start",
          inline: "nearest",
          behavior: "instant",
        });
        window.scrollBy?.({ top: -12, behavior: "instant" });
        box = element.getBoundingClientRect();
      }
      const radius = element
        ? parseFloat(window.getComputedStyle(element).borderTopLeftRadius) || 0
        : 0;
      setLayout(
        spotlightLayout(
          box
            ? {
                top: box.top,
                left: box.left,
                width: box.width,
                height: box.height,
                radius,
              }
            : null,
          window.innerWidth,
          window.innerHeight,
          dh,
        ),
      );
    };
    const update = (allowAdjustment = false) => {
      adjustmentPending ||= allowAdjustment;
      window.cancelAnimationFrame(raf);
      raf = window.requestAnimationFrame(() => {
        const shouldAdjust = adjustmentPending;
        adjustmentPending = false;
        measure(shouldAdjust);
      });
    };
    const resizeUpdate = () => update(true);
    const scrollUpdate = () => update(false);
    const element = getTarget();
    if (element && typeof element.scrollIntoView === "function")
      element.scrollIntoView({
        block: "center",
        inline: "nearest",
        behavior: "instant",
      });
    measure(true);
    dialogRef.current?.focus({ preventScroll: true });
    const observer =
      typeof ResizeObserver !== "undefined"
        ? new ResizeObserver(resizeUpdate)
        : null;
    if (element) observer?.observe(element);
    if (dialogRef.current) observer?.observe(dialogRef.current);
    window.addEventListener("resize", resizeUpdate);
    window.addEventListener("scroll", scrollUpdate, true);
    window.visualViewport?.addEventListener("resize", resizeUpdate);
    for (const event of ["pointerdown", "touchstart", "wheel", "keydown"])
      window.addEventListener(event, stopAdjustment, {
        capture: true,
        passive: true,
      });
    return () => {
      for (const event of ["pointerdown", "touchstart", "wheel", "keydown"])
        window.removeEventListener(event, stopAdjustment, true);
      observer?.disconnect();
      window.cancelAnimationFrame(raf);
      window.removeEventListener("resize", resizeUpdate);
      window.removeEventListener("scroll", scrollUpdate, true);
      window.visualViewport?.removeEventListener("resize", resizeUpdate);
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
    <div className={`tutorial-spotlight-layer${hole ? " has-spotlight" : ""}`}>
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
          {onNext && !complete && (
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
            className={
              complete
                ? "primary tutorial-popup-complete"
                : "tutorial-popup-exit"
            }
            disabled={busy}
            onClick={onExit}
          >
            {complete ? "完成，回大廳" : "離開教學"}
          </button>
        </nav>
      </aside>
    </div>
  );
  return typeof document !== "undefined" && document.body
    ? createPortal(content, document.body)
    : content;
}
