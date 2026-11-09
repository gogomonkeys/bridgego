// Local browser regression suite. API and Google are fixtures; no real account or service is contacted.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require(
  process.env.PLAYWRIGHT_MODULE ||
    "playwright",
);
const { build } = require("esbuild");
const fixture = await build({
  entryPoints: [new URL("../src/test-fixtures.ts", import.meta.url).pathname],
  bundle: true,
  write: false,
  platform: "node",
  format: "esm",
});
const { playedRoom } = await import(
  `data:text/javascript;base64,${Buffer.from(fixture.outputFiles[0].text).toString("base64")}`
);
const browser = await chromium.launch({
  ...(process.env.CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.CHROMIUM_EXECUTABLE_PATH } : {}),
  headless: true,
  args: ["--no-sandbox"],
});
const base = process.env.STATISTICS_BASE_URL || "http://127.0.0.1:5189";
const identity = {
  token: "fixture-session",
  playerId: "alice",
  displayName: "小白",
};
const stats = {
  status: "AVAILABLE",
  quarter: "2026-Q4",
  asOf: "2026-11-09T12:00:00Z",
  ruleVersion: "four-humans-at-settlement-v1",
  personal: {
    matches: 2,
    completedDeals: 6,
    wins: 1,
    losses: 0,
    draws: 1,
    totalScore: 99,
  },
  overall: { matches: 3, completedDeals: 7, draws: 1 },
};
const reports = [];
async function scenario(name, run, options = {}) {
  const context = await browser.newContext({
    serviceWorkers: "block",
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  page.setDefaultTimeout(6000);
  const state = {
    signedIn: false,
    consents: 0,
    reads: 0,
    authFail: false,
    statsFail: false,
    consentFail: !!options.consentFail,
    errors: [],
  };
  page.on("pageerror", (e) => state.errors.push(e.message));
  if (options.restored || options.active)
    await page.addInitScript(
      (value) =>
        localStorage.setItem("bridgego.phase1.guest", JSON.stringify(value)),
      identity,
    );
  await page.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (url.origin === base && !url.pathname.startsWith("/api/"))
      return route.continue();
    if (url.href === "https://accounts.google.com/gsi/client")
      return route.fulfill({
        contentType: "application/javascript",
        body: `window.google={accounts:{id:{initialize(o){window.fixtureCredential=o.callback},renderButton(e){let b=document.createElement('button');b.textContent='Choose fixture account';b.onclick=()=>window.fixtureCredential({credential:'fixture-only'});e.append(b)},cancel(){}}}}`,
      });
    assert.equal(url.origin, base);
    let result = {},
      status = 200;
    switch (url.pathname) {
      case "/api/sessions":
        result = identity;
        break;
      case "/api/me":
        result = { roomId: options.active ? "ROOM01" : null };
        break;
      case "/api/rooms":
        result = [];
        break;
      case "/api/rooms/ROOM01":
        result = playedRoom();
        break;
      case "/api/auth/config":
        result = {
          enabled: true,
          clientId: "fixture-client",
          verificationEnabled: true,
          collectionMode: "managed",
          collectionReady: true,
          privacyContact: "fixture@example.invalid",
          retention: "manual",
        };
        break;
      case "/api/statistics/config":
        result = {
          enabled: options.enabled !== false,
          consentVersion: "statistics-v1",
        };
        break;
      case "/api/statistics/consent":
        state.consents++;
        assert.deepEqual(route.request().postDataJSON(), {
          consent: true,
          version: "statistics-v1",
        });
        result = { accepted: !state.consentFail };
        break;
      case "/api/auth/nonce":
        result = { nonce: "fixture-nonce" };
        break;
      case "/api/auth/google":
        state.signedIn = true;
        result = { signedIn: true };
        break;
      case "/api/auth/logout":
        state.signedIn = false;
        result = { signedIn: false };
        break;
      case "/api/auth/cancel":
        result = { cancelled: true };
        break;
      case "/api/auth/status":
        if (state.authFail) status = 503;
        result = { signedIn: state.signedIn };
        break;
      case "/api/statistics":
        state.reads++;
        if (options.gate) await options.gate;
        if (state.statsFail) status = 503;
        result = stats;
        break;
      default:
        throw Error("Unexpected API " + url.pathname);
    }
    await route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(result),
    });
  });
  await page.routeWebSocket("**/ws", (socket) =>
    socket.onMessage(() =>
      socket.send(JSON.stringify({ type: "snapshot", room: playedRoom() })),
    ),
  );
  try {
    await page.goto(base);
    await run(page, state);
    assert.deepEqual(state.errors, []);
    reports.push({ name, passed: true });
  } finally {
    await context.close();
  }
}
async function enter(page, account = false) {
  if (await page.getByLabel("遊戲暱稱").count())
    await page.getByLabel("遊戲暱稱").fill("小白");
  await page
    .getByRole("button", {
      name: account ? "使用 Google 登入" : "以訪客身分遊玩",
      exact: true,
    })
    .click();
  if (account)
    await page.getByRole("button", { name: "Choose fixture account" }).click();
  await page.getByRole("button", { name: "＋ 建立四人房間" }).waitFor();
}
try {
  await scenario(
    "Guest notice, consent, no entry or tabs even direct hash",
    async (p, s) => {
      await p.locator(".entry-statistics-notice").waitFor();
      assert.equal(s.consents, 0);
      await enter(p);
      assert.equal(s.consents, 1);
      await p.evaluate(() => (location.hash = "statistics"));
      await p.waitForTimeout(100);
      assert.equal(
        await p
          .getByRole("button", { name: "本季戰績統計", exact: true })
          .count(),
        0,
      );
      assert.equal(await p.locator(".statistics-tabs").count(), 0);
      assert.equal(s.reads, 0);
    },
  );
  await scenario(
    "Linked account personal/overall tabs, back/forward and mobile layout",
    async (p) => {
      await enter(p, true);
      await p
        .getByRole("button", { name: "本季戰績統計", exact: true })
        .click();
      await p.getByText("我方累計分數", { exact: true }).waitFor();
      await p.getByRole("button", { name: "整體", exact: true }).click();
      assert.equal(
        await p.getByText("我方累計分數", { exact: true }).count(),
        0,
      );
      await p.goBack();
      await p.getByRole("button", { name: "＋ 建立四人房間" }).waitFor();
      await p.goForward();
      await p.locator(".statistics-counts").waitFor();
      assert.equal(
        await p.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      await p.screenshot({
        path: "/tmp/bridgego-statistics-mobile.png",
        fullPage: true,
      });
    },
  );
  await scenario(
    "Consent failure stays at entry, retry succeeds",
    async (p, s) => {
      await p.getByLabel("遊戲暱稱").fill("小白");
      await p
        .getByRole("button", { name: "以訪客身分遊玩", exact: true })
        .click();
      await p
        .getByText("尚無法確認身分或資料使用同意，請重試；目前仍停留在入口。")
        .waitFor();
      assert.equal(
        await p.getByRole("button", { name: "＋ 建立四人房間" }).count(),
        0,
      );
      s.consentFail = false;
      await enter(p);
      assert.equal(s.consents, 2);
    },
    { consentFail: true },
  );
  await scenario(
    "Disabled statistics preserves guest entry without acknowledgement",
    async (p, s) => {
      await enter(p);
      assert.equal(s.consents, 0);
      assert.equal(s.reads, 0);
      assert.equal(await p.locator(".statistics-tabs").count(), 0);
    },
    { enabled: false },
  );
  await scenario(
    "Restored session shows notice and never silently acknowledges",
    async (p, s) => {
      await p.locator(".entry-statistics-notice").waitFor();
      assert.equal(s.consents, 0);
      await enter(p);
      assert.equal(s.consents, 1);
    },
    { restored: true },
  );
  await scenario(
    "Auth failure hides entry and direct statistics route",
    async (p, s) => {
      await enter(p, true);
      await p
        .getByRole("button", { name: "本季戰績統計", exact: true })
        .waitFor();
      s.authFail = true;
      await p.evaluate(() =>
        window.dispatchEvent(new Event("bridge-account-changed")),
      );
      await p.waitForTimeout(150);
      await p.evaluate(() => (location.hash = "statistics"));
      await p.waitForTimeout(100);
      assert.equal(await p.locator(".statistics-tabs").count(), 0);
      assert.equal(
        await p
          .getByRole("button", { name: "本季戰績統計", exact: true })
          .count(),
        0,
      );
    },
  );
  await scenario(
    "Statistics read error and retry are distinct from zero",
    async (p, s) => {
      await enter(p, true);
      s.statsFail = true;
      await p
        .getByRole("button", { name: "本季戰績統計", exact: true })
        .click();
      await p
        .getByRole("alert")
        .filter({ hasText: "目前無法取得統計" })
        .waitFor();
      assert.equal(
        await p.getByText("本季尚無已確認保存的合資格場次。").count(),
        0,
      );
      s.statsFail = false;
      await p.getByRole("button", { name: "重新讀取統計" }).click();
      await p.getByText("我方累計分數", { exact: true }).waitFor();
    },
  );
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  await scenario(
    "Account invalidation discards delayed statistics response",
    async (p, s) => {
      await enter(p, true);
      await p
        .getByRole("button", { name: "本季戰績統計", exact: true })
        .click();
      await p.getByText("正在讀取本季統計…").waitFor();
      s.signedIn = false;
      await p.evaluate(() => {
        window.dispatchEvent(new Event("bridge-account-changing"));
        window.dispatchEvent(new Event("bridge-account-changed"));
      });
      release();
      await p.waitForTimeout(200);
      assert.equal(await p.locator(".statistics-tabs").count(), 0);
      assert.equal(
        await p.getByText("我方累計分數", { exact: true }).count(),
        0,
      );
    },
    { gate },
  );
  await scenario(
    "Active restored match cannot be replaced by statistics hash",
    async (p, s) => {
      await p.locator(".match-workspace").waitFor();
      await p.evaluate(() => (location.hash = "statistics"));
      await p.locator(".match-workspace").waitFor();
      assert.equal(s.consents, 0);
      assert.equal(s.reads, 0);
      assert.equal(await p.locator(".statistics-tabs").count(), 0);
    },
    { active: true },
  );
  console.log(JSON.stringify(reports, null, 2));
} finally {
  await browser.close();
}
