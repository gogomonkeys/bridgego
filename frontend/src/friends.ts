import { errorMessages } from "./error-messages";
import { getClientState } from "./client";
export type Presence = "ONLINE" | "IN_GAME" | "OFFLINE";
export interface Friend {
  friendCode: string;
  nickname: string;
  presence: Presence;
  joinable: boolean;
}
export interface FriendRequest {
  requestId: string;
  friendCode: string;
  nickname: string;
  direction: "INCOMING" | "OUTGOING";
}
export interface RoomInvite {
  inviteId: string;
  inviterNickname: string;
  roomName: string;
  expiresAt: string;
}
export interface FriendsSnapshot {
  enabled: boolean;
  friendCode: string | null;
  friends: Friend[];
  requests: FriendRequest[];
  invites: RoomInvite[];
  pendingCount: number;
  tablePlayers: {
    playerId: string;
    nickname: string;
    eligible: boolean;
    relation: "NONE" | "PENDING" | "FRIEND";
  }[];
}
export class FriendsError extends Error {
  constructor(
    message: string,
    public uncertain = false,
    public unavailable = false,
  ) {
    super(message);
  }
}
const messages: Record<string, string> = {
  FRIEND_NOT_FOUND: "找不到這個好友碼。",
  FRIEND_SELF: "不能加入自己。",
  FRIEND_CAPACITY: "好友資料容量已滿，請聯絡管理者。",
  FRIEND_LIMIT: "好友或申請數量已達上限。",
  FRIEND_REQUEST_NOT_FOUND: "這份申請已失效，請重新讀取。",
  FRIEND_INVITE_EXPIRED: "邀請已過期。",
  INVITE_EXPIRED: "邀請已過期。",
  ROOM_FULL: "房間已滿。",
  ROOM_NOT_FOUND: "房間已關閉。",
  GAME_ALREADY_STARTED: "房間已開始遊戲。",
  ALREADY_IN_ROOM: "你已在房間內，請先自行離開再加入。",
  RATE_LIMITED: "操作太頻繁，請稍後。",
  FRIEND_TARGET_UNAVAILABLE: "這位玩家目前無法接受好友申請。",
};
export async function friendsRequest<T>(
  token: string,
  path = "",
  method = "GET",
  body?: object,
): Promise<T> {
  if (getClientState().session?.token !== token)
    throw new FriendsError("登入狀態已改變。");
  let response: Response;
  try {
    response = await fetch(`/api/friends${path}`, {
      method,
      cache: "no-store",
      signal: AbortSignal.timeout(60000),
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body:
        method === "GET"
          ? undefined
          : JSON.stringify({ ...body, actionId: crypto.randomUUID() }),
    });
  } catch {
    throw new FriendsError(
      method === "GET"
        ? "暫時無法讀取好友。"
        : "操作結果尚未確認，請勿重複送出。",
      method !== "GET",
    );
  }
  if (getClientState().session?.token !== token)
    throw new FriendsError("登入狀態已改變。", method !== "GET");
  let data: unknown;
  try {
    data = await response.json();
  } catch {
    throw new FriendsError("回應尚未確認，請勿重複送出。", method !== "GET");
  }
  if (!response.ok) {
    const code = (data as { code?: string })?.code ?? "";
    throw new FriendsError(
      errorMessages[code] ??
        messages[code] ??
        (response.status === 401 || response.status === 403
          ? "請使用 Google 帳號登入。"
          : "好友服務暫時無法完成操作。"),
      method !== "GET" && response.status >= 500,
      response.status === 401 ||
        response.status === 403 ||
        response.status === 404,
    );
  }
  return data as T;
}
export const presenceLabel: Record<Presence, string> = {
  ONLINE: "在線",
  IN_GAME: "遊戲中",
  OFFLINE: "離線",
};
export function inviteAvailable(
  invite: RoomInvite,
  room: unknown,
  now = Date.now(),
): boolean {
  return (
    !room &&
    Number.isFinite(Date.parse(invite.expiresAt)) &&
    Date.parse(invite.expiresAt) > now
  );
}
