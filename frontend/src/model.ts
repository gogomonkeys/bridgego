export interface Session {
  token: string;
  playerId: string;
  displayName: string;
}
export type Team = "NS" | "EW";
export type Suit = "CLUB" | "DIAMOND" | "HEART" | "SPADE";
export type Strain = Suit | "NO_TRUMP";
export type Phase =
  | "WAITING"
  | "REDEAL_DECISION"
  | "PRIVILEGE_DECISION"
  | "BIDDING"
  | "DEAL_RESULT"
  | "PLAYING"
  | "TRICK_RESULT"
  | "FORFEIT_PENDING"
  | "MATCH_RESULT"
  | "ABORTED";
export interface Card {
  suit: Suit;
  rank: number;
}
export interface Call {
  type: "PASS" | "BID";
  level: number | null;
  strain: string | null;
}
export type PlayerType = "HUMAN" | "BOT";
export type ActionSource = "MANUAL" | "TIMEOUT" | "BOT" | "BOT_TAKEOVER";
export type ControlMode = "HUMAN" | "RECONNECTING" | "BOT_TAKEOVER" | "BOT";
export interface Player {
  controlMode?: ControlMode;
  playerType?: PlayerType;
  playerId: string;
  displayName: string;
  seat: number;
  ready: boolean;
  connected: boolean;
  disconnectedAt: number | null;
  reconnectDeadline: number | null;
  initialSubscriptionDeadline?: number | null;
  teamId: Team;
}
export interface OperationTimer {
  kind:
    | "REDEAL_DECISION"
    | "PRIVILEGE"
    | "BID_TURN"
    | "PLAY_TURN"
    | "DEAL_RESULT"
    | "TRICK_RESULT";
  turnSeat: number | null;
  remainingMs: number;
  paused: boolean;
}
export interface Play {
  seat: number;
  card: Card;
  source: ActionSource;
}
export interface AuctionRound {
  dealId: string;
  dealIndex: number;
  redealCount: number;
  dealerSeat: number;
  calls: Array<Call & { seat: number; source: ActionSource }>;
  result: Game["auction"]["result"];
  status: "IN_PROGRESS" | "ALL_PASS" | "CONTRACT";
}
export interface Trick {
  cardsVisible?: boolean;
  number: number;
  leaderSeat: number;
  plays: Play[];
  winnerSeat: number;
}
export interface TeamScore {
  won: boolean;
  privilegeUsed: boolean;
  base: number;
  exactTargetBonus: number;
  penalty: number;
  privilegeAdjustment: number;
  total: number;
}
export interface DealResult {
  dealId: string;
  dealIndex: number;
  dealerSeat: number;
  contract: {
    type: "CONTRACT";
    holderSeat: number;
    level: number;
    strain: Strain;
    teamId: Team;
  };
  privilegeUsed: Record<Team, boolean>;
  tricksByTeam: Record<Team, number>;
  scores: Record<Team, TeamScore>;
  completedTricks: Trick[];
  auctionRounds?: AuctionRound[];
}
export interface Game {
  gameId: string;
  dealId: string;
  plannedDeals: 1 | 3 | 5;
  dealIndex: number;
  completedDeals: number;
  dealerSeat: number;
  redealCount: number;
  lastRedealReason?: "HAND_REDEAL" | "ALL_PASS" | null;
  paused: boolean;
  abortReason: string | null;
  operationTimer: OperationTimer | null;
  redeal?: {
    yourPoints: number | null;
    eligible: boolean;
    yourChoice: boolean | null;
    canChoose: boolean;
  };
  privilege: {
    status: "PENDING" | "REVEALED";
    yourChoice: boolean | null;
    yourChoiceSource: ActionSource | null;
    canChoose: boolean;
    usedByTeam: Record<Team, boolean> | null;
  };
  auction: {
    nextSeat: number | null;
    calls: Array<Call & { seat: number; source: ActionSource }>;
    highestBid: { seat: number; level: number; strain: string } | null;
    result: {
      type: "CONTRACT";
      holderSeat: number;
      level: number;
      strain: string;
      teamId: Team;
    } | null;
  };
  auctionRounds?: AuctionRound[];
  roster: Array<{
    playerType?: PlayerType;
    playerId: string;
    displayName: string;
    seat: number;
    teamId: Team;
  }>;
  play: null | {
    nextSeat: number | null;
    leaderSeat: number;
    trickNumber: number;
    currentTrick: Play[];
    completedTricks: Trick[];
    tricksByTeam: Record<Team, number>;
  };
  resultAcknowledgedSeats: number[];
  teamTotals: Record<Team, number>;
  completedDealResults: DealResult[];
  forfeitEvents: Array<{
    playerId: string;
    seat: number;
    cause: "MANUAL_EXIT" | "RECONNECT_TIMEOUT";
  }>;
  pendingForfeit: null | {
    reason: "AWAITING_EXISTING_RECONNECT_GRACE";
    failedTeamIds: Team[];
    waitingSeats: number[];
  };
  matchResult: null | {
    outcome: "SCORED" | "FORFEIT" | "NO_CONTEST";
    winnerTeamId: Team | null;
    reason:
      | "THREE_DEALS_COMPLETE"
      | "PLANNED_DEALS_COMPLETE"
      | "MANUAL_EXIT"
      | "RECONNECT_TIMEOUT"
      | "BOTH_TEAMS_FAILED"
      | "NO_HUMANS_CONNECTED"
      | "NO_HUMANS_REMAIN";
    failedTeamIds: Team[];
  };
}
export interface Room {
  plannedDeals?: 1 | 3 | 5;
  allHumansReconnectDeadline?: number | null;
  roomId: string;
  revision: number;
  phase: Phase;
  capacity: 4;
  allReady: boolean;
  hostPlayerId: string | null;
  canStart: boolean;
  canRematch: boolean;
  lastMatch: MatchArchive | null;
  players: Player[];
  game: Game | null;
  you: {
    playerId: string;
    seat: number;
    teamId: Team;
    ownHand: Card[];
    legalCards: Card[];
    canAcknowledgeResult: boolean;
    legalCalls: Call[];
  } | null;
}
export interface MatchArchive {
  auctionRounds?: AuctionRound[];
  gameId: string;
  roster: Game["roster"];
  teamTotals: Game["teamTotals"];
  completedDealResults: DealResult[];
  matchResult: NonNullable<Game["matchResult"]>;
  forfeitEvents: Game["forfeitEvents"];
}
export interface RoomSummary {
  plannedDeals?: 1 | 3 | 5;
  state: "WAITING" | "ACTIVE" | "FINISHED";
  phase: Phase;
  joinable: boolean;
  roomId: string;
  playerCount: number;
  capacity: 4;
}
export interface CommandResult {
  actionId: string;
  outcome: "APPLIED" | "REPLAYED";
  appliedRevision: number;
  room: Room;
}
export function acceptSnapshot(
  current: Room | null,
  incoming: Room,
  roomId: string | null,
): Room | null {
  if (incoming.roomId !== roomId) return current;
  if (
    current?.roomId === incoming.roomId &&
    incoming.revision < current.revision
  )
    return current;
  return incoming;
}
