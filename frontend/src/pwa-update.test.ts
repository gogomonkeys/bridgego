import { describe, expect, it, vi } from "vitest";
import { PwaUpdateController, updateMessage } from "./pwa-update";

class Worker extends EventTarget {
  state: ServiceWorkerState = "installing";
  change(state: ServiceWorkerState) {
    this.state = state;
    this.dispatchEvent(new Event("statechange"));
  }
}
class Registration extends EventTarget {
  installing: Worker | null = null;
  waiting: Worker | null = null;
  update = vi.fn(async () => this as unknown as ServiceWorkerRegistration);
  found(worker = new Worker()) {
    this.installing = worker;
    this.dispatchEvent(new Event("updatefound"));
    return worker;
  }
}
function setup() {
  const reg = new Registration();
  const report = vi.fn();
  const controller = new PwaUpdateController(
    reg as unknown as ServiceWorkerRegistration,
    report,
  );
  return { reg, report, controller };
}

describe("truthful PWA update states", () => {
  it("a resolved unchanged check never claims a download or latest version", async () => {
    const { controller, report } = setup();
    await controller.check();
    expect(report.mock.calls.map(([s]) => s)).toEqual(["checking", "unchanged"]);
    expect(updateMessage.unchanged).not.toContain("正在下載");
    expect(updateMessage.unchanged).not.toContain("最新版本");
  });
  it("reports downloading only with a real installing worker, then waiting ready", async () => {
    const { reg, controller, report } = setup();
    reg.update.mockImplementation(async () => {
      reg.found();
      return reg as unknown as ServiceWorkerRegistration;
    });
    await controller.check();
    expect(report).toHaveBeenLastCalledWith("downloading");
    const worker = reg.installing!;
    reg.installing = null;
    reg.waiting = worker;
    worker.change("installed");
    expect(report).toHaveBeenLastCalledWith("ready");
  });
  it("discovers an already waiting update without running update or activation", () => {
    const reg = new Registration();
    reg.waiting = new Worker();
    const report = vi.fn();
    new PwaUpdateController(reg as unknown as ServiceWorkerRegistration, report);
    expect(report).toHaveBeenLastCalledWith("ready");
    expect(reg.update).not.toHaveBeenCalled();
  });
  it("preserves an already downloaded waiting update when a later check fails", async () => {
    const { reg, controller, report } = setup();
    reg.waiting = new Worker();
    reg.update.mockRejectedValueOnce(new Error("offline"));
    await controller.check();
    expect(report.mock.calls.map(([s]) => s)).toEqual(["ready", "ready"]);
  });
  it("reports an asynchronous install failure after update resolved", async () => {
    const { reg, controller, report } = setup();
    const worker = reg.found();
    await controller.check();
    reg.installing = null;
    worker.change("redundant");
    expect(report).toHaveBeenLastCalledWith("failed");
    await controller.check();
    expect(report).toHaveBeenLastCalledWith("unchanged");
  });
  it("reports first installation activated without promising a page upgrade", async () => {
    const { reg, report, controller } = setup();
    const worker = reg.found();
    reg.installing = null;
    worker.change("activated");
    expect(report).toHaveBeenLastCalledWith("prepared");
    await controller.check();
    expect(report).toHaveBeenLastCalledWith("unchanged");
  });
  it("reports network rejection honestly and permits retry", async () => {
    const { reg, controller, report } = setup();
    reg.update.mockRejectedValueOnce(new Error("offline"));
    await controller.check();
    expect(report).toHaveBeenLastCalledWith("failed");
    await controller.check();
    expect(report).toHaveBeenLastCalledWith("unchanged");
  });
  it("deduplicates repeated checks and ignores completion after disposal", async () => {
    const { reg, controller, report } = setup();
    let finish!: (reg: ServiceWorkerRegistration) => void;
    reg.update.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const pending = controller.check();
    await controller.check();
    expect(reg.update).toHaveBeenCalledTimes(1);
    controller.dispose();
    report.mockClear();
    finish(reg as unknown as ServiceWorkerRegistration);
    await pending;
    reg.found().change("installed");
    expect(report).not.toHaveBeenCalled();
  });
  it("replaces worker listeners so an older failed worker cannot override the new one", () => {
    const { reg, controller, report } = setup();
    const old = reg.found();
    reg.found();
    report.mockClear();
    old.change("redundant");
    expect(report).not.toHaveBeenCalled();
    controller.dispose();
    reg.installing?.change("redundant");
    expect(report).not.toHaveBeenCalled();
  });
});
