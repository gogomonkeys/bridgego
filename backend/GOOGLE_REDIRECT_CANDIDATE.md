# Google iOS redirect candidate (not deployed)

Default off. Existing popup flow stays unchanged unless frontend selects the new redirect path and `BRIDGE_GOOGLE_REDIRECT_ENABLED=true`.

## Required approved configuration before future activation

- `PUBLIC_ORIGIN=https://bridgego.onrender.com` (HTTPS origin, no trailing slash).
- Same existing Google web client: add exact Authorized redirect URI `https://bridgego.onrender.com/api/auth/google/redirect/callback`. This candidate does not edit Google Console.
- Set `BRIDGE_GOOGLE_REDIRECT_ENABLED=true` only after review and controlled real-device validation. Current candidate leaves it false.
- Collection still must be enabled in managed mode. No changes to quarterly cleanup, storage schema, identity admission or source data.

## Protocol

- `GET /api/auth/google/redirect/config`: `{enabled,loginUri}` public information only.
- `POST .../start`: original Bearer guest session + exact Origin + JSON `{consent:true,profileConsentVersion:'account-profile-v1'}`. Returns `{nonce,expiresInSeconds:300,loginUri}`. Sets random `__Host-bridge_google_redirect` HttpOnly Secure SameSite=None Path=/ cookie. Existing WAITING/RECEIVED/SUBMITTING/UNKNOWN attempts cannot be silently replaced.
- GIS uses `ux_mode:'redirect'`, returned nonce and exact loginUri. Callback POST is `application/x-www-form-urlencoded`.
- `POST .../callback`: validates Google `g_csrf_token` double-submit cookie and form, unique form fields/cookies, browser binding and expiry. Credential remains only in bounded process RAM. No account writes. Fixed 303 to `/?google_return=1`; no session, nonce, binding, identity or credential in URLs. Duplicate callback rejected.
- `GET .../status`: requires original Bearer (no Origin expected on a same-origin GET). Returns `{state,status?}`; states WAITING, RECEIVED, SUBMITTING, COMPLETE, FAILED, UNKNOWN.
- `POST .../finish`: original Bearer + exact Origin. Only RECEIVED atomically claims once. Reuses existing Google JWT signature/audience/issuer/session nonce verification and `loginForEntry`. Success COMPLETE; an audited allowlist of definitively pre-storage JWT/nonce/consent/admission rejections becomes FAILED and can start a new attempt. All storage errors, AUTH_CANCELLED/AUTH_IN_ROOM/session failures and other exceptions remain UNKNOWN. Repeated calls never resend to storage. Unknown results cannot be cleared by start/cancel.
- `POST .../cancel`: original Bearer + exact Origin. Only WAITING/RECEIVED become FAILED and revoke nonce. No cancellation of submitted or unknown writes.

All API traffic passes existing no-store/body-size/rate-limit filter. Callback also enforces 16 KiB body and 12 KiB credential bounds. Expired RAM credentials cleared by scheduled 30-second sweep; no credentials in logs or durable storage. At most 1024 attempts retained; uncertain results are not evicted to permit retry. On process restart attempts and existing guest sessions disappear, so the old session cannot finalize. This is not durable auth-resume support.

## Remaining real-device gates

1. On iPhone Home Screen App, verify Google's redirect returns to original app context OR original app can resume and read server status with the original session. Never assume Safari local/session storage equals PWA storage.
2. If iOS omits browser binding or Google CSRF cookie across its container boundary, callback fails closed. Need actual device evidence; do not remove binding/CSRF checks to make it pass.
3. Cancel/back/background/resume, repeated taps, expired/failed login, revoked consent, app killed and browser switched. No login credential retry or account mutation from callback alone.
4. Verify callback errors show concise safe text; return URL contains only public `google_return` marker.
5. Offline focused tests are not Google consent/real iPhone authentication certification.
