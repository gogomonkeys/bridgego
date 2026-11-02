import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent,
} from "react";
import type { Card } from "./model";
import { rankLabel, sortedHand, strainNames, symbols } from "./game-view";
import { client } from "./client";
export const cardKey = (card: Card) => `${card.suit}-${card.rank}`;
export const canSelectCard = (card: Card, legal: Card[], enabled: boolean) =>
  enabled && legal.some((c) => cardKey(c) === cardKey(card));
export default function HandPanel({
  cards,
  legalCards,
  enabled,
  collecting,
  playing,
}: {
  cards: Card[];
  legalCards: Card[];
  enabled: boolean;
  collecting: boolean;
  playing: boolean;
}) {
  const [mobile, setMobile] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia("(max-width: 730px)").matches,
  );
  const [expanded, setExpanded] = useState(false);
  const [selection, setSelection] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const sendingRef = useRef(false);
  const rail = useRef<HTMLDivElement>(null);
  const dragging = useRef<number | null>(null);
  useEffect(() => {
    const media = window.matchMedia("(max-width: 730px)");
    const change = () => setMobile(media.matches);
    media.addEventListener("change", change);
    return () => media.removeEventListener("change", change);
  }, []);
  const hand = sortedHand(cards);
  const selected = enabled
    ? hand.find(
        (c) =>
          cardKey(c) === selection && canSelectCard(c, legalCards, enabled),
      )
    : undefined;
  const submit = (card: Card) => {
    if (sendingRef.current || !canSelectCard(card, legalCards, enabled)) return;
    sendingRef.current = true;
    setSending(true);
    setSelection(null);
    void client.play(card).finally(() => {
      sendingRef.current = false;
      setSending(false);
    });
  };
  const selectAt = (event: PointerEvent<HTMLDivElement>) => {
    if (!mobile || expanded || !enabled || sendingRef.current) return;
    const buttons = [
      ...(rail.current?.querySelectorAll<HTMLButtonElement>(
        "button[data-card]",
      ) ?? []),
    ];
    const rect = rail.current?.getBoundingClientRect();
    if (
      !rect ||
      event.clientX < rect.left - 20 ||
      event.clientX > rect.right + 20
    )
      return;
    const found =
      buttons
        .filter((b) => b.getBoundingClientRect().left <= event.clientX)
        .at(-1) ?? buttons[0];
    if (found)
      setSelection(found.disabled ? null : (found.dataset.card ?? null));
  };
  return (
    <section
      className={`own-hand-panel ${mobile ? "mobile-hand" : ""}`}
      aria-label="你的私人手牌"
    >
      <div className="section-heading">
        <h3>
          你的手牌 <small>{hand.length} 張</small>
        </h3>
        {mobile && (
          <button
            type="button"
            className="hand-mode secondary"
            aria-pressed={expanded}
            onClick={() => setExpanded(!expanded)}
          >
            {expanded ? "重疊手牌" : "展開手牌"}
          </button>
        )}
      </div>
      <div
        className={`hand-cards ${mobile && !expanded ? "overlap-hand" : ""} ${expanded ? "expanded-hand" : ""}`}
        data-testid="own-hand"
        ref={rail}
        style={{ "--hand-count": hand.length } as CSSProperties}
        onPointerDown={(event) => {
          if (!mobile || expanded || !enabled) return;
          dragging.current = event.pointerId;
          event.currentTarget.setPointerCapture(event.pointerId);
          selectAt(event);
        }}
        onPointerMove={(event) => {
          if (dragging.current === event.pointerId) selectAt(event);
        }}
        onPointerUp={(event) => {
          if (dragging.current === event.pointerId) dragging.current = null;
        }}
        onPointerCancel={() => {
          dragging.current = null;
        }}
      >
        {hand.map((card, index) => (
          <button
            type="button"
            key={cardKey(card)}
            data-card={cardKey(card)}
            disabled={sending || !canSelectCard(card, legalCards, enabled)}
            onClick={() =>
              mobile ? setSelection(cardKey(card)) : submit(card)
            }
            onKeyDown={(event) => {
              if (
                !mobile ||
                !["ArrowLeft", "ArrowRight", "Escape"].includes(event.key)
              )
                return;
              event.preventDefault();
              if (event.key === "Escape") {
                setSelection(null);
                return;
              }
              const delta = event.key === "ArrowRight" ? 1 : -1;
              const next = hand
                .slice()
                .filter((c) => canSelectCard(c, legalCards, enabled));
              const current = next.findIndex(
                (c) => cardKey(c) === cardKey(card),
              );
              const target =
                next[(current + delta + next.length) % next.length];
              if (target) {
                setSelection(cardKey(target));
                rail.current
                  ?.querySelector<HTMLButtonElement>(
                    `[data-card="${cardKey(target)}"]`,
                  )
                  ?.focus();
              }
            }}
            className={`hand-card ${card.suit === "HEART" || card.suit === "DIAMOND" ? "red" : ""} ${selected && cardKey(selected) === cardKey(card) ? "selected-card" : ""}`}
            aria-label={`${strainNames[card.suit]} ${rankLabel(card.rank)}`}
            aria-pressed={
              mobile
                ? (selected && cardKey(selected) === cardKey(card)) || false
                : undefined
            }
            style={{ "--card-index": index } as CSSProperties}
          >
            <b>{rankLabel(card.rank)}</b>
            <span>{symbols[card.suit]}</span>
            <span className="card-pip" aria-hidden="true">
              {symbols[card.suit]}
            </span>
          </button>
        ))}
      </div>
      {mobile && playing && (enabled || selected || sending) && (
        <div className="hand-confirm">
          <span aria-live="polite">
            {selected
              ? `已選 ${symbols[selected.suit]} ${rankLabel(selected.rank)}`
              : collecting
                ? "本回合結算中"
                : enabled
                  ? "點選或左右滑動選牌"
                  : "等候輪到你出牌"}
          </span>
          {selected && (
            <span
              className={`selected-card-preview ${selected.suit === "HEART" || selected.suit === "DIAMOND" ? "red" : ""}`}
              aria-hidden="true"
            >
              <b>{rankLabel(selected.rank)}</b>
              <span>{symbols[selected.suit]}</span>
            </span>
          )}
          <button
            className="primary"
            type="button"
            disabled={!selected || sending}
            onClick={() => selected && submit(selected)}
          >
            出這張牌
          </button>
        </div>
      )}
      <p className="fine">
        {collecting
          ? "本回合結算中，下一回合尚未開始。"
          : playing
            ? mobile
              ? "選牌不會送出 · 按出牌確認 · 每次出牌 20 秒"
              : "亮框牌可出 · 有首引花色必須跟牌 · 每次出牌 20 秒"
            : "先看手牌；此階段不能出牌。"}
      </p>
    </section>
  );
}
