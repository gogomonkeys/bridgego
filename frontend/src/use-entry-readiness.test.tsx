import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useEntryReadiness } from "./use-entry-readiness";
import {
  EntryReadError,
  publicEntryRead,
  retryAfterTime,
} from "./public-entry-read";
const probes = vi.hoisted(() => ({
  health: vi.fn(),
  stats: vi.fn(),
  auth: vi.fn(),
}));
vi.mock("./statistics", () => ({ readStatisticsConfig: probes.stats }));
vi.mock("./google-login", () => ({ readAuthConfig: probes.auth }));
vi.mock("./public-entry-read", async (original) => ({
  ...(await original<typeof import("./public-entry-read")>()),
  readEntryHealth: probes.health,
}));
let state!: ReturnType<typeof useEntryReadiness>;
let view: ReactTestRenderer;
let visibility = "visible";
let listeners: (() => void)[];
function Harness() {
  state = useEntryReadiness();
  return null;
}
async function tick(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(1000000);
  visibility = "visible";
  listeners = [];
  vi.stubGlobal("document", {
    get visibilityState() {
      return visibility;
    },
    addEventListener: (_: string, fn: () => void) => listeners.push(fn),
    removeEventListener: (_: string, fn: () => void) => {
      listeners = listeners.filter((item) => item !== fn);
    },
  });
  probes.health.mockReset().mockResolvedValue("epoch-a");
  probes.stats.mockReset().mockResolvedValue({ enabled: false });
  probes.auth.mockReset().mockResolvedValue({ enabled: false, clientId: "" });
});
afterEach(() => {
  act(() => view?.unmount());
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
async function mount() {
  await act(async () => {
    view = create(<Harness />);
  });
}
describe("bounded entry readiness", () => {
  it("allows guests independently of unavailable Google, and retries only the failed config", async () => {
    probes.auth.mockRejectedValue(new Error("unreachable"));
    await mount();
    expect(state.ready).toBe(true);
    expect(state.authFailed).toBe(true);
    await tick(2000);
    expect(probes.auth).toHaveBeenCalledTimes(2);
    expect(probes.stats).toHaveBeenCalledTimes(1);
    expect(probes.health).toHaveBeenCalledTimes(2);
    probes.auth.mockResolvedValue({ enabled: true, clientId: "fixture" });
    await tick(4000);
    expect(state.phase).toBe("ready");
  });
  it("discards cached config after the ready process changes", async () => {
    probes.auth.mockRejectedValue(new Error("unreachable"));
    await mount();
    expect(state.ready).toBe(true);
    probes.health.mockResolvedValue("epoch-b");
    probes.stats.mockRejectedValue(new Error("new process not configured"));
    probes.auth.mockResolvedValue({ enabled: false, clientId: "" });
    await tick(2000);
    expect(probes.stats).toHaveBeenCalledTimes(2);
    expect(state.statistics).toBe(null);
    expect(state.ready).toBe(false);
  });
  it("does not hold guest readiness behind a slow Google response", async () => {
    probes.auth.mockImplementation(() => new Promise(() => {}));
    await mount();
    expect(state.ready).toBe(true);
  });
  it("does not request configs before game health, stops bounded retries and permits manual restart", async () => {
    probes.health.mockRejectedValue(new Error("offline"));
    await mount();
    expect(state.ready).toBe(false);
    expect(probes.stats).not.toHaveBeenCalled();
    await tick(120000);
    expect(probes.health).toHaveBeenCalledTimes(8);
    expect(state.phase).toBe("stopped");
    expect(state.retryAllowed).toBe(true);
    await tick(120000);
    expect(probes.health).toHaveBeenCalledTimes(8);
    probes.health.mockResolvedValue("epoch-a");
    await act(async () => state.retry());
    expect(state.ready).toBe(true);
  });
  it("honors Retry-After even when it exceeds the automatic budget", async () => {
    probes.health.mockRejectedValue(
      new EntryReadError(429, Date.now() + 180000),
    );
    await mount();
    expect(state.phase).toBe("stopped");
    expect(state.retryAllowed).toBe(false);
    await tick(120000);
    expect(probes.health).toHaveBeenCalledTimes(1);
    expect(state.retryAllowed).toBe(false);
    await tick(60000);
    expect(state.retryAllowed).toBe(true);
  });
  it("honors Retry-After on a bounded automatic retry", async () => {
    probes.health.mockRejectedValueOnce(
      new EntryReadError(429, Date.now() + 10000),
    );
    await mount();
    expect(state.phase).toBe("limited");
    await tick(9999);
    expect(probes.health).toHaveBeenCalledTimes(1);
    await tick(1);
    expect(state.ready).toBe(true);
  });
  it("stops while hidden and after unmount, and resumes only within the budget", async () => {
    probes.health.mockRejectedValue(new Error("offline"));
    await mount();
    act(() => {
      visibility = "hidden";
      listeners.forEach((fn) => fn());
    });
    await tick(20000);
    expect(probes.health).toHaveBeenCalledTimes(1);
    act(() => {
      visibility = "visible";
      listeners.forEach((fn) => fn());
    });
    await tick();
    expect(probes.health).toHaveBeenCalledTimes(2);
    act(() => view.unmount());
    await tick(120000);
    expect(probes.health).toHaveBeenCalledTimes(2);
  });
  it("does not restart completed reads when returning to the tab", async () => {
    await mount();
    act(() => {
      visibility = "hidden";
      listeners.forEach((fn) => fn());
    });
    act(() => {
      visibility = "visible";
      listeners.forEach((fn) => fn());
    });
    await tick(10000);
    expect(probes.health).toHaveBeenCalledTimes(1);
    expect(state.phase).toBe("ready");
  });
  it("shows maintenance rather than inferring sleep from a stopping server", async () => {
    probes.health.mockRejectedValue(new EntryReadError(503, 0, true));
    await mount();
    expect(state.phase).toBe("maintenance");
  });
});
describe("pure public reads", () => {
  it("sends no token, cookies, or mutation and validates game health shape", async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          status: "ready",
          instanceEpoch: "fixture",
          version: "test",
        }),
      ),
    );
    vi.stubGlobal("fetch", fetch);
    await publicEntryRead("/health/ready");
    expect(fetch.mock.calls[0][1]).toMatchObject({
      method: "GET",
      credentials: "omit",
      cache: "no-store",
    });
    expect(fetch.mock.calls[0][1]).not.toHaveProperty("headers");
    const actual = await vi.importActual<typeof import("./public-entry-read")>(
      "./public-entry-read",
    );
    fetch.mockResolvedValue(new Response(JSON.stringify({ enabled: true })));
    await expect(actual.readEntryHealth()).rejects.toThrow();
  });
  it("propagates external abort and cleans up timeout and listener", async () => {
    const controller = new AbortController();
    const remove = vi.spyOn(controller.signal, "removeEventListener");
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_path, options) =>
          new Promise((_resolve, reject) => {
            options.signal.addEventListener(
              "abort",
              () => reject(new DOMException("cancelled", "AbortError")),
              { once: true },
            );
          }),
      ),
    );
    const request = publicEntryRead("/health/ready", controller.signal);
    const assertion = expect(request).rejects.toMatchObject({
      name: "AbortError",
    });
    controller.abort();
    await assertion;
    expect(remove).toHaveBeenCalledWith("abort", expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });
  it("cleans up after successful reads and never fetches with an already aborted signal", async () => {
    const controller = new AbortController();
    const remove = vi.spyOn(controller.signal, "removeEventListener");
    const fetch = vi.fn().mockResolvedValue(new Response("{}"));
    vi.stubGlobal("fetch", fetch);
    await publicEntryRead("/api/auth/config", controller.signal);
    expect(remove).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    controller.abort();
    await expect(
      publicEntryRead("/api/auth/config", controller.signal),
    ).rejects.toMatchObject({ name: "AbortError" });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
  it("parses numeric/date cooldowns and rejects invalid dates", () => {
    expect(retryAfterTime("20", 1000)).toBe(21000);
    expect(retryAfterTime("Thu, 01 Jan 1970 00:01:00 GMT", 1000)).toBe(60000);
    expect(retryAfterTime("nonsense", 1000)).toBe(0);
  });
  it("extracts stopping response without classifying arbitrary 503 as maintenance", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ status: "stopping" }), { status: 503 }),
        ),
    );
    await expect(publicEntryRead("/health/ready")).rejects.toMatchObject({
      stopping: true,
    });
  });
});
