/* TEST ONLY. Injected before the app by statistics-preview.mjs, never production. */
(() => {
  const key = "bridgego.phase1.guest";
  const saved = JSON.parse(
    sessionStorage.getItem("bridge-statistics-qa") || "{}",
  );
  const state = {
    enabled: true,
    signedIn: false,
    consentFail: false,
    statsFail: false,
    authFail: false,
    hold: false,
    empty: false,
    active: false,
    consents: 0,
    reads: 0,
    ...saved,
  };
  let identity = JSON.parse(localStorage.getItem(key) || "null") || {
    token: "fixture-session",
    playerId: "alice",
    displayName: "小白",
  };
  const held = [];
  const persist = () => {
    sessionStorage.setItem("bridge-statistics-qa", JSON.stringify(state));
    renderStatus();
  };
  const notify = () => {
    window.dispatchEvent(new Event("bridge-account-changing"));
    window.dispatchEvent(new Event("bridge-account-changed"));
  };
  function renderStatus() {
    const target = document.getElementById("qa-status");
    if (target)
      target.textContent = JSON.stringify({
        ...state,
        pendingReads: held.length,
      });
  }
  const response = (body, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  const originalFetch = window.fetch.bind(window);
  window.fetch = async (input, options = {}) => {
    const url = new URL(
      typeof input === "string" ? input : input.url || String(input),
      location.href,
    );
    if (url.origin !== location.origin)
      throw new Error("QA blocks external fetch");
    if (!url.pathname.startsWith("/api/")) return originalFetch(input, options);
    let value = {};
    switch (url.pathname) {
      case "/api/sessions":
        value = identity;
        break;
      case "/api/me":
        value = { roomId: state.active ? "ROOM01" : null };
        break;
      case "/api/rooms":
        value = [];
        break;
      case "/api/rooms/ROOM01":
        value = window.__QA_ROOM__;
        break;
      case "/api/auth/config":
        value = {
          enabled: true,
          clientId: "fixture-only",
          verificationEnabled: true,
          collectionMode: "managed",
          collectionReady: true,
          privacyContact: "fixture@example.invalid",
          retention: "manual",
        };
        break;
      case "/api/statistics/config":
        value = { enabled: state.enabled, consentVersion: "statistics-v1" };
        break;
      case "/api/statistics/consent":
        state.consents++;
        value = { accepted: !state.consentFail };
        break;
      case "/api/auth/status":
        if (state.authFail) return response({ code: "QA_UNAVAILABLE" }, 503);
        value = { signedIn: state.signedIn };
        break;
      case "/api/auth/nonce":
        state.signedIn = false;
        value = { nonce: "fixture-nonce" };
        break;
      case "/api/auth/google":
        state.signedIn = true;
        value = { signedIn: true };
        break;
      case "/api/auth/logout":
        state.signedIn = false;
        value = { signedIn: false };
        break;
      case "/api/auth/cancel":
        value = { cancelled: true };
        break;
      case "/api/statistics": {
        state.reads++;
        persist();
        const result = state.statsFail
          ? response({ code: "QA_UNAVAILABLE" }, 503)
          : response({
              status: "AVAILABLE",
              quarter: "2026-Q4",
              asOf: "2026-11-09T12:00:00Z",
              ruleVersion: "four-humans-at-settlement-v1",
              personal: {
                matches: state.empty ? 0 : 2,
                completedDeals: state.empty ? 0 : 6,
                wins: state.empty ? 0 : 1,
                losses: 0,
                draws: state.empty ? 0 : 1,
                totalScore: state.empty ? 0 : 99,
              },
              overall: { matches: state.empty ? 0 : 3, completedDeals: state.empty ? 0 : 7, draws: state.empty ? 0 : 1 },
            });
        if (state.hold)
          await new Promise((resolve) => {
            held.push(resolve);
            renderStatus();
          });
        return result;
      }
      default:
        return response({ code: "QA_UNKNOWN_ENDPOINT" }, 404);
    }
    persist();
    return response(value);
  };
  let credential;
  window.google = {
    accounts: {
      id: {
        initialize(options) {
          credential = options.callback;
        },
        renderButton(element) {
          const button = document.createElement("button");
          button.textContent = "Choose fixture Google account";
          button.onclick = () => credential({ credential: "fixture-only" });
          element.append(button);
        },
        cancel() {},
      },
    },
  };
  const OriginalSocket = window.WebSocket;
  window.WebSocket = class extends OriginalSocket {
    constructor(url, protocols) {
      if (new URL(url, location.href).pathname !== "/ws") {
        super(url, protocols);
        return;
      }
      const socket = {
        readyState: 1,
        send() {
          setTimeout(
            () =>
              socket.onmessage?.({
                data: JSON.stringify({
                  type: "snapshot",
                  room: window.__QA_ROOM__,
                }),
              }),
            0,
          );
        },
        close() {
          socket.readyState = 3;
        },
      };
      setTimeout(() => socket.onopen?.(), 0);
      return socket;
    }
  };
  document.addEventListener("DOMContentLoaded", () => {
    const panel = document.createElement("details");
    panel.id = "qa-controls";
    panel.open = true;
    panel.style.cssText =
      "position:relative;background:#fff;color:#111;border:3px solid #b33;padding:10px;font:14px sans-serif;z-index:99999;";
    panel.innerHTML =
      '<summary>SYNTHETIC QA ONLY · Controls (collapse for screenshots)</summary><p>No real Google or backend. App components are unchanged.</p><pre id="qa-status" style="white-space:pre-wrap;overflow-wrap:anywhere"></pre><div id="qa-buttons" style="display:flex;gap:6px;flex-wrap:wrap"></div><div id="qa-account"></div>';
    document.body.prepend(panel);
    const buttons = panel.querySelector("#qa-buttons");
    const add = (name, action) => {
      const b = document.createElement("button");
      b.textContent = name;
      b.style.cssText =
        "border:1px solid #555;padding:6px;background:#eee;color:#111;";
      b.onclick = action;
      buttons.append(b);
    };
    add("Reset fresh guest", () => {
      localStorage.removeItem(key);
      sessionStorage.removeItem("bridge-statistics-qa");
      location.href = location.pathname;
    });
    add("Reload restored session", () => {
      persist();
      location.reload();
    });
    for (const [property, label] of [
      ["enabled", "Stats enabled"],
      ["consentFail", "Consent failure"],
      ["statsFail", "Stats failure"],
      ["authFail", "Auth failure"],
      ["hold", "Hold stats read"],
      ["empty", "Empty counts"],
    ])
      add(`Toggle ${label}`, () => {
        state[property] = !state[property];
        persist();
      });
    add("Release held reads", () => {
      state.hold = false;
      held.splice(0).forEach((resolve) => resolve());
      persist();
    });
    add("Simulate guest + account event", () => {
      state.signedIn = false;
      persist();
      notify();
    });
    add("Simulate linked + account event", () => {
      state.signedIn = true;
      persist();
      notify();
    });
    add("Account changing event", () =>
      window.dispatchEvent(new Event("bridge-account-changing")),
    );
    add("Account changed event", () =>
      window.dispatchEvent(new Event("bridge-account-changed")),
    );
    add("Replace browser session", () => {
      state.signedIn = false;
      identity = {
        token: "fixture-replacement",
        playerId: "bob",
        displayName: "小黑",
      };
      localStorage.setItem(key, JSON.stringify(identity));
      persist();
      window.dispatchEvent(new StorageEvent("storage", { key }));
    });
    add("Restore active match", () => {
      state.active = true;
      localStorage.setItem(key, JSON.stringify(identity));
      persist();
      location.hash = "statistics";
      location.reload();
    });
    add("Show real GoogleAccount controls", async () => {
      const { mountAccount } = await import("/qa/statistics-account.tsx");
      mountAccount(document.getElementById("qa-account"));
    });
    renderStatus();
  });
})();
