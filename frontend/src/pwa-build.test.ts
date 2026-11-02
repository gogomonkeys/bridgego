import { describe, expect, it, vi } from "vitest";
import { runInNewContext } from "node:vm";
import { serviceWorkerSource } from "../pwa-build";

function worker() {
  const handlers: Record<string, (event: unknown) => void> = {};
  const stored = new Map<string, Response>();
  const cache = {
    put: vi.fn(async (key, value) => {
      stored.set(key, value);
    }),
    match: vi.fn(async (key) => stored.get(key)),
  };
  const caches = {
    open: vi.fn(async () => cache),
    keys: vi.fn(async () => ["other-app", "bridgego-static-old"]),
    delete: vi.fn(async () => true),
  };
  const fetch = vi.fn<(...args: unknown[]) => Promise<Response>>(
    async () =>
      new Response("<html/>", { headers: { "Content-Type": "text/html" } }),
  );
  const self = {
    location: { origin: "https://bridge.test" },
    addEventListener: (type: string, fn: (event: unknown) => void) => {
      handlers[type] = fn;
    },
    skipWaiting: vi.fn(),
    clients: { claim: vi.fn() },
  };
  runInNewContext(serviceWorkerSource("new", ["/index.html"]), {
    self,
    caches,
    fetch,
    URL,
    Set,
    Error,
  });
  return { handlers, stored, cache, caches, fetch, self };
}
describe("production static-only service worker", () => {
  it("installs valid shell and never forces activation or claims game windows", async () => {
    const w = worker();
    let work!: Promise<void>;
    w.handlers.install({
      waitUntil: (p: Promise<void>) => {
        work = p;
      },
    });
    await work;
    expect(w.stored.has("/index.html")).toBe(true);
    expect(w.self.skipWaiting).not.toHaveBeenCalled();
    w.handlers.activate({
      waitUntil: (p: Promise<void>) => {
        work = p;
      },
    });
    await work;
    expect(w.caches.delete).toHaveBeenCalledWith("bridgego-static-old");
    expect(w.caches.delete).not.toHaveBeenCalledWith("other-app");
    expect(w.self.clients.claim).not.toHaveBeenCalled();
  });
  it("rejects error pages during install", async () => {
    const w = worker();
    w.fetch.mockResolvedValue(new Response("error", { status: 503 }));
    let work!: Promise<void>;
    w.handlers.install({
      waitUntil: (p: Promise<void>) => {
        work = p;
      },
    });
    await expect(work).rejects.toThrow("Static shell unavailable");
    expect(w.cache.put).not.toHaveBeenCalled();
    expect(w.caches.delete).toHaveBeenCalledWith("bridgego-static-new");
  });
  it("never caches API/health or intercepts mutations, unknown routes and query URLs", async () => {
    const w = worker();
    for (const path of [
      "/api/me",
      "/api/rooms/private",
      "/health/ready",
      "/ws",
    ]) {
      let response!: Promise<Response>;
      w.handlers.fetch({
        request: { url: "https://bridge.test" + path, method: "GET" },
        respondWith: (p: Promise<Response>) => {
          response = p;
        },
      });
      await response;
      expect(w.fetch.mock.calls.at(-1)?.[1]).toEqual({ cache: "no-store" });
    }
    expect(w.caches.open).not.toHaveBeenCalled();
    for (const [path, method] of [
      ["/api/rooms", "POST"],
      ["/unknown", "GET"],
      ["/index.html?token=x", "GET"],
    ]) {
      const respondWith = vi.fn();
      w.handlers.fetch({
        request: { url: "https://bridge.test" + path, method },
        respondWith,
      });
      expect(respondWith).not.toHaveBeenCalled();
    }
  });
  it("serves offline navigation from shell only", async () => {
    const w = worker();
    w.stored.set("/index.html", new Response("offline shell"));
    let response!: Promise<Response>;
    w.handlers.fetch({
      request: { url: "https://bridge.test/", method: "GET", mode: "navigate" },
      respondWith: (p: Promise<Response>) => {
        response = p;
      },
    });
    expect(await (await response).text()).toBe("offline shell");
    expect(w.fetch).not.toHaveBeenCalled();
  });
});
