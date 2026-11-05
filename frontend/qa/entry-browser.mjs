// Local interaction checks. All API/Google traffic is intercepted; no external
// account, token, deployment or service is used. Start Vite before running.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const { build } = require("esbuild");
const fixture = await build({ entryPoints: [new URL("../src/test-fixtures.ts", import.meta.url).pathname], bundle: true, write: false, platform: "node", format: "esm" });
const { playedRoom } = await import(`data:text/javascript;base64,${Buffer.from(fixture.outputFiles[0].text).toString("base64")}`);
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium", headless: true, args: ["--no-sandbox"] });
const base = process.env.ENTRY_BASE_URL || "http://127.0.0.1:5173";
const identity = { token: "local-entry-token", playerId: "alice", displayName: "小白" };
const reports = [];
async function scenario(name, run, options = {}) {
  const context = await browser.newContext({ serviceWorkers: "block", viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  const calls = { google: 0, verify: 0, link: 0, cancel: 0, nonce: 0, sessions: 0, errors: [] };
  page.on("pageerror", error => calls.errors.push(error.message));
  if (options.restored) await page.addInitScript(value => localStorage.setItem("bridgego.phase1.guest", JSON.stringify(value)), identity);
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.origin === new URL(base).origin && !url.pathname.startsWith("/api/")) return route.continue();
    if (url.href === "https://accounts.google.com/gsi/client") {
      calls.google++; if (options.googleGate) await options.googleGate;
      return route.fulfill({ contentType: "application/javascript", body: `window.google={accounts:{id:{initialize(o){window.entryCredential=o.callback},renderButton(e){const b=document.createElement('button');b.textContent='Mock choose Google';b.onclick=()=>window.entryCredential({credential:'local-fixture-only'});e.append(b)},cancel(){}}}};` });
    }
    assert.equal(url.origin, new URL(base).origin, `Unexpected external request: ${url.origin}`);
    let result = {};
    if (url.pathname === "/api/sessions") { calls.sessions++; result = identity; }
    else if (url.pathname === "/api/me") result = { roomId: options.activeRoom ? "ROOM01" : null };
    else if (url.pathname === "/api/rooms") { if (options.roomGate) await options.roomGate; result = []; }
    else if (url.pathname === "/api/rooms/ROOM01") result = playedRoom();
    else if (url.pathname === "/api/auth/config") result = { enabled: false, clientId: "local-test-client", verificationEnabled: true, collectionMode: "restricted", collectionReady: false };
    else if (url.pathname === "/api/auth/nonce") { calls.nonce++; assert.equal(route.request().postDataJSON().purpose, "verify"); result = { nonce: `local-nonce-${calls.nonce}` }; }
    else if (url.pathname === "/api/auth/google/verify") { calls.verify++; result = { issuer: "https://accounts.google.com", subject: "local-subject", admittedTester: false }; }
    else if (url.pathname === "/api/auth/google") { calls.link++; throw new Error("Entry must never link"); }
    else if (url.pathname === "/api/auth/cancel") { calls.cancel++; result = { cancelled: true }; }
    else throw new Error(`Unexpected local endpoint: ${url.pathname}`);
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(result) });
  });
  await page.routeWebSocket("**/ws", socket => socket.onMessage(() => socket.send(JSON.stringify({ type: "snapshot", room: playedRoom() }))));
  try {
    await page.goto(base); await run(page, calls);
    assert.deepEqual(calls.errors, []); assert.equal(calls.link, 0);
    reports.push({ name, passed: true, ...calls });
  } finally { await context.close(); }
}
try {
  await scenario("Fresh guest enters without Google", async (page, calls) => {
    await page.getByLabel("遊戲暱稱").fill("小白");
    assert.equal(calls.google, 0);
    assert.equal(await page.locator(".entry-details[open]").count(), 0);
    await page.getByRole("button", { name: "以訪客身分遊玩", exact: true }).click();
    await page.getByRole("button", { name: "＋ 建立四人房間", exact: true }).waitFor();
    assert.equal(calls.google, 0); assert.equal(calls.nonce, 0);
  });
  let releaseRooms; const roomGate = new Promise(resolve => { releaseRooms = resolve; });
  await scenario("Session emit does not leave verification entry", async (page, calls) => {
    await page.getByLabel("遊戲暱稱").fill("小白");
    await page.getByRole("button", { name: "Google 驗證", exact: true }).click();
    await page.getByRole("heading", { name: "你好，小白" }).waitFor();
    assert.equal(await page.getByRole("button", { name: "＋ 建立四人房間", exact: true }).count(), 0);
    releaseRooms();
    await page.getByRole("button", { name: "Mock choose Google" }).click();
    await page.getByText("✓ Google 驗證成功", { exact: true }).waitFor();
    assert.equal(calls.verify, 1); assert.equal(await page.locator("#entry-subject").isVisible(), false);
    assert.equal(await page.getByRole("button", { name: "＋ 建立四人房間", exact: true }).count(), 0);
    await page.screenshot({ path: "/tmp/bridgego-entry-verified.png", fullPage: true });
    await page.getByRole("button", { name: "繼續進入大廳", exact: true }).click();
    await page.getByRole("button", { name: "＋ 建立四人房間", exact: true }).waitFor();
  }, { roomGate });
  await scenario("Restored guest chooses again; cancellation ignores stale callback", async (page, calls) => {
    await page.getByRole("button", { name: "Google 驗證", exact: true }).click();
    await page.getByRole("button", { name: "Mock choose Google" }).waitFor();
    await page.getByRole("button", { name: "取消驗證", exact: true }).click();
    await page.evaluate(() => window.entryCredential({ credential: "stale" }));
    assert.equal(calls.verify, 0);
    await page.getByRole("button", { name: "以訪客身分遊玩", exact: true }).click();
    await page.getByRole("button", { name: "＋ 建立四人房間", exact: true }).waitFor();
  }, { restored: true });
  let releaseGoogle; const googleGate = new Promise(resolve => { releaseGoogle = resolve; });
  await scenario("Guest continue during Google load stays in lobby", async (page, calls) => {
    await page.getByRole("button", { name: "Google 驗證", exact: true }).click();
    await page.getByText("正在載入 Google…", { exact: true }).waitFor();
    await page.getByRole("button", { name: "以訪客身分遊玩", exact: true }).click();
    await page.getByRole("button", { name: "＋ 建立四人房間", exact: true }).waitFor();
    releaseGoogle();
    await page.waitForTimeout(100);
    assert.equal(calls.verify, 0); assert.equal(await page.locator(".entry-choice").count(), 0);
  }, { restored: true, googleGate });
  await scenario("Session change invalidates pending Google callback", async (page, calls) => {
    await page.getByRole("button", { name: "Google 驗證", exact: true }).click();
    await page.getByRole("button", { name: "Mock choose Google" }).waitFor();
    await page.evaluate(() => { window.dispatchEvent(new StorageEvent("storage", { key: "bridgego.phase1.guest" })); window.entryCredential({ credential: "stale" }); });
    await page.getByLabel("遊戲暱稱").waitFor();
    assert.equal(calls.verify, 0);
  }, { restored: true });
  await scenario("Active room recovery bypasses entry", async (page, calls) => {
    await page.locator(".match-workspace").waitFor();
    assert.equal(await page.locator(".entry-choice").count(), 0); assert.equal(calls.google, 0);
  }, { restored: true, activeRoom: true });
  console.log(JSON.stringify(reports, null, 2));
} finally { await browser.close(); }
