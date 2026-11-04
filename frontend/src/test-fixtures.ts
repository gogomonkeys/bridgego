import type { Room, Phase } from "./model";
export function gameRoom(phase: Phase = "PRIVILEGE_DECISION"): Room {
  return {
    roomId: "ROOM01",
    revision: 10,
    phase,
    capacity: 4,
    allReady: true,
    hostPlayerId: "alice",
    canStart: false,
    canRematch: false,
    lastMatch: null,
    players: ["alice", "bob", "carol", "dan"].map((playerId, seat) => ({
      playerId,
      displayName: playerId,
      seat,
      teamId: seat % 2 === 0 ? ("NS" as const) : ("EW" as const),
      ready: true,
      connected: true,
      disconnectedAt: null,
      reconnectDeadline: null,
      initialSubscriptionDeadline: null,
    })),
    game: {
      gameId: "game-one",
      dealId: "deal-one",
      plannedDeals: 3,
      dealIndex: 1,
      completedDeals: 0,
      dealerSeat: 0,
      redealCount: 0,
      paused: false,
      abortReason: null,
      operationTimer:
        phase === "PRIVILEGE_DECISION"
          ? {
              kind: "PRIVILEGE",
              turnSeat: null,
              remainingMs: 20000,
              paused: false,
            }
          : phase === "BIDDING"
            ? {
                kind: "BID_TURN",
                turnSeat: 0,
                remainingMs: 20000,
                paused: false,
              }
            : null,
      privilege: {
        status: "PENDING",
        yourChoice: null,
        yourChoiceSource: null,
        canChoose: true,
        usedByTeam: null,
      },
      auction: {
        nextSeat: phase === "BIDDING" ? 0 : null,
        calls: [],
        highestBid: null,
        result: null,
      },
      roster: ["alice", "bob", "carol", "dan"].map((playerId, seat) => ({
        playerId,
        displayName: playerId,
        seat,
        teamId: seat % 2 === 0 ? ("NS" as const) : ("EW" as const),
      })),
      play: null,
      forfeitEvents: [],
      pendingForfeit: null,
      matchResult: null,
      resultAcknowledgedSeats: [],
      teamTotals: { NS: 0, EW: 0 },
      completedDealResults: [],
    },
    you: {
      playerId: "alice",
      seat: 0,
      teamId: "NS",
      ownHand: Array.from({ length: 13 }, (_, i) => ({
        suit: "SPADE",
        rank: i + 2,
      })),
      legalCards: [],
      canAcknowledgeResult: false,
      legalCalls:
        phase === "BIDDING"
          ? [
              { type: "PASS", level: null, strain: null },
              { type: "BID", level: 1, strain: "CLUB" },
            ]
          : [],
    },
  };
}

export function playedRoom(): Room {
  const room = gameRoom("PLAYING");
  room.game!.privilege.status = "REVEALED";
  room.game!.privilege.canChoose = false;
  room.game!.privilege.usedByTeam = { NS: false, EW: false };
  room.game!.auction.result = {
    type: "CONTRACT",
    holderSeat: 3,
    level: 2,
    strain: "HEART",
    teamId: "EW",
  };
  room.game!.operationTimer = {
    kind: "PLAY_TURN",
    turnSeat: 0,
    remainingMs: 20000,
    paused: false,
  };
  room.game!.play = {
    nextSeat: 0,
    leaderSeat: 0,
    trickNumber: 1,
    currentTrick: [],
    completedTricks: [],
    tricksByTeam: { NS: 0, EW: 0 },
  };
  room.you!.legalCards = [room.you!.ownHand[0]];
  return room;
}
export function completedDeal(): import("./model").DealResult {
  return {
    dealId: "completed-one",
    dealIndex: 1,
    dealerSeat: 0,
    contract: {
      type: "CONTRACT",
      holderSeat: 0,
      level: 2,
      strain: "HEART",
      teamId: "NS",
    },
    privilegeUsed: { NS: true, EW: false },
    tricksByTeam: { NS: 8, EW: 5 },
    scores: {
      NS: {
        won: true,
        privilegeUsed: true,
        base: 2,
        exactTargetBonus: 2,
        penalty: 0,
        privilegeAdjustment: 1,
        total: 5,
      },
      EW: {
        won: false,
        privilegeUsed: false,
        base: 0,
        exactTargetBonus: 0,
        penalty: 0,
        privilegeAdjustment: 0,
        total: 0,
      },
    },
    completedTricks: [],
  };
}
