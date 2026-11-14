import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { beforeEach, describe, expect, it, vi } from "vitest";
import WelcomeEntry from "./WelcomeEntry";
const fixture = vi.hoisted(() => ({
  session: { token: "fixture", playerId: "player", displayName: "玩家" },
  saved: false,
  silent: false,
  real: false,
  renders: 0,
  nonces: 0,
  loadFails: false,
  callback: (response: { credential?: string }) => { void response; },
  request: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
  adopt: vi.fn(),
}));
vi.mock("./client", () => ({
  getClientState: () => ({ session: fixture.session, room: null }),
  client: { adoptNickname: (...args: unknown[]) => fixture.adopt(...args) },
}));
vi.mock("./statistics", () => ({
  readStatisticsConfig: async () => ({
    enabled: true,
    consentVersion: "statistics-v4",
  }),
  acceptStatistics: async () => {},
}));
vi.mock("./google-login", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./google-login")>();
  return {
    accountRequest: (...args: unknown[]) => fixture.request(...args),
    readAuthConfig: async () => ({
      enabled: true,
      verificationEnabled: true,
      privacyContact: "fixture@example.test",
      retention: "quarter",
      clientId: "fixture",
      collectionMode: "managed",
      collectionReady: true,
    }),
    loadGoogleIdentity: async () => {
      if (fixture.loadFails) throw new Error("Google load fixture failure");
      return {
        initialize(options: {
          callback: (response: { credential?: string }) => void;
        }) {
          fixture.callback = options.callback;
        },
        renderButton() {
          fixture.renders++;
        },
        cancel() {},
      };
    },
    createLoginAttempt: (
      deps: Parameters<typeof actual.createLoginAttempt>[0],
    ) =>
      fixture.real
        ? actual.createLoginAttempt(deps)
        : {
            start: async () => {
              if (fixture.silent) return;
              deps.update("idle", "", true);
            },
            cancel: () => {},
            dispose: () => {},
          },
  };
});
function deferred() {
  let resolve!: (v?: unknown) => void;
  const promise = new Promise((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
const ready = { signedIn: true, nicknameRequired: false, nickname: "小白" };
const pending = { signedIn: true, nicknameRequired: true, nickname: null };
async function flush(work?: () => void | Promise<unknown>) {
  await act(async () => {
    await work?.();
    await Promise.resolve();
  });
}
async function mount() {
  let view!: ReactTestRenderer;
  const enter = vi.fn();
  await flush(() => {
    view = create(
      <WelcomeEntry session={fixture.session} busy={false} onEnter={enter} />,
      { createNodeMock: () => ({ replaceChildren() {} }) },
    );
  });
  const button = (name: string) =>
    view.root
      .findAllByType("button")
      .find((b) => b.children.join("") === name)!;
  const google = () => flush(() => button("使用 Google 登入").props.onClick());
  const fill = () =>
    flush(() =>
      view.root
        .findByProps({ id: "guest-name" })
        .props.onChange({ target: { value: "小白" } }),
    );
  const submit = () =>
    flush(() =>
      view.root.findByType("form").props.onSubmit({ preventDefault() {} }),
    );
  return {
    view,
    enter,
    button,
    google,
    fill,
    submit,
    text: () => JSON.stringify(view.toJSON()),
    close: () => flush(() => view.unmount()),
  };
}
beforeEach(() => {
  fixture.saved = false;
  fixture.silent = false;
  fixture.real = false;
  fixture.renders = 0;
  fixture.nonces = 0;
  fixture.loadFails = false;
  fixture.adopt.mockReset().mockReturnValue(true);
  fixture.request.mockReset().mockImplementation(async (path) => {
    if (path === "/auth/nonce") return { nonce: `fixture-${++fixture.nonces}` };
    if (path === "/auth/google") return pending;
    if (path === "/auth/cancel") return { cancelled: true };
    if (path === "/auth/status") return fixture.saved ? ready : pending;
    if (path === "/auth/nickname") {
      fixture.saved = true;
      return ready;
    }
    throw new Error(String(path));
  });
});
describe("mounted first Google nickname entry", () => {
  it("saves once and enters with the same session", async () => {
    const ui = await mount();
    await ui.google();
    await ui.fill();
    await ui.submit();
    expect(
      fixture.request.mock.calls.filter((c) => c[0] === "/auth/nickname"),
    ).toHaveLength(1);
    expect(fixture.adopt).toHaveBeenCalledWith("fixture", "小白");
    expect(ui.enter).toHaveBeenCalledWith("fixture", false);
    await ui.close();
  });
  it("shows slow saving and prevents duplicate submits", async () => {
    const gate = deferred();
    const ui = await mount();
    await ui.google();
    await ui.fill();
    fixture.request.mockImplementation(async (path) =>
      path === "/auth/nickname" ? gate.promise : ready,
    );
    await ui.submit();
    expect(ui.button("正在保存暱稱…").props.disabled).toBe(true);
    expect(ui.text()).toContain("不需要再次按確認");
    await ui.submit();
    expect(
      fixture.request.mock.calls.filter((c) => c[0] === "/auth/nickname"),
    ).toHaveLength(1);
    await flush(() => gate.resolve(ready));
    expect(ui.enter).toHaveBeenCalledTimes(1);
    await ui.close();
  });
  it("shows confirmation progress during slow status reads", async () => {
    const gate = deferred();
    const ui = await mount();
    await ui.google();
    await ui.fill();
    fixture.request.mockImplementation(async (path) =>
      path === "/auth/nickname" ? ready : gate.promise,
    );
    await ui.submit();
    expect(ui.button("正在確認登入…").props.disabled).toBe(true);
    expect(ui.enter).not.toHaveBeenCalled();
    await flush(() => gate.resolve(ready));
    expect(ui.enter).toHaveBeenCalledTimes(1);
    await ui.close();
  });
  it("keeps input and blocks replay after timeout", async () => {
    const ui = await mount();
    await ui.google();
    await ui.fill();
    fixture.request.mockRejectedValueOnce(new Error("Timeout"));
    await ui.submit();
    expect(ui.text()).toContain("暱稱儲存尚未確認");
    expect(ui.view.root.findByProps({ id: "guest-name" }).props.value).toBe(
      "小白",
    );
    expect(ui.button("確認暱稱並進入大廳").props.disabled).toBe(true);
    await ui.submit();
    expect(
      fixture.request.mock.calls.filter((c) => c[0] === "/auth/nickname"),
    ).toHaveLength(1);
    expect(ui.enter).not.toHaveBeenCalled();
    await ui.close();
  });
  it.each([
    ["signed out", { signedIn: false }, "尚未確認登入狀態"],
    ["still missing", pending, "尚未確認暱稱完成"],
    [
      "malformed nickname",
      { signedIn: true, nicknameRequired: false },
      "暱稱同步尚未確認",
    ],
  ])(
    "makes %s visible without losing input",
    async (_name, response, message) => {
      const ui = await mount();
      await ui.google();
      await ui.fill();
      fixture.request.mockImplementation(async (path) =>
        path === "/auth/nickname" ? ready : response,
      );
      await ui.submit();
      expect(ui.text()).toContain(message);
      expect(ui.view.root.findByProps({ id: "guest-name" }).props.value).toBe(
        "小白",
      );
      expect(ui.enter).not.toHaveBeenCalled();
      expect(ui.button("確認暱稱並進入大廳").props.disabled).toBe(true);
      await ui.close();
    },
  );
  it("resets unfinished guest IME composition on account form transition", async () => {
    const ui = await mount();
    await flush(() => ui.button("以訪客身分遊玩").props.onClick());
    await flush(() =>
      ui.view.root.findByProps({ id: "guest-name" }).props.onCompositionStart(),
    );
    await flush(() => ui.button("返回身分選擇").props.onClick());
    await ui.google();
    await ui.fill();
    expect(ui.button("確認暱稱並進入大廳").props.disabled).toBe(false);
    await ui.submit();
    expect(ui.enter).toHaveBeenCalledTimes(1);
    await ui.close();
  });
  it("real login controller can restart Google after unknown save without replay", async () => {
    fixture.real = true;
    const ui = await mount();
    await ui.google();
    expect(fixture.renders).toBe(1);
    await flush(() => fixture.callback({ credential: "fixture-only" }));
    await ui.fill();
    fixture.request.mockRejectedValueOnce(new Error("Timeout"));
    await ui.submit();
    expect(ui.text()).toContain("暱稱儲存尚未確認");
    await flush(() => ui.button("返回身分選擇").props.onClick());
    await ui.google();
    expect(fixture.nonces).toBe(2);
    expect(fixture.renders).toBe(2);
    expect(ui.text()).toContain("請選擇 Google 帳號");
    fixture.saved = true;
    await flush(() => fixture.callback({ credential: "fixture-only-second" }));
    expect(ui.enter).toHaveBeenCalledTimes(1);
    expect(
      fixture.request.mock.calls.filter((c) => c[0] === "/auth/nickname"),
    ).toHaveLength(1);
    await ui.close();
  });
  it("real login controller exposes Google loading failure after returning", async () => {
    fixture.real = true;
    const ui = await mount();
    await ui.google();
    await flush(() => fixture.callback({ credential: "fixture-only" }));
    await ui.fill();
    fixture.request.mockRejectedValueOnce(new Error("Timeout"));
    await ui.submit();
    await flush(() => ui.button("返回身分選擇").props.onClick());
    fixture.loadFails = true;
    await ui.google();
    expect(ui.text()).toContain("Google 登入暫時無法使用");
    expect(fixture.renders).toBe(1);
    expect(fixture.nonces).toBe(2);
    expect(
      fixture.request.mock.calls.filter((c) => c[0] === "/auth/nickname"),
    ).toHaveLength(1);
    expect(ui.enter).not.toHaveBeenCalled();
    await ui.close();
  });

  it("exposes a no-op Google start instead of silently returning", async () => {
    fixture.silent = true;
    const ui = await mount();
    await ui.google();
    expect(ui.text()).toContain("本次 Google 登入未能啟動");
    expect(ui.enter).not.toHaveBeenCalled();
    await ui.close();
  });
  it("makes failed confirmation read visible and keeps saving disabled", async () => {
    const ui = await mount();
    await ui.google();
    await ui.fill();
    fixture.request.mockImplementation(async (path) => {
      if (path === "/auth/nickname") return ready;
      throw new Error("Status timeout");
    });
    await ui.submit();
    expect(ui.text()).toContain("尚無法確認登入及暱稱");
    expect(ui.button("確認暱稱並進入大廳").props.disabled).toBe(true);
    expect(ui.enter).not.toHaveBeenCalled();
    await ui.close();
  });
});
