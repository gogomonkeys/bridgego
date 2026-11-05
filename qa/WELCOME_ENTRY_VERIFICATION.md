# Pre-lobby Google verification entry (local only)

Base: production v0.12.0 commit `7cae91e2d83a47c51994f2ad27d3a196bad36f80`.
No upload, commit, push, deployment, Google configuration, backend or collection settings changes.

## Behavior
- Nickname plus Google verification or guest play before the lobby. Verification is not account login and does not enable persistence.
- Guest session creation is internal preparation for the existing session-bound nonce; verification remains on the entry screen until explicit continue.
- Existing room recovery bypasses the entry gate. A restored session outside a room receives the choice; refresh does not preserve verification-result display.
- Google script loads only after deliberate verification selection. Privacy and subject details are collapsed by default.
- Navigation/unmount, session replacement, cancellation and mismatched identity invalidate pending transitions. Existing nonce/token verification backend unchanged.

## Verified
On the clean production-base worktree: 460 frontend tests / 36 files, typecheck, lint, Vite production build and git diff --check passed.
Tests cover presentation, early session emit, delayed refresh, failed creation, unmount, session replacement, restored room bypass and existing login controller cancellation/replay safeguards.
Existing installed dependency cache was reused; fresh npm ci was not performed.
Build assets: index-Cf5Oqxwy.js, index-BbL8B9S_.css.

## Not verified
Browser interaction harness `frontend/qa/entry-browser.mjs` uses fake APIs and fake Google, but Chromium was unable to start because platform socket creation is prohibited. Therefore pixel/layout and actual browser flows are not claimed to pass. Real Google behavior with the changed entry has not been tested. Backend suites not rerun because backend is unchanged.
Before release, run the browser harness in a permitted environment and smoke-test real Google cancellation/success with Sheets disabled. Keep accurate Google verification label until real account linking is separately enabled.
