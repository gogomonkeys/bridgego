import { describe, expect, it, vi } from "vitest";
import { ScreenAwakeController, type ScreenLock } from "./screen-awake";
function fakeLock() {
  let onRelease = () => {};
  const lock: ScreenLock = {
    released: false,
    release: vi.fn(async () => {
      lock.released = true;
      onRelease();
    }),
    addEventListener: (_type, cb) => {
      onRelease = cb;
    },
  };
  return {
    lock,
    releaseBySystem: () => {
      lock.released = true;
      onRelease();
    },
  };
}
const flush = async () => {
  for (let i = 0; i < 8; i++) await Promise.resolve();
};
describe("optional foreground screen lock", () => {
  it("requests only after opt-in and active visible game; repeated updates deduplicate", async () => {
    const { lock } = fakeLock();
    const request = vi.fn(async () => lock);
    const report = vi.fn();
    const c = new ScreenAwakeController(request, report);
    c.configure(false, true, true);
    c.configure(true, false, true);
    c.configure(true, true, false);
    expect(request).not.toHaveBeenCalled();
    c.configure(true, true, true);
    c.configure(true, true, true);
    await flush();
    expect(request).toHaveBeenCalledTimes(1);
    expect(report).toHaveBeenLastCalledWith("active");
    c.configure(true, false, true);
    expect(lock.release).toHaveBeenCalledOnce();
  });
  it("releases when hidden and reacquires when visible, but not after user disables", async () => {
    const a = fakeLock();
    const b = fakeLock();
    const request = vi
      .fn()
      .mockResolvedValueOnce(a.lock)
      .mockResolvedValueOnce(b.lock);
    const c = new ScreenAwakeController(request, vi.fn());
    c.configure(true, true, true);
    await flush();
    c.configure(true, true, false);
    expect(a.lock.release).toHaveBeenCalledOnce();
    c.configure(true, true, true);
    await flush();
    expect(request).toHaveBeenCalledTimes(2);
    c.configure(false, true, true);
    expect(b.lock.release).toHaveBeenCalledOnce();
    c.configure(false, true, false);
    c.configure(false, true, true);
    expect(request).toHaveBeenCalledTimes(2);
  });
  it("releases late requests after leaving or disposal", async () => {
    const { lock } = fakeLock();
    let resolve!: (l: ScreenLock) => void;
    const c = new ScreenAwakeController(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
      vi.fn(),
    );
    c.configure(true, true, true);
    c.dispose();
    resolve(lock);
    await flush();
    expect(lock.release).toHaveBeenCalledOnce();
  });
  it("does not claim success for unsupported, rejected or already released locks", async () => {
    const report = vi.fn();
    const a = new ScreenAwakeController(undefined, report);
    a.configure(true, true, true);
    expect(report).toHaveBeenLastCalledWith("unavailable");
    const b = new ScreenAwakeController(async () => {
      throw Error("NotAllowed");
    }, report);
    b.configure(true, true, true);
    await flush();
    expect(report).toHaveBeenLastCalledWith("unavailable");
    const { lock } = fakeLock();
    lock.released = true;
    const c = new ScreenAwakeController(async () => lock, report);
    c.configure(true, true, true);
    await flush();
    expect(report).toHaveBeenLastCalledWith("released");
  });
  it("reports platform release without fighting it in a retry loop", async () => {
    const { lock, releaseBySystem } = fakeLock();
    const request = vi.fn(async () => lock);
    const report = vi.fn();
    const c = new ScreenAwakeController(request, report);
    c.configure(true, true, true);
    await flush();
    releaseBySystem();
    await flush();
    expect(report).toHaveBeenLastCalledWith("released");
    expect(request).toHaveBeenCalledOnce();
  });
  it("visibility racing pending acquisition releases obsolete lock then requests once", async () => {
    const a = fakeLock(),
      b = fakeLock();
    let resolve!: (l: ScreenLock) => void;
    const request = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((r) => {
            resolve = r;
          }),
      )
      .mockResolvedValueOnce(b.lock);
    const c = new ScreenAwakeController(request, vi.fn());
    c.configure(true, true, true);
    c.configure(true, true, false);
    c.configure(true, true, true);
    expect(request).toHaveBeenCalledOnce();
    resolve(a.lock);
    await flush();
    expect(a.lock.release).toHaveBeenCalledOnce();
    expect(request).toHaveBeenCalledTimes(2);
  });
});
