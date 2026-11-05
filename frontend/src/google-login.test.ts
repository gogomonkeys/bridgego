import { afterEach, describe, expect, it, vi } from "vitest";
import { createLoginAttempt, type GoogleIdentity } from "./google-login";
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
      expect.stringContaining("尚未建立帳號連結"),
      false,
    );
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
