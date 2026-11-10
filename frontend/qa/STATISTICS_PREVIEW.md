# Synthetic statistics preview

Start `node frontend/qa/statistics-preview.mjs` from the repository root. It serves http://127.0.0.1:5190, using the actual App, WelcomeEntry, StatisticsPanel and GoogleAccount components unchanged. Vite injects fixtures before application startup. This file and its adapters are test-only and are not included by the production entry point/build.

No real Google account, backend, or Google Sheet is contacted. Google Identity is an in-memory fake. All /api/ fetches use fixture responses. External fetch is blocked. App game WebSockets use a synthetic room snapshot; Vite's development socket remains normal. This checks client behavior, not real persistence or authentication.

Visible top controls:
- Reset fresh guest: removes guest and fixture state and reloads.
- Toggle Stats enabled / Consent failure / Stats failure / Auth failure / Hold stats read: flips indicated fixture flag; current state and counters appear above buttons. Changes to config require Reload restored session before entry.
- Release held reads: completes delayed stats responses. To test stale data, first hold a read, enter the stats view, simulate guest/account change or replace browser session, then release.
- Simulate guest/linked + account event: changes fixture server authentication and signals the client.
- Account changing/changed event: tests immediate invalidation independent of server state.
- Replace browser session: replaces local guest token/player and sends the genuine storage event handled by the app. Previously displayed stats must disappear.
- Restore active match: reloads a synthetic active match at #statistics; the match must remain visible and no new consent is submitted.
- Show real GoogleAccount controls: mounts the production account component. After logging in through the app, click the actual “解除 Google 連結，保留訪客” button to exercise the real logout handler and verify stats disappear.

Normal login: input nickname, click 使用 Google 登入, then Choose fixture Google account. Guest: click 以訪客身分遊玩. The entry should omit the long statistics paragraph above the nickname, retain the shorter Google consent text and help link, and keep the consent counter at zero until selection. The complete statistics and data-retention disclosure remains on the help page.

The control panel collapses for screenshots. Counts are artificial: personal 2 matches/6 deals/99 score; overall 3 matches/7 deals. Browser back/forward, empty/error/retry, guest hash gating and responsive layout require visual/interaction verification.

Some execution tools isolate loopback networking between calls. If the browser cannot reach a server that reports ready, use a supported environment with shared browser/server networking. Do not interpret connection refusal as a client test failure. No tunnel or production deployment is required.
