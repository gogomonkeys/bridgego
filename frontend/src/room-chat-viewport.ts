import type { CSSProperties } from "react";
/** Visible viewport coordinates: iOS keyboards can pan as well as resize the viewport. */
export function roomChatViewportStyle(
  win: Pick<Window, "innerWidth" | "innerHeight" | "visualViewport">,
): CSSProperties {
  const visible = win.visualViewport;
  const positive = (value: number | undefined, fallback: number) =>
    Number.isFinite(value) && value! > 0 ? value! : fallback;
  const offset = (value: number | undefined) =>
    Number.isFinite(value) ? Math.max(0, value!) : 0;
  return {
    "--room-chat-visible-width": `${positive(visible?.width, win.innerWidth)}px`,
    "--room-chat-visible-height": `${positive(visible?.height, win.innerHeight)}px`,
    "--room-chat-visible-left": `${offset(visible?.offsetLeft)}px`,
    "--room-chat-visible-top": `${offset(visible?.offsetTop)}px`,
  } as CSSProperties;
}
