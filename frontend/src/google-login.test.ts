import { afterEach, describe, expect, it, vi } from "vitest";
import { errorMessages } from "./error-messages";
import {
  AccountRequestError,
  createLoginAttempt,
  type GoogleIdentity,
} from "./google-login";
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
function setup() {
  let callback: (response: { credential?: string }) => void = () => undefined;
  const google: GoogleIdentity = {
    initialize: vi.fn((options) => {
      callback = options.callback;
    }),
    renderButton: vi.fn(),
    cancel: vi.fn(),
  };
  const deps = {
    nonce: vi.fn(async () => ({ nonce: "nonce" })),
    load: vi.fn(async () => google),
    link: vi.fn(async () => ({ signedIn: true })),
    verify: vi.fn(async () => ({
      issuer: "https://accounts.google.com",
      subject: "test-subject",
      admittedTester: false,
    })),
    verified: vi.fn(),
    cancel: vi.fn(async () => ({})),
    eligible: vi.fn(() => true),
    update: vi.fn(),
  };
  const controller = createLoginAttempt(deps);
  return {
    controller,
    deps,
    google,
    credential: (value = "test-only") => callback({ credential: value }),
    element: {} as HTMLElement,
  };
}
afterEach(() => vi.restoreAllMocks());
describe("optional consent-scoped Google attempts", () => {
  it.each([
    "EXPERIMENT_EXPIRED",
    "EXPERIMENT_UNAVAILABLE",
    "EXPERIMENT_MATCH_LIMIT",
    "TESTER_NOT_ADMITTED",
  ])("does not retry or claim a link after %s", async (code) => {
    const s = setup();
    s.deps.link.mockRejectedValue(new AccountRequestError(errorMessages[code]));
    await s.controller.start("configured", s.element, "link");
    s.credential();
    await Promise.resolve();
    expect(s.deps.link).toHaveBeenCalledTimes(1);
    expect(s.deps.verify).not.toHaveBeenCalled();
    expect(s.deps.update).toHaveBeenLastCalledWith(
      "idle",
      errorMessages[code],
      undefined,
    );
    expect(s.deps.update).not.toHaveBeenCalledWith(
      "idle",
      expect.anything(),
      true,
    );
  });
  it("reports a rejected nonce without loading Google or automatically retrying", async () => {
    const s = setup();
    s.deps.nonce.mockRejectedValue(
      new AccountRequestError("限定測試已結束；請勿重試保存。"),
    );
    await s.controller.start("configured", s.element, "link");
    expect(s.deps.nonce).toHaveBeenCalledTimes(1);
    expect(s.deps.load).not.toHaveBeenCalled();
    expect(s.deps.link).not.toHaveBeenCalled();
    expect(s.deps.update).toHaveBeenLastCalledWith(
      "idle",
      "限定測試已結束；請勿重試保存。",
      undefined,
    );
  });
  it("verification-only never invokes linking or reports a saved mapping", async () => {
    const s = setup();
    await s.controller.start("configured", s.element, "verify");
    expect(s.deps.nonce).toHaveBeenCalledWith("verify");
    s.credential();
    await Promise.resolve();
    expect(s.deps.verify).toHaveBeenCalledTimes(1);
    expect(s.deps.link).not.toHaveBeenCalled();
    expect(s.deps.verified).toHaveBeenCalledWith(
      expect.objectContaining({
        subject: "test-subject",
        admittedTester: false,
      }),
    );
    expect(s.deps.update).toHaveBeenLastCalledWith(
      "idle",
      expect.stringContaining("這次驗證不建立"),
      undefined,
    );
  });
  it("false or malformed linking status never claims account linkage", async () => {
    for (const status of [false, undefined, "true", 1]) {
      const s = setup();
      s.deps.link.mockResolvedValue({ signedIn: status } as {
        signedIn: boolean;
      });
      await s.controller.start("configured", s.element, "link");
      s.credential();
      await Promise.resolve();
      expect(s.deps.update).toHaveBeenLastCalledWith(
        "idle",
        expect.stringContaining("尚未確認"),
        false,
      );
    }
  });
  it("accepted link nonce clears previous linkage before the Google prompt", async () => {
    const s = setup();
    await s.controller.start("configured", s.element, "link");
    expect(s.deps.update).toHaveBeenCalledWith("loading", undefined, false);
  });
  it("unmount during submission cancels only its nonce and suppresses late result", async () => {
    const s = setup();
    const pending = deferred<{ signedIn: boolean }>();
    s.deps.link.mockReturnValue(pending.promise);
    await s.controller.start("configured", s.element, "link");
    s.credential();
    s.controller.dispose();
    expect(s.deps.cancel).toHaveBeenCalledWith("nonce");
    s.deps.update.mockClear();
    pending.resolve({ signedIn: true });
    await Promise.resolve();
    expect(s.deps.update).not.toHaveBeenCalled();
  });
  it("normal linking never silently falls back to verification discovery", async () => {
    const s = setup();
    s.deps.link.mockRejectedValue(new Error("TESTER_NOT_ADMITTED"));
    await s.controller.start("configured", s.element, "link");
    expect(s.deps.nonce).toHaveBeenCalledWith("link");
    s.credential();
    await Promise.resolve();
    expect(s.deps.verify).not.toHaveBeenCalled();
    expect(s.deps.verified).not.toHaveBeenCalled();
  });
  it("unmount during verification suppresses the subject result", async () => {
    const s = setup();
    const pending = deferred<{
      issuer: string;
      subject: string;
      admittedTester: boolean;
    }>();
    s.deps.verify.mockReturnValue(pending.promise);
    await s.controller.start("configured", s.element, "verify");
    s.credential();
    s.controller.dispose();
    pending.resolve({
      issuer: "https://accounts.google.com",
      subject: "hidden",
      admittedTester: true,
    });
    await Promise.resolve();
    expect(s.deps.verified).not.toHaveBeenCalled();
    expect(s.deps.link).not.toHaveBeenCalled();
  });
  it("a cancelled verification callback cannot reveal a subject", async () => {
    const s = setup();
    await s.controller.start("configured", s.element, "verify");
    s.controller.cancel();
    s.credential();
    await Promise.resolve();
    expect(s.deps.verify).not.toHaveBeenCalled();
    expect(s.deps.verified).not.toHaveBeenCalled();
  });

  it("does nothing without configured client ID or lobby eligibility", async () => {
    const s = setup();
    await s.controller.start("", s.element);
    s.deps.eligible.mockReturnValue(false);
    await s.controller.start("configured", s.element);
    expect(s.deps.load).not.toHaveBeenCalled();
    expect(s.deps.nonce).not.toHaveBeenCalled();
  });
  it("deduplicates repeated consent and credential clicks", async () => {
    const s = setup();
    await Promise.all([
      s.controller.start("configured", s.element),
      s.controller.start("configured", s.element),
    ]);
    expect(s.deps.nonce).toHaveBeenCalledTimes(1);
    expect(s.google.initialize).toHaveBeenCalledWith(
      expect.objectContaining({ nonce: "nonce", auto_select: false }),
    );
    s.credential();
    s.credential();
    await Promise.resolve();
    expect(s.deps.link).toHaveBeenCalledTimes(1);
  });
  it("ignores old callback after cancellation and a new attempt", async () => {
    const s = setup();
    await s.controller.start("configured", s.element);
    const old = vi.mocked(s.google.initialize).mock.calls[0][0].callback;
    s.controller.cancel();
    await s.controller.start("configured", s.element);
    old({ credential: "stale" });
    expect(s.deps.link).not.toHaveBeenCalled();
    s.credential();
    await Promise.resolve();
    expect(s.deps.link).toHaveBeenCalledTimes(1);
  });
  it("cancelled nonce load never contacts Google or submits credentials", async () => {
    const s = setup();
    const pending = deferred<{ nonce: string }>();
    s.deps.nonce.mockReturnValue(pending.promise);
    const start = s.controller.start("configured", s.element);
    s.controller.cancel();
    pending.resolve({ nonce: "old" });
    await start;
    expect(s.deps.load).not.toHaveBeenCalled();
    expect(s.deps.cancel).toHaveBeenCalledWith("old");
  });
  it("late nonce cleanup targets only the obsolete challenge", async () => {
    const s = setup();
    const oldNonce = deferred<{ nonce: string }>();
    s.deps.nonce
      .mockReturnValueOnce(oldNonce.promise)
      .mockResolvedValueOnce({ nonce: "new" });
    const oldStart = s.controller.start("configured", s.element);
    s.controller.cancel();
    await s.controller.start("configured", s.element);
    oldNonce.resolve({ nonce: "old" });
    await oldStart;
    expect(s.deps.cancel).toHaveBeenCalledWith("old");
    expect(s.deps.cancel).not.toHaveBeenCalledWith("new");
    s.credential();
    await Promise.resolve();
    expect(s.deps.link).toHaveBeenCalledTimes(1);
  });
  it("unmount during script load suppresses initialization and updates", async () => {
    const s = setup();
    const pending = deferred<GoogleIdentity>();
    s.deps.load.mockReturnValue(pending.promise);
    const start = s.controller.start("configured", s.element);
    await Promise.resolve();
    s.controller.dispose();
    s.deps.update.mockClear();
    pending.resolve(s.google);
    await start;
    expect(s.google.initialize).not.toHaveBeenCalled();
    expect(s.deps.update).not.toHaveBeenCalled();
  });
  it("room entry or changed guest makes Google callbacks inert", async () => {
    const s = setup();
    await s.controller.start("configured", s.element);
    s.deps.eligible.mockReturnValue(false);
    s.credential();
    expect(s.deps.link).not.toHaveBeenCalled();
  });
  it("failed Google load returns to guest path without submitting", async () => {
    const s = setup();
    s.deps.load.mockRejectedValue(new Error("blocked"));
    await s.controller.start("configured", s.element);
    expect(s.deps.update).toHaveBeenLastCalledWith(
      "idle",
      expect.stringContaining("訪客"),
      undefined,
    );
    expect(s.deps.link).not.toHaveBeenCalled();
  });
  it("cannot cancel an exchange already submitted; unmount suppresses late success", async () => {
    const s = setup();
    const pending = deferred<{ signedIn: boolean }>();
    s.deps.link.mockReturnValue(pending.promise);
    await s.controller.start("configured", s.element);
    s.credential();
    s.controller.cancel();
    expect(s.google.cancel).not.toHaveBeenCalled();
    s.controller.dispose();
    s.deps.update.mockClear();
    pending.resolve({ signedIn: true });
    await Promise.resolve();
    expect(s.deps.update).not.toHaveBeenCalled();
  });
});

describe("one-time account recovery", () => {
  function recoverySetup() {
    const base = setup();
    const recover = vi.fn(async () => ({ signedIn: true }));
    const recoverySubmitted = vi.fn();
    const controller = createLoginAttempt({
      ...base.deps,
      recover,
      recoverySubmitted,
    });
    return { ...base, controller, recover, recoverySubmitted };
  }
  it("uses only explicit recovery nonce and endpoint and does not create an identity", async () => {
    const s = recoverySetup();
    await s.controller.start("configured", s.element, "recover");
    expect(s.deps.nonce).toHaveBeenCalledWith("recover");
    s.credential();
    await Promise.resolve();
    expect(s.recoverySubmitted).toHaveBeenCalledOnce();
    expect(s.recover).toHaveBeenCalledOnce();
    expect(s.deps.link).not.toHaveBeenCalled();
    expect(s.deps.verify).not.toHaveBeenCalled();
    expect(s.deps.update).toHaveBeenLastCalledWith(
      "idle",
      expect.stringContaining("已恢復"),
      true,
    );
  });
  it("blocks retry and normal-login fallback after uncertain submission", async () => {
    const s = recoverySetup();
    s.recover.mockRejectedValue(new Error("network"));
    await s.controller.start("configured", s.element, "recover");
    s.credential();
    await Promise.resolve();
    await s.controller.start("configured", s.element, "recover");
    await s.controller.start("configured", s.element, "link");
    expect(s.recover).toHaveBeenCalledOnce();
    expect(s.deps.nonce).toHaveBeenCalledOnce();
    expect(s.deps.link).not.toHaveBeenCalled();
    expect(s.deps.update).toHaveBeenLastCalledWith(
      "idle",
      expect.stringContaining("不要重新登入"),
      undefined,
    );
  });
  it("blocks a duplicate callback during and after submission", async () => {
    const s = recoverySetup();
    const pending = deferred<{ signedIn: boolean }>();
    s.recover.mockReturnValue(pending.promise);
    await s.controller.start("configured", s.element, "recover");
    s.credential();
    s.credential();
    s.controller.cancel();
    expect(s.recover).toHaveBeenCalledOnce();
    pending.resolve({ signedIn: true });
    await Promise.resolve();
    s.credential();
    expect(s.recover).toHaveBeenCalledOnce();
  });
  it("cancel before credential prevents late callback without consuming recovery", async () => {
    const s = recoverySetup();
    await s.controller.start("configured", s.element, "recover");
    const old = vi.mocked(s.google.initialize).mock.calls[0][0].callback;
    s.controller.cancel();
    old({ credential: "old" });
    expect(s.recover).not.toHaveBeenCalled();
    expect(s.recoverySubmitted).not.toHaveBeenCalled();
    await s.controller.start("configured", s.element, "recover");
    old({ credential: "old" });
    s.credential();
    await Promise.resolve();
    expect(s.recover).toHaveBeenCalledOnce();
  });
  it("expiry/navigation makes callbacks inert", async () => {
    const s = recoverySetup();
    await s.controller.start("configured", s.element, "recover");
    s.deps.eligible.mockReturnValue(false);
    s.credential();
    expect(s.recover).not.toHaveBeenCalled();
  });
  it("dispose ignores pending recovery result", async () => {
    const s = recoverySetup();
    const pending = deferred<{ signedIn: boolean }>();
    s.recover.mockReturnValue(pending.promise);
    await s.controller.start("configured", s.element, "recover");
    s.credential();
    s.controller.dispose();
    const count = s.deps.update.mock.calls.length;
    pending.resolve({ signedIn: true });
    await Promise.resolve();
    expect(s.deps.update.mock.calls).toHaveLength(count);
  });
  it("missing recovery implementation does not downgrade to normal link", async () => {
    const s = setup();
    await s.controller.start("configured", s.element, "recover");
    expect(s.deps.nonce).not.toHaveBeenCalled();
    expect(s.deps.link).not.toHaveBeenCalled();
  });
  it("false recovery response stays blocked rather than inviting a retry", async () => {
    const s = recoverySetup();
    s.recover.mockResolvedValue({ signedIn: false });
    await s.controller.start("configured", s.element, "recover");
    s.credential();
    await Promise.resolve();
    await s.controller.start("configured", s.element, "recover");
    expect(s.recover).toHaveBeenCalledOnce();
    expect(s.deps.update).toHaveBeenLastCalledWith(
      "idle",
      expect.stringContaining("不要重試"),
      false,
    );
  });
});

it("does not revoke a submitted recovery after navigation or network timeout", async () => {
  const s = setup();
  const pending = deferred<{ signedIn: boolean }>();
  const recover = vi.fn(() => pending.promise);
  const controller = createLoginAttempt({ ...s.deps, recover });
  await controller.start("configured", s.element, "recover");
  s.credential();
  controller.dispose();
  expect(s.deps.cancel).not.toHaveBeenCalled();
  pending.resolve({ signedIn: true });
  await Promise.resolve();
  expect(s.deps.cancel).not.toHaveBeenCalled();
});


describe("normal login response reconciliation", () => {
  it("reads status after a lost response without cancelling the valid server link", async () => {
    const s = setup();
    s.deps.link.mockRejectedValue(new Error("timeout"));
    const readStatus = vi.fn(async () => ({ signedIn: true }));
    const controller = createLoginAttempt({ ...s.deps, readStatus });
    await controller.start("configured", s.element, "link");
    s.credential();
    for (let i = 0; i < 5; i++) await Promise.resolve();
    expect(readStatus).toHaveBeenCalledOnce();
    expect(s.deps.cancel).not.toHaveBeenCalled();
    expect(s.deps.link).toHaveBeenCalledOnce();
    expect(s.deps.nonce).toHaveBeenCalledOnce();
    expect(s.deps.update).toHaveBeenLastCalledWith("idle", expect.stringContaining("登入成功"), true);
    controller.dispose();
    expect(s.deps.cancel).not.toHaveBeenCalled();
  });
  it("waits for a delayed attachment without prompting for Google a second time", async () => {
    vi.useFakeTimers();
    try {
      const s = setup();
      s.deps.link.mockRejectedValue(new Error("timeout"));
      const readStatus = vi.fn().mockResolvedValueOnce({ signedIn: false }).mockResolvedValue({ signedIn: true });
      const controller = createLoginAttempt({ ...s.deps, readStatus });
      await controller.start("configured", s.element, "link");
      s.credential();
      await vi.advanceTimersByTimeAsync(1000);
      expect(readStatus).toHaveBeenCalledTimes(2);
      expect(s.deps.link).toHaveBeenCalledOnce();
      expect(s.deps.nonce).toHaveBeenCalledOnce();
      expect(s.deps.cancel).not.toHaveBeenCalled();
      expect(s.deps.update).toHaveBeenLastCalledWith("idle", expect.any(String), true);
    } finally { vi.useRealTimers(); }
  });
  it("does not claim success or cancel when bounded status reads stay uncertain", async () => {
    vi.useFakeTimers();
    try {
      const s = setup();
      s.deps.link.mockRejectedValue(new Error("timeout"));
      const readStatus = vi.fn(async () => ({ signedIn: false }));
      const controller = createLoginAttempt({ ...s.deps, readStatus });
      await controller.start("configured", s.element, "link");
      s.credential();
      await vi.runAllTimersAsync();
      expect(readStatus).toHaveBeenCalledTimes(5);
      expect(s.deps.cancel).not.toHaveBeenCalled();
      expect(s.deps.update).toHaveBeenLastCalledWith("idle", expect.stringContaining("重新確認帳號狀態"), undefined);
      expect(s.deps.link).toHaveBeenCalledOnce();
    } finally { vi.useRealTimers(); }
  });
  it("suppresses stale status after navigation and preserves explicit cancellation", async () => {
    const s = setup();
    s.deps.link.mockRejectedValue(new Error("timeout"));
    const pending = deferred<{ signedIn: boolean }>();
    const readStatus = vi.fn(() => pending.promise);
    const controller = createLoginAttempt({ ...s.deps, readStatus });
    await controller.start("configured", s.element, "link");
    s.credential();
    for (let i = 0; i < 3; i++) await Promise.resolve();
    controller.dispose();
    s.deps.update.mockClear();
    pending.resolve({ signedIn: true });
    for (let i = 0; i < 3; i++) await Promise.resolve();
    expect(s.deps.update).not.toHaveBeenCalled();
    expect(s.deps.cancel).toHaveBeenCalledOnce();
  });
  it("keeps explicit authentication failures rejected without status fallback", async () => {
    const s = setup();
    s.deps.link.mockRejectedValue(new AccountRequestError("Google 驗證未成功"));
    const readStatus = vi.fn(async () => ({ signedIn: true }));
    const controller = createLoginAttempt({ ...s.deps, readStatus });
    await controller.start("configured", s.element, "link");
    s.credential();
    for (let i = 0; i < 3; i++) await Promise.resolve();
    expect(readStatus).not.toHaveBeenCalled();
    expect(s.deps.cancel).toHaveBeenCalledOnce();
    expect(s.deps.update).toHaveBeenLastCalledWith("idle", "Google 驗證未成功", undefined);
  });
});
