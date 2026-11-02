import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";
import { gameRoom } from "./test-fixtures";
import type { Play, Room, Suit } from "./model";
import {
  createTrumpEffects,
  trumpEffectKind,
  TRUMP_EFFECT_MS,
} from "./trump-effects";
import TrumpEffects from "./TrumpEffects";
import PublicTrick from "./PublicTrick";
const play = (
  seat: number,
  suit: Suit = "SPADE",
  rank = 10,
  source: Play["source"] = "MANUAL",
): Play => ({ seat, card: { suit, rank }, source });
function room(plays: Play[] = [], revision = 10, trick = 1): Room {
  const r = gameRoom("PLAYING");
  r.revision = revision;
  r.game!.auction.result = {
    type: "CONTRACT",
    holderSeat: 0,
    level: 1,
    strain: "SPADE",
    teamId: "NS",
  };
  r.game!.play = {
    nextSeat: plays.length % 4,
    leaderSeat: 0,
    trickNumber: trick,
    currentTrick: plays,
    completedTricks: [],
    tricksByTeam: { NS: 0, EW: 0 },
  };
  return r;
}
const clone = (r: Room) => structuredClone(r);
describe("trump effect definition", () => {
  it("washes only on a trump lead, cuts every later trump on a non-trump lead", () => {
    expect(trumpEffectKind([play(0)], 0, "SPADE")).toBe("wash");
    expect(trumpEffectKind([play(0), play(1)], 1, "SPADE")).toBeNull();
    const plays = [
      play(0, "HEART"),
      play(1, "SPADE", 14),
      play(2, "SPADE", 2),
      play(3, "SPADE", 13),
    ];
    expect(plays.map((_, i) => trumpEffectKind(plays, i, "SPADE"))).toEqual([
      null,
      "cut",
      "cut",
      "cut",
    ]);
    expect(plays.map((_, i) => trumpEffectKind(plays, i, null))).toEqual([
      null,
      null,
      null,
      null,
    ]);
  });
});
describe("authoritative snapshot edge tracking", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());
  const tracker = (r = room(), online = true, epoch = 0) =>
    createTrumpEffects(r, online, epoch, () => Date.now());
  it("does not replay initial cards, restored history or no-trump games", () => {
    const r = room([play(0)]);
    const t = tracker(r);
    expect(t.getSnapshot()).toEqual([]);
    t.update(clone(r), true, 0);
    expect(t.getSnapshot()).toEqual([]);
    const nt = room();
    nt.game!.auction.result!.strain = "NO_TRUMP";
    const n = tracker(nt);
    const next = clone(nt);
    next.revision++;
    next.game!.play!.currentTrick = [play(0)];
    n.update(next, true, 0);
    expect(n.getSnapshot()).toEqual([]);
  });
  it("emits once on appended manual lead, does not restart on revision/timer/duplicate snapshots", () => {
    const t = tracker();
    const r = room([play(0)], 11);
    t.update(r, true, 0);
    expect(t.getSnapshot()).toHaveLength(1);
    const first = t.getSnapshot()[0];
    vi.advanceTimersByTime(900);
    const tick = clone(r);
    tick.revision++;
    t.update(tick, true, 0);
    expect(t.getSnapshot()[0]).toBe(first);
    vi.advanceTimersByTime(599);
    expect(t.getSnapshot()).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(t.getSnapshot()).toEqual([]);
    t.update({ ...r, revision: 13 }, true, 0);
    expect(t.getSnapshot()).toEqual([]);
  });
  it("auto timeout matches manual; every rapid cut has its own lifetime, no queue", () => {
    const lead = play(0, "HEART");
    const t = tracker(room([lead]));
    t.update(room([lead, play(1, "SPADE", 14, "TIMEOUT")], 11), true, 0);
    expect(t.getSnapshot().map((e) => e.kind)).toEqual(["cut"]);
    vi.advanceTimersByTime(500);
    const fourth = room(
      [
        lead,
        play(1, "SPADE", 14, "TIMEOUT"),
        play(2, "SPADE", 2),
        play(3, "SPADE", 13),
      ],
      12,
    );
    fourth.phase = "TRICK_RESULT";
    t.update(fourth, true, 0);
    expect(t.getSnapshot().map((e) => e.seat)).toEqual([1, 2, 3]);
    vi.advanceTimersByTime(1000);
    expect(t.getSnapshot().map((e) => e.seat)).toEqual([2, 3]);
    vi.advanceTimersByTime(500);
    expect(t.getSnapshot()).toEqual([]);
  });
  it("suppresses reconnect snapshots even when offline renders were batched away", () => {
    const t = tracker();
    t.update(room([play(0)], 11), true, 1);
    expect(t.getSnapshot()).toEqual([]);
    const next = room([play(0, "HEART")], 12, 2);
    t.update(next, true, 1);
    expect(t.getSnapshot()).toEqual([]);
    t.update(room([play(0, "HEART"), play(1)], 13, 2), true, 1);
    expect(t.getSnapshot()).toHaveLength(1);
  });
  it("clears while offline/paused, baselines on recovery, never replays past plays", () => {
    const t = tracker();
    const r = room([play(0)], 11);
    t.update(r, true, 0);
    t.update(r, false, 0);
    expect(t.getSnapshot()).toEqual([]);
    t.update(r, true, 0);
    expect(t.getSnapshot()).toEqual([]);
    const p = clone(r);
    p.game!.paused = true;
    t.update(p, true, 0);
    p.game!.paused = false;
    t.update(p, true, 0);
    expect(t.getSnapshot()).toEqual([]);
  });
  it("normal new trick emits lead even if empty snapshot was batched away", () => {
    const old = room([play(0), play(1), play(2), play(3)], 11);
    old.phase = "TRICK_RESULT";
    const t = tracker(old);
    t.update(room([play(2)], 12, 2), true, 0);
    expect(t.getSnapshot().map((e) => [e.kind, e.seat])).toEqual([["wash", 2]]);
  });
  it("first play after bidding emits, but new game/deal/room baselines", () => {
    const bid = gameRoom("BIDDING");
    const t = tracker(bid);
    t.update(room([play(0)], 11), true, 0);
    expect(t.getSnapshot()).toHaveLength(1);
    for (const change of ["room", "game", "deal"] as const) {
      const r = room([play(1)], 12);
      if (change === "room") r.roomId = "other";
      if (change === "game") r.game!.gameId = "other";
      if (change === "deal") r.game!.dealId = "other";
      t.update(r, true, 0);
      expect(t.getSnapshot()).toEqual([]);
    }
  });
  it("does not replay after backwards or replaced play arrays and skips multi-trick catch-up", () => {
    const r = room([play(0)], 11);
    const t = tracker(r);
    t.update(room([], 10), true, 0);
    t.update({ ...r, revision: 12 }, true, 0);
    expect(t.getSnapshot()).toEqual([]);
    t.update(room([play(2)], 13), true, 0);
    expect(t.getSnapshot()).toEqual([]);
    t.update(room([play(0)], 14, 5), true, 0);
    expect(t.getSnapshot()).toEqual([]);
  });
  it("clears on exit/result and cleanup cancels callbacks without extending StrictMode re-setup", () => {
    const t = tracker();
    const r = room([play(0)], 11);
    const listener = vi.fn();
    const unsub = t.subscribe(listener);
    t.update(r, true, 0);
    vi.advanceTimersByTime(1000);
    t.stop();
    t.update(clone(r), true, 0);
    vi.advanceTimersByTime(500);
    expect(t.getSnapshot()).toEqual([]);
    const end = room([], 12);
    end.phase = "DEAL_RESULT";
    t.update(end, true, 0);
    expect(t.getSnapshot()).toEqual([]);
    unsub();
    t.stop();
    expect(vi.getTimerCount()).toBe(0);
  });
  it("keeps the original expiry after a stale update cancels the React effect timer", () => {
    const t = tracker();
    t.update(room([play(0)], 12), true, 0);
    vi.advanceTimersByTime(1000);
    t.stop();
    t.update(room([], 11), true, 0);
    vi.advanceTimersByTime(500);
    expect(t.getSnapshot()).toEqual([]);
    t.stop();
  });
  it("never reads hidden completed history or changes input/server timers", () => {
    const r = room();
    Object.defineProperty(r.game!.play!, "completedTricks", {
      get() {
        throw Error("private history read");
      },
    });
    const t = tracker(r);
    t.update(room([play(0)], 11), true, 0);
    expect(t.getSnapshot()).toHaveLength(1);
    expect(r.game!.play!.currentTrick).toEqual([]);
    expect(TRUMP_EFFECT_MS).toBe(1500);
  });
  it("renders two distinct large-text classes and matching unobstructive card flares", () => {
    const t = tracker(room([play(0, "HEART")]));
    const r = room([play(0, "HEART"), play(1)], 11);
    t.update(r, true, 0);
    const html = renderToStaticMarkup(
      createElement(TrumpEffects, { events: t.getSnapshot(), ownSeat: 0 }),
    );
    expect(html).toContain("切王");
    expect(html).toContain("trump-cut");
    expect(html).toContain('data-effect-seat="1"');
    const cards = renderToStaticMarkup(
      createElement(PublicTrick, {
        plays: r.game!.play!.currentTrick,
        roster: r.game!.roster,
        ownSeat: 0,
        effects: t.getSnapshot(),
      }),
    );
    expect(cards).toContain("trump-card-flare trump-cut");
    expect(cards).toContain("played-rank");
    expect(cards).not.toContain("切王");
  });
});
