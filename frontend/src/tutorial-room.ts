import type { Call, Room } from "./model";
import type { TutorialSnapshot } from "./tutorial";

/** Display adapter only. This room is never sent to a room endpoint or statistics. */
export function tutorialRoom(
  snapshot: TutorialSnapshot | null,
  call: Call | null = null,
): Room {
  const players: Room["players"] = [0, 1, 2, 3].map((seat) => ({
    playerId: `tutorial-seat-${seat}`,
    displayName: seat === 0 ? "你" : `電腦${seat}`,
    seat,
    teamId: seat % 2 === 0 ? "NS" : "EW",
    ready: true,
    connected: true,
    disconnectedAt: null,
    reconnectDeadline: null,
    playerType: seat === 0 ? "HUMAN" : "BOT",
    controlMode: seat === 0 ? "HUMAN" : "BOT",
  }));
  const completed = snapshot?.completedTricks ?? [];
  const current = snapshot?.currentTrick ?? [];
  const hand = snapshot?.hand ?? [
    ...[2, 3].map((rank) => ({ suit: "CLUB" as const, rank })),
    ...[2, 3].map((rank) => ({ suit: "HEART" as const, rank })),
    ...Array.from({ length: 9 }, (_, i) => ({
      suit: "SPADE" as const,
      rank: i + 2,
    })),
  ];
  const calls: Call[] = [{ type: "PASS", level: null, strain: null }];
  for (let level = 1; level <= 7; level++)
    for (const strain of ["CLUB", "DIAMOND", "HEART", "SPADE", "NO_TRUMP"])
      calls.push({ type: "BID", level, strain });
  return {
    roomId: `tutorial:${snapshot?.sessionId ?? "bidding"}`,
    revision: snapshot?.revision ?? 0,
    roomType: "CASUAL",
    phase: snapshot ? "PLAYING" : "BIDDING",
    capacity: 4,
    allReady: true,
    hostPlayerId: players[0].playerId,
    canStart: false,
    canRematch: false,
    lastMatch: null,
    players,
    you: {
      playerId: players[0].playerId,
      seat: 0,
      teamId: "NS",
      ownHand: hand,
      legalCards: snapshot?.legalCards ?? [],
      legalCalls: snapshot ? [] : calls,
      canAcknowledgeResult: false,
    },
    game: {
      gameId: snapshot?.sessionId ?? "tutorial-bidding",
      dealId: "tutorial-deal",
      plannedDeals: 1,
      dealIndex: 1,
      completedDeals: 0,
      dealerSeat: 2,
      redealCount: 0,
      paused: false,
      abortReason: null,
      operationTimer: null,
      privilege: {
        status: "REVEALED",
        yourChoice: false,
        yourChoiceSource: "MANUAL",
        canChoose: false,
        usedByTeam: null,
      },
      auction: {
        nextSeat: snapshot ? null : 0,
        calls: call ? [{ ...call, seat: 0, source: "MANUAL" }] : [],
        highestBid:
          call?.type === "BID"
            ? { seat: 0, level: call.level!, strain: call.strain! }
            : null,
        result: snapshot
          ? {
              type: "CONTRACT",
              holderSeat: 2,
              level: 1,
              strain: "SPADE",
              teamId: "NS",
            }
          : null,
      },
      roster: players,
      play: snapshot
        ? {
            nextSeat: snapshot.lesson === "COMPLETE" ? null : 0,
            leaderSeat: current[0]?.seat ?? completed.at(-1)?.winnerSeat ?? 1,
            trickNumber: Math.min(13, completed.length + 1),
            currentTrick: current,
            completedTricks: completed,
            tricksByTeam: {
              NS: completed.filter((t) => t.winnerSeat % 2 === 0).length,
              EW: completed.filter((t) => t.winnerSeat % 2 === 1).length,
            },
          }
        : null,
      resultAcknowledgedSeats: [],
      teamTotals: { NS: 0, EW: 0 },
      completedDealResults: [],
      forfeitEvents: [],
      pendingForfeit: null,
      matchResult: null,
    },
  };
}
