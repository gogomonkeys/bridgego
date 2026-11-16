# iOS Home Screen sign-in candidate — not deployed

Base: 0389754d50607c54a9e16a07741a33ba7537b066 (0.14.5). No production or Google Console changes. No version bump yet.

The reported Safari/PWA difference is consistent with the popup path, not proof of the exact iOS failure. Google's supported-browser guide says iOS requires redirect mode due to ITP. This candidate targets iOS standalone only to avoid changing the currently working Safari/desktop login during investigation.

## Changes
- Default-off server redirect API; exact-origin start, double-submit Google CSRF and HttpOnly browser binding cookie, 5-minute nonce, original bearer-only one-shot finish. Callback itself never writes an account.
- iOS standalone uses GIS redirect, no credential/token URL or client credential persistence. Ordinary Safari/desktop/verification/recovery keep existing flow.
- Only a timestamp marker in sessionStorage supports return/resume. Original client session remains mandatory. Foreground/pageshow/status polling is bounded at 5 minutes, never auto-replays a finish POST.
- Known pre-storage JWT/nonce rejection becomes FAILED and permits a fresh attempt. On uncertain finish, reads only; server UNKNOWN/SUBMITTING cannot be overwritten/cancelled. Same-session nonce and original JWT verifier remain required.
- Clear cancellation, failure and return-to-original-app copy. If Google returns in a different container, do not silently create another authenticated session.

## Configuration required later (not changed)
- Authorized redirect URI on existing Google Web client: https://bridgego.onrender.com/api/auth/google/redirect/callback
- PUBLIC_ORIGIN=https://bridgego.onrender.com
- BRIDGE_GOOGLE_REDIRECT_ENABLED=true only for approved controlled activation; default false.

## Critical remaining gates
Real iPhone Home Screen testing is required: first/returning Google account, account selection, cancel/back, background/resume, app killed, expired flow, missing cookies, duplicate taps, network loss during callback/finish. Browser activation/cookie containers cannot be certified by Linux unit tests. If iOS returns without the binding or Google CSRF cookie, reject safely; do not weaken CSRF/nonce to force success. No cross-container session transfer is implemented.

Server restart invalidates pending attempts and guest sessions, not durable recovery. Long-lived UNKNOWN slots fail closed (bounded 1024); no broad persistence/schema changes here. Current authenticated bearer endpoints remain unchanged.

## Sources
- https://developers.google.com/identity/gsi/web/guides/supported-browsers
- https://developers.google.com/identity/gsi/web/reference/js-reference#ux_mode
- https://developers.google.com/identity/gsi/web/guides/integrate
- https://developers.google.com/identity/gsi/web/reference/html-reference (g_csrf_token)
- https://webkit.org/blog/14787/webkit-features-in-safari-17-2/ (cookie copy at installation does not prove live shared storage)

## Local verification
- Frontend: 64 files / 734 tests passed; TypeScript, ESLint and production build passed.
- Backend: 106 classes / 1192 tests passed, zero failures/errors/skips.
- Includes cancellation while status/script is delayed, lost start response, lost finish response with no replay, unknown cancellation, and a fresh successful attempt after known rejection.
- No actual Google login or real iPhone run was performed by these tests.
