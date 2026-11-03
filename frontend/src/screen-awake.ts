export interface ScreenLock {
  released: boolean;
  release(): Promise<void>;
  addEventListener(type: "release", listener: () => void): void;
}
export type AwakeStatus =
  "off" | "waiting" | "requesting" | "active" | "unavailable" | "released";

/** Optional screen-only lock. Never attempts to keep a hidden app or CPU awake. */
export class ScreenAwakeController {
  private enabled = false;
  private active = false;
  private visible = false;
  private revision = 0;
  private lock: ScreenLock | null = null;
  private pending = false;
  constructor(
    private request: (() => Promise<ScreenLock>) | undefined,
    private report: (status: AwakeStatus) => void,
  ) {}
  configure(enabled: boolean, active: boolean, visible: boolean) {
    if (
      enabled === this.enabled &&
      active === this.active &&
      visible === this.visible
    )
      return;
    this.enabled = enabled;
    this.active = active;
    this.visible = visible;
    this.revision++;
    if (!enabled || !active || !visible) {
      const old = this.lock;
      this.lock = null;
      if (old) void old.release().catch(() => {});
      this.report(enabled ? "waiting" : "off");
      return;
    }
    void this.acquire();
  }
  private async acquire() {
    if (
      this.pending ||
      this.lock ||
      !this.enabled ||
      !this.active ||
      !this.visible
    )
      return;
    if (!this.request) {
      this.report("unavailable");
      return;
    }
    const revision = this.revision;
    this.pending = true;
    this.report("requesting");
    try {
      const lock = await this.request();
      if (revision !== this.revision) {
        await lock.release().catch(() => {});
        return;
      }
      if (lock.released) {
        this.report("released");
        return;
      }
      this.lock = lock;
      lock.addEventListener("release", () => {
        if (this.lock !== lock) return;
        this.lock = null;
        this.report(
          this.enabled && this.active && this.visible
            ? "released"
            : this.enabled
              ? "waiting"
              : "off",
        );
        // No retry loop against platform power policy. A visibility transition or
        // explicit off/on toggle can request again.
      });
      this.report("active");
    } catch {
      if (revision === this.revision) this.report("unavailable");
    } finally {
      this.pending = false;
      if (revision !== this.revision) void this.acquire();
    }
  }
  dispose() {
    this.configure(false, false, false);
  }
}
