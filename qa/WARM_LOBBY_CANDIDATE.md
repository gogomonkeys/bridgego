# Warm lobby candidate — 2026-11-17

Base: published v0.15.2, commit 0b81e1e8ee0805cc0a1188a67032203e6cb637c7.
Local candidate only: no push, deploy, database change or quarterly cleanup.

Changes: compact forest fox welcome, reduced secondary controls, preserved distinct account/create/join/room-list sections, tutorial and friends adjacent. Leaderboard friends entry hidden; lobby/waiting-room entry remains. Welcome-page heading preserved. Existing artwork reused.

Validation: frontend full suite 769 tests / 67 files passed; typecheck, lint and production build passed. Backend and storage untouched. Visual QA rendered real App in isolated synthetic preview via native Terminal server and CUA browser: 320/390/mobile and 1040/1280 desktop viewed without overflow or overlap. Room-type switching, room-code input and joining synthetic waiting room passed. QA identified 320px long-name wrapping; corrected by giving greeting its own row, moving tools below and sizing fox to 62px. Final recheck passed: 320 greeting is two lines, tools separate, 62px fox fully visible; 320/390 friends entry beside tutorial, overlay opens/closes without overlap and preserves background, leaderboard has zero friends entry. No real iOS, Google account or backend flow claimed.

Files: frontend/src/App.tsx, frontend/src/lobby-feedback.css, frontend/src/LobbyFeedback.test.tsx, frontend/src/HelpLayout.test.tsx.

Release preparation: version v0.15.3, approved for publishing on 2026-11-17. Keep original published source and nickname10-compatible rollback separate.

Final checks after narrow-width CSS correction: full 769 frontend tests, typecheck, lint and build passed. Preview fixture now supports local synthetic friends response only; no production request allowed.
