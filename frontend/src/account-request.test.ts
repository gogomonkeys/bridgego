import { afterEach, describe, expect, it, vi } from "vitest";
const state = vi.hoisted(() => ({
  session: { token: "current" } as { token: string } | null,
}));
vi.mock("./client", () => ({ getClientState: () => state }));
import { accountRequest, readAuthConfig } from "./google-login";
afterEach(() => {
  vi.unstubAllGlobals();
  state.session = { token: "current" };
});
describe("public config and identity-scoped account requests", () => {
  it("reads public config without creating or sending a guest identity", async () => {
    state.session = null;
    const fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ enabled: false, clientId: "" }),
    }));
    vi.stubGlobal("fetch", fetch);
    expect(await readAuthConfig()).toEqual({ enabled: false, clientId: "" });
    const [path, options] = fetch.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ];
    expect(path).toBe("/api/auth/config");
    expect(options.headers).toBeUndefined();
    expect(options.body).toBeUndefined();
    expect(options.cache).toBe("no-store");
  });
  it("does not send an obsolete attempt under a replacement token", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(
      accountRequest("/auth/cancel", "POST", { nonce: "old" }, "old-token"),
    ).rejects.toThrow("身分已改變");
    expect(fetch).not.toHaveBeenCalled();
  });
  it("checks session identity again after delayed response parsing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({
        ok: true,
        json: async () => {
          state.session = { token: "replacement" };
          return { signedIn: true };
        },
      })),
    );
    await expect(
      accountRequest("/auth/google", "POST", {}, "current"),
    ).rejects.toThrow("身分已改變");
  });
});
