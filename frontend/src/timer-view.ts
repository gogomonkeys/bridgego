import type { OperationTimer, Room } from "./model";

// Display-only interpolation; reaching zero never submits an action or closes a socket.
export function operationRemainingMs(
  timer: OperationTimer | null,
  receivedAt: number,
  monotonicNow: number,
): number | null {
  if (!timer) return null;
  const sampled = Math.max(0, timer.remainingMs);
  if (timer.paused) return sampled;
  return Math.max(0, sampled - Math.max(0, monotonicNow - receivedAt));
}
export function operationTimerKey(room: Room): string | null {
  const game = room.game,
    timer = game?.operationTimer;
  return game && timer
    ? [
        game.gameId,
        game.dealId,
        timer.kind,
        timer.turnSeat ?? "all",
        game.auction.calls.length,
        game.play?.trickNumber ?? 0,
        game.play?.currentTrick.length ?? 0,
      ].join(":")
    : null;
}
export function timerDisplayAnchor(
  previous: Room | null,
  previousAnchor: number,
  incoming: Room,
  receivedNow: number,
): number {
  const before = previous?.game?.operationTimer,
    after = incoming.game?.operationTimer;
  // Equal revision can still carry a differently sampled remainingMs. A delayed response
  // must not move the same active turn's displayed deadline later. A newer revision may
  // include an unseen pause/resume, so its authoritative remaining budget is respected.
  if (
    previous &&
    previous.revision === incoming.revision &&
    before &&
    after &&
    !before.paused &&
    !after.paused &&
    operationTimerKey(previous) === operationTimerKey(incoming)
  ) {
    return (
      Math.min(
        receivedNow + after.remainingMs,
        previousAnchor + before.remainingMs,
      ) - after.remainingMs
    );
  }
  return receivedNow;
}
