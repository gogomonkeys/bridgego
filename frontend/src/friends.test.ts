import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { FriendsError, friendsRequest, inviteAvailable } from "./friends";
const identity = vi.hoisted(() => ({ token: "test" }));
vi.mock("./client", () => ({
  getClientState: () => ({ session: { token: identity.token } }),
}));
beforeEach(() => {
  identity.token = "test";
  vi.stubGlobal("fetch", vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
});
describe("friends transport", () => {
  it("authenticates reads without creating action IDs or writing", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ enabled: true })),
    );
    await friendsRequest("test");
    const [url, options] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/friends");
    expect(options?.method).toBe("GET");
    expect(options?.body).toBeUndefined();
    expect(options?.headers).toMatchObject({ Authorization: "Bearer test" });
  });
  it("sends exactly once with operation identity and no automatic retry", async () => {
    vi.mocked(fetch).mockRejectedValue(new Error("network"));
    await expect(
      friendsRequest("test", "/requests", "POST", { friendCode: "ABC" }),
    ).rejects.toMatchObject({ uncertain: true });
    expect(fetch).toHaveBeenCalledTimes(1);
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
    expect(body.actionId).toMatch(/^[a-f0-9-]{36}$/);
    expect(body.friendCode).toBe("ABC");
  });
  it("rejects stale identity before transmitting", async () => {
    identity.token = "other";
    await expect(friendsRequest("test")).rejects.toBeInstanceOf(FriendsError);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("rejects late response after identity replacement", async () => {
    vi.mocked(fetch).mockImplementation(async () => {
      identity.token = "other";
      return new Response("{}");
    });
    await expect(
      friendsRequest("test", "/initialize", "POST"),
    ).rejects.toMatchObject({ uncertain: true });
  });
  it("marks unauthorized or disabled routes unavailable", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response("{}", { status: 403 }));
    await expect(friendsRequest("test")).rejects.toMatchObject({
      unavailable: true,
      uncertain: false,
    });
  });
  it("does not permit expired, malformed or occupied-room invitations", () => {
    const invitation = {
      inviteId: "i",
      inviterNickname: "N",
      roomName: "R",
      expiresAt: "2026-11-17T12:00:00Z",
    };
    expect(
      inviteAvailable(invitation, null, Date.parse("2026-11-17T11:59:00Z")),
    ).toBe(true);
    expect(inviteAvailable(invitation, {}, 0)).toBe(false);
    expect(
      inviteAvailable(invitation, null, Date.parse(invitation.expiresAt)),
    ).toBe(false);
    expect(
      inviteAvailable({ ...invitation, expiresAt: "invalid" }, null, 0),
    ).toBe(false);
  });
});
