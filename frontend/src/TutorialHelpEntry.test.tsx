import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { useState } from "react";
import { readFileSync } from "node:fs";
import TutorialHelpEntry from "./TutorialHelpEntry";
import { accountRequest } from "./google-login";
vi.mock("./google-login", () => ({ accountRequest: vi.fn() }));
const request = vi.mocked(accountRequest);
let tree: ReactTestRenderer;
const start = vi.fn();
function Harness() {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  return (
    <TutorialHelpEntry
      token="token"
      enabled={enabled}
      onAvailability={setEnabled}
      onStart={start}
    />
  );
}
const button = (label: string) =>
  tree.root.findAllByType("button").find((b) => b.children.join("") === label)!;
beforeEach(() => {
  request.mockReset();
  start.mockReset();
  vi.stubGlobal("localStorage", { setItem: vi.fn() });
});
describe("Help tutorial readonly recovery", () => {
  it("enables entry after a transient failed check then successful readonly retry", async () => {
    await act(async () => {
      tree = create(<Harness />);
    });
    expect(button("開始教學").props.disabled).toBe(true);
    request.mockRejectedValueOnce(new Error("offline"));
    await act(async () => button("確認教學狀態").props.onClick());
    expect(button("開始教學").props.disabled).toBe(true);
    request.mockResolvedValueOnce({ enabled: true });
    await act(async () => button("確認教學狀態").props.onClick());
    expect(button("開始教學").props.disabled).toBe(false);
    expect(
      request.mock.calls.every(
        (c) => c[0] === "/tutorial/status" && c[1] === "GET",
      ),
    ).toBe(true);
    expect(localStorage.setItem).not.toHaveBeenCalled();
    expect(start).not.toHaveBeenCalled();
    act(() => button("開始教學").props.onClick());
    expect(start).toHaveBeenCalledOnce();
  });
  it("keeps disabled status disabled without acknowledging preference", async () => {
    await act(async () => {
      tree = create(<Harness />);
    });
    request.mockResolvedValueOnce({ enabled: false });
    await act(async () => button("確認教學狀態").props.onClick());
    expect(button("開始教學").props.disabled).toBe(true);
    expect(localStorage.setItem).not.toHaveBeenCalled();
  });
  it("ignores stale replies after identity-keyed Help component leaves", async () => {
    let resolve!: (value: unknown) => void;
    const available = vi.fn();
    request.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    await act(async () => {
      tree = create(
        <TutorialHelpEntry
          token="token"
          enabled={null}
          onAvailability={available}
          onStart={start}
        />,
      );
    });
    act(() => button("確認教學狀態").props.onClick());
    act(() => tree.unmount());
    await act(async () => resolve({ enabled: true }));
    expect(available).not.toHaveBeenCalled();
  });
  it("App connects recovery using identity key and shared availability", () => {
    const source = readFileSync(new URL("./App.tsx", import.meta.url), "utf8");
    expect(source).toContain("<TutorialHelpEntry");
    expect(source).toContain(
      "key={`${state.session.token}:${tutorialAccountEpoch}`}",
    );
    expect(source).toContain("setTutorialAvailability");
  });
});
