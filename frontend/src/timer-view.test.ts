import { describe, expect, it } from "vitest";
import { gameRoom, playedRoom } from "./test-fixtures";
import {
  operationRemainingMs,
  operationTimerKey,
  timerDisplayAnchor,
} from "./timer-view";
describe("server timer presentation only", () => {
  it("counts down from sampled remaining time, never a new local 20-second budget", () => {
    const timer = {
      kind: "BID_TURN" as const,
      turnSeat: 0,
      remainingMs: 7000,
      paused: false,
    };
    expect(operationRemainingMs(timer, 1000, 4000)).toBe(4000);
    expect(operationRemainingMs(timer, 1000, 4000)).toBe(4000); // Same rerender, same anchor.
  });
  it("freezes the exact remaining budget while paused", () => {
    expect(
      operationRemainingMs(
        { kind: "PRIVILEGE", turnSeat: null, remainingMs: 6134, paused: true },
        1000,
        99000,
      ),
    ).toBe(6134);
  });
  it("clamps a delayed timer tick at zero without manufacturing a result", () => {
    const timer = {
      kind: "BID_TURN" as const,
      turnSeat: 0,
      remainingMs: 3000,
      paused: false,
    };
    expect(operationRemainingMs(timer, 1000, 99999)).toBe(0);
    expect(timer.remainingMs).toBe(3000);
  });
  it("does not move a same-revision same-turn deadline later on an old response", () => {
    const first = gameRoom("BIDDING");
    first.game!.operationTimer!.remainingMs = 10000;
    const delayed = structuredClone(first);
    delayed.game!.operationTimer!.remainingMs = 12000;
    const anchor = timerDisplayAnchor(first, 1000, delayed, 4000);
    expect(
      operationRemainingMs(delayed.game!.operationTimer, anchor, 4000),
    ).toBe(7000);
  });
  it("accepts a smaller fresh sample without extending the turn", () => {
    const first = gameRoom("BIDDING"),
      incoming = structuredClone(first);
    incoming.game!.operationTimer!.remainingMs = 5000;
    const anchor = timerDisplayAnchor(first, 1000, incoming, 4000);
    expect(
      operationRemainingMs(incoming.game!.operationTimer, anchor, 4000),
    ).toBe(5000);
  });
  it("keys new turns and redeals separately and permits their fresh budgets", () => {
    const first = gameRoom("BIDDING"),
      next = structuredClone(first);
    next.revision++;
    next.game!.auction.calls.push({
      seat: 0,
      type: "PASS",
      level: null,
      strain: null,
      source: "TIMEOUT",
    });
    next.game!.operationTimer!.turnSeat = 1;
    expect(operationTimerKey(first)).not.toBe(operationTimerKey(next));
    expect(timerDisplayAnchor(first, 1000, next, 16000)).toBe(16000);
    const redeal = gameRoom();
    redeal.game!.dealId = "new-deal";
    expect(operationTimerKey(redeal)).not.toBe(operationTimerKey(first));
  });
  it("resumes the server-frozen budget and shows no timer in terminal phases", () => {
    const paused = gameRoom();
    paused.game!.operationTimer!.paused = true;
    paused.game!.operationTimer!.remainingMs = 6000;
    const resumed = structuredClone(paused);
    resumed.revision++;
    resumed.game!.operationTimer!.paused = false;
    const anchor = timerDisplayAnchor(paused, 1000, resumed, 9000);
    expect(
      operationRemainingMs(resumed.game!.operationTimer, anchor, 10000),
    ).toBe(5000);
    expect(operationRemainingMs(null, 0, 99999)).toBeNull();
  });
});

it("separates consecutive play turns and repeated leaders across tricks", () => {
  const first = playedRoom(),
    next = structuredClone(first);
  next.game!.play!.currentTrick.push({
    seat: 0,
    card: { suit: "SPADE", rank: 2 },
    source: "MANUAL",
  });
  expect(operationTimerKey(first)).not.toBe(operationTimerKey(next));
  next.game!.play!.currentTrick = [];
  next.game!.play!.trickNumber++;
  expect(operationTimerKey(first)).not.toBe(operationTimerKey(next));
  next.revision++;
  expect(timerDisplayAnchor(first, 1000, next, 21000)).toBe(21000);
});
it("result acknowledgment snapshots cannot extend equal-revision budget and next privilege has a new key", () => {
  const result = gameRoom("DEAL_RESULT");
  result.game!.operationTimer = {
    kind: "DEAL_RESULT",
    turnSeat: null,
    remainingMs: 12000,
    paused: false,
  };
  const later = structuredClone(result);
  later.game!.operationTimer!.remainingMs = 14000;
  const anchor = timerDisplayAnchor(result, 1000, later, 4000);
  expect(operationRemainingMs(later.game!.operationTimer, anchor, 4000)).toBe(
    9000,
  );
  const next = gameRoom();
  next.game!.dealId = "next-deal";
  expect(operationTimerKey(next)).not.toBe(operationTimerKey(result));
});
