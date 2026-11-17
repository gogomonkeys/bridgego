import { describe, expect, it, vi } from "vitest";
import { mountFriendsDialog } from "./friends-dialog";
function fixture() {
  const doc = Object.assign(new EventTarget(), {
    body: { style: { overflow: "scroll" } },
    activeElement: null as unknown,
  });
  const node = () => ({
    isConnected: true,
    getClientRects: () => [{}],
    focus: vi.fn(function (this: unknown) {
      doc.activeElement = this;
    }),
  });
  const first = node(),
    last = node(),
    trigger = node();
  const dialog = {
    querySelector: () => first,
    querySelectorAll: () => [first, last],
    contains: (value: unknown) => value === first || value === last,
    focus: vi.fn(),
  };
  const close = vi.fn();
  const cleanup = mountFriendsDialog(
    dialog as unknown as HTMLElement,
    trigger as unknown as HTMLElement,
    close,
    doc as unknown as Document,
  );
  const key = (name: string, shiftKey = false) => {
    const event = Object.assign(new Event("keydown", { cancelable: true }), {
      key: name,
      shiftKey,
    });
    doc.dispatchEvent(event);
    return event;
  };
  return { doc, first, last, trigger, dialog, close, cleanup, key };
}
describe("friends sheet focus and scroll lifecycle", () => {
  it("focuses close and traps Tab/Shift-Tab within the sheet", () => {
    const f = fixture();
    expect(f.doc.body.style.overflow).toBe("hidden");
    expect(f.doc.activeElement).toBe(f.first);
    expect(f.key("Tab", true).defaultPrevented).toBe(true);
    expect(f.doc.activeElement).toBe(f.last);
    expect(f.key("Tab").defaultPrevented).toBe(true);
    expect(f.doc.activeElement).toBe(f.first);
    f.cleanup();
  });
  it("Escape closes, cleanup restores prior scroll and trigger focus and removes listeners", () => {
    const f = fixture();
    expect(f.key("Escape").defaultPrevented).toBe(true);
    expect(f.close).toHaveBeenCalledTimes(1);
    f.cleanup();
    expect(f.doc.body.style.overflow).toBe("scroll");
    expect(f.doc.activeElement).toBe(f.trigger);
    f.key("Escape");
    expect(f.close).toHaveBeenCalledTimes(1);
  });
  it("does not focus a detached trigger after identity interruption", () => {
    const f = fixture();
    f.trigger.isConnected = false;
    f.cleanup();
    expect(f.trigger.focus).not.toHaveBeenCalled();
    expect(f.doc.body.style.overflow).toBe("scroll");
  });
  it("returns focus to the dialog when Tab starts outside", () => {
    const f = fixture();
    f.doc.activeElement = {};
    f.key("Tab");
    expect(f.doc.activeElement).toBe(f.first);
    f.cleanup();
  });
});
