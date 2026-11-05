import { describe, expect, it } from "vitest";
import { canCompleteEntry, needsEntry, prepareEntryChoice } from "./entry-flow";
describe("pre-lobby choice is scoped to a session", () => {
  it("holds a new or restored guest on entry until explicit continue", () => {
    expect(needsEntry(null, null, false)).toBe(true);
    expect(needsEntry("created", null, false)).toBe(true);
    expect(needsEntry("restored", null, false)).toBe(true);
    expect(needsEntry("created", "created", false)).toBe(false);
  });
  it("another session cannot inherit a completed entry choice", () => {
    expect(needsEntry("new", "old", false)).toBe(true);
    expect(canCompleteEntry("old", "new", false)).toBe(false);
    expect(canCompleteEntry("old", undefined, false)).toBe(false);
    expect(canCompleteEntry("old", "old", true)).toBe(false);
  });
  it("room recovery is never displaced by the entry gate", () => {
    expect(needsEntry("restored", null, true)).toBe(false);
    expect(needsEntry("restored", "other", true)).toBe(false);
  });
});

describe("async entry identity creation", () => {
  function pendingChoice() {
    let resolve!: (session: { token: string } | null) => void;
    const created = new Promise<{ token: string } | null>((done) => {
      resolve = done;
    });
    const state = {
      token: undefined as string | undefined,
      hasRoom: false,
      mounted: true,
    };
    const result = prepareEntryChoice({
      previousToken: undefined,
      createSession: () => created,
      current: () => state,
      isMounted: () => state.mounted,
    });
    return { resolve, state, result };
  }
  it("holds through the early session emit until creation finishes", async () => {
    const test = pendingChoice();
    let completed = false;
    void test.result.then(() => {
      completed = true;
    });
    test.state.token = "created";
    await Promise.resolve();
    expect(completed).toBe(false);
    test.resolve({ token: "created" });
    expect(await test.result).toBe("created");
  });
  it("help navigation/unmount while refresh is pending cannot continue or verify", async () => {
    const test = pendingChoice();
    test.state.token = "created";
    test.state.mounted = false;
    test.resolve({ token: "created" });
    expect(await test.result).toBeNull();
  });
  it("cross-tab replacement while refresh is pending cannot adopt the replacement", async () => {
    const test = pendingChoice();
    test.state.token = "replacement";
    test.resolve({ token: "created" });
    expect(await test.result).toBeNull();
  });
  it("recovered room or failed guest creation prevents entry transition", async () => {
    const test = pendingChoice();
    test.state.token = "created";
    test.state.hasRoom = true;
    test.resolve({ token: "created" });
    expect(await test.result).toBeNull();
    const failed = pendingChoice();
    failed.resolve(null);
    expect(await failed.result).toBeNull();
  });
});
