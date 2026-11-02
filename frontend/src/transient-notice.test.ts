import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";
import {
  createTransientNotice,
  INFORMATION_NOTICE_MS,
} from "./transient-notice";
import OwnTimeoutNotice, { ownPrivilegeTimeoutKey } from "./OwnTimeoutNotice";
import { gameRoom } from "./test-fixtures";
const timedOutRoom = () => {
  const room = gameRoom("BIDDING");
  room.game!.privilege = {
    status: "REVEALED",
    yourChoice: false,
    yourChoiceSource: "TIMEOUT",
    canChoose: false,
    usedByTeam: { NS: false, EW: false },
  };
  return room;
};
describe("transient informational notice lifetime", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());
  it("shows for five seconds then removes the text", () => {
    const n = createTransientNotice("a");
    n.activate("a");
    vi.advanceTimersByTime(INFORMATION_NOTICE_MS - 1);
    expect(n.getSnapshot()).toBe("a");
    vi.advanceTimersByTime(1);
    expect(n.getSnapshot()).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });
  it("does not prolong on repeated snapshots or StrictMode cleanup/setup", () => {
    const n = createTransientNotice("a");
    n.activate("a");
    vi.advanceTimersByTime(2000);
    n.stop();
    n.activate("a");
    n.activate("a");
    vi.advanceTimersByTime(3000);
    expect(n.getSnapshot()).toBeNull();
    n.activate("a");
    expect(n.getSnapshot()).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });
  it("new events get a full window and old timers cannot dismiss them", () => {
    const n = createTransientNotice("a");
    n.activate("a");
    vi.advanceTimersByTime(4000);
    n.activate("b");
    vi.advanceTimersByTime(1000);
    expect(n.getSnapshot()).toBe("b");
    vi.advanceTimersByTime(4000);
    expect(n.getSnapshot()).toBeNull();
  });
  it("phase change dismisses immediately without replay on returning", () => {
    const n = createTransientNotice("a");
    n.activate("a");
    vi.advanceTimersByTime(1000);
    n.activate(null);
    expect(n.getSnapshot()).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    n.activate("a");
    expect(n.getSnapshot()).toBeNull();
    n.activate("new-deal");
    expect(n.getSnapshot()).toBe("new-deal");
    n.stop();
  });
  it("unmount cleanup removes timer without later state notification", () => {
    const n = createTransientNotice("a");
    const listener = vi.fn();
    const unsubscribe = n.subscribe(listener);
    n.activate("a");
    listener.mockClear();
    unsubscribe();
    n.stop();
    vi.advanceTimersByTime(20000);
    expect(listener).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
  it("delayed re-setup does not revive an elapsed window", () => {
    const n = createTransientNotice("a");
    n.activate("a");
    n.stop();
    vi.advanceTimersByTime(8000);
    n.activate("a");
    expect(n.getSnapshot()).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });
});
describe("own privilege timeout eligibility", () => {
  it("requires own revealed timeout provenance, not a manual no vote", () => {
    const room = timedOutRoom();
    expect(ownPrivilegeTimeoutKey(room)).not.toBeNull();
    expect(
      renderToStaticMarkup(createElement(OwnTimeoutNotice, { room })),
    ).toContain('role="status"');
    room.game!.privilege.yourChoiceSource = "MANUAL";
    expect(ownPrivilegeTimeoutKey(room)).toBeNull();
    room.game!.privilege.yourChoiceSource = "TIMEOUT";
    room.game!.privilege.status = "PENDING";
    expect(ownPrivilegeTimeoutKey(room)).toBeNull();
  });
  it("does not show stale notice during play, settlement or reconnect judgment", () => {
    const room = timedOutRoom();
    for (const phase of [
      "PLAYING",
      "TRICK_RESULT",
      "DEAL_RESULT",
      "MATCH_RESULT",
      "FORFEIT_PENDING",
      "ABORTED",
    ] as const) {
      room.phase = phase;
      expect(ownPrivilegeTimeoutKey(room)).toBeNull();
      expect(
        renderToStaticMarkup(createElement(OwnTimeoutNotice, { room })),
      ).toBe("");
    }
  });
  it("ignores revision and turn updates but distinguishes deal/redeal/player", () => {
    const room = timedOutRoom();
    const original = ownPrivilegeTimeoutKey(room);
    room.revision += 1;
    room.game!.auction.nextSeat = 2;
    expect(ownPrivilegeTimeoutKey(room)).toBe(original);
    room.game!.redealCount += 1;
    expect(ownPrivilegeTimeoutKey(room)).not.toBe(original);
    const redeal = ownPrivilegeTimeoutKey(room);
    room.game!.dealId += "-next";
    expect(ownPrivilegeTimeoutKey(room)).not.toBe(redeal);
    const deal = ownPrivilegeTimeoutKey(room);
    room.you!.playerId += "-other";
    expect(ownPrivilegeTimeoutKey(room)).not.toBe(deal);
  });
});
