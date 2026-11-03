export type UpdateStatus =
  | "idle"
  | "checking"
  | "downloading"
  | "ready"
  | "unchanged"
  | "prepared"
  | "failed";

export const updateMessage: Record<UpdateStatus, string> = {
  idle: "",
  checking: "正在檢查更新…",
  downloading: "正在下載更新，完成後會顯示通知。",
  ready: "新版本已準備好。請先完成遊戲，再關閉所有 BridgeGo 分頁與應用程式後重新開啟。",
  unchanged: "檢查完成，目前未偵測到可套用的更新。若版本仍舊，請稍後再試。",
  prepared: "離線啟動畫面已準備好。",
  failed: "更新檢查或下載未完成，請確認網路後再試。",
};

/** Observes installation; update() resolving alone never proves a download. */
export class PwaUpdateController {
  private disposed = false;
  private checking = false;
  private worker: ServiceWorker | null = null;

  constructor(
    private registration: ServiceWorkerRegistration,
    private report: (status: UpdateStatus) => void,
  ) {
    registration.addEventListener("updatefound", this.found);
    this.inspect();
  }

  private publish(status: UpdateStatus) {
    if (!this.disposed) this.report(status);
  }

  private found = () => this.inspect();
  private changed = () => this.inspect();

  private inspect(afterCheck = false) {
    if (this.disposed) return;
    const installing = this.registration.installing;
    if (installing && installing !== this.worker) {
      this.worker?.removeEventListener("statechange", this.changed);
      this.worker = installing;
      installing.addEventListener("statechange", this.changed);
    }
    if (this.registration.waiting) this.publish("ready");
    else if (installing && ["installing", "installed"].includes(installing.state))
      this.publish("downloading");
    else if (this.worker?.state === "redundant") this.publish("failed");
    else if (this.worker?.state === "activated" && !afterCheck) this.publish("prepared");
    else if (afterCheck) this.publish("unchanged");
  }

  async check() {
    if (this.disposed || this.checking) return;
    this.checking = true;
    // A previous failed installation must not masquerade as this check's result.
    if (this.worker?.state === "redundant") {
      this.worker.removeEventListener("statechange", this.changed);
      this.worker = null;
    }
    this.publish(this.registration.waiting ? "ready" : "checking");
    try {
      await this.registration.update();
      this.inspect(true);
    } catch {
      this.publish(this.registration.waiting ? "ready" : "failed");
    } finally {
      this.checking = false;
    }
  }

  dispose() {
    this.disposed = true;
    this.registration.removeEventListener("updatefound", this.found);
    this.worker?.removeEventListener("statechange", this.changed);
  }
}
