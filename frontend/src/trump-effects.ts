import type { Play, Room, Suit } from "./model";

export const TRUMP_EFFECT_MS = 1500;
export type TrumpEffectKind = "wash" | "cut";
export interface TrumpEffect {
  id: string;
  trickKey: string;
  seat: number;
  kind: TrumpEffectKind;
  expiresAt: number;
  epoch: number;
}
interface Frame {
  context: string;
  revision: number;
  phase: Room["phase"];
  epoch: number;
  online: boolean;
  paused: boolean;
  trick: number | null;
  trump: Suit | null;
  plays: Play[];
}
export function trumpEffectKind(
  plays: Play[],
  index: number,
  trump: Suit | null,
): TrumpEffectKind | null {
  if (!trump || plays[index]?.card.suit !== trump) return null;
  if (index === 0) return "wash";
  return plays[0]?.card.suit !== trump ? "cut" : null;
}
function frame(room: Room, online: boolean, epoch: number): Frame {
  const game = room.game;
  const strain = game?.auction.result?.strain;
  return {
    context: JSON.stringify([room.roomId, game?.gameId, game?.dealId]),
    revision: room.revision,
    phase: room.phase,
    epoch,
    online,
    paused: game?.paused ?? false,
    trick: game?.play?.trickNumber ?? null,
    trump:
      strain && ["CLUB", "DIAMOND", "HEART", "SPADE"].includes(strain)
        ? (strain as Suit)
        : null,
    // Only the currently public trick. Never inspect completed/hidden history.
    plays:
      game?.play?.currentTrick.map((play) => ({
        ...play,
        card: { ...play.card },
      })) ?? [],
  };
}
const playKey = (play: Play) =>
  `${play.seat}:${play.card.suit}:${play.card.rank}`;
const keyFor = (f: Frame) => `${f.context}:${f.trick}`;
const playing = (f: Frame) =>
  f.phase === "PLAYING" || f.phase === "TRICK_RESULT";

/** Presentation-only live edge detector. No server commands or authoritative timers. */
export function createTrumpEffects(
  room: Room,
  online: boolean,
  epoch: number,
  now: () => number = () => performance.now(),
) {
  let previous = frame(room, online, epoch);
  let events: TrumpEffect[] = [];
  let timer: ReturnType<typeof setTimeout> | undefined;
  const listeners = new Set<() => void>();
  const publish = (next: TrumpEffect[]) => {
    if (
      next.length === events.length &&
      next.every((event, i) => event === events[i])
    )
      return;
    events = next;
    listeners.forEach((listener) => listener());
  };
  const stop = () => {
    clearTimeout(timer);
    timer = undefined;
  };
  const schedule = () => {
    stop();
    if (!events.length) return;
    timer = setTimeout(
      () => {
        publish(events.filter((event) => event.expiresAt > now()));
        schedule();
      },
      Math.max(0, Math.min(...events.map((event) => event.expiresAt)) - now()),
    );
  };
  const update = (room: Room, online: boolean, epoch: number) => {
    const next = frame(room, online, epoch);
    // Ignore obsolete HTTP/WS responses even if a caller missed its revision guard.
    if (
      next.context === previous.context &&
      next.epoch === previous.epoch &&
      next.revision < previous.revision
    ) {
      schedule();
      return;
    }
    const continuous =
      next.context === previous.context &&
      next.epoch === previous.epoch &&
      next.online &&
      previous.online &&
      !next.paused &&
      !previous.paused;
    let start = -1;
    if (continuous && playing(next) && next.trump === previous.trump) {
      if (
        next.trick === previous.trick &&
        next.plays.length >= previous.plays.length &&
        previous.plays.every(
          (play, i) => playKey(play) === playKey(next.plays[i]),
        )
      ) {
        start = previous.plays.length;
      } else if (previous.trick !== null && next.trick === previous.trick + 1) {
        // Empty-next-trick and first-card snapshots may be batched by React.
        start = 0;
      }
    }
    // Auction→play may establish the contract and first card in one render.
    if (
      continuous &&
      previous.phase === "BIDDING" &&
      playing(next) &&
      next.trick === 1
    )
      start = 0;
    const time = now();
    let visible =
      continuous && playing(next) && start >= 0
        ? events.filter(
            (event) =>
              event.trickKey === keyFor(next) && event.expiresAt > time,
          )
        : [];
    if (start >= 0) {
      for (let index = start; index < next.plays.length; index++) {
        const kind = trumpEffectKind(next.plays, index, next.trump);
        if (!kind) continue;
        const play = next.plays[index];
        const id = `${keyFor(next)}:${index}:${playKey(play)}`;
        if (!visible.some((event) => event.id === id))
          visible.push({
            id,
            trickKey: keyFor(next),
            seat: play.seat,
            kind,
            expiresAt: time + TRUMP_EFFECT_MS,
            epoch: next.epoch,
          });
      }
    }
    previous = next;
    visible = visible.slice(-4);
    publish(visible);
    schedule();
  };
  return {
    update,
    stop,
    getSnapshot: () => events,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
